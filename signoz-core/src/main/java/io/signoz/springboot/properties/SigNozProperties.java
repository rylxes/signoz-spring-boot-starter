package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Root configuration properties for the SigNoz Spring Boot Starter.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * signoz:
 *   endpoint: http://localhost:4317
 *   service-name: my-app
 *   service-version: 1.0.0
 *   environment: production
 *   enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz")
public class SigNozProperties {

    /** Whether the SigNoz integration is enabled. Defaults to {@code true}. */
    private boolean enabled = true;

    /** OTLP gRPC endpoint for SigNoz (collector or direct). Default: localhost:4317. */
    private String endpoint = "http://localhost:4317";

    /** Logical name of this service as it appears in SigNoz. */
    private String serviceName = "application";

    /** Semantic version of the service (e.g. {@code "1.0.0"}). */
    private String serviceVersion = "unknown";

    /** Deployment environment tag (e.g. {@code "production"}, {@code "staging"}). */
    private String environment = "default";

    /**
     * Custom headers to send with OTLP export requests.
     * Used for SigNoz Cloud authentication when the OpenTelemetry Java Agent is not present.
     * <pre>
     * signoz:
     *   headers:
     *     signoz-ingestion-key: your-key-here
     * </pre>
     * When the OTEL Java Agent is active, these headers are ignored (the agent handles auth
     * via {@code OTEL_EXPORTER_OTLP_HEADERS} env var).
     */
    private Map<String, String> headers = new HashMap<String, String>();

    @NestedConfigurationProperty
    private SigNozLoggingProperties logging = new SigNozLoggingProperties();

    @NestedConfigurationProperty
    private SigNozTracingProperties tracing = new SigNozTracingProperties();

    @NestedConfigurationProperty
    private SigNozWebProperties web = new SigNozWebProperties();

    @NestedConfigurationProperty
    private SigNozAuditProperties audit = new SigNozAuditProperties();

    @NestedConfigurationProperty
    private SigNozTimedProperties timed = new SigNozTimedProperties();

    @NestedConfigurationProperty
    private SigNozOutboundProperties outbound = new SigNozOutboundProperties();

    @NestedConfigurationProperty
    private SigNozMessagingProperties messaging = new SigNozMessagingProperties();

    @NestedConfigurationProperty
    private SigNozDatabaseProperties database = new SigNozDatabaseProperties();

    @NestedConfigurationProperty
    private SigNozUserContextProperties userContext = new SigNozUserContextProperties();

    @NestedConfigurationProperty
    private SigNozAlertProperties alerts = new SigNozAlertProperties();

    @NestedConfigurationProperty
    private SigNozErrorProperties errors = new SigNozErrorProperties();

    @NestedConfigurationProperty
    private SigNozAsyncProperties async = new SigNozAsyncProperties();

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getServiceVersion() { return serviceVersion; }
    public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public SigNozLoggingProperties getLogging() { return logging; }
    public void setLogging(SigNozLoggingProperties logging) { this.logging = logging; }

    public SigNozTracingProperties getTracing() { return tracing; }
    public void setTracing(SigNozTracingProperties tracing) { this.tracing = tracing; }

    public SigNozWebProperties getWeb() { return web; }
    public void setWeb(SigNozWebProperties web) { this.web = web; }

    public SigNozAuditProperties getAudit() { return audit; }
    public void setAudit(SigNozAuditProperties audit) { this.audit = audit; }

    public SigNozTimedProperties getTimed() { return timed; }
    public void setTimed(SigNozTimedProperties timed) { this.timed = timed; }

    public SigNozOutboundProperties getOutbound() { return outbound; }
    public void setOutbound(SigNozOutboundProperties outbound) { this.outbound = outbound; }

    public SigNozMessagingProperties getMessaging() { return messaging; }
    public void setMessaging(SigNozMessagingProperties messaging) { this.messaging = messaging; }

    public SigNozDatabaseProperties getDatabase() { return database; }
    public void setDatabase(SigNozDatabaseProperties database) { this.database = database; }

    public SigNozUserContextProperties getUserContext() { return userContext; }
    public void setUserContext(SigNozUserContextProperties userContext) { this.userContext = userContext; }

    public SigNozAlertProperties getAlerts() { return alerts; }
    public void setAlerts(SigNozAlertProperties alerts) { this.alerts = alerts; }

    public SigNozErrorProperties getErrors() { return errors; }
    public void setErrors(SigNozErrorProperties errors) { this.errors = errors; }

    public SigNozAsyncProperties getAsync() { return async; }
    public void setAsync(SigNozAsyncProperties async) { this.async = async; }
}
