package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.properties.SigNozAuditProperties;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozProperties;
import io.signoz.springboot.properties.SigNozTracingProperties;
import io.signoz.springboot.properties.SigNozWebProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Root auto-configuration entry point for the SigNoz Spring Boot 3.x starter.
 *
 * <p>Identical purpose to the SB2 counterpart; uses {@code @AutoConfiguration}
 * (SB3 preferred) and imports all sub-configurations.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "signoz.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({
        SigNozProperties.class,
        SigNozLoggingProperties.class,
        SigNozTracingProperties.class,
        SigNozWebProperties.class,
        SigNozAuditProperties.class
})
@Import({
        SigNozLoggingAutoConfiguration.class,
        SigNozTracingAutoConfiguration.class,
        SigNozWebAutoConfiguration.class,
        SigNozAuditAutoConfiguration.class,
        SigNozMetricsAutoConfiguration.class
})
public class SigNozAutoConfiguration {
}
