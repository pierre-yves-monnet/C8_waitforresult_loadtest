package io.camunda.loadtest.loader;

import io.camunda.loadtest.executor.ExecuteWithResult;
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

    @Value("${waitforresult.creator.processId:}")
    private String processId;

    @Value("${waitforresult.creator.numberOfLoops:}")
    private int numberOfLoops;
    @Value("${waitforresult.creator.numberOfThreads:1}")
    private int numberOfThreads;

    @Value("${waitforresult.creator.enabled:true}")
    private Boolean enabledCreator;

    @Value("${waitforresult.message.enabled:false}")
    private Boolean enabledMessage;

    @Value("${waitforresult.message.name:blue}")
    private String messageName;

    @Value("${waitforresult.worker.implementation:DYNAMIC}")
    private String workerImplementation;

    @Value("${waitforresult.creator.topicPrefix:end-result}")
    private String prefixTopicCreation;

    @Value("${waitforresult.message.topicPrefix:end-message}")
    private String prefixTopicMessage;


    @Autowired
    ZeebeClient zeebeClient;

    WithResultAPI withResultAPI;

    Map<TYPE_REPORT, Map<Integer, Report>> report = new HashMap<>();


    BlockingQueue<ResultCreation> queue = new LinkedBlockingQueue<>(10000);


    public void initialize() {
        report.put(TYPE_REPORT.CREATOR, new ConcurrentHashMap<>());
        report.put(TYPE_REPORT.MESSAGE, new ConcurrentHashMap<>());

        if (enabledCreator) {
            logger.info("Enable Creator numberOfThreads {} Loops {} EnableCreator {} enableMessage {}",
                    numberOfThreads, numberOfLoops,
                    enabledCreator, enabledMessage);

            WithResultAPI.WorkerImplementation strategy;
            try {
                strategy = WithResultAPI.WorkerImplementation.valueOf(workerImplementation);
            } catch (Exception e) {
                logger.error("Unknown worker implementation {}, choose DYNAMIC", workerImplementation);
                strategy = WithResultAPI.WorkerImplementation.DYNAMIC;
            }
            withResultAPI = new WithResultAPI(zeebeClient, null, false, false, strategy);

            String podName = String.valueOf(System.currentTimeMillis());
            try {
                podName = InetAddress.getLocalHost().getHostName();

            } catch (Exception e) {
                logger.error("Can't get inetAddress: " + e.getMessage());
            }


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
        Report report = getReport(TYPE_REPORT.CREATOR, prefix);
        report.markBeginTime();

        for (int i = 0; i < numberOfLoops; i++) {
            Map<String, Object> variables = new HashMap<>();


            try {
                String jobKey = podName + "_" + prefix + "_" + i;

                variables.put("applicationKeyColor", jobKey);
                ExecuteWithResult execute = withResultAPI.processInstanceWithResult(processId, variables, jobKey, prefixTopicCreation, 50000L);

                if (execute.creationError) {
                    errors++;
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
                if (i % 200 == 0)
                    logger.info("Creator {} loop {}/{} correct {} error {} averageTime/exec {} ms", prefix, (i + 1), numberOfLoops, correct, errors, totalExecutionTime / (correct == 0 ? 1 : correct));
                report = getReport(TYPE_REPORT.CREATOR, prefix);
                report.countLoops = i;
                report.correct = correct;
                report.errors = errors;
                report.totalExecutionTime = totalExecutionTime;

            }
        } // end loop
        report = getReport(TYPE_REPORT.CREATOR, prefix);
        report.markEndTime();
        logger.info("Creator {} loop {} correct {} error {} averageTime/exec {} ms", prefix, numberOfLoops, correct, errors, totalExecutionTime / correct);

    }


    /**
     * Execute message by agent
     *
     * @param numberOfLoops
     */
    public final void executeMessage(String podName, int prefix, int numberOfLoops) {
        long totalExecutionTime = 0;
        int correct = 0;
        int errors = 0;
        try {
            Report report = getReport(TYPE_REPORT.MESSAGE, prefix);

            int count = 0;
            while (count < numberOfLoops) {
                count++;
                ResultCreation resultCreation = queue.take();
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
                            50000L);
                    if (execute.messageError || execute.timeOut) {
                        errors++;
                    } else {
                        totalExecutionTime += execute.executionTime;
                        correct++;
                    }
                } catch (Exception e) {
                    logger.error("Error During execution with result {}", e.getMessage());
                    errors++;
                }
                if (count % 10 == 0 && count > 0) {
                    if (count % 200 == 0)
                        logger.info("Message {} loop {}/{} correct {} error {} averageTime/exec {} ms", prefix, count, numberOfLoops, correct, errors, totalExecutionTime / (correct == 0 ? 1 : correct));
                    report = getReport(TYPE_REPORT.MESSAGE, prefix);
                    report.countLoops = count;
                    report.correct = correct;
                    report.errors = errors;
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
        double sumThroughput = 0;
        int sumTotalExecutionTime = 0;
        for (TYPE_REPORT type : TYPE_REPORT.values()) {
            int countLoops = 0;
            int corrects = 0;
            int errors = 0;
            int totalExecutionTime = 0;
            List<Report> listReport = collectReports(type);

            double localThroughput = 0;
            boolean stillRunning = false;
            for (Report report : report.get(type).values()) {
                countLoops += report.countLoops;
                corrects += report.correct;
                errors += report.errors;
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
            sumTotalExecutionTime += totalExecutionTime;
            sumThroughput += localThroughput;
            logger.info("----- SYNTHESIS {}:  {} AVERAGE {} ms, THROUGHPUT {} exec/s loop {}/{} ({} %) corrects {} error {}  for {} agents",
                    type.toString(),
                    stillRunning ? "RUNNING" : "FINISH",
                    totalExecutionTime / Math.max(1, corrects),
                    String.format("%.1f", localThroughput),
                    countLoops,
                    numberOfLoops * numberOfThreads,
                    String.format("%.2f", (double) 100 * countLoops / (numberOfLoops * numberOfThreads)),
                    corrects,
                    errors,
                    numberOfThreads);
        }
        logger.info("----- SYNTHESIS TOTAL:  AVERAGE {} ms, THROUGHPUT {} exec/s loop {}/{} ({} %) correct {} error {}  for {} agents",
                sumTotalExecutionTime / Math.max(1, sumCorrects),
                String.format("%.1f", sumThroughput),
                sumLoops,
                numberOfLoops * numberOfThreads,
                String.format("%.2f", (double) 100 * sumLoops / (numberOfLoops * numberOfThreads)),
                sumCorrects,
                sumErrors,
                numberOfThreads);
    }


    public enum TYPE_REPORT {CREATOR, MESSAGE}

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

    private class Report {
        public int countLoops;
        public int correct;
        public int errors;
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
            report.correct = correct;
            report.errors = errors;
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
            if (beginTime == null || correct == 0)
                return -1;
            long endTimeSnapshot = endTime == null ? System.currentTimeMillis() : endTime;
            return (1000.0 * correct / (endTimeSnapshot - beginTime));
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

