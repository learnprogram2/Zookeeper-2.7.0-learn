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

package org.apache.zookeeper.server;

import java.io.Flushable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * 这个syncRequestProcessor就是把所有请求(需要commit的请i去)刷到disk里面.
 * 会把request打一个batch, request只有等到batch刷新到disk里面之后才会接着processor链条往下走.
 *
 * This RequestProcessor logs requests to disk. It batches the requests to do
 * the io efficiently. The request is not passed to the next RequestProcessor
 * until its log has been synced to disk.
 *
 * SyncRequestProcessor is used in 3 different cases
 * 1. Leader - Sync request to disk and forward it to AckRequestProcessor which
 *             send ack back to itself.
 * 2. Follower - Sync request to disk and forward request to
 *             SendAckRequestProcessor which send the packets to leader.
 *             SendAckRequestProcessor is flushable which allow us to force
 *             push packets to leader.
 *             把request刷到磁盘上, 把request转发到sendACK, 会把package发到leader上去.
 *
 * 3. Observer - Sync committed request to disk (received as INFORM packet). 只会简单的刷到磁盘上去, 没有投票权, 不会响应.
 *             It never send ack back to the leader, so the nextProcessor will
 *             be null. This change the semantic of txnlog on the observer
 *             since it only contains committed txns.
 */
public class SyncRequestProcessor extends ZooKeeperCriticalThread implements RequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(SyncRequestProcessor.class);

    private static final Request REQUEST_OF_DEATH = Request.requestOfDeath;

    /** The number of log entries to log before starting a snapshot */
    private static int snapCount = ZooKeeperServer.getSnapCount();

    /**
     * The total size of log entries before starting a snapshot
     */
    private static long snapSizeInBytes = ZooKeeperServer.getSnapSizeInBytes();

    /**
     * Random numbers used to vary snapshot timing
     */
    private int randRoll;
    private long randSize;

    private final BlockingQueue<Request> queuedRequests = new LinkedBlockingQueue<Request>();

    private final Semaphore snapThreadMutex = new Semaphore(1);

    private final ZooKeeperServer zks;

    private final RequestProcessor nextProcessor;

    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     */
    private final Queue<Request> toFlush;
    private long lastFlushTime;

    public SyncRequestProcessor(ZooKeeperServer zks, RequestProcessor nextProcessor) {
        super("SyncThread:" + zks.getServerId(), zks.getZooKeeperServerListener());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        this.toFlush = new ArrayDeque<>(zks.getMaxBatchSize());
    }

    /**
     * used by tests to check for changing
     * snapcounts
     * @param count
     */
    public static void setSnapCount(int count) {
        snapCount = count;
    }

    /**
     * used by tests to get the snapcount
     * @return the snapcount
     */
    public static int getSnapCount() {
        return snapCount;
    }

    private long getRemainingDelay() {
        long flushDelay = zks.getFlushDelay();
        long duration = Time.currentElapsedTime() - lastFlushTime;
        if (duration < flushDelay) {
            return flushDelay - duration;
        }
        return 0;
    }

    /** If both flushDelay and maxMaxBatchSize are set (bigger than 0), flush
     * whenever either condition is hit. If only one or the other is
     * set, flush only when the relevant condition is hit.
     */
    private boolean shouldFlush() {
        long flushDelay = zks.getFlushDelay();
        long maxBatchSize = zks.getMaxBatchSize();
        if ((flushDelay > 0) && (getRemainingDelay() == 0)) {
            return true;
        }
        return (maxBatchSize > 0) && (toFlush.size() >= maxBatchSize);
    }

    /**
     * used by tests to check for changing
     * snapcounts
     * @param size
     */
    public static void setSnapSizeInBytes(long size) {
        snapSizeInBytes = size;
    }

    private boolean shouldSnapshot() {
        int logCount = zks.getZKDatabase().getTxnCount();
        long logSize = zks.getZKDatabase().getTxnSize();
        return (logCount > (snapCount / 2 + randRoll))
               || (snapSizeInBytes > 0 && logSize > (snapSizeInBytes / 2 + randSize));
    }

    private void resetSnapshotStats() {
        randRoll = ThreadLocalRandom.current().nextInt(snapCount / 2);
        randSize = Math.abs(ThreadLocalRandom.current().nextLong() % (snapSizeInBytes / 2));
    }

    @Override
    public void run() {
        try {
            // we do this in an attempt to ensure that not all of the servers
            // in the ensemble take a snapshot at the same time
            resetSnapshotStats();
            lastFlushTime = Time.currentElapsedTime();
            while (true) {
                ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUE_SIZE.add(queuedRequests.size());

                long pollTime = Math.min(zks.getMaxWriteQueuePollTime(), getRemainingDelay());
                Request si = queuedRequests.poll(pollTime, TimeUnit.MILLISECONDS);
                if (si == null) {
                    /* We timed out looking for more writes to batch, go ahead and flush immediately */
                    // 这个应该是处理已经sync到磁盘上去的request, 传递到下一个processor上.
                    flush();
                    si = queuedRequests.take();
                }

                if (si == REQUEST_OF_DEATH) {
                    break;
                }

                long startProcessTime = Time.currentElapsedTime();
                ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUE_TIME.add(startProcessTime - si.syncQueueStartTime);

                // TODO: 168. 如何基于SyncProcessor写本地磁盘事务日志?
                // 1. 把request添加到db里面.
                // track the number of records written to the log
                if (!si.isThrottled() && zks.getZKDatabase().append(si)) {
                    // 检查snapshot: 从logcount和logsize来判断
                    if (shouldSnapshot()) {
                        resetSnapshotStats();
                        // roll the log
                        zks.getZKDatabase().rollLog();
                        // take a snapshot
                        if (!snapThreadMutex.tryAcquire()) {
                            LOG.warn("Too busy to snap, skipping");
                        } else {
                            new ZooKeeperThread("Snapshot Thread") {
                                public void run() {
                                    try {
                                        zks.takeSnapshot();
                                    } catch (Exception e) {
                                        LOG.warn("Unexpected exception", e);
                                    } finally {
                                        snapThreadMutex.release();
                                    }
                                }
                            }.start();
                        }
                    }
                } else if (toFlush.isEmpty()) {
                    // optimization for read heavy workloads
                    // iff this is a read or a throttled request(which doesn't need to be written to the disk),
                    // and there are no pending flushes (writes), then just pass this to the next processor
                    if (nextProcessor != null) {
                        nextProcessor.processRequest(si);
                        if (nextProcessor instanceof Flushable) {
                            ((Flushable) nextProcessor).flush();
                        }
                    }
                    continue;
                }
                // 2. 追加到zkdb里面了, 然后加到toFlush队列里面呢
                toFlush.add(si);
                if (shouldFlush()) {
                    flush();
                }
                ServerMetrics.getMetrics().SYNC_PROCESS_TIME.add(Time.currentElapsedTime() - startProcessTime);
            }
        } catch (Throwable t) {
            handleException(this.getName(), t);
        }
        LOG.info("SyncRequestProcessor exited!");
    }

    // 这个应该是处理已经sync到磁盘上去的request, 传递到下一个processor上.
    private void flush() throws IOException, RequestProcessorException {
        if (this.toFlush.isEmpty()) {
            return;
        }

        ServerMetrics.getMetrics().BATCH_SIZE.add(toFlush.size());

        long flushStartTime = Time.currentElapsedTime();
        // 1. 这就保证刷到磁盘里了.
        zks.getZKDatabase().commit();
        ServerMetrics.getMetrics().SYNC_PROCESSOR_FLUSH_TIME.add(Time.currentElapsedTime() - flushStartTime);

        if (this.nextProcessor == null) {
            this.toFlush.clear();
        } else {
            while (!this.toFlush.isEmpty()) {
                // 直接传递到下一个processor里面了.
                final Request i = this.toFlush.remove();
                long latency = Time.currentElapsedTime() - i.syncQueueStartTime;
                ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUE_AND_FLUSH_TIME.add(latency);
                this.nextProcessor.processRequest(i);
            }
            if (this.nextProcessor instanceof Flushable) {
                ((Flushable) this.nextProcessor).flush();
            }
        }
        lastFlushTime = Time.currentElapsedTime();
    }

    public void shutdown() {
        LOG.info("Shutting down");
        queuedRequests.add(REQUEST_OF_DEATH);
        try {
            this.join();
            this.flush();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while wating for {} to finish", this);
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.warn("Got IO exception during shutdown");
        } catch (RequestProcessorException e) {
            LOG.warn("Got request processor exception during shutdown");
        }
        if (nextProcessor != null) {
            nextProcessor.shutdown();
        }
    }

    public void processRequest(final Request request) {
        Objects.requireNonNull(request, "Request cannot be null");

        request.syncQueueStartTime = Time.currentElapsedTime();
        // 直接加入到队列里了
        queuedRequests.add(request);
        ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUED.add(1);
    }

}
