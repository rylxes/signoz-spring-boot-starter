package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Central registry that maps field names and patterns to their {@link MaskingStrategy}.
 *
 * <p>At startup it merges:
 * <ol>
 *   <li>Built-in rules for common sensitive fields (password, creditCard, ssn, etc.)</li>
 *   <li>User-configured field names from {@code signoz.logging.masked-fields}</li>
 *   <li>User-configured regex patterns from {@code signoz.logging.custom-patterns}</li>
 * </ol>
 *
 * <p>Consumers call {@link #mask(String, String)} to mask a value by field name,
 * or {@link #maskMessage(String)} to scan a free-form log message for patterns.
 */
@Component
public class MaskingRegistry {

    /** Field-name → strategy map (case-insensitive lookup). */
    private final Map<String, MaskingStrategy> fieldStrategies = new HashMap<String, MaskingStrategy>();

    /** Ordered list of regex strategies applied to free-form messages. */
    private final List<RegexMaskingStrategy> messagePatterns = new ArrayList<RegexMaskingStrategy>();

    private final boolean maskEnabled;

    public MaskingRegistry(SigNozLoggingProperties loggingProps) {
        this.maskEnabled = loggingProps.isMaskEnabled();

        if (!maskEnabled) {
            return;
        }

        // Register user-configured field names with FULL masking
        FullMaskingStrategy full = new FullMaskingStrategy();
        for (String field : loggingProps.getMaskedFields()) {
            fieldStrategies.put(field.toLowerCase(), full);
        }

        // Register credit card with partial masking (show last 4)
        PartialMaskingStrategy partialCard = new PartialMaskingStrategy(0, 4, '*');
        for (String cardField : new String[]{"creditcard", "cardnumber", "card_number", "pan"}) {
            fieldStrategies.put(cardField, partialCard);
        }

        // Register custom regex patterns
        for (SigNozLoggingProperties.PatternConfig pc : loggingProps.getCustomPatterns()) {
            if (pc.getRegex() != null && !pc.getRegex().isEmpty()) {
                messagePatterns.add(RegexMaskingStrategy.fullMatch(pc.getRegex()));
            }
        }

        // Built-in message-level patterns (applied to free-form log text)
        messagePatterns.add(new RegexMaskingStrategy(
                "(?i)(password|passwd|pwd)\\s*[=:\\s]+\\s*(\\S+)",
                "$1=***"));
        messagePatterns.add(new RegexMaskingStrategy(
                "(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}",
                "$1***"));
        messagePatterns.add(new RegexMaskingStrategy(
                "\\b(?:\\d{4}[\\s\\-]?){3}\\d{4}\\b",
                "****-****-****-****"));
        messagePatterns.add(new RegexMaskingStrategy(
                "\\b\\d{3}-\\d{2}-\\d{4}\\b",
                "***-**-****"));
    }

    /**
     * Returns the masked value for a given field name and raw value.
     * If the field is not registered for masking, the raw value is returned unchanged.
     *
     * @param fieldName case-insensitive field name
     * @param rawValue  value to potentially mask
     * @return masked value, or {@code rawValue} if not a sensitive field
     */
    public String mask(String fieldName, String rawValue) {
        if (!maskEnabled || fieldName == null) {
            return rawValue;
        }
        MaskingStrategy strategy = fieldStrategies.get(fieldName.toLowerCase());
        if (strategy != null) {
            // Delegate to strategy even for null values so e.g. FullMaskingStrategy returns "***"
            return strategy.mask(fieldName, rawValue);
        }
        return rawValue;
    }

    /**
     * Applies all registered message-level regex patterns to a free-form log message.
     *
     * @param message the raw log message
     * @return the message with sensitive patterns replaced
     */
    public String maskMessage(String message) {
        if (!maskEnabled || message == null || message.isEmpty()) {
            return message;
        }
        String result = message;
        for (RegexMaskingStrategy strategy : messagePatterns) {
            // Use strategy.mask() so the strategy's own replacement template (e.g. "$1***") is applied
            result = strategy.mask(null, result);
        }
        return result;
    }

    /**
     * Whether the given field name should be masked.
     */
    public boolean isSensitiveField(String fieldName) {
        return maskEnabled && fieldName != null
                && fieldStrategies.containsKey(fieldName.toLowerCase());
    }

    /**
     * Registers a custom strategy for a specific field name at runtime.
     */
    public void register(String fieldName, MaskingStrategy strategy) {
        if (fieldName != null && strategy != null) {
            fieldStrategies.put(fieldName.toLowerCase(), strategy);
        }
    }

    /**
     * Masks all values in a simple JSON string by scanning for
     * {@code "fieldName":"value"} patterns where the field is sensitive.
     */
    public String maskJsonString(String json) {
        if (!maskEnabled || json == null || json.isEmpty()) {
            return json;
        }
        // Apply regex-based message patterns first
        String result = maskMessage(json);

        // Then mask by field name: "fieldName":"value" or "fieldName": "value"
        for (Map.Entry<String, MaskingStrategy> entry : fieldStrategies.entrySet()) {
            String fieldName = entry.getKey();
            MaskingStrategy strategy = entry.getValue();
            // Pattern matches JSON field: "fieldName" : "value" or "fieldName":"value"
            Pattern p = Pattern.compile(
                    "(?i)(\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\")(.*?)(\")",
                    Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String masked = strategy.mask(fieldName, m.group(2));
                m.appendReplacement(sb, m.group(1) + masked + m.group(3));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }
}
