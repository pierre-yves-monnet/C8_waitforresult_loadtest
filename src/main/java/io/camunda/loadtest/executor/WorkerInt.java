package io.camunda.loadtest.executor;

import io.camunda.zeebe.client.api.worker.JobWorker;

public interface WorkerInt {
    JobWorker getJobWorker();
}
