package io.camunda.loadtest.executor;


import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This implementation create one worker immediately, per host.
 * In the process varaible, a value "jobKey" is present, to find the correct thread to unlock
 *
 */
public class ResultWorkerHost extends ResultWorker {

    Logger logger = LoggerFactory.getLogger(ResultWorkerHost.class.getName());

    ZeebeClient zeebeClient;
    HandlerEndResult HandleMarker= new HandlerEndResult();


    ConcurrentHashMap<String, JobWorker> mapWorker = new ConcurrentHashMap<>();

    String podName;
    ResultWorkerHost(ZeebeClient zeebeClient, String podName) {
        this.zeebeClient = zeebeClient;
        this.podName = podName;
    }

    @Override
    public void initialize() {

    }

    /**
     * Open the transaction. Create a dedicated worker for this transaction.
     * @param context
     * @param prefixTopicWorker
     * @param jobKey
     * @return
     */
    @Override
    public LockObjectTransporter openTransaction(String context, String prefixTopicWorker, String jobKey) {
        LockObjectTransporter lockObjectTransporter = new LockObjectTransporter();
        lockObjectTransporter.jobKey = jobKey;
        lockObjectTransporter.context = context;
        JobWorker worker = getWorker(getTopic(context, prefixTopicWorker,jobKey));

        logger.debug("Register on prefix[{}] jobKey[{}]", prefixTopicWorker+podName, jobKey);
        synchronized (lockObjectsMap) {
            lockObjectsMap.put(jobKey, lockObjectTransporter);
        }

        return lockObjectTransporter;
    }

    /**
     * The topic is different per host
     * @param context
     * @param prefixTopicWorker
     * @param jobKey
     * @return
     */
    @Override
    public String getTopic(String context, String prefixTopicWorker, String jobKey)
    {
        return prefixTopicWorker+podName;
    }

    @Override
    public void waitForResult(LockObjectTransporter lockObjectTransporter, long timeOut) {
        lockObjectTransporter.waitForResult(timeOut);
        logger.debug("Receive answer jobKey[{}] notification? {} inprogress {} context{}",
                lockObjectTransporter.jobKey,
                lockObjectTransporter.notification,
                lockObjectsMap.size(),
                lockObjectTransporter.context);

    }

    @Override
    public void closeTransaction(LockObjectTransporter lockObjectTransporter) {
        // we got the result
        // we can close the worker now
        synchronized (lockObjectsMap) {
            lockObjectsMap.remove(lockObjectTransporter.jobKey);
        }
    }


private JobWorker getWorker(String topicName) {
    JobWorker worker = mapWorker.get(topicName);
    if (worker == null) {
        synchronized (this) {
            if (mapWorker.get(topicName) == null) {
                worker = zeebeClient.newWorker()
                        .jobType(topicName)
                        .handler(HandleMarker)
                        .streamEnabled(true)
                        .open();
                mapWorker.put(topicName, worker);
            }
        }
    }
    return worker;

}
    /**
     * Handle the job. This worker register under the correct topic, and capture when it's come here
     */
    private class HandlerEndResult implements JobHandler {
        public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
            // Get the variable "lockKey"
            jobClient.newCompleteCommand(activatedJob.getKey()).send();

            String jobKey = (String) activatedJob.getVariable(WithResultAPI.PROCESS_VARIABLE_JOB_KEY);
            // logger.info("Handle marker for jobKey[{}]", jobKey);
            ResultWorkerDynamic.LockObjectTransporter lockObjectTransporter = lockObjectsMap.get(jobKey);

            if (lockObjectTransporter == null) {
                logger.error("No object for jobKey[{}]", jobKey);
                return;
            }
            lockObjectTransporter.processVariables = activatedJob.getVariablesAsMap();
            lockObjectTransporter.elementId = activatedJob.getElementId();
            lockObjectTransporter.elementInstanceKey = activatedJob.getElementInstanceKey();
            logger.debug("HandleMarkerDynamicWorker jobKey[{}] variables[{}]", jobKey, lockObjectTransporter.processVariables);

            // Notify the thread waiting on this item
            lockObjectTransporter.notifyResult();
        }
    }




    static final Map<String, LockObjectTransporter> lockObjectsMap = new ConcurrentHashMap<>();


}
