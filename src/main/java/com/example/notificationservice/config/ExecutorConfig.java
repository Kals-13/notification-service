package com.example.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService notificationDispatchExecutor() {
        return Executors.newScheduledThreadPool(10);
    }
}
