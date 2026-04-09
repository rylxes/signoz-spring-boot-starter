package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.properties.SigNozAlertProperties;
import io.signoz.springboot.properties.SigNozAsyncProperties;
import io.signoz.springboot.properties.SigNozAuditProperties;
import io.signoz.springboot.properties.SigNozDatabaseProperties;
import io.signoz.springboot.properties.SigNozErrorProperties;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozMessagingProperties;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import io.signoz.springboot.properties.SigNozProperties;
import io.signoz.springboot.properties.SigNozSamplingProperties;
import io.signoz.springboot.properties.SigNozTimedProperties;
import io.signoz.springboot.properties.SigNozTracingProperties;
import io.signoz.springboot.properties.SigNozUserContextProperties;
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
        SigNozAuditProperties.class,
        SigNozTimedProperties.class,
        SigNozOutboundProperties.class,
        SigNozMessagingProperties.class,
        SigNozDatabaseProperties.class,
        SigNozUserContextProperties.class,
        SigNozAlertProperties.class,
        SigNozErrorProperties.class,
        SigNozAsyncProperties.class,
        SigNozSamplingProperties.class
})
@Import({
        SigNozLoggingAutoConfiguration.class,
        SigNozTracingAutoConfiguration.class,
        SigNozWebAutoConfiguration.class,
        SigNozAuditAutoConfiguration.class,
        SigNozMetricsAutoConfiguration.class,
        SigNozTimedAutoConfiguration.class,
        SigNozAlertAutoConfiguration.class,
        SigNozOutboundAutoConfiguration.class,
        SigNozMessagingAutoConfiguration.class,
        SigNozDatabaseAutoConfiguration.class,
        SigNozUserContextAutoConfiguration.class,
        SigNozAsyncAutoConfiguration.class,
        SigNozDiagnosticsAutoConfiguration.class
})
public class SigNozAutoConfiguration {
}
