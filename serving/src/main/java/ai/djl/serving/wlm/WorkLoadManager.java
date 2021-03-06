/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.serving.wlm;

import ai.djl.serving.util.ConfigManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorkLoadManager is repsonsible to manage the work load of worker thread. the manage scales
 * up/down the required amount of worker threads per model.
 *
 * @author erik.bamberg@web.de
 */
class WorkLoadManager {

    private static final Logger logger = LoggerFactory.getLogger(WorkLoadManager.class);
    private ConfigManager configManager;
    private AtomicInteger gpuCounter;
    private ExecutorService threadPool;

    private ConcurrentHashMap<String, WorkerPool> workerPools;

    /**
     * construct using the configuration.
     *
     * @param configManager configuration manager to get configuration parameter.
     */
    public WorkLoadManager(ConfigManager configManager) {
        this.configManager = configManager;
        this.gpuCounter = new AtomicInteger(0);
        threadPool = Executors.newCachedThreadPool();
        workerPools = new ConcurrentHashMap<>();
    }

    /**
     * get the workers for the specific model.
     *
     * @param modelName The name of the model we are looking for.
     * @return the list of workers responsible to handle predictions for this model.
     */
    public List<WorkerThread> getWorkers(String modelName) {
        List<WorkerThread> list;
        WorkerPool pool = workerPools.get(modelName);
        if (pool == null) {
            list = Collections.emptyList();
        } else {
            list = pool.getWorkers();
            if (list == null) {
                list = Collections.emptyList();
            }
        }
        return list;
    }

    /**
     * Adds an inference job to the job queue of the next free worker. scales up worker if
     * necessary.
     *
     * @param modelInfo the model to use.
     * @param job an inference job to be executed.
     * @return {@code true} if submit success, false otherwise.
     */
    public boolean addJob(ModelInfo modelInfo, Job job) {
        boolean accepted = false;
        WorkerPool pool = getWorkerPoolForModel(modelInfo);
        if (getNumRunningWorkers(modelInfo.getModelName()) > 0) {

            try {
                accepted = pool.getJobQueue().offer(job);

                if (!accepted) {
                    synchronized (modelInfo.getModelName()) {
                        scaleUpWorkers(modelInfo, pool);
                        accepted =
                                pool.getJobQueue()
                                        .offer(
                                                job,
                                                modelInfo.getMaxBatchDelay(),
                                                TimeUnit.MILLISECONDS);
                    }
                }

            } catch (ScaleCapacityExceededException e) {
                logger.error(e.getMessage(), e);
                accepted = false;
            } catch (InterruptedException e) {
                logger.info(
                        "Worker Queue Capacity Exceeded. cannot add to worker queue in appropriate time. You can configure max batch delay time for this model.");
            }
        }
        return accepted;
    }

    private void scaleUpWorkers(ModelInfo modelInfo, WorkerPool pool)
            throws ScaleCapacityExceededException {
        int currentWorkers = getNumRunningWorkers(modelInfo.getModelName());
        if (currentWorkers < modelInfo.getMaxWorkers()) {
            logger.debug("scaling up workers for model {} to {} ", modelInfo, currentWorkers + 1);
            addThreads(pool.getWorkers(), modelInfo, 1, false);
        } else {
            throw new ScaleCapacityExceededException(
                    "scale up capacity of "
                            + modelInfo.getMaxWorkers()
                            + " workers reached. Unable to scale up worker pool.");
        }
    }

    /**
     * returns the number of running workers of a model. running workers are workers which are not
     * stopped, in error or scheduled to scale down.
     *
     * @param modelName the model we are interested in.
     * @return number of running workers.
     */
    public int getNumRunningWorkers(String modelName) {
        int numWorking = 0;
        WorkerPool pool = workerPools.get(modelName);
        if (pool != null) {
            pool.cleanup();
            List<WorkerThread> threads = pool.getWorkers();
            for (WorkerThread thread : threads) {
                if ((thread.getState() != WorkerState.WORKER_STOPPED)
                        && (thread.getState() != WorkerState.WORKER_ERROR)
                        && (thread.getState() != WorkerState.WORKER_SCALED_DOWN)) {
                    ++numWorking;
                }
            }
        }
        return numWorking;
    }

    /**
     * trigger a model change event. scales up and down workers to match minWorkers/maxWorkers.
     *
     * @param modelInfo the changed model.
     */
    public void modelChanged(ModelInfo modelInfo) {
        synchronized (modelInfo.getModelName()) {
            int minWorker = modelInfo.getMinWorkers();

            WorkerPool pool = getWorkerPoolForModel(modelInfo);
            if (pool != null) {
                pool.cleanup();

                List<WorkerThread> threads;
                if (minWorker == 0) {
                    workerPools.remove(modelInfo.getModelName());
                }

                threads = pool.getWorkers();
                List<WorkerThread> fixedPoolThread =
                        threads.stream()
                                .filter(WorkerThread::isFixPoolThread)
                                .collect(Collectors.toList());

                int numberOfCurrentFixedWorkers = fixedPoolThread.size();

                if (numberOfCurrentFixedWorkers < minWorker) {
                    // scale up the fixed pool
                    addThreads(threads, modelInfo, minWorker - numberOfCurrentFixedWorkers, true);
                } else {
                    // scale down the fixed pool
                    fixedPoolThread
                            .subList(minWorker, numberOfCurrentFixedWorkers)
                            .forEach(
                                    t -> {
                                        threads.remove(t);
                                        t.shutdown(WorkerState.WORKER_SCALED_DOWN);
                                    });
                }
                pool.log();
            }
        }
    }

    private WorkerPool getWorkerPoolForModel(ModelInfo modelInfo) {
        return workerPools.computeIfAbsent(
                modelInfo.getModelName(), k -> new WorkerPool(modelInfo));
    }

    private void addThreads(
            List<WorkerThread> threads, ModelInfo model, int count, boolean permanent) {
        int maxGpu = configManager.getNumberOfGpu();
        for (int i = 0; i < count; ++i) {
            int gpuId = -1;

            if (maxGpu > 0) {
                gpuId = gpuCounter.accumulateAndGet(maxGpu, (prev, maxGpuId) -> ++prev % maxGpuId);
            }
            BatchAggregator aggregator;
            if (permanent) {
                aggregator =
                        new PermanentBatchAggregator(
                                model, workerPools.get(model.getModelName()).getJobQueue());
            } else {
                aggregator =
                        new TemporaryBatchAggregator(
                                model, workerPools.get(model.getModelName()).getJobQueue());
            }
            WorkerThread thread = new WorkerThread(gpuId, model, aggregator, permanent);

            threads.add(thread);
            threadPool.submit(thread);
        }
    }

    /**
     * Worker pools holds information per model.
     *
     * @author erik.bamberg@web.de
     */
    private static final class WorkerPool {

        private List<WorkerThread> workers;
        private LinkedBlockingDeque<Job> jobQueue;
        private String modelName;

        /**
         * Construct and initial data structure.
         *
         * @param model the model this WorkerPool belongs to.
         */
        public WorkerPool(ModelInfo model) {
            workers = Collections.synchronizedList(new ArrayList<>());
            jobQueue = new LinkedBlockingDeque<>(model.getQueueSize());
            modelName = model.getModelName();
        }

        /**
         * Returns a list of worker thread.
         *
         * @return the workers
         */
        public List<WorkerThread> getWorkers() {
            return workers;
        }

        /**
         * Returns the {@code JobQueue} for this model.
         *
         * @return the jobQueue
         */
        public LinkedBlockingDeque<Job> getJobQueue() {
            return jobQueue;
        }

        /**
         * Logs the current state of this {@code WorkerPool} when level "Debug" is enabled.
         *
         * <p>Logs all thread-ids in the pool.
         */
        public void log() {
            if (logger.isDebugEnabled()) {
                StringBuffer buf = new StringBuffer();
                workers.forEach(
                        w -> {
                            buf.append(w.getWorkerId());
                            if (w.isFixPoolThread()) {
                                buf.append("-fixedPool\n");
                            } else {
                                buf.append("-tmpPool\n");
                            }
                        });
                logger.debug("worker pool for model {}:\n {}", modelName, buf.toString());
            }
        }

        /** removes all stopped workers and workers in state error from the pool. */
        public void cleanup() {
            workers.removeIf(
                    t ->
                            t.getState() == WorkerState.WORKER_STOPPED
                                    || t.getState() == WorkerState.WORKER_ERROR);
        }
    }
}
