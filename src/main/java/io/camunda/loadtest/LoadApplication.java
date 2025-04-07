package io.camunda.loadtest;

import io.camunda.loadtest.loader.LoaderC8;
import io.camunda.loadtest.worker.SimpleWorker;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientConfiguration;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.CompletableFuture;

@SpringBootApplication
@EnableScheduling  // Enables the Spring scheduler

public class LoadApplication {
    private static final Logger logger = LoggerFactory.getLogger(LoadApplication.class);


    @Autowired
    ZeebeClient zeebe;
    ZeebeClientConfiguration config;
    @Autowired
    SimpleWorker simpleWorker;

    @Autowired
    LoaderC8 loaderC8;

    public static void main(String[] args) {
        SpringApplication.run(LoadApplication.class, args);
    }

    @PostConstruct
    public void init() {
        logger.info("Start LoadApplication ZeebeGrpcAddress[{}] ", config == null ? "null" : config.getGrpcAddress());
        CompletableFuture.runAsync(() -> {
            logger.info("Initialize application in thread {}", Thread.currentThread().getName());
            simpleWorker.initialize();
            loaderC8.initialize();
        });

    }
}