package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Logging-specific configuration nested under {@code signoz.logging.*}.
 *
 * <pre>
 * signoz:
 *   logging:
 *     mode: BOTH
 *     mask-enabled: true
 *     masked-fields:
 *       - password
 *       - creditCard
 *       - ssn
 *       - authorization
 *     custom-patterns:
 *       - name: internalToken
 *         regex: "token=[A-Za-z0-9]+"
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.logging")
public class SigNozLoggingProperties {

    /** Output mode for log records. */
    public enum LoggingMode {
        /** Send logs to SigNoz via OTLP gRPC. */
        OTLP,
        /** Write structured JSON to stdout/file. */
        JSON,
        /** Both OTLP and JSON simultaneously (default). */
        BOTH
    }

    private LoggingMode mode = LoggingMode.BOTH;

    /** Whether sensitive field masking is enabled. Defaults to {@code true}. */
    private boolean maskEnabled = true;

    /**
     * List of field names whose values should be fully masked in log output.
     * Matching is case-insensitive. Built-in defaults are merged with this list.
     */
    private List<String> maskedFields = new ArrayList<>(Arrays.asList(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "creditcard", "cardnumber", "card_number", "cvv",
            "ssn", "authorization", "x-api-key", "x-auth-token"
    ));

    /**
     * Custom regex-based masking patterns. Each entry specifies a name and a
     * regex; any log message or JSON field value matching the regex is masked.
     */
    private List<PatternConfig> customPatterns = new ArrayList<>();

    /** Whether to include the MDC context map in every log record. Default: {@code true}. */
    private boolean includeMdc = true;

    /** Whether to include caller (class + line) info. Slightly expensive. Default: {@code false}. */
    private boolean includeCallerData = false;

    // --- Nested type ---

    public static class PatternConfig {
        /** Human-readable name for the pattern (used in debug messages). */
        private String name;
        /** Java regex. The entire match is replaced with {@code "***"}. */
        private String regex;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getRegex() { return regex; }
        public void setRegex(String regex) { this.regex = regex; }
    }

    // --- Getters & Setters ---

    public LoggingMode getMode() { return mode; }
    public void setMode(LoggingMode mode) { this.mode = mode; }

    public boolean isMaskEnabled() { return maskEnabled; }
    public void setMaskEnabled(boolean maskEnabled) { this.maskEnabled = maskEnabled; }

    public List<String> getMaskedFields() { return maskedFields; }
    public void setMaskedFields(List<String> maskedFields) { this.maskedFields = maskedFields; }

    public List<PatternConfig> getCustomPatterns() { return customPatterns; }
    public void setCustomPatterns(List<PatternConfig> customPatterns) {
        this.customPatterns = customPatterns;
    }

    public boolean isIncludeMdc() { return includeMdc; }
    public void setIncludeMdc(boolean includeMdc) { this.includeMdc = includeMdc; }

    public boolean isIncludeCallerData() { return includeCallerData; }
    public void setIncludeCallerData(boolean includeCallerData) {
        this.includeCallerData = includeCallerData;
    }
}
