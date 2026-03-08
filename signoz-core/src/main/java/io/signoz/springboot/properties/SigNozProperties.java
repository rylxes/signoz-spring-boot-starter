package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

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

    @NestedConfigurationProperty
    private SigNozLoggingProperties logging = new SigNozLoggingProperties();

    @NestedConfigurationProperty
    private SigNozTracingProperties tracing = new SigNozTracingProperties();

    @NestedConfigurationProperty
    private SigNozWebProperties web = new SigNozWebProperties();

    @NestedConfigurationProperty
    private SigNozAuditProperties audit = new SigNozAuditProperties();

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

    public SigNozLoggingProperties getLogging() { return logging; }
    public void setLogging(SigNozLoggingProperties logging) { this.logging = logging; }

    public SigNozTracingProperties getTracing() { return tracing; }
    public void setTracing(SigNozTracingProperties tracing) { this.tracing = tracing; }

    public SigNozWebProperties getWeb() { return web; }
    public void setWeb(SigNozWebProperties web) { this.web = web; }

    public SigNozAuditProperties getAudit() { return audit; }
    public void setAudit(SigNozAuditProperties audit) { this.audit = audit; }
}
