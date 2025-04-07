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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;


@Component
@ConfigurationProperties()

public class LoaderC8 {
    Logger logger = LoggerFactory.getLogger(LoaderC8.class.getName());
    @Autowired
    ZeebeClient zeebeClient;
    WithResultAPI withResultAPI;
    Map<TYPE_REPORT, Map<Integer, Report>> report = new HashMap<>();
    BlockingQueue<ResultCreation> queue = new LinkedBlockingQueue<>(10000);
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
    @Value("${waitforresult.creator.timeoutCreationInMs:50000}")
    private Long timeoutCreationInMs;
    @Value("${waitforresult.message.enabled:false}")
    private Boolean enabledMessage;
    @Value("${waitforresult.message.name:blue}")
    private String messageName;
    @Value("${waitforresult.message.topicPrefix:end-message}")
    private String prefixTopicMessage;
    @Value("${waitforresult.message.timeoutCreationInMs:50000}")
    private Long timeoutMessageInMs;
    @Value("${waitforresult.resultworker.implementation:HOST}")
    private String workerImplementation;

    public void initialize() {
        report.put(TYPE_REPORT.CREATOR, new ConcurrentHashMap<>());
        report.put(TYPE_REPORT.MESSAGE, new ConcurrentHashMap<>());

        if (enabledCreator) {

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
            logger.info("Enable Creator ResultImplementation {} numberOfThreads {} Loops {} EnableCreator {} enableMessage {} podName{}",
                    strategy,
                    numberOfThreads, numberOfLoops,
                    enabledCreator, enabledMessage,
                    podName);


            withResultAPI = new WithResultAPI(zeebeClient, null, false, false, strategy);

            // Creation section
            ExecutorService executorCreation = Executors.newFixedThreadPool(numberOfThreads);

            for (int i = 0; i < numberOfThreads; i++) {
                int finalI = i;
                String finalPodName = podName;
                executorCreation.submit(() -> executeCreation(finalPodName, finalI, numberOfLoops));
            }

            if (enabledMessage) {
                // Message section
                ExecutorService executorMessage = Executors.newFixedThreadPool(numberOfThreads);

                for (int i = 0; i < numberOfThreads; i++) {
                    int finalI = i;
                    String finalPodName = podName;
                    executorMessage.submit(() -> executeMessage(finalPodName, finalI, numberOfLoops));
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
     * @param prefix
     * @param numberOfLoops
     */
    public final void executeCreation(String podName, int prefix, int numberOfLoops) {

        if (numberOfLoops == 0)
            return;
        logger.info("Start creator {} for Loops:{}", prefix, numberOfLoops);
        long totalExecutionTime = 0;
        int correct = 0;
        int errors = 0;
        int timeout = 0;
        Report report = getReport(TYPE_REPORT.CREATOR, prefix);
        report.markBeginTime();

        for (int i = 0; i < numberOfLoops; i++) {
            Map<String, Object> variables = new HashMap<>();


            try {
                String jobKey = podName + "_" + prefix + "_" + i;

                variables.put("applicationKeyColor", jobKey);
                ExecuteWithResult execute = withResultAPI.processInstanceWithResult(processId,
                        variables, jobKey, prefixTopicCreation,
                        Duration.ofMillis(timeoutCreationInMs)).join();

                if (execute.creationError) {
                    errors++;
                } else if (execute.timeOut) {
                    timeout++;
                } else {
                    totalExecutionTime += execute.executionTime;
                    correct++;
                    ResultCreation resultCreation = new ResultCreation();
                    resultCreation.resultData = jobKey;
                    if (enabledMessage)
                        queue.put(resultCreation); // Blocks if the queue is full
                }

            } catch (Exception e) {
                logger.error("Error During execution with result {}", e.getMessage());
                errors++;
            }
            if (i % 10 == 0 && i > 0) {
                if (i % 500 == 0)
                    logger.info("Creator {} loop {}/{} correct {} error {} averageTime/exec {} ms", prefix, (i + 1), numberOfLoops, correct, errors, totalExecutionTime / (correct == 0 ? 1 : correct));
                report = getReport(TYPE_REPORT.CREATOR, prefix);
                report.countLoops = i;
                report.corrects = correct;
                report.errors = errors;
                report.timeouts = timeout;
                report.totalExecutionTime = totalExecutionTime;

            }
        } // end loop
        report = getReport(TYPE_REPORT.CREATOR, prefix);
        report.markEndTime();
        logger.info("Creator {} loop {} corrects {} errors {} timeout {} averageTime/exec {} ms",
                prefix, numberOfLoops, correct, errors, timeout, totalExecutionTime / correct);

    }


    /**
     * Execute message by agent
     *
     * @param numberOfLoops
     */
    public final void executeMessage(String podName, int prefix, int numberOfLoops) {
        long totalExecutionTime = 0;
        int corrects = 0;
        int errors = 0;
        int timeouts = 0;
        try {
            Report report = getReport(TYPE_REPORT.MESSAGE, prefix);

            int count = 0;
            while (count < numberOfLoops) {
                count++;
                ResultCreation resultCreation = queue.take();

                // Need to start the begin time to calculate the throughput
                if (report.beginTime == null)
                    report.markBeginTime();


                // Process this result
                // THe jobKey is the correlation value.
                // To be sure to have the end, we add  "_msg_" for the new jobKey
                // The end
                try {
                    ExecuteWithResult execute = withResultAPI.publishNewMessageWithResult(messageName,
                            resultCreation.resultData,
                            Duration.ZERO,
                            Collections.emptyMap(),
                            resultCreation.resultData,
                            prefixTopicMessage,
                            Duration.ofMillis(timeoutCreationInMs)).join();
                    if (execute.messageError) {
                        errors++;
                    } else if (execute.timeOut) {
                        timeouts++;
                    } else {
                        totalExecutionTime += execute.executionTime;
                        corrects++;
                    }

                } catch (Exception e) {
                    logger.error("Error During execution with result {}", e.getMessage());
                    errors++;
                }
                if (count % 10 == 0 && count > 0) {
                    if (count % 500 == 0)
                        logger.info("Message {} loop {}/{} correct {} error {} averageTime/exec {} ms", prefix, count, numberOfLoops, corrects, errors, totalExecutionTime / (corrects == 0 ? 1 : corrects));
                    report = getReport(TYPE_REPORT.MESSAGE, prefix);
                    report.countLoops = count;
                    report.corrects = corrects;
                    report.errors = errors;
                    report.timeouts = timeouts;
                    report.totalExecutionTime = totalExecutionTime;

                }
            }
        } catch (Exception e) {
            logger.error("Error During executeMessage{}", e.getMessage());
        }

    }


    @Scheduled(fixedRate = 60000) // Runs every 60 seconds
    public void runTask() {
        int sumLoops = 0;
        int sumCorrects = 0;
        int sumErrors = 0;
        int sumTimeouts = 0;
        double sumThroughput = 0;
        int sumTotalExecutionTime = 0;
        for (TYPE_REPORT type : TYPE_REPORT.values()) {
            int countLoops = 0;
            int corrects = 0;
            int errors = 0;
            int timeouts = 0;
            int totalExecutionTime = 0;
            List<Report> listReport = collectReports(type);

            double localThroughput = 0;
            boolean stillRunning = false;
            for (Report report : report.get(type).values()) {
                countLoops += report.countLoops;
                corrects += report.corrects;
                errors += report.errors;
                timeouts += report.timeouts;
                totalExecutionTime += report.totalExecutionTime;
                double reportThroughput = report.calculateThroughput();
                if (reportThroughput >= 0) {
                    localThroughput += reportThroughput;
                }
                if (report.endTime == null)
                    stillRunning = true;
            }
            sumLoops += countLoops;
            sumCorrects += corrects;
            sumErrors += errors;
            sumTimeouts += timeouts;
            sumTotalExecutionTime += totalExecutionTime;
            sumThroughput += localThroughput;
            logger.info("----- SYNTHESIS {}:  {} AVERAGE {} ms, THROUGHPUT {} exec/s loop {}/{} ({} %) corrects {} errors {} timeouts {} for {} agents",
                    type.toString(),
                    stillRunning ? "RUNNING" : "FINISH",
                    totalExecutionTime / Math.max(1, corrects),
                    String.format("%.1f", localThroughput),
                    countLoops,
                    numberOfLoops * numberOfThreads,
                    String.format("%.2f", (double) 100 * countLoops / (numberOfLoops * numberOfThreads)),
                    corrects,
                    errors,
                    sumTimeouts,
                    numberOfThreads);
        }
        logger.info("----- SYNTHESIS TOTAL:  AVERAGE {} ms, THROUGHPUT {} exec/s loop {}/{} ({} %) corrects {} errors {} timeouts {} for {} agents",
                sumTotalExecutionTime / Math.max(1, sumCorrects),
                String.format("%.1f", sumThroughput),
                sumLoops,
                numberOfLoops * numberOfThreads,
                String.format("%.2f", (double) 100 * sumLoops / (numberOfLoops * numberOfThreads)),
                sumCorrects,
                sumErrors,
                sumTimeouts,
                numberOfThreads);
    }

    /**
     * getReport Each thread maintains a report
     *
     * @param type   of report: CREATION, MESSAGE
     * @param prefix
     * @return
     */

    public Report getReport(TYPE_REPORT type, int prefix) {

        Map<Integer, Report> reportCreator = report.get(type);
        return reportCreator.computeIfAbsent(prefix, Report::new);
    }

    public synchronized List<Report> collectReports(TYPE_REPORT type) {
        List<Report> reportList = new ArrayList<>();
        report.get(type).forEach((threadName, report) -> reportList.add(report.getCopy()));
        return reportList;
    }

    public enum TYPE_REPORT {CREATOR, MESSAGE}

    private class Report {
        public int countLoops;
        public int corrects;
        public int errors;
        public int timeouts;
        public long totalExecutionTime;
        public int prefix;
        public Long beginTime;
        public Long endTime = null;

        public Report(int prefix) {
            this.prefix = prefix;
        }

        public Report getCopy() {
            Report report = new Report(prefix);
            report.countLoops = countLoops;
            report.corrects = corrects;
            report.errors = errors;
            report.timeouts = timeouts;
            report.totalExecutionTime = totalExecutionTime;
            report.beginTime = beginTime;
            report.endTime = endTime;
            return report;
        }

        /**
         * Return the throughput: number of correct items created per second.
         *
         * @return
         */
        public double calculateThroughput() {
            if (beginTime == null || corrects == 0)
                return -1;
            long endTimeSnapshot = endTime == null ? System.currentTimeMillis() : endTime;
            return (1000.0 * corrects / (endTimeSnapshot - beginTime));
        }

        public void markBeginTime() {
            beginTime = System.currentTimeMillis();
        }

        public void markEndTime() {
            endTime = System.currentTimeMillis();
        }
    }

    class ResultCreation {
        public String resultData;

    }
}

