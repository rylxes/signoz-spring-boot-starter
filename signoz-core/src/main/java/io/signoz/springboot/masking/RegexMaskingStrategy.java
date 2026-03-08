package io.signoz.springboot.masking;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masking strategy that applies a regex to the raw value, replacing every match
 * with a fixed replacement string.
 *
 * <p>Useful for partially masking structured values such as email addresses,
 * phone numbers, or structured token strings.
 *
 * <p>Example — mask email local part:
 * <pre>
 *   regex:       "([A-Za-z0-9._%+\-]+)@"
 *   replacement: "***@"
 *   input:       "john.doe@example.com"
 *   output:      "***@example.com"
 * </pre>
 */
public class RegexMaskingStrategy implements MaskingStrategy {

    private final Pattern pattern;
    private final String replacement;

    public RegexMaskingStrategy(String regex, String replacement) {
        this.pattern = Pattern.compile(regex);
        this.replacement = replacement != null ? replacement : "***";
    }

    /**
     * Builds a strategy that replaces the full match with {@code "***"}.
     */
    public static RegexMaskingStrategy fullMatch(String regex) {
        return new RegexMaskingStrategy(regex, "***");
    }

    @Override
    public String mask(String fieldName, String rawValue) {
        if (rawValue == null) {
            return "***";
        }
        Matcher matcher = pattern.matcher(rawValue);
        return matcher.replaceAll(replacement);
    }

    public Pattern getPattern() {
        return pattern;
    }
}
