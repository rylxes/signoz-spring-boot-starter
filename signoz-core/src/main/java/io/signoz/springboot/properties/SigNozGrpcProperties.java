package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for gRPC trace propagation under {@code signoz.grpc.*}.
 *
 * <pre>
 * signoz:
 *   grpc:
 *     enabled: true
 *     propagate-trace: true
 * </pre>
 *
 * <p>When the OpenTelemetry Java Agent is present, all beans gated by this
 * configuration are skipped — the agent owns gRPC instrumentation. These
 * settings only apply in the agentless path.
 */
@ConfigurationProperties(prefix = "signoz.grpc")
public class SigNozGrpcProperties {

    /** Whether gRPC tracing is enabled. */
    private boolean enabled = true;

    /** Whether to propagate W3C trace context via gRPC Metadata. */
    private boolean propagateTrace = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isPropagateTrace() { return propagateTrace; }
    public void setPropagateTrace(boolean propagateTrace) { this.propagateTrace = propagateTrace; }
}
