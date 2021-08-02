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

import java.io.IOException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.txn.ErrorTxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LeaderZKServer的Process链条的第一步
 *
 * 这个是leader quorum升级本地session的地方.
 * 但我还不知道session升级是什么意思: 看processRequest!
 *
 * Responsible for performing local session upgrade. Only request submitted
 * directly to the leader should go through this processor.
 */
public class LeaderRequestProcessor implements RequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(LeaderRequestProcessor.class);

    private final LeaderZooKeeperServer lzks;

    private final RequestProcessor nextProcessor;

    public LeaderRequestProcessor(LeaderZooKeeperServer zks, RequestProcessor nextProcessor) {
        this.lzks = zks;
        this.nextProcessor = nextProcessor;
    }


    // 暂时先不看升级session ,因为还搞不懂本地session和升级之后的session的却别.
    @Override
    public void processRequest(Request request) throws RequestProcessorException {
        // 这个应该是看了一下权限. 但是我不知道权限认证不通过之后, 怎么给用户响应回去.??????
        // Screen quorum requests against ACLs first
        if (!lzks.authWriteRequest(request)) {
            return;
        }

        // 如果这是一个本地session ,但是要创建一个临时节点, 我们就需要升级session
        // Check if this is a local session and we are trying to create
        // an ephemeral node, in which case we upgrade the session
        Request upgradeRequest = null;
        try {
            upgradeRequest = lzks.checkUpgradeSession(request);
        } catch (KeeperException ke) {
            if (request.getHdr() != null) {
                LOG.debug("Updating header");
                request.getHdr().setType(OpCode.error);
                request.setTxn(new ErrorTxn(ke.code().intValue()));
            }
            request.setException(ke);
            LOG.warn("Error creating upgrade request", ke);
        } catch (IOException ie) {
            LOG.error("Unexpected error in upgrade", ie);
        }

        // 如果要升级session , 就发一条升级的request. 处理完升级, 在处理当前的request.
        if (upgradeRequest != null) {
            nextProcessor.processRequest(upgradeRequest);
        }

        nextProcessor.processRequest(request);
    }

    @Override
    public void shutdown() {
        LOG.info("Shutting down");
        nextProcessor.shutdown();
    }

}
