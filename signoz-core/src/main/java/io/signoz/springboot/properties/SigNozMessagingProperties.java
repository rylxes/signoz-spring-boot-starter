package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for messaging (Kafka) tracing under {@code signoz.messaging.*}.
 *
 * <pre>
 * signoz:
 *   messaging:
 *     enabled: true
 *     propagate-trace: true
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.messaging")
public class SigNozMessagingProperties {

    /** Whether messaging tracing is enabled. */
    private boolean enabled = true;

    /** Whether to propagate trace context via Kafka record headers. */
    private boolean propagateTrace = true;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isPropagateTrace() { return propagateTrace; }
    public void setPropagateTrace(boolean propagateTrace) { this.propagateTrace = propagateTrace; }
}
