package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Curated field-set profiles to apply, e.g. {@code [pci]}.
     * See {@link io.signoz.springboot.masking.MaskingProfiles}.
     */
    private List<String> profiles = new ArrayList<>();

    /**
     * Per-field masking strategy, overriding {@code masked-fields} and any profile.
     * Values use the notation parsed by
     * {@link io.signoz.springboot.masking.MaskingStrategySpec}: {@code full},
     * {@code partial:<prefix>:<suffix>} or {@code regex:<pattern>}.
     *
     * <pre>
     * field-strategies:
     *   cardPan: "partial:6:4"
     *   clearPin: "full"
     * </pre>
     */
    private Map<String, String> fieldStrategies = new LinkedHashMap<>();

    /** Whether to include the MDC context map in every log record. Default: {@code true}. */
    private boolean includeMdc = true;

    /** Whether to include caller (class + line) info. Slightly expensive. Default: {@code false}. */
    private boolean includeCallerData = false;

    /**
     * Whether to include the Logback {@code LoggerContext} properties as top-level
     * fields on every JSON log record. Default: {@code false}.
     *
     * <p>Spring Boot publishes internal bootstrap variables
     * ({@code CONSOLE_LOG_PATTERN}, {@code CONSOLE_LOG_CHARSET}, {@code FILE_LOG_PATTERN},
     * {@code FILE_LOG_CHARSET}, {@code PID}, etc.) into the {@code LoggerContext} so its
     * default pattern layouts can resolve them. These were never intended as structured
     * log fields, so we exclude them by default. Set to {@code true} only if you
     * intentionally publish custom context properties you want emitted on every event.
     */
    private boolean includeContext = false;

    @NestedConfigurationProperty
    private SigNozSamplingProperties sampling = new SigNozSamplingProperties();

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

    public List<String> getProfiles() { return profiles; }
    public void setProfiles(List<String> profiles) {
        this.profiles = profiles != null ? profiles : new ArrayList<>();
    }

    public Map<String, String> getFieldStrategies() { return fieldStrategies; }
    public void setFieldStrategies(Map<String, String> fieldStrategies) {
        this.fieldStrategies = fieldStrategies != null ? fieldStrategies : new LinkedHashMap<>();
    }

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

    public boolean isIncludeContext() { return includeContext; }
    public void setIncludeContext(boolean includeContext) { this.includeContext = includeContext; }

    public SigNozSamplingProperties getSampling() { return sampling; }
    public void setSampling(SigNozSamplingProperties sampling) { this.sampling = sampling; }
}
