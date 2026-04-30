package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.detect.OnMissingAgentCondition;
import io.signoz.springboot.sqs.AwspringV2SqsListenerTraceAspect;
import io.signoz.springboot.sqs.AwspringV3SqsListenerTraceAspect;
import io.signoz.springboot.sqs.LegacySpringCloudAwsSqsListenerTraceAspect;
import io.signoz.springboot.sqs.TracingSqsRequestHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures AWS SQS trace-context propagation for the agentless path.
 *
 * <p>Enabled when:
 * <ul>
 *   <li>The OpenTelemetry Java Agent is <em>not</em> present
 *       ({@link OnMissingAgentCondition}). When the agent is on, it owns SQS
 *       instrumentation and these beans are skipped to avoid double-injection.</li>
 *   <li>{@code signoz.sqs.enabled} is {@code true} (default).</li>
 * </ul>
 *
 * <p><b>Producer wiring:</b> when AWS SDK v1 is present, the
 * {@link TracingSqsRequestHandler} bean must be attached to your SQS client at
 * construction:
 * <pre>{@code
 * @Bean
 * AmazonSQSAsync sqs(TracingSqsRequestHandler tracingHandler) {
 *     return AmazonSQSAsyncClientBuilder.standard()
 *         .withRequestHandlers(tracingHandler)
 *         .build();
 * }
 * }</pre>
 * (Same pattern as the existing Kafka {@code TracingProducerInterceptor}.)
 *
 * <p><b>Consumer wiring:</b> the appropriate {@code @SqsListener} aspect is
 * registered automatically based on which annotation flavor is on the
 * classpath (legacy Spring Cloud AWS, awspring 2.x, or awspring 3.x).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OnMissingAgentCondition.class)
@ConditionalOnProperty(name = "signoz.sqs.enabled", havingValue = "true", matchIfMissing = true)
public class SigNozSqsAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.amazonaws.handlers.RequestHandler2")
    static class AwsSdkV1ProducerConfig {
        @Bean
        @ConditionalOnMissingBean
        public TracingSqsRequestHandler tracingSqsRequestHandler() {
            return new TracingSqsRequestHandler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.awspring.cloud.messaging.listener.annotation.SqsListener")
    static class AwspringV2ListenerConfig {
        @Bean
        @ConditionalOnMissingBean
        public AwspringV2SqsListenerTraceAspect awspringV2SqsListenerTraceAspect() {
            return new AwspringV2SqsListenerTraceAspect();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.awspring.cloud.sqs.annotation.SqsListener")
    static class AwspringV3ListenerConfig {
        @Bean
        @ConditionalOnMissingBean
        public AwspringV3SqsListenerTraceAspect awspringV3SqsListenerTraceAspect() {
            return new AwspringV3SqsListenerTraceAspect();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cloud.aws.messaging.listener.annotation.SqsListener")
    static class LegacyListenerConfig {
        @Bean
        @ConditionalOnMissingBean
        public LegacySpringCloudAwsSqsListenerTraceAspect legacySpringCloudAwsSqsListenerTraceAspect() {
            return new LegacySpringCloudAwsSqsListenerTraceAspect();
        }
    }
}
