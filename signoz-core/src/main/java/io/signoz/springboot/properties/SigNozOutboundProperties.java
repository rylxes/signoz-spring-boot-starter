package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for outbound HTTP call tracing under {@code signoz.outbound.*}.
 *
 * <pre>
 * signoz:
 *   outbound:
 *     enabled: true
 *     log-requests: true
 *     propagate-headers: true
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.outbound")
public class SigNozOutboundProperties {

    /** Whether outbound HTTP tracing is enabled. */
    private boolean enabled = true;

    /** Whether to log outbound HTTP requests and their responses. */
    private boolean logRequests = true;

    /** Whether to propagate W3C trace context headers on outbound calls. */
    private boolean propagateHeaders = true;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isLogRequests() { return logRequests; }
    public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }

    public boolean isPropagateHeaders() { return propagateHeaders; }
    public void setPropagateHeaders(boolean propagateHeaders) { this.propagateHeaders = propagateHeaders; }
}
