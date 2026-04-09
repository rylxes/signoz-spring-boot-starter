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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Root auto-configuration entry point for the SigNoz Spring Boot 2.x starter.
 *
 * <p>Imports all sub-configurations in dependency order:
 * <ol>
 *   <li>Logging (masking registry, OTLP appender)</li>
 *   <li>Tracing (OTel SDK, tracer, {@code @Traced} aspect)</li>
 *   <li>Web (MDC filter, HTTP logging filter, {@code @Masked} aspect)</li>
 *   <li>Audit ({@code @AuditLog} aspect, audit event handler)</li>
 *   <li>Metrics (Micrometer OTLP registry)</li>
 * </ol>
 *
 * <p>All features are individually toggleable via {@code signoz.*} properties.
 * Set {@code signoz.enabled=false} to disable the entire integration.
 */
@Configuration
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
    // Configuration assembled via @Import — no additional beans required here.
}
