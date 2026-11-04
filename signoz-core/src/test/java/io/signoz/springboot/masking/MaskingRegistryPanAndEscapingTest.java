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

    /** Value planted in a sensitive position; it must never appear in masked output. */
    private static final String CANARY = "s3cret";

    /**
     * Planted values carrying {@code $1}, {@code $2} and a JSON-escaped backslash, the characters
     * {@code appendReplacement} would misread as group references. Both start with a prefix the
     * assertions look for.
     */
    private static final String METACHAR_CANARY = "p$a\\\\ss$1word";
    private static final String METACHAR_KEY_CANARY = "k$2";

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
        String json = "{\"password\":\"" + METACHAR_CANARY + "\",\"apikey\":\"" + METACHAR_KEY_CANARY
                + "\",\"user\":\"ada\"}";

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
        String json = "{\"password\":\"" + CANARY + "\",\"user\":\"ada\"}";
        String first = r.maskJsonString(json);
        assertThat(r.maskJsonString(json)).isEqualTo(first);
        assertThat(first).doesNotContain(CANARY);
    }

    // --- URL query strings ---

    @Test
    @DisplayName("Sensitive query parameters are masked wherever they sit; neighbours survive")
    void masksQueryStringParameters() {
        MaskingRegistry registry = registry("token", "secret");

        assertThat(registry.maskQueryString("token=" + CANARY + "&tenant=acme"))
                .isEqualTo("token=***&tenant=acme");
        assertThat(registry.maskQueryString("tenant=acme&TOKEN=" + CANARY + "&page=2"))
                .isEqualTo("tenant=acme&TOKEN=***&page=2");
        assertThat(registry.maskQueryString("page=2&secret=" + CANARY))
                .isEqualTo("page=2&secret=***");
    }

    @Test
    @DisplayName("The whole value is masked, including characters that end a field=value match")
    void masksEntireQueryValue() {
        MaskingRegistry registry = registry("token");

        for (String value : new String[]{"a%2Bb%3D", "a,", "a;", "a)", "a'", "[a]", "a=b"}) {
            assertThat(registry.maskQueryString("token=" + value + CANARY + "&page=2"))
                    .isEqualTo("token=***&page=2");
        }
    }

    @Test
    @DisplayName("A percent-encoded parameter name is still recognised")
    void masksPercentEncodedQueryName() {
        assertThat(registry("token").maskQueryString("t%6Fken=" + CANARY))
                .isEqualTo("t%6Fken=***");
    }

    @Test
    @DisplayName("A strategy that keeps part of a value still applies")
    void appliesPartialStrategyToQueryValue() {
        assertThat(registry().maskQueryString("cardnumber=4111111111111111"))
                .isEqualTo("cardnumber=************1111");
    }

    @Test
    @DisplayName("Secrets in a non-sensitive parameter are caught by the payload patterns")
    void appliesPayloadPatternsToOtherQueryParameters() {
        MaskingRegistry registry = registry("token");

        assertThat(registry.maskQueryString("tenant=a;token=" + CANARY)).doesNotContain(CANARY);
        assertThat(registry.maskQueryString("note=4111111111111111")).doesNotContain("4111111111111111");
    }

    @Test
    @DisplayName("Query strings with nothing sensitive are unchanged, even when malformed")
    void leavesOrdinaryQueryStringsAlone() {
        MaskingRegistry registry = registry("token");

        for (String query : new String[]{"q=spring%20boot&page=2&flag", "a=%zz", "&&", "=x", "token"}) {
            assertThat(registry.maskQueryString(query)).isEqualTo(query);
        }
        assertThat(registry.maskQueryString("")).isEmpty();
        assertThat(registry.maskQueryString(null)).isNull();
    }

    @Test
    @DisplayName("Nothing is masked when masking is disabled")
    void leavesQueryAloneWhenMaskingDisabled() {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskEnabled(false);

        assertThat(new MaskingRegistry(props).maskQueryString("token=" + CANARY))
                .isEqualTo("token=" + CANARY);
    }

    @Test
    @DisplayName("OAuth parameters are masked by default")
    void masksOAuthQueryParametersByDefault() {
        MaskingRegistry registry = new MaskingRegistry(new SigNozLoggingProperties());

        for (String name : new String[]{"access_token", "refresh_token", "client_secret", "code"}) {
            assertThat(registry.maskQueryString("state=xyz&" + name + "=" + CANARY))
                    .isEqualTo("state=xyz&" + name + "=***");
        }
    }
}
