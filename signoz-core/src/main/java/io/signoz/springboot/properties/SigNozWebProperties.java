package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HTTP request/response logging configuration under {@code signoz.web.*}.
 *
 * <pre>
 * signoz:
 *   web:
 *     log-requests: true
 *     log-response-body: false
 *     max-body-bytes: 4096
 *     exclude-paths:
 *       - /actuator/**
 *       - /health
 *       - /favicon.ico
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.web")
public class SigNozWebProperties {

    /** Whether to log all inbound HTTP requests and their responses. */
    private boolean logRequests = true;

    /**
     * Whether to capture and log the response body.
     * Disable for endpoints returning large payloads (files, binary streams).
     * Defaults to {@code false}.
     */
    private boolean logResponseBody = false;

    /**
     * Whether to capture and log the request body.
     * Defaults to {@code true} for POST/PUT/PATCH requests only.
     */
    private boolean logRequestBody = true;

    /**
     * Maximum size of request/response body to include in the log entry.
     * Bodies larger than this are truncated. Defaults to 4096 bytes (4 KB).
     */
    private int maxBodyBytes = 4096;

    /**
     * Ant-style path patterns to exclude from HTTP logging.
     * Actuator and health endpoints are excluded by default.
     */
    private List<String> excludePaths = new ArrayList<>(Arrays.asList(
            "/actuator/**",
            "/health",
            "/health/**",
            "/favicon.ico"
    ));

    /**
     * Whether to log request headers. Sensitive headers (Authorization, Cookie)
     * are automatically masked via the masking registry.
     */
    private boolean logHeaders = false;

    /** Whether to include query string parameters in the logged URI. */
    private boolean logQueryString = true;

    // --- Getters & Setters ---

    public boolean isLogRequests() { return logRequests; }
    public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }

    public boolean isLogResponseBody() { return logResponseBody; }
    public void setLogResponseBody(boolean logResponseBody) { this.logResponseBody = logResponseBody; }

    public boolean isLogRequestBody() { return logRequestBody; }
    public void setLogRequestBody(boolean logRequestBody) { this.logRequestBody = logRequestBody; }

    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }

    public List<String> getExcludePaths() { return excludePaths; }
    public void setExcludePaths(List<String> excludePaths) { this.excludePaths = excludePaths; }

    public boolean isLogHeaders() { return logHeaders; }
    public void setLogHeaders(boolean logHeaders) { this.logHeaders = logHeaders; }

    public boolean isLogQueryString() { return logQueryString; }
    public void setLogQueryString(boolean logQueryString) { this.logQueryString = logQueryString; }
}
