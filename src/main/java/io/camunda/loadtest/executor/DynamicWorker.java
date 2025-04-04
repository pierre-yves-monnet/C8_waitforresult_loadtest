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
 * This implementation use the Dynamic worker implementation
 */
public class DynamicWorker {
    Logger logger = LoggerFactory.getLogger(DynamicWorker.class.getName());

    ZeebeClient zeebeClient;
    HandleMarker handleMarker = new HandleMarker();

    DynamicWorker(ZeebeClient zeebeClient) {
        this.zeebeClient = zeebeClient;
    }

    public LockObjectTransporter openTransaction(String context, String prefixTopicWorker, String jobKey) {
        LockObjectTransporter lockObjectTransporter = new LockObjectTransporter();
        lockObjectTransporter.jobKey = jobKey;
        lockObjectTransporter.context = context;
        logger.debug("Register worker[{}]", prefixTopicWorker + jobKey);
        lockObjectTransporter.worker =  zeebeClient.newWorker()
                .jobType(prefixTopicWorker + jobKey)
                .handler(handleMarker)
                .streamEnabled(true)
                .open();
        synchronized (lockObjectsMap) {
            lockObjectsMap.put(jobKey, lockObjectTransporter);
        }

        return lockObjectTransporter;
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
    private class HandleMarker implements JobHandler {
        public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
            // Get the variable "lockKey"
            jobClient.newCompleteCommand(activatedJob.getKey()).send();

            String jobKey = (String) activatedJob.getVariable("jobKey");
            // logger.info("Handle marker for jobKey[{}]", jobKey);
            DynamicWorker.LockObjectTransporter lockObjectTransporter = lockObjectsMap.get(jobKey);

            if (lockObjectTransporter == null) {
                logger.error("No object for jobKey[{}]", jobKey);
                return;
            }
            lockObjectTransporter.processVariables = activatedJob.getVariablesAsMap();
            lockObjectTransporter.elementId = activatedJob.getElementId();
            lockObjectTransporter.elementInstanceKey = activatedJob.getElementInstanceKey();
            logger.debug("HandleMarker jobKey[{}] variables[{}]", jobKey, lockObjectTransporter.processVariables);

            // Notify the thread waiting on this item
            lockObjectTransporter.notifyResult();
        }
    }



    public class LockObjectTransporter {
        public String jobKey;
        // With result will return the process variable here
        public Map<String, Object> processVariables;
        // elementId is the ID of the element, defined as the ID in the modeler. Example "ReviewApplication"
        public String elementId;
        // EleemntKey is the uniq key. Each instance has it's own key
        public long elementInstanceKey;

        public String context;
        public JobWorker worker;
        public boolean notification = false;

        public synchronized void waitForResult(long timeoutDurationInMs) {
            try {
                logger.debug("Wait on object[{}]", this);
                wait(timeoutDurationInMs);
            } catch (InterruptedException e) {
                logger.error("Can' wait ", e);
            }
        }

        public synchronized void notifyResult() {
            logger.debug("Notify on object[{}]", this);
            notification = true;
            notify();
        }
    }

    static final Map<String, LockObjectTransporter> lockObjectsMap = new ConcurrentHashMap<>();

}
