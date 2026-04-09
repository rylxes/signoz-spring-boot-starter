package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.async.TracingTaskDecorator;
import io.signoz.springboot.properties.SigNozAsyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.TaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures async MDC context propagation for Spring Boot 2.x.
 *
 * <p>Registers a {@link TracingTaskDecorator} and a {@link TaskExecutorCustomizer}
 * that applies the decorator to all {@code ThreadPoolTaskExecutor} instances.
 */
@Configuration
@ConditionalOnProperty(name = "signoz.async.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SigNozAsyncProperties.class)
public class SigNozAsyncAutoConfiguration {

    /**
     * Creates the {@link TracingTaskDecorator} bean.
     *
     * @return a new task decorator instance
     */
    @Bean
    @ConditionalOnMissingBean
    public TracingTaskDecorator tracingTaskDecorator() {
        return new TracingTaskDecorator();
    }

    /**
     * Creates a {@link TaskExecutorCustomizer} that applies the tracing task decorator
     * to all auto-configured task executors.
     *
     * @param decorator the tracing task decorator
     * @return a customizer that sets the decorator
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskExecutorCustomizer sigNozTaskExecutorCustomizer(TracingTaskDecorator decorator) {
        return new TaskExecutorCustomizer() {
            @Override
            public void customize(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor) {
                executor.setTaskDecorator(decorator);
            }
        };
    }
}
