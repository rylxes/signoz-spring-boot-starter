package io.signoz.springboot.masking;

/**
 * Parses the compact strategy notation used by {@code signoz.logging.field-strategies}.
 *
 * <p>A flat {@code masked-fields} list can only say "mask this entirely", which is wrong for a
 * primary account number: PCI-DSS permits retaining the first six (BIN) and last four digits, and
 * support teams need them to reconcile a transaction. This notation lets a field pick its strategy:
 *
 * <pre>
 * signoz:
 *   logging:
 *     field-strategies:
 *       cardPan:   "partial:6:4"   # 506105*********6567
 *       clearPin:  "full"          # ***
 *       accountRef: "regex:\\d{4}$" # strategy-specific
 * </pre>
 *
 * <p>Accepted forms:
 * <ul>
 *   <li>{@code full} — replace the whole value</li>
 *   <li>{@code partial:<prefix>:<suffix>} — keep that many leading/trailing characters</li>
 *   <li>{@code partial} — shorthand for {@code partial:2:2}</li>
 *   <li>{@code regex:<pattern>} — replace every match of the pattern</li>
 * </ul>
 */
public final class MaskingStrategySpec {

    private MaskingStrategySpec() {
        // utility
    }

    /**
     * @throws IllegalArgumentException if the spec cannot be parsed. Failing at startup is
     *         deliberate: a typo that silently degraded to "no masking" is exactly the failure this
     *         whole area is meant to prevent.
     */
    public static MaskingStrategy parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException("Masking strategy must not be blank");
        }
        String trimmed = spec.trim();
        String lower = trimmed.toLowerCase();

        if ("full".equals(lower)) {
            return new FullMaskingStrategy();
        }
        if ("partial".equals(lower)) {
            return new PartialMaskingStrategy();
        }
        if (lower.startsWith("partial:")) {
            String[] parts = trimmed.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException(
                        "Expected partial:<prefix>:<suffix> but got '" + spec + "'");
            }
            int prefix = parseCount(parts[1], spec);
            int suffix = parseCount(parts[2], spec);
            return new PartialMaskingStrategy(prefix, suffix, '*');
        }
        if (lower.startsWith("regex:")) {
            String pattern = trimmed.substring("regex:".length());
            if (pattern.isEmpty()) {
                throw new IllegalArgumentException("regex: strategy needs a pattern in '" + spec + "'");
            }
            return RegexMaskingStrategy.fullMatch(pattern);
        }
        throw new IllegalArgumentException(
                "Unknown masking strategy '" + spec + "'. Expected full, partial, "
                        + "partial:<prefix>:<suffix> or regex:<pattern>");
    }

    private static int parseCount(String raw, String spec) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException(
                        "Visible character counts must not be negative in '" + spec + "'");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Visible character counts must be integers in '" + spec + "'", e);
        }
    }
}
