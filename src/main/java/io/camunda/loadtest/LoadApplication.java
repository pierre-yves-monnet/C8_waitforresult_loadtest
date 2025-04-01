package io.camunda.loadtest;

import io.camunda.loadtest.loader.LoaderC8;
import io.camunda.loadtest.worker.SimpleWorker;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.concurrent.CompletableFuture;

@SpringBootApplication
public class LoadApplication {
    private static final Logger logger = LoggerFactory.getLogger(LoadApplication.class);


    @Autowired
    SimpleWorker simpleWorker;

    @Autowired
    LoaderC8 loaderC8;
    public static void main(String[] args) {
        SpringApplication.run(LoadApplication.class, args);
    }

    @PostConstruct
    public void init() {
        logger.info("Start LoadApplica");
        CompletableFuture.runAsync(() -> {
            logger.info("Initialize application in thread {}",Thread.currentThread().getName());
            simpleWorker.initialize();
            loaderC8.initialize();
        });

    }
}