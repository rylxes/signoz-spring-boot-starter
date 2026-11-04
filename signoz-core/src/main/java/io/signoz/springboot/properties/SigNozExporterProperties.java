package io.signoz.springboot.properties;

/**
 * How telemetry is exported over OTLP ({@code signoz.exporter.*}). The defaults keep the
 * long-standing behaviour: OTLP gRPC to {@code signoz.endpoint}.
 *
 * <p>Grafana Cloud (Loki, Tempo, Mimir behind the OTLP gateway):
 * <pre>
 * signoz:
 *   endpoint: https://otlp-gateway-prod-eu-west-2.grafana.net/otlp
 *   exporter:
 *     backend: grafana-cloud          # implies protocol http/protobuf
 *     basic-auth:
 *       username: 123456              # Grafana Cloud instance ID
 *       password: ${GRAFANA_CLOUD_TOKEN}
 * </pre>
 *
 * <p>Self-hosted Grafana stack with a separate URL per signal:
 * <pre>
 * signoz:
 *   exporter:
 *     protocol: http/protobuf
 *     logs-endpoint: http://loki:3100/otlp/v1/logs
 *     traces-endpoint: http://tempo:4318/v1/traces
 *     metrics-endpoint: http://mimir:9009/otlp/v1/metrics
 * </pre>
 */
public class SigNozExporterProperties {

    /** Where telemetry goes. Only changes defaults; every other setting can still be set. */
    public enum Backend {
        /** SigNoz (self-hosted or Cloud): OTLP gRPC by default. */
        SIGNOZ,
        /** Grafana Cloud OTLP gateway: OTLP over HTTP (the gateway accepts no gRPC). */
        GRAFANA_CLOUD,
        /** Any other OTLP receiver (OTel Collector, Grafana Alloy, self-hosted Loki/Tempo, …). */
        OTLP
    }

    private Backend backend = Backend.SIGNOZ;

    /**
     * {@code grpc} or {@code http/protobuf} (also {@code http}). Unset means the backend's
     * default: {@code http/protobuf} for {@code grafana-cloud}, {@code grpc} otherwise.
     */
    private String protocol;

    /**
     * Full OTLP endpoint for logs. For {@code http/protobuf} this is the complete URL
     * (e.g. {@code http://loki:3100/otlp/v1/logs}); unset means {@code signoz.endpoint} plus
     * {@code /v1/logs}. For {@code grpc}, unset means {@code signoz.endpoint}.
     */
    private String logsEndpoint;

    /** Full OTLP endpoint for traces; see {@link #getLogsEndpoint()}. */
    private String tracesEndpoint;

    /** Full OTLP endpoint for metrics; see {@link #getLogsEndpoint()}. */
    private String metricsEndpoint;

    /** HTTP Basic credentials, sent as an {@code Authorization} header (Grafana Cloud). */
    private BasicAuth basicAuth = new BasicAuth();

    public static class BasicAuth {
        /** User name; for Grafana Cloud, the stack's instance ID. */
        private String username;
        /** Password; for Grafana Cloud, an access policy token with write scopes. */
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public Backend getBackend() { return backend; }
    public void setBackend(Backend backend) { this.backend = backend; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getLogsEndpoint() { return logsEndpoint; }
    public void setLogsEndpoint(String logsEndpoint) { this.logsEndpoint = logsEndpoint; }
    public String getTracesEndpoint() { return tracesEndpoint; }
    public void setTracesEndpoint(String tracesEndpoint) { this.tracesEndpoint = tracesEndpoint; }
    public String getMetricsEndpoint() { return metricsEndpoint; }
    public void setMetricsEndpoint(String metricsEndpoint) { this.metricsEndpoint = metricsEndpoint; }
    public BasicAuth getBasicAuth() { return basicAuth; }
    public void setBasicAuth(BasicAuth basicAuth) { this.basicAuth = basicAuth; }
}
