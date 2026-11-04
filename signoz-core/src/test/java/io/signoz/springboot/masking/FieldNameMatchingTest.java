package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field names used to match only as listed, apart from case, so {@code access_token} in
 * {@code masked-fields} left {@code accessToken} and {@code access-token} in clear, and names no list
 * anticipates ({@code auth_token}, {@code X-Amz-Signature}) were never masked. Names now match after
 * normalisation, and unlisted names ending in a sensitive word are masked in full.
 */
class FieldNameMatchingTest {

    /** Value planted in a sensitive field; it must never appear in masked output. */
    private static final String CANARY = "s3cret";

    private static MaskingRegistry registry(String... maskedFields) {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Arrays.asList(maskedFields));
        props.setCustomPatterns(Collections.<SigNozLoggingProperties.PatternConfig>emptyList());
        return new MaskingRegistry(props);
    }

    private static MaskingRegistry withoutSuffixMatching(String... maskedFields) {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Arrays.asList(maskedFields));
        props.setCustomPatterns(Collections.<SigNozLoggingProperties.PatternConfig>emptyList());
        props.setMaskedFieldSuffixes(Collections.<String>emptyList());
        return new MaskingRegistry(props);
    }

    // --- normalisation ---

    @Test
    @DisplayName("One listed name covers its camelCase, kebab-case and upper-case spellings")
    void listedNameCoversEverySpelling() {
        // Suffix matching off, so only normalisation can be what masks these.
        MaskingRegistry registry = withoutSuffixMatching("access_token");

        for (String name : new String[]{"access_token", "accessToken", "access-token", "ACCESS_TOKEN",
                "Access.Token"}) {
            assertThat(registry.isSensitiveField(name)).as(name).isTrue();
            assertThat(registry.mask(name, CANARY)).as(name).isEqualTo("***");
            assertThat(registry.maskJsonString("{\"" + name + "\":\"" + CANARY + "\"}")).as(name)
                    .doesNotContain(CANARY);
            assertThat(registry.maskJsonString("User(" + name + "=" + CANARY + ", id=7)")).as(name)
                    .doesNotContain(CANARY).contains("id=7");
            assertThat(registry.maskQueryString(name + "=" + CANARY + "&page=2")).as(name)
                    .isEqualTo(name + "=***&page=2");
        }
    }

    @Test
    @DisplayName("A listed partial strategy applies to every spelling of the name")
    void partialStrategyFollowsTheNormalisedName() {
        MaskingRegistry registry = registry();  // built-in card_number keeps the last four

        assertThat(registry.mask("cardNumber", "4111111111111234")).isEqualTo("************1234");
        assertThat(registry.mask("card-number", "4111111111111234")).isEqualTo("************1234");
    }

    // --- suffix matching ---

    @Test
    @DisplayName("Unlisted names ending in a sensitive word are masked in JSON, key=value and queries")
    void unlistedSensitiveNamesAreMaskedBySuffix() {
        MaskingRegistry registry = registry();  // nothing listed: only the default suffixes apply

        for (String name : new String[]{"auth_token", "sessionToken", "x-csrf-token", "clientSecret",
                "app_secret", "dbPassword", "new-password", "googleApiKey", "X-Amz-Signature",
                "private_key", "ns:password"}) {
            assertThat(registry.isSensitiveField(name)).as(name).isTrue();
            assertThat(registry.maskJsonString("{\"" + name + "\":\"" + CANARY + "\",\"id\":\"7\"}"))
                    .as(name).doesNotContain(CANARY).contains("\"id\":\"7\"");
            assertThat(registry.maskQueryString("id=7&" + name + "=" + CANARY))
                    .as(name).isEqualTo("id=7&" + name + "=***");
        }
        assertThat(registry.maskJsonString("Creds(auth_token=" + CANARY + ", user=ada)"))
                .isEqualTo("Creds(auth_token=***, user=ada)");
    }

    @Test
    @DisplayName("Names that merely contain or start with a sensitive word stay visible")
    void suffixMatchingDoesNotOverreach() {
        MaskingRegistry registry = registry();

        for (String name : new String[]{"tokenType", "maxTokens", "token_count", "secretary",
                "passwordPolicy", "signatureAlgorithm", "apiKeyId", "privateKeyAlias", "id"}) {
            assertThat(registry.isSensitiveField(name)).as(name).isFalse();
            assertThat(registry.maskJsonString("{\"" + name + "\":\"visible\"}"))
                    .as(name).isEqualTo("{\"" + name + "\":\"visible\"}");
        }
    }

    @Test
    @DisplayName("A name with its own entry keeps its own strategy rather than full masking")
    void ownEntryWinsOverSuffixMatch() {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Collections.<String>emptyList());
        Map<String, String> strategies = new LinkedHashMap<String, String>();
        strategies.put("sessionToken", "partial:0:4");
        props.setFieldStrategies(strategies);

        assertThat(new MaskingRegistry(props).mask("session_token", "abcdef123456")).isEqualTo("********3456");
    }

    @Test
    @DisplayName("An empty suffix list turns suffix matching off; custom suffixes are normalised")
    void suffixListIsConfigurable() {
        assertThat(withoutSuffixMatching().maskJsonString("{\"auth_token\":\"" + CANARY + "\"}"))
                .contains(CANARY);

        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Collections.<String>emptyList());
        props.setMaskedFieldSuffixes(Arrays.asList("Session-Id"));
        MaskingRegistry registry = new MaskingRegistry(props);

        assertThat(registry.mask("http_session_id", CANARY)).isEqualTo("***");
        assertThat(registry.isSensitiveField("auth_token")).as("replaced, not merged").isFalse();
    }

    // --- key=value boundaries ---

    @Test
    @DisplayName("key=value still takes the whole token as the name")
    void keyValueNameIsTheWholeToken() {
        MaskingRegistry registry = withoutSuffixMatching("pan");

        // "pan" must not be found inside "cardPan": that would mask from the wrong offset.
        assertThat(registry.maskJsonString("cardPan=abc pan=" + CANARY)).isEqualTo("cardPan=abc pan=***");
        // A dotted name is one token, and normalises to a listed name.
        assertThat(withoutSuffixMatching("dbpassword").maskJsonString("spring.db.password=" + CANARY))
                .doesNotContain(CANARY);
    }

    @Test
    @DisplayName("Nothing is masked when masking is disabled")
    void disabledMaskingIgnoresNamesAndSuffixes() {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskEnabled(false);
        MaskingRegistry registry = new MaskingRegistry(props);

        assertThat(registry.isSensitiveField("auth_token")).isFalse();
        assertThat(registry.maskJsonString("{\"auth_token\":\"" + CANARY + "\"}")).contains(CANARY);
    }

    @Test
    @DisplayName("normalizeFieldName lower-cases and drops _ - and .")
    void normalisesFieldNames() {
        assertThat(MaskingRegistry.normalizeFieldName("X-Amz.Signature_v4")).isEqualTo("xamzsignaturev4");
        assertThat(MaskingRegistry.normalizeFieldName(null)).isNull();
    }
}
