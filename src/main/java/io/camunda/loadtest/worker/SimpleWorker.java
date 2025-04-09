package io.camunda.loadtest.worker;


import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties()
public class SimpleWorker {

    Logger logger = LoggerFactory.getLogger(SimpleWorker.class.getName());
    @Autowired
    ZeebeClient zeebeClient;
    HandleInitServiceTask handleInitServiceTask = new HandleInitServiceTask();
    MiddleServiceTask middleServiceTask = new MiddleServiceTask();
    CloseServiceTask closeServiceTask = new CloseServiceTask();
    InitializeServiceTask initializeServiceTask = new InitializeServiceTask();

    @Value("${waitforresult.worker.enabled:true}")
    private Boolean enabled;

    public void initialize() {
        if (Boolean.TRUE.equals(enabled)) {
            logger.info("Start workers [initservicetask, middleservicetask, closeservicetask]");
            try {
                JobWorker worker1 = zeebeClient.newWorker()
                        .jobType("initservicetask")
                        .handler(handleInitServiceTask)
                        .streamEnabled(true)
                        .open();
                JobWorker worker2 = zeebeClient.newWorker()
                        .jobType("middleservicetask")
                        .handler(middleServiceTask)
                        .streamEnabled(true)
                        .open();
                JobWorker worker3 = zeebeClient.newWorker()
                        .jobType("closeservicetask")
                        .handler(closeServiceTask)
                        .streamEnabled(true)
                        .open();

                JobWorker worker4 = zeebeClient.newWorker()
                        .jobType("InitialiseTaskWorker")
                        .handler(initializeServiceTask)
                        .streamEnabled(true)
                        .open();
                JobWorker worker5 = zeebeClient.newWorker()
                        .jobType("MiddleTaskWorker")
                        .handler(middleServiceTask)
                        .streamEnabled(true)
                        .open();
                JobWorker worker6 = zeebeClient.newWorker()
                        .jobType("CloseTaskWorker")
                        .handler(closeServiceTask)
                        .streamEnabled(true)
                        .open();



            } catch (Error e) {
                logger.error("error {}", e.getMessage());
            } catch (Exception e) {
                logger.error("exception {}", e.getMessage());
            }
        } else {
            logger.info("No workers");
        }

    }


    private class HandleInitServiceTask implements JobHandler {
        public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
            logger.debug("Job handled: Type[{}] PI[{}]", activatedJob.getType(), activatedJob.getProcessInstanceKey());
            jobClient.newCompleteCommand(activatedJob.getKey()).send();

        }
    }

    private class MiddleServiceTask implements JobHandler {
        public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
            logger.debug("Job handled: Type[{}] PI[{}]", activatedJob.getType(), activatedJob.getProcessInstanceKey());
            jobClient.newCompleteCommand(activatedJob.getKey()).send();
        }
    }


    private class CloseServiceTask implements JobHandler {
        public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
            logger.debug("Job handled: Type[{}] PI[{}]", activatedJob.getType(), activatedJob.getProcessInstanceKey());
            jobClient.newCompleteCommand(activatedJob.getKey()).send();
        }
    }

    private class InitializeServiceTask implements JobHandler {
        public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
            logger.debug("Job handled: Type[{}] PI[{}]", activatedJob.getType(), activatedJob.getProcessInstanceKey());
            jobClient.newCompleteCommand(activatedJob.getKey())
                    .variables(Map.of("processInstanceKey", activatedJob.getProcessInstanceKey()))
                    .send();
        }
    }


}
