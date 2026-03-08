package io.signoz.springboot.masking;

/**
 * Masking strategy that reveals a configurable number of characters at the start
 * and end of the value while masking the rest with asterisks.
 *
 * <p>Examples with {@code visibleChars=2}:
 * <ul>
 *   <li>{@code "4111111111111234"} → {@code "41**********1234"}</li>
 *   <li>{@code "hello"} → {@code "he*lo"}</li>
 *   <li>{@code "ab"} → {@code "**"} (too short, fully masked)</li>
 * </ul>
 */
public class PartialMaskingStrategy implements MaskingStrategy {

    private final int visiblePrefix;
    private final int visibleSuffix;
    private final char maskChar;

    /**
     * Default: show 2 characters at start and end, mask middle with {@code *}.
     */
    public PartialMaskingStrategy() {
        this(2, 2, '*');
    }

    public PartialMaskingStrategy(int visiblePrefix, int visibleSuffix, char maskChar) {
        this.visiblePrefix = visiblePrefix;
        this.visibleSuffix = visibleSuffix;
        this.maskChar = maskChar;
    }

    @Override
    public String mask(String fieldName, String rawValue) {
        if (rawValue == null) {
            return "***";
        }
        int len = rawValue.length();
        int minLength = visiblePrefix + visibleSuffix + 1;

        // Too short to partially mask — fully mask it
        if (len < minLength) {
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                sb.append(maskChar);
            }
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder(len);
        sb.append(rawValue, 0, visiblePrefix);
        for (int i = visiblePrefix; i < len - visibleSuffix; i++) {
            sb.append(maskChar);
        }
        sb.append(rawValue, len - visibleSuffix, len);
        return sb.toString();
    }
}
