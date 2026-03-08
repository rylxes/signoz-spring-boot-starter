package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.metrics.SigNozMetricsConfig;
import io.signoz.springboot.properties.SigNozProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures the Micrometer OTLP metrics registry when Micrometer is on the classpath.
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
