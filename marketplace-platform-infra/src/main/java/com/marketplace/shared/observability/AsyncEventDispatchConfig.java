package com.marketplace.shared.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

@Configuration
class AsyncEventDispatchConfig {

    private static final String APPLICATION_TASK_EXECUTOR_BEAN_NAME = "applicationTaskExecutor";

    @Bean(name = APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    AsyncTaskExecutor applicationTaskExecutor() {
        return new VirtualThreadTaskExecutor("app-async-");
    }
}
