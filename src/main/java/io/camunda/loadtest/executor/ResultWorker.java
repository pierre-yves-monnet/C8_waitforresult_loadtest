package io.camunda.loadtest.executor;

import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public abstract class ResultWorker {
    Logger logger = LoggerFactory.getLogger(ResultWorker.class.getName());

    public enum WorkerImplementation { DYNAMIC, HOST}

    public abstract void initialize();

    public abstract LockObjectTransporter openTransaction(String context, String prefixTopicWorker, String jobKey);
    public abstract void waitForResult(LockObjectTransporter lockObjectTransporter, long timeOut);
    public abstract void closeTransaction(LockObjectTransporter lockObjectTransporter);

    public abstract String getTopic(String context, String prefixTopicWorker, String jobKey);


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
    }
