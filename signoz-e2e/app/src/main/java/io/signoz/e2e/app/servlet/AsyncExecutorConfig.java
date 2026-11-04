package io.signoz.e2e.app.servlet;

import io.signoz.springboot.async.TracingTaskDecorator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * STOMP registers executor beans of its own, so Spring Boot does not create its task executor
 * and {@code @Async} would fall back to an undecorated {@code SimpleAsyncTaskExecutor}. This is the
 * executor the tracing guide tells such applications to declare.
 */
@Configuration
public class AsyncExecutorConfig {

    @Bean(name = "taskExecutor")
    ThreadPoolTaskExecutor taskExecutor(ObjectProvider<TracingTaskDecorator> tracing) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("e2e-async-");
        TracingTaskDecorator decorator = tracing.getIfAvailable();
        if (decorator != null) {
            executor.setTaskDecorator(decorator);
        }
        return executor;
    }
}
