package io.camunda.loadtest.executor;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This implementation use the Dynamic worker implementation
 */
public class ResultWorkerDynamic extends ResultWorker {
    Logger logger = LoggerFactory.getLogger(ResultWorkerDynamic.class.getName());

    ZeebeClient zeebeClient;
    HandleMarkerDynamicWorker handleMarkerDynamicWorker = new HandleMarkerDynamicWorker();

    ResultWorkerDynamic(ZeebeClient zeebeClient) {
        this.zeebeClient = zeebeClient;
    }

    @Override
    public void initialize() {
        // nothing to do
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
        logger.debug("Register worker[{}]", prefixTopicWorker + jobKey);
        lockObjectTransporter.worker =  zeebeClient.newWorker()
                .jobType( getTopic(context, prefixTopicWorker, jobKey))
                .handler(handleMarkerDynamicWorker)
                .streamEnabled(true)
                .open();
        synchronized (lockObjectsMap) {
            lockObjectsMap.put(jobKey, lockObjectTransporter);
        }

        return lockObjectTransporter;
    }

    /**
     * The topic is unique for each job worker. So it contains the prefix and the jobkey
     * @param context
     * @param prefixTopicWorker
     * @param jobKey
     * @return
     */
    @Override
    public String getTopic(String context, String prefixTopicWorker, String jobKey)
    {
        return prefixTopicWorker+jobKey;
    }



    public void waitForResult(LockObjectTransporter lockObjectTransporter, long timeOut) {
        lockObjectTransporter.waitForResult(timeOut);
        logger.debug("Receive answer jobKey[{}] notification? {} inprogress {} context{}",
                lockObjectTransporter.jobKey,
                lockObjectTransporter.notification,
                lockObjectsMap.size(),
                lockObjectTransporter.context);

    }

    public void closeTransaction(LockObjectTransporter lockObjectTransporter) {
        // we got the result
        // we can close the worker now
        lockObjectTransporter.worker.close();
        synchronized (lockObjectsMap) {
            lockObjectsMap.remove(lockObjectTransporter.jobKey);
        }
    }



    /**
     * Handle the job. This worker register under the correct topic, and capture when it's come here
     */
    private class HandleMarkerDynamicWorker implements JobHandler {
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
