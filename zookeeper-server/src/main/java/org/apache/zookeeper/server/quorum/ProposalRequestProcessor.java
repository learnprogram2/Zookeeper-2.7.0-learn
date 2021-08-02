/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.quorum.Leader.XidRolloverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * 这个proposal处理简单的把request分发到ACKRequestProcessor和SyncRequestProcessor
 *
 * This RequestProcessor simply forwards requests to an AckRequestProcessor and
 * SyncRequestProcessor.
 */
public class ProposalRequestProcessor implements RequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ProposalRequestProcessor.class);

    LeaderZooKeeperServer zks;

    RequestProcessor nextProcessor;

    SyncRequestProcessor syncProcessor;

    // If this property is set, requests from Learners won't be forwarded
    // to the CommitProcessor in order to save resources
    public static final String FORWARD_LEARNER_REQUESTS_TO_COMMIT_PROCESSOR_DISABLED =
          "zookeeper.forward_learner_requests_to_commit_processor_disabled";
    private final boolean forwardLearnerRequestsToCommitProcessorDisabled;

    public ProposalRequestProcessor(LeaderZooKeeperServer zks, RequestProcessor nextProcessor) {
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        AckRequestProcessor ackProcessor = new AckRequestProcessor(zks.getLeader());
        syncProcessor = new SyncRequestProcessor(zks, ackProcessor);

        forwardLearnerRequestsToCommitProcessorDisabled = Boolean.getBoolean(
                FORWARD_LEARNER_REQUESTS_TO_COMMIT_PROCESSOR_DISABLED);
        LOG.info("{} = {}", FORWARD_LEARNER_REQUESTS_TO_COMMIT_PROCESSOR_DISABLED,
                forwardLearnerRequestsToCommitProcessorDisabled);
    }

    /**
     * initialize this processor
     */
    public void initialize() {
        syncProcessor.start();
    }

    public void processRequest(Request request) throws RequestProcessorException {
        /* In the following IF-THEN-ELSE block, we process syncs on the leader.
         * If the sync is coming from a follower, then the follower
         * handler adds it to syncHandler. Otherwise, if it is a client of
         * the leader that issued the sync command, then syncHandler won't
         * contain the handler. In this case, we add it to syncHandler, and
         * call processRequest on the next processor.
         */
        // FIXME: 这种先不看.
        if (request instanceof LearnerSyncRequest) {
            // 这应该是磁盘相关的, learner同步请求.
            // 如果是learner的同步请求, 就这样处理. 这个learnerSyncRequest什么是同步请求呢?
            zks.getLeader().processSync((LearnerSyncRequest) request);
        } else {
            // 我们走这里, 普通的create请求.

            // 1. 把request传递到 CommitProcessor: 就是把需要commit的request加入到了commitProcessor的queuedWriteRequests队列, 不需要commit的req就简单计数.
            if (shouldForwardToNextProcessor(request)) {
                nextProcessor.processRequest(request);
            }
            // 这个肯定不为null啊
            // 2. 交给leader做一个propose, 然后交给syncProcessor传播过去
            if (request.getHdr() != null) {
                // We need to sync and get consensus on any transactions
                try {
                    zks.getLeader().propose(request);
                } catch (XidRolloverException e) {
                    throw new RequestProcessorException(e.getMessage(), e);
                }
                // 这个sync应该是同步到磁盘里
                syncProcessor.processRequest(request);
            }
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");
        nextProcessor.shutdown();
        syncProcessor.shutdown();
    }

    private boolean shouldForwardToNextProcessor(Request request) {
        if (!forwardLearnerRequestsToCommitProcessorDisabled) {
            return true;
        }
        if (request.getOwner() instanceof LearnerHandler) {
            ServerMetrics.getMetrics().REQUESTS_NOT_FORWARDED_TO_COMMIT_PROCESSOR.add(1);
            return false;
        }
        return true;
    }
}
