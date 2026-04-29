package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for WebSocket / STOMP trace propagation under
 * {@code signoz.websocket.*}.
 *
 * <pre>
 * signoz:
 *   websocket:
 *     enabled: true
 *     propagate-trace: true
 * </pre>
 *
 * <p>When the OpenTelemetry Java Agent is present, all beans gated by this
 * configuration are skipped — the agent owns WebSocket instrumentation. These
 * settings only apply in the agentless path.
 */
@ConfigurationProperties(prefix = "signoz.websocket")
public class SigNozWebSocketProperties {

    /** Whether WebSocket tracing is enabled. */
    private boolean enabled = true;

    /** Whether to propagate W3C trace context via STOMP headers and handshake attributes. */
    private boolean propagateTrace = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isPropagateTrace() { return propagateTrace; }
    public void setPropagateTrace(boolean propagateTrace) { this.propagateTrace = propagateTrace; }
}
