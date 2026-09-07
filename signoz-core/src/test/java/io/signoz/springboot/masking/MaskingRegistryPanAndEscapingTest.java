package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers two defects in {@link MaskingRegistry} that both let sensitive values through.
 */
class MaskingRegistryPanAndEscapingTest {

    private static MaskingRegistry registry(String... maskedFields) {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Arrays.asList(maskedFields));
        props.setCustomPatterns(Collections.emptyList());
        return new MaskingRegistry(props);
    }

    // --- PAN length coverage ---

    @Test
    @DisplayName("19-digit PANs are redacted (the built-in pattern only matched 16 digits)")
    void redactsNineteenDigitPan() {
        // Verve, UnionPay and some Maestro ranges issue 19-digit PANs. The previous pattern was a
        // fixed 4-4-4-4 group, so these passed through untouched.
        assertThat(registry().maskMessage("pan 5061051800019666567 charged"))
                .doesNotContain("5061051800019666567");
    }

    @Test
    @DisplayName("13, 15 and 16 digit PANs are all redacted")
    void redactsOtherValidPanLengths() {
        assertThat(registry().maskMessage("x 4111111111112")).doesNotContain("4111111111112");
        assertThat(registry().maskMessage("x 378282246310005")).doesNotContain("378282246310005");
        assertThat(registry().maskMessage("x 4111111111111234")).doesNotContain("4111111111111234");
        assertThat(registry().maskMessage("x 4111 1111 1111 1234")).doesNotContain("4111 1111 1111 1234");
    }

    @Test
    @DisplayName("long non-card numerics are left alone")
    void doesNotRedactNonCardNumerics() {
        // Epoch nanos (19 digits) and epoch millis (13) both start with 1 at present-day values,
        // outside the 3-6 major industry identifier range, so they survive.
        MaskingRegistry r = registry();
        assertThat(r.maskMessage("ts 1788784291216000000")).contains("1788784291216000000");
        assertThat(r.maskMessage("ts 1788784291216")).contains("1788784291216");
        assertThat(r.maskMessage("rrn 878418815534")).contains("878418815534");
    }

    // --- appendReplacement escaping ---

    @Test
    @DisplayName("a secret containing $ or \\ does not break the whole masking pass")
    void handlesRegexMetacharactersInValues() {
        MaskingRegistry r = registry("password", "apikey");
        String json = "{\"password\":\"p$a\\\\ss$1word\",\"apikey\":\"k$2\",\"user\":\"ada\"}";

        // Before quoteReplacement was applied, appendReplacement treated $1 / $2 in the substituted
        // text as group references: this threw IndexOutOfBoundsException, the exception escaped
        // maskJsonString, and the caller emitted the unmasked JSON.
        assertThatCode(() -> r.maskJsonString(json)).doesNotThrowAnyException();

        String masked = r.maskJsonString(json);
        assertThat(masked).doesNotContain("p$a").doesNotContain("k$2");
        assertThat(masked).as("non-sensitive fields must survive").contains("ada");
    }

    @Test
    @DisplayName("a field name containing regex metacharacters is handled literally")
    void handlesRegexMetacharactersInFieldNames() {
        MaskingRegistry r = registry("card.pan");
        String masked = r.maskJsonString("{\"card.pan\":\"4111111111111234\",\"id\":\"7\"}");
        assertThat(masked).doesNotContain("4111111111111234").contains("\"id\":\"7\"");
    }

    @Test
    @DisplayName("repeated calls return identical results (pattern caching is not stateful)")
    void patternCachingIsStable() {
        MaskingRegistry r = registry("password");
        String json = "{\"password\":\"hunter2\",\"user\":\"ada\"}";
        String first = r.maskJsonString(json);
        assertThat(r.maskJsonString(json)).isEqualTo(first);
        assertThat(first).doesNotContain("hunter2");
    }
}
