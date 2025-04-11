package io.camunda.loadtest.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Reporting {
    Logger logger = LoggerFactory.getLogger(Reporting.class.getName());

    private Map<String, Map<Integer, Report>> report = new LinkedHashMap<>();



    @Scheduled(fixedRate = 60000) // Runs every 60 seconds
    public void runTask() {
        int bigSumLoops = 0;
        int bigSumNumberOfLoops = 0;
        int bigSumCorrects = 0;
        int bigSumErrors = 0;
        int bigSumTimeouts = 0;
        double bigSumThroughput = 0;
        int bigSumThreads=0;
        int bigSumTotalExecutionTime = 0;
        for (String type : report.keySet()) {
            int countLoops = 0;
            int numberOfLoops = 0;
            int corrects = 0;
            int errors = 0;
            int timeouts = 0;
            int totalExecutionTime = 0;
            List<Report> listReport = collectReports(type);

            double localThroughput = 0;

            int sumNotStarted=0;
            int sumRunning=0;
            int sumFinished=0;

            int countAgents = 0;
            Map<Integer, Report> reportOneType = report.get(type);
            bigSumThreads+= reportOneType.size();

            for (Report report : reportOneType.values()) {
                countAgents++;
                countLoops += report.countLoops;
                numberOfLoops += report.numberOfLoops;
                corrects += report.corrects;
                errors += report.errors;
                timeouts += report.timeouts;
                totalExecutionTime += report.totalExecutionTime;
                double reportThroughput = report.calculateThroughput();
                if (reportThroughput >= 0) {
                    localThroughput += reportThroughput;
                }
                if (report.beginTime ==null)
                    sumNotStarted++; //
                else if (report.endTime == null)
                    sumRunning++;
                else
                    sumFinished++;
            }
            logger.info("----- SYNTHESIS {}:  (start/running/finish) {}/{}/{} AVERAGE {} ms, THROUGHPUT {} exec/s loop {}/{} ({} %) corrects/errors/timeout:{}/{}/{} for {} agents",
                    type.toString(),
                    sumNotStarted, sumRunning,sumFinished,
                    totalExecutionTime / Math.max(1, corrects),
                    String.format("%,.1f", localThroughput),
                    countLoops,
                    numberOfLoops * reportOneType.size(),
                    String.format("%,.2f", (double) 100 * countLoops / (numberOfLoops * reportOneType.size())),
                    corrects,
                    errors,
                    bigSumTimeouts,
                    countAgents);

            bigSumLoops += countLoops;
            bigSumNumberOfLoops = numberOfLoops;
            bigSumCorrects += corrects;
            bigSumErrors += errors;
            bigSumTimeouts += timeouts;
            bigSumTotalExecutionTime += totalExecutionTime;
            bigSumThroughput += localThroughput;

        }
        logger.info("----- SYNTHESIS TOTAL:  AVERAGE {} ms, THROUGHPUT {} exec/s loop {}/{} ({} %) corrects/errors/timeout:{}/{}/{} for {} agents",
                bigSumTotalExecutionTime / Math.max(1, bigSumCorrects),
                String.format("%,.1f", bigSumThroughput),
                bigSumLoops,
                bigSumNumberOfLoops,
                String.format("%,.2f", (double) 100 * bigSumLoops / (Math.max(1, bigSumNumberOfLoops))),
                bigSumCorrects,
                bigSumErrors,
                bigSumTimeouts,
                bigSumThreads);
    }


    public void addReport(String type) {
        report.put(type,new ConcurrentHashMap<>());
    }
    /**
     * getReport Each thread maintains a report
     *
     * @param type   of report: CREATION, MESSAGE
     * @param prefix
     * @return
     */

    public Report getReport(String type, int prefix) {

        Map<Integer, Report> reportCreator = report.get(type);
        return reportCreator.computeIfAbsent(prefix, Report::new);
    }

    public synchronized List<Report> collectReports(String typeReport) {
        List<Report> reportList = new ArrayList<>();
        report.get(typeReport).forEach((threadName, report) -> reportList.add(report.getCopy()));
        return reportList;
    }


    public static class Report {
        public int countLoops;
        public int numberOfLoops;
        public int corrects;
        public int errors;
        public int timeouts;
        public long totalExecutionTime;
        public int prefix;
        public Long beginTime=null;
        public Long endTime = null;

        public Report(int prefix) {
            this.prefix = prefix;
        }

        public Report getCopy() {
            Report report = new Report(prefix);
            report.countLoops = countLoops;
            report.numberOfLoops = numberOfLoops;
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
            if (beginTime == null || corrects == 0) {
                return 0;
            }
            long currentTime = System.currentTimeMillis();
            long endTimeSnapshot = endTime == null ? currentTime : endTime;
            return (1000.0 * corrects / (endTimeSnapshot - beginTime));
        }

        public void markBeginTime() {
            beginTime = System.currentTimeMillis();
        }

        public void markEndTime() {
            endTime = System.currentTimeMillis();
        }
    }


}
