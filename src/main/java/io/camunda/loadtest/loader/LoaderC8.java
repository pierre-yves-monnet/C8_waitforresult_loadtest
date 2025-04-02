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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.InetAddress;

@Component
@ConfigurationProperties(value = "waitforresult")

public class LoaderC8 {
    Logger logger = LoggerFactory.getLogger(LoaderC8.class.getName());

    @Value("${waitforresult.creator.processId:}")
    private String processId;

    @Value("${waitforresult.creator.numberOfLoops:}")
    private int numberOfLoops;
    @Value("${waitforresult.creator.numberOfThreads:1}")
    private int numberOfThreads;

    @Value("${waitforresult.creator.enabled:true}")
    private Boolean enabled;

    @Autowired
    ZeebeClient zeebeClient;

    WithResultAPI withResultAPI;


    Map<Integer, Report> reportCreator = new ConcurrentHashMap<>();

    public void initialize() {
        if (enabled) {
            logger.info("Enable Creator numberOfThreads {} Loops {}", numberOfThreads, numberOfLoops);

            withResultAPI = new WithResultAPI(zeebeClient, false, false);
            ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

            String podName=String.valueOf(System.currentTimeMillis());
            try {
                podName = InetAddress.getLocalHost().getHostName();

            }catch (Exception e) {
                logger.error("Can't get inetAddress: "+e.getMessage());
            }

            for (int i = 0; i < numberOfThreads; i++) {
                int finalI = i;
                String finalPodName = podName;
                executor.submit(() -> executeCreation(finalPodName, finalI, numberOfLoops));
            }
        } else {
            logger.info("Disable Creator");
        }

    }


    public final void executeCreation(String uniqKey, int prefix, int numberOfLoops) {

        if (numberOfLoops == 0)
            return;
        logger.info("Start creator {} for Loops:{}", prefix, numberOfLoops);
        long totalExecutionTime = 0;
        int correct = 0;
        int errors = 0;
        Report report = getReport(prefix);
        report.markBeginTime();

        for (int i = 0; i < numberOfLoops; i++) {
            Map<String, Object> variables = new HashMap<>();


            try {
                String jobKey = uniqKey+"_"+prefix + "_" + i;

                ExecuteWithResult execute = withResultAPI.processInstanceWithResult(processId,
                        variables,
                        jobKey,
                        50000L);
                totalExecutionTime += execute.executionTime;
                correct++;
            } catch (Exception e) {
                logger.error("Error During execution with result {}", e.getMessage());
                errors++;
            }
            if (i % 200 == 0 && i > 0) {
                logger.info("Creator {} loop {}/{} correct {} error {} averageTime/exec {} ms", prefix, (i + 1), numberOfLoops, correct, errors, totalExecutionTime / (correct == 0 ? 1 : correct));
                report = getReport(prefix);
                report.countLoops = i;
                report.correct = correct;
                report.errors = errors;
                report.totalExecutionTime = totalExecutionTime;

            }
        } // end loop
        report = getReport(prefix);
        report.markEndTime();
        logger.info("Creator {} loop {} correct {} error {} averageTime/exec {} ms", prefix, numberOfLoops, correct, errors, totalExecutionTime / correct);

    }

    @Scheduled(fixedRate = 60000) // Runs every 60 seconds
    public void runTask() {
        int countLoops = 0;
        int correct = 0;
        int errors = 0;
        int totalExecutionTime = 0;
        List<Report> listReport = collectReports();

        double localThroughput = 0;
        boolean stillRunning = false;
        for (Report report : reportCreator.values()) {
            countLoops += report.countLoops;
            correct += report.correct;
            errors += report.errors;
            totalExecutionTime += report.totalExecutionTime;
            double reportThroughput = report.calculateThroughput();
            if (reportThroughput >=0) {
                localThroughput += reportThroughput;
            }
            if (report.endTime==null)
                stillRunning=true;
        }
        logger.info("----- SYNTHESIS {} loop {}/{} ({} %) correct {} error {} averageTime/exec {} ms: throughput:{} exec/s for {} agents",
                stillRunning? "RUNNING": "FINISH",
                countLoops,
                numberOfLoops*numberOfThreads,
                String.format("%.2f", (double) 100 * countLoops/ (numberOfLoops*numberOfThreads)),
                correct,
                errors,
                totalExecutionTime / Math.max(1,correct),
                String.format("%.1f", localThroughput),
                numberOfThreads);
    }


    /**
     * getReport Each thread maintains a report
     * @param prefix
     * @return
     */
    public Report getReport(int prefix) {
        return reportCreator.computeIfAbsent(prefix, Report::new);
    }

    public synchronized List<Report> collectReports() {
        List<Report> reportList = new ArrayList<>();
        reportCreator.forEach((threadName, report) -> reportList.add(report.getCopy()));
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
}


