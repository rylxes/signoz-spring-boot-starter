package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for database query tracing under {@code signoz.database.*}.
 *
 * <pre>
 * signoz:
 *   database:
 *     enabled: true
 *     slow-query-threshold-ms: 500
 *     log-all-queries: false
 *     max-query-length: 1000
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.database")
public class SigNozDatabaseProperties {

    /** Whether database tracing is enabled. */
    private boolean enabled = true;

    /** Threshold in milliseconds above which a query is considered slow. */
    private long slowQueryThresholdMs = 500;

    /** Whether to log all queries regardless of duration. */
    private boolean logAllQueries = false;

    /** Maximum length of SQL text to include in log entries. */
    private int maxQueryLength = 1000;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getSlowQueryThresholdMs() { return slowQueryThresholdMs; }
    public void setSlowQueryThresholdMs(long slowQueryThresholdMs) { this.slowQueryThresholdMs = slowQueryThresholdMs; }

    public boolean isLogAllQueries() { return logAllQueries; }
    public void setLogAllQueries(boolean logAllQueries) { this.logAllQueries = logAllQueries; }

    public int getMaxQueryLength() { return maxQueryLength; }
    public void setMaxQueryLength(int maxQueryLength) { this.maxQueryLength = maxQueryLength; }
}
