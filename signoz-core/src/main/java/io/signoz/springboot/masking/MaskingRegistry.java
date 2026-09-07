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

        // Precedence, least to most specific. Later stages overwrite earlier ones:
        //   1. built-in card defaults
        //   2. masked-fields          - the flat "mask these entirely" list
        //   3. profiles               - a curated policy, so it refines the flat list
        //                               (pci gives cardPan partial:6:4 rather than full)
        //   4. field-strategies       - explicit per-field override, always wins

        // 1. Built-in card defaults (show last 4).
        PartialMaskingStrategy partialCard = new PartialMaskingStrategy(0, 4, '*');
        for (String cardField : new String[]{"creditcard", "cardnumber", "card_number", "pan"}) {
            fieldStrategies.put(cardField, partialCard);
        }

        // 2. User-configured field names, masked in full.
        FullMaskingStrategy full = new FullMaskingStrategy();
        for (String field : loggingProps.getMaskedFields()) {
            fieldStrategies.put(field.toLowerCase(), full);
        }

        // 3. Curated profiles.
        for (String profile : loggingProps.getProfiles()) {
            fieldStrategies.putAll(MaskingProfiles.get(profile));
        }

        // 4. Explicit per-field strategies. Parse errors surface at startup rather than degrading
        //    to no masking at runtime.
        for (Map.Entry<String, String> entry : loggingProps.getFieldStrategies().entrySet()) {
            fieldStrategies.put(entry.getKey().toLowerCase(),
                    MaskingStrategySpec.parse(entry.getValue()));
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
        // Card numbers. The previous pattern was a fixed 4-4-4-4 group and so only ever matched
        // 16-digit PANs; 19-digit PANs (Verve, UnionPay, some Maestro) passed straight through.
        // ISO/IEC 7812 allows 13-19 digits, so match that range, anchored on a plausible major
        // industry identifier (3-6) to avoid swallowing epoch timestamps and numeric ids, which
        // start with 1 at present-day values.
        messagePatterns.add(new RegexMaskingStrategy(
                "\\b[3-6](?:[ -]?\\d){12,18}\\b",
                "[card-number-redacted]"));
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
     * Masks sensitive values in a rendered log payload.
     *
     * <p>Despite the name this is not JSON-only. It applies, in order: the message-level regex
     * patterns, then {@code "field":"value"} JSON pairs, then bare {@code field=value} pairs. The
     * last covers {@code application/x-www-form-urlencoded} bodies and Lombok's default
     * {@code toString} rendering, both of which are common ways a secret reaches a log line without
     * ever looking like JSON.
     */
    public String maskJsonString(String json) {
        if (!maskEnabled || json == null || json.isEmpty()) {
            return json;
        }
        // XML is parsed rather than pattern-matched: element text can be split across nodes and
        // namespace prefixes separate the field name from its value, so a regex over the serialised
        // form misses fields it appears to cover.
        if (XmlMasker.looksLikeXml(json)) {
            return XmlMasker.mask(json, this);
        }
        // Apply regex-based message patterns first
        String result = maskMessage(json);

        // Then mask by field name: "fieldName":"value" or "fieldName": "value"
        for (Map.Entry<String, MaskingStrategy> entry : fieldStrategies.entrySet()) {
            String fieldName = entry.getKey();
            MaskingStrategy strategy = entry.getValue();
            java.util.regex.Matcher m = jsonFieldPattern(fieldName).matcher(result);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String masked = strategy.mask(fieldName, m.group(2));
                // quoteReplacement is required: appendReplacement treats '$' and '\' in the
                // replacement as group syntax, so a secret or field name containing either would
                // throw (dropping the whole masking pass) or splice in unintended text.
                m.appendReplacement(sb,
                        java.util.regex.Matcher.quoteReplacement(m.group(1) + masked + m.group(3)));
            }
            m.appendTail(sb);
            result = sb.toString();

            // Then the `field=value` form. This covers two shapes JSON matching misses entirely:
            // application/x-www-form-urlencoded bodies (the conventional OAuth2 token request) and
            // Lombok's default toString rendering, which is what a DTO logged with {} produces.
            java.util.regex.Matcher kv = kvFieldPattern(fieldName).matcher(result);
            StringBuffer kvSb = new StringBuffer();
            while (kv.find()) {
                String masked = strategy.mask(fieldName, kv.group(2));
                kv.appendReplacement(kvSb,
                        java.util.regex.Matcher.quoteReplacement(kv.group(1) + masked));
            }
            kv.appendTail(kvSb);
            result = kvSb.toString();
        }
        return result;
    }

    /**
     * Compiled {@code "field":"value"} matchers, cached per field name.
     *
     * <p>These used to be compiled inside the masking loop, so every log event recompiled one
     * pattern per registered field. That cost is paid on the hot path of every appender that masks.
     */
    private final Map<String, Pattern> jsonFieldPatterns =
            new java.util.concurrent.ConcurrentHashMap<String, Pattern>();

    private Pattern jsonFieldPattern(String fieldName) {
        Pattern cached = jsonFieldPatterns.get(fieldName);
        if (cached == null) {
            cached = Pattern.compile(
                    "(\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\")(.*?)(\")",
                    Pattern.CASE_INSENSITIVE);
            jsonFieldPatterns.put(fieldName, cached);
        }
        return cached;
    }

    private final Map<String, Pattern> kvFieldPatterns =
            new java.util.concurrent.ConcurrentHashMap<String, Pattern>();

    /**
     * Matches {@code field=value} where the field name stands alone.
     *
     * <p>The leading {@code (?<![\w.-])} stops {@code pan} from matching inside {@code cardPan=},
     * which would mask from the wrong offset and leave the first characters of the real value
     * exposed. The value runs to the first separator that can legitimately end one in a form body,
     * a query string or a Lombok {@code toString}.
     */
    private Pattern kvFieldPattern(String fieldName) {
        Pattern cached = kvFieldPatterns.get(fieldName);
        if (cached == null) {
            cached = Pattern.compile(
                    "(?<![\\w.-])(" + Pattern.quote(fieldName) + "\\s*=\\s*)([^&\\s,;)\"'}\\]]*)",
                    Pattern.CASE_INSENSITIVE);
            kvFieldPatterns.put(fieldName, cached);
        }
        return cached;
    }
}
