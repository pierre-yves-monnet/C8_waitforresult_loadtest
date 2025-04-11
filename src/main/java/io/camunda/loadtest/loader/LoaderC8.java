package io.camunda.loadtest.loader;

import io.camunda.loadtest.executor.ExecuteWithResult;
import io.camunda.loadtest.executor.ResultWorker;
import io.camunda.loadtest.executor.WithResultAPI;
import io.camunda.zeebe.client.ZeebeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;


@Component
@ConfigurationProperties()

public class LoaderC8 {
    public static final String REPORT_CREATOR = "creator";
    Logger logger = LoggerFactory.getLogger(LoaderC8.class.getName());
    @Autowired
    ZeebeClient zeebeClient;
    WithResultAPI withResultAPI;

    @Autowired
    Reporting reporting;

    @Value("${waitforresult.resultworker.modeLinear:false}")
    private Boolean modeLinear;


    @Value("${waitforresult.creator.processId:}")
    private String processId;
    @Value("${waitforresult.creator.numberOfLoops:}")
    private int numberOfLoops;
    @Value("${waitforresult.creator.numberOfThreads:1}")
    private int numberOfThreads;


    @Value("${waitforresult.creator.enabled:true}")
    private Boolean enabledCreator;
    @Value("${waitforresult.creator.topicPrefix:end-result}")
    private String prefixTopicCreation;
    @Value("${waitforresult.creator.timeoutCreationInMs:1000}")
    private Long timeoutCreationInMs;
    @Value("${waitforresult.message.enabled:false}")
    private Boolean enabledMessage;

    @Value("${waitforresult.message.names:blue,red}")
    private List<String> messagesName;
    @Value("${waitforresult.message.numberOfThreads:1}")
    private int numberOfThreadsMessage;

    @Value("${waitforresult.message.keyCorrelation:PROCESSINSTANCEKEY}")
    private String keyCorrelation;

    @Value("${waitforresult.message.topicPrefix:end-message}")
    private String prefixTopicMessage;
    @Value("${waitforresult.message.timeoutMessageLiveInMs:50000}")
    private Long timeoutMessageLiveInMs;
    @Value("${waitforresult.message.timeoutMessageInMs:1000}")
    private Long timeoutMessageInMs;

    @Value("${waitforresult.resultworker.implementation:HOST}")
    private String workerImplementation;

    public void initialize() {
        reporting.addReport(REPORT_CREATOR);

        ResultWorker.WorkerImplementation strategy;
        try {
            strategy = ResultWorker.WorkerImplementation.valueOf(workerImplementation);
        } catch (Exception e) {
            logger.error("Unknown worker implementation {}, choose DYNAMIC", workerImplementation);
            strategy = ResultWorker.WorkerImplementation.DYNAMIC;
        }

        String podName = String.valueOf(System.currentTimeMillis());
        try {
            podName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            logger.error("Can't get inetAddress: " + e.getMessage());
        }


        logger.info("ModeLinear {} Enable Creator ResultImplementation {} numberOfThreads {} Loops {} EnableCreator {} enableMessage {} numberOfMessages {} : {} podName{}",
                modeLinear,
                strategy,
                numberOfThreads, numberOfLoops,
                enabledCreator, enabledMessage,
                messagesName.size(), messagesName.toString(),
                podName);


        if (enabledCreator) {
            withResultAPI = new WithResultAPI(zeebeClient, null, false, false, strategy);

            BlockingQueue<ResultCreation> queueCreation = new LinkedBlockingQueue<>(500000);
            List<BlockingQueue> listQueues = new ArrayList<>();
            listQueues.add(queueCreation);

            // Creation section
            ExecutorService executorCreation = Executors.newFixedThreadPool(numberOfThreads);
            logger.info("-------Start creator with {} agents, queueCapacity 100000", numberOfThreads);

            for (int i = 0; i < numberOfThreads; i++) {
                int finalI = i;
                String finalPodName = podName;
                executorCreation.submit(() -> executeCreation(finalPodName, finalI, numberOfLoops, queueCreation));
            }

            if (enabledMessage) {
                // Message section
                ExecutorService executorMessage = Executors.newFixedThreadPool(numberOfThreadsMessage * messagesName.size());

                // We create one queue per messageName, excep the last one does not have a queue
                for (int i = 0; i < messagesName.size() - 1; i++) {
                    listQueues.add(new LinkedBlockingQueue<ResultCreation>(500000));
                }
                for (int m = 0; m < messagesName.size(); m++) {

                    final String name = messagesName.get(m);
                    reporting.addReport(name);

                    final BlockingQueue<ResultCreation> queueSource = listQueues.get(m);
                    final BlockingQueue<ResultCreation> queueTarget = m + 1 < listQueues.size() ? listQueues.get(m + 1) : null;
                    logger.info("------- Start messageName [{}] with {} agents, queueCapacity 100000 Queue Source? {} queueTarget? {}",
                            name, numberOfThreadsMessage,
                            queueSource != null,
                            queueTarget != null);

                    for (int i = 0; i < numberOfThreadsMessage; i++) {
                        int agendID = i ;
                        String finalPodName = podName;
                        executorMessage.submit(() -> executeMessage(name, queueSource, queueTarget, finalPodName, agendID, numberOfLoops));
                    }
                }

            }
        } else {
            logger.info("Disable Creator");
        }

    }


    /**
     * Agent to simulate a create process instance.
     * The agent populate for each PI
     *
     * @param podName
     * @param agentID
     * @param numberOfLoops
     */
    public final void executeCreation(String podName, int agentID, int numberOfLoops, BlockingQueue queueCreation) {

        if (numberOfLoops == 0)
            return;
        LoggerMessage loggerMessage = new LoggerMessage("Creator " + agentID);

        logger.info("Start creator {} for Loops:{}", agentID, numberOfLoops);
        long totalExecutionTime = 0;
        int correct = 0;
        int errors = 0;
        int timeouts = 0;
        Reporting.Report report = reporting.getReport("creator", agentID);
        report.markBeginTime();

        for (int i = 0; i < numberOfLoops; i++) {
            Map<String, Object> variables = new HashMap<>();

            try {
                String jobKey = podName + "_" + agentID + "_" + i;

                variables.put("applicationKeyColor", jobKey);
                ExecuteWithResult execute = withResultAPI.processInstanceWithResult(processId,
                        variables, jobKey, prefixTopicCreation,
                        Duration.ofMillis(timeoutCreationInMs)).join();

                if (execute.creationError) {
                    errors++;
                    loggerMessage.message("Errors! (total " + errors + ")");
                } else if (execute.timeOut) {
                    timeouts++;
                    loggerMessage.message("timeouts! (total " + timeouts + ")");
                } else {
                    totalExecutionTime += execute.executionTime;
                    correct++;
                    ResultCreation resultCreation = new ResultCreation();
                    resultCreation.resultKey = jobKey;
                    resultCreation.processInstanceKey = execute.processInstanceKey;
                    if (enabledMessage) {
                        boolean success = queueCreation.offer(resultCreation, 10, TimeUnit.MILLISECONDS);
                        if (!success) {
                            loggerMessage.message("QueueFull: can't push in the queue");
                        }
                    }

                }

            } catch (Exception e) {
                logger.error("Error During execution with result {}", e.getMessage());
                errors++;
            }
            if (i % 10 == 0 && i > 0) {
                if (loggerMessage.canLog()) {
                    loggerMessage.message(String.format("loop %,d/%,d corrects:%,d errors:%,d timeouts:%,d averageTime/exec %d ms queueSize:%d",
                            (i + 1), numberOfLoops, correct, errors, timeouts, totalExecutionTime / (correct == 0 ? 1 : correct),
                            queueCreation.size()));
                }

                report = reporting.getReport("creator", agentID);
                report.countLoops = i;
                report.numberOfLoops = numberOfLoops;
                report.corrects = correct;
                report.errors = errors;
                report.timeouts = timeouts;
                report.totalExecutionTime = totalExecutionTime;

            }
        } // end loop
        report = reporting.getReport(REPORT_CREATOR, agentID);
        report.markEndTime();
        logger.info("------------- END Creator {} loop {} corrects {} errors {} timeouts {} averageTime/exec {} ms",
                agentID, numberOfLoops, correct, errors, timeouts, totalExecutionTime / correct);

    }


    /**
     * Execute message by agent
     *
     * @param queueSource
     * @param queueTarget
     * @param finalPodName
     * @param numberOfLoops
     */
    public final void executeMessage(String messageName,
                                     BlockingQueue<ResultCreation> queueSource,
                                     BlockingQueue<ResultCreation> queueTarget,
                                     String finalPodName,
                                     int agentID,
                                     int numberOfLoops) {
        LoggerMessage loggerMessage = new LoggerMessage("Message [" + messageName + "]_" + agentID);

        loggerMessage.message("Start Message with "+numberOfLoops);
        long totalExecutionTime = 0;
        int corrects = 0;
        int errors = 0;
        int timeouts = 0;

        try {
            Reporting.Report report = reporting.getReport(messageName, agentID);

            int count = 0;
            while (count < numberOfLoops) {
                count++;
                ResultCreation resultCreation = null;
                do {

                    resultCreation = queueSource.poll(60, TimeUnit.SECONDS);
                    if (resultCreation == null) {
                        loggerMessage.message("Nothing in the queue for 60 s");
                    }
                } while (resultCreation == null);

                // Need to start the begin time to calculate the throughput
                if (report.beginTime == null)
                    report.markBeginTime();


                // Process this result
                // THe jobKey is the correlation value.
                // To be sure to have the end, we add  "_msg_" for the new jobKey
                // The end
                try {
                    ExecuteWithResult execute = withResultAPI.publishNewMessageWithResult(messageName,
                            "PROCESSINSTANCEKEY".equals(keyCorrelation) ? String.valueOf(resultCreation.processInstanceKey) : resultCreation.resultKey,
                            Duration.ofMillis(timeoutMessageLiveInMs),
                            Collections.emptyMap(),
                            resultCreation.resultKey,
                            prefixTopicMessage,
                            Duration.ofMillis(timeoutMessageInMs)).join();
                    if (execute.messageError) {
                        errors++;
                        loggerMessage.message("Errors! (total " + errors + ")");
                    } else if (execute.timeOut) {
                        timeouts++;
                        loggerMessage.message("Timeout! (total " + timeouts + ")");
                    } else {
                        totalExecutionTime += execute.executionTime;
                        corrects++;
                        if (queueTarget != null) {
                            boolean success = queueTarget.offer(resultCreation, 10, TimeUnit.MILLISECONDS);
                            if (!success) {
                                loggerMessage.message("QueueFull: can't push in the queue");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Message {} Error During execution with result {}", agentID, e.getMessage());
                    errors++;
                }
                if (count % 10 == 0 && count > 0) {
                    if (loggerMessage.canLog()) {
                        loggerMessage.message(String.format("loop %,d/%,d corrects:%,d errors:%,d timeouts:%,d averageTime/exec:%d ms QueueSourceSize:%,d queueTargetSize:%,d",
                                count, numberOfLoops, corrects, errors,
                                timeouts,
                                totalExecutionTime / (corrects == 0 ? 1 : corrects),
                                queueSource.size(),
                                queueTarget == null ? -1 : queueTarget.size()));
                    }
                    report = reporting.getReport(messageName, agentID);
                    report.countLoops = count;
                    report.numberOfLoops = numberOfLoops;
                    report.corrects = corrects;
                    report.errors = errors;
                    report.timeouts = timeouts;
                    report.totalExecutionTime = totalExecutionTime;

                }
            }
            logger.info("------ END Message {}/{} loop {}/{} correct {} error {} averageTime/exec {} ms QueueSourceSize {} queueTargetSize {}",
                    messageName, agentID,
                    count, numberOfLoops, corrects, errors, totalExecutionTime / (corrects == 0 ? 1 : corrects),
                    queueSource.size(),
                    queueTarget.size());

        } catch (Exception e) {
            logger.error("Error During executeMessage{}", e.getMessage());
        }

    }

    class ResultCreation {
        public String resultKey;
        public Long processInstanceKey;

    }
}

