package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.messaging.TracingConsumerInterceptor;
import io.signoz.springboot.messaging.TracingProducerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures Kafka trace propagation interceptors.
 */
@Configuration
@ConditionalOnProperty(name = "signoz.messaging.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(name = "org.apache.kafka.clients.producer.ProducerInterceptor")
public class SigNozMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TracingProducerInterceptor tracingProducerInterceptor() {
        return new TracingProducerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public TracingConsumerInterceptor tracingConsumerInterceptor() {
        return new TracingConsumerInterceptor();
    }
}
