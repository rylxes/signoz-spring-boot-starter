package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
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
 *
 * <p><b>How field names match.</b> Names are compared after {@linkplain #normalizeFieldName
 * normalisation}: lower-cased, with {@code _}, {@code -} and {@code .} removed. So
 * {@code access_token}, {@code accessToken}, {@code access-token} and {@code ACCESS_TOKEN} are all
 * the same field, and one entry in {@code masked-fields} covers every spelling. A name with no
 * entry of its own is still masked in full when it ends with one of
 * {@code signoz.logging.masked-field-suffixes} ({@code auth_token}, {@code clientSecret},
 * {@code X-Amz-Signature}); an entry of its own, with its own strategy, always wins.
 */
@Component
public class MaskingRegistry {

    /** Normalised field name → strategy (see {@link #normalizeFieldName}). */
    private final Map<String, MaskingStrategy> fieldStrategies = new HashMap<String, MaskingStrategy>();

    /** Normalised name endings that make an otherwise unlisted field sensitive. */
    private final List<String> sensitiveSuffixes = new ArrayList<String>();

    /** Strategy for fields matched only by a suffix. */
    private static final MaskingStrategy SUFFIX_MATCH_STRATEGY = new FullMaskingStrategy();

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
            putField(cardField, partialCard);
        }

        // 2. User-configured field names, masked in full.
        FullMaskingStrategy full = new FullMaskingStrategy();
        for (String field : loggingProps.getMaskedFields()) {
            putField(field, full);
        }

        // 3. Curated profiles.
        for (String profile : loggingProps.getProfiles()) {
            for (Map.Entry<String, MaskingStrategy> entry : MaskingProfiles.get(profile).entrySet()) {
                putField(entry.getKey(), entry.getValue());
            }
        }

        // 4. Explicit per-field strategies. Parse errors surface at startup rather than degrading
        //    to no masking at runtime.
        for (Map.Entry<String, String> entry : loggingProps.getFieldStrategies().entrySet()) {
            putField(entry.getKey(), MaskingStrategySpec.parse(entry.getValue()));
        }

        // Name endings that catch the spellings no list anticipates (auth_token, clientSecret).
        for (String suffix : loggingProps.getMaskedFieldSuffixes()) {
            String normalized = normalizeFieldName(suffix);
            if (normalized != null && !normalized.isEmpty()) {
                sensitiveSuffixes.add(normalized);
            }
        }

        // Register custom regex patterns
        for (SigNozLoggingProperties.PatternConfig pc : loggingProps.getCustomPatterns()) {
            if (pc.getRegex() != null && !pc.getRegex().isEmpty()) {
                messagePatterns.add(RegexMaskingStrategy.fullMatch(pc.getRegex()));
            }
        }

        // Built-in message-level patterns (applied to free-form log text)
        messagePatterns.add(new RegexMaskingStrategy(
                "(?i)(password|passwd|pwd)\\s*[=:\\s]+\\s*(\\[[^\\]\\r\\n]*\\]|\\S+)",
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
        MaskingStrategy strategy = strategyFor(fieldName);
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
        return maskEnabled && fieldName != null && strategyFor(fieldName) != null;
    }

    /**
     * Registers a custom strategy for a specific field name at runtime.
     */
    public void register(String fieldName, MaskingStrategy strategy) {
        if (fieldName != null && strategy != null) {
            putField(fieldName, strategy);
        }
    }

    /**
     * The form in which field names are compared: lower case, without {@code _}, {@code -} or
     * {@code .}. These separators are how the same name is spelled across JSON, headers, query
     * parameters and Java fields ({@code access_token}, {@code access-token}, {@code accessToken}),
     * so a name listed once matches all of them.
     *
     * @param fieldName a field name; may be {@code null}
     * @return the normalised name, or {@code null} for {@code null}
     */
    public static String normalizeFieldName(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(fieldName.length());
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (c != '_' && c != '-' && c != '.') {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private void putField(String fieldName, MaskingStrategy strategy) {
        String normalized = normalizeFieldName(fieldName);
        if (normalized != null && !normalized.isEmpty()) {
            fieldStrategies.put(normalized, strategy);
        }
    }

    /** The strategy for a field: its own entry if it has one, else full masking on a suffix match. */
    private MaskingStrategy strategyFor(String fieldName) {
        String normalized = normalizeFieldName(fieldName);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        MaskingStrategy strategy = fieldStrategies.get(normalized);
        if (strategy != null) {
            return strategy;
        }
        for (String suffix : sensitiveSuffixes) {
            if (normalized.endsWith(suffix)) {
                return SUFFIX_MATCH_STRATEGY;
            }
        }
        return null;
    }

    /**
     * Masks sensitive parameters in a raw URL query string, leaving the others as they arrived.
     *
     * <p>The query is split into parameters rather than matched with {@link #maskJsonString}'s
     * {@code field=value} pattern, which ends a value at characters such as {@code ,} and
     * {@code ;}. Those are legal unencoded in a query, so the tail of a secret would be logged.
     * Names are percent-decoded for the lookup; values reach the strategy still encoded, so a
     * strategy that keeps part of a value cannot write a decoded line break into the log.
     *
     * @param rawQuery the raw query string, without the leading {@code ?}
     * @return the query string with sensitive parameter values masked
     */
    public String maskQueryString(String rawQuery) {
        if (!maskEnabled || rawQuery == null || rawQuery.isEmpty()) {
            return rawQuery;
        }
        String[] parameters = rawQuery.split("&", -1);
        StringBuilder masked = new StringBuilder(rawQuery.length());
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                masked.append('&');
            }
            masked.append(maskQueryParameter(parameters[i]));
        }
        return masked.toString();
    }

    private String maskQueryParameter(String parameter) {
        int separator = parameter.indexOf('=');
        if (separator >= 0) {
            String rawName = parameter.substring(0, separator);
            String name = decodeQueryName(rawName);
            if (isSensitiveField(name)) {
                return rawName + '=' + mask(name, parameter.substring(separator + 1));
            }
        }
        // Not a sensitive name, but the value can still hold a card number or a
        // semicolon-separated pair, so the payload patterns apply.
        return maskJsonString(parameter);
    }

    private static String decodeQueryName(String rawName) {
        try {
            return URLDecoder.decode(rawName, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            // A malformed escape cannot be decoded; compare the name as it arrived.
            return rawName;
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

        // Then by field name. Both passes find every name/value pair and look the name up, rather
        // than searching for each registered name: names are normalised and suffix-matched, so the
        // set of sensitive names is open-ended. One pass per shape is also cheaper than one per
        // registered field on every log event.
        // "fieldName":"value" or "fieldName": "value"
        result = maskPairs(JSON_PAIR, result, true);

        // Then the `field=value` form. This covers two shapes JSON matching misses entirely:
        // application/x-www-form-urlencoded bodies (the conventional OAuth2 token request) and
        // Lombok's default toString rendering, which is what a DTO logged with {} produces.
        result = maskPairs(KV_PAIR, result, false);
        return result;
    }

    /**
     * Replaces the value of every pair whose name is sensitive. In both patterns group 1 is the text
     * from the start of the pair up to the value, group 2 the name, group 3 the value and, for JSON,
     * group 4 the closing quote.
     */
    private String maskPairs(Pattern pattern, String input, boolean json) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = null;
        while (m.find()) {
            String name = m.group(2);
            MaskingStrategy strategy = strategyFor(name);
            if (strategy == null) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuffer(input.length());
            }
            String masked = m.group(1) + strategy.mask(name, m.group(3)) + (json ? m.group(4) : "");
            // quoteReplacement is required: appendReplacement treats '$' and '\' in the
            // replacement as group syntax, so a secret or field name containing either would
            // throw (dropping the whole masking pass) or splice in unintended text.
            m.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        if (sb == null) {
            return input;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * {@code "name":"value"}. The name is any run of up to 128 characters without a quote,
     * backslash or line break; the value runs to the next quote, as before.
     */
    private static final Pattern JSON_PAIR = Pattern.compile(
            "(\"([^\"\\\\\\r\\n]{1,128})\"\\s*:\\s*\")(.*?)(\")");

    /**
     * {@code name=value} where the name stands alone.
     *
     * <p>The leading {@code (?<![\w.-])} and the greedy name make the whole token the name, so
     * {@code pan} is never found inside {@code cardPan=}, which would mask from the wrong offset and
     * leave the first characters of the real value exposed. The value runs to the first separator
     * that can legitimately end one in a form body, a query string or a Lombok {@code toString}. A
     * value that opens with {@code [} runs to the matching {@code ]} instead: that is how a list or
     * array renders ({@code tokens=[a, b]}), and stopping at the first comma would mask only the
     * first element.
     */
    private static final Pattern KV_PAIR = Pattern.compile(
            "(?<![\\w.-])((\\w[\\w.-]{0,127})\\s*=\\s*)(\\[[^\\]\\r\\n]*\\]|[^&\\s,;)\"'}\\]]*)");
}
