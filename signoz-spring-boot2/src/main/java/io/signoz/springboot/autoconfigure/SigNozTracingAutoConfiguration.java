package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.tracing.OpenTelemetrySdkConfig;
import io.signoz.springboot.tracing.SigNozTracer;
import io.signoz.springboot.tracing.TracedAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Auto-configures OpenTelemetry tracing beans:
 * {@link OpenTelemetrySdkConfig}, {@link SigNozTracer}, {@link TracedAspect}.
 */
@Configuration
@ConditionalOnProperty(name = "signoz.tracing.enabled", havingValue = "true", matchIfMissing = true)
@Import(OpenTelemetrySdkConfig.class)
public class SigNozTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SigNozTracer sigNozTracer(io.opentelemetry.api.OpenTelemetry openTelemetry) {
        return new SigNozTracer(openTelemetry);
    }

    @Bean
    @ConditionalOnMissingBean
    public TracedAspect tracedAspect(SigNozTracer sigNozTracer) {
        return new TracedAspect(sigNozTracer);
    }
}
