package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.metrics.SigNozMetricsConfig;
import io.signoz.springboot.properties.SigNozProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics auto-configuration for Spring Boot 3.x.
 */
@Configuration
@ConditionalOnClass(name = {
        "io.micrometer.core.instrument.MeterRegistry",
        "io.micrometer.registry.otlp.OtlpMeterRegistry"
})
public class SigNozMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SigNozMetricsConfig sigNozMetricsConfig(SigNozProperties props) {
        return new SigNozMetricsConfig(props);
    }
}
