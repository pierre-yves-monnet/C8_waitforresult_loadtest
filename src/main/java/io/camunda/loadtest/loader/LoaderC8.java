package io.camunda.loadtest.loader;

import io.camunda.loadtest.executor.ExecuteWithResult;
import io.camunda.loadtest.executor.WithResultAPI;
import io.camunda.zeebe.client.ZeebeClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public void initialize() {
        if (enabled) {
            logger.info("Enable Creator numberOfThreads {} Loops {}", numberOfThreads, numberOfLoops);

            withResultAPI = new WithResultAPI(zeebeClient, false, false);
            ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

            for (int i = 0; i < numberOfThreads; i++) {
                int finalI = i;
                executor.submit(() -> executeCreation(finalI, numberOfLoops));
            }
        }
        else {
            logger.info("Disable Creator");
        }

    }

    public final void executeCreation(int prefix, int numberOfLoops) {

        if (numberOfLoops == 0)
            return;
        logger.info("Start creator {} for Loops:{}", prefix, numberOfLoops);
        long totalExecutionTime = 0;
        int correct=0;
        int errors=0;
        for (int i = 0; i < numberOfLoops; i++) {
            Map<String, Object> variables = new HashMap<>();


            try {
                String jobKey = prefix + "_" + i;

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
            if (i % 100 == 0 && correct > 0) {
                logger.info("Creator {} loop {}/{} correct {} error {} averageTime/creation {}", prefix, (i+1), numberOfLoops, correct, errors, totalExecutionTime / correct);
            }
        }
        logger.info("Creator {} loop {} correct {} error {} averageTime/creation {}", prefix, numberOfLoops, correct, errors, totalExecutionTime / correct);


    }
}


