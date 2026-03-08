package io.signoz.springboot.masking;

/**
 * Masking strategy that replaces the entire value with a fixed placeholder.
 * This is the default strategy for most sensitive fields.
 *
 * <p>Example: {@code "MyS3cretP@ss"} → {@code "***"}
 */
public class FullMaskingStrategy implements MaskingStrategy {

    private final String replacement;

    public FullMaskingStrategy() {
        this("***");
    }

    public FullMaskingStrategy(String replacement) {
        this.replacement = replacement != null ? replacement : "***";
    }

    @Override
    public String mask(String fieldName, String rawValue) {
        if (rawValue == null) {
            return replacement;
        }
        return replacement;
    }
}
