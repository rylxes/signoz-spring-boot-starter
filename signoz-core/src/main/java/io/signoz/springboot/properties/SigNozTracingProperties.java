package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tracing-specific configuration nested under {@code signoz.tracing.*}.
 *
 * <pre>
 * signoz:
 *   tracing:
 *     enabled: true
 *     sample-rate: 1.0
 *     propagation: W3C
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.tracing")
public class SigNozTracingProperties {

    /** Whether distributed tracing is enabled. Defaults to {@code true}. */
    private boolean enabled = true;

    /**
     * Sampling rate between 0.0 (no sampling) and 1.0 (sample everything).
     * Defaults to {@code 1.0} (100% sampling — suitable for development;
     * reduce for high-traffic production services).
     */
    private double sampleRate = 1.0;

    /** Propagation format for distributed context. */
    public enum PropagationFormat {
        /** W3C TraceContext (default, recommended). */
        W3C,
        /** B3 single-header format (compatible with Zipkin). */
        B3,
        /** B3 multi-header format. */
        B3_MULTI
    }

    private PropagationFormat propagation = PropagationFormat.W3C;

    /**
     * Maximum duration (in milliseconds) to wait for span export on shutdown.
     * Defaults to 5000 ms.
     */
    private long exportTimeoutMs = 5000L;

    /**
     * Batch export schedule delay in milliseconds. Defaults to 1000 ms.
     * Lower values reduce latency; higher values improve throughput.
     */
    private long exportScheduleDelayMs = 1000L;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getSampleRate() { return sampleRate; }
    public void setSampleRate(double sampleRate) { this.sampleRate = sampleRate; }

    public PropagationFormat getPropagation() { return propagation; }
    public void setPropagation(PropagationFormat propagation) { this.propagation = propagation; }

    public long getExportTimeoutMs() { return exportTimeoutMs; }
    public void setExportTimeoutMs(long exportTimeoutMs) { this.exportTimeoutMs = exportTimeoutMs; }

    public long getExportScheduleDelayMs() { return exportScheduleDelayMs; }
    public void setExportScheduleDelayMs(long exportScheduleDelayMs) {
        this.exportScheduleDelayMs = exportScheduleDelayMs;
    }
}
