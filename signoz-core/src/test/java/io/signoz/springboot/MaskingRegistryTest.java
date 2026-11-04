package io.signoz.springboot;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingRegistryTest {

    /**
     * Value planted in a sensitive position; it must never appear in masked output. At least eight
     * characters, because the built-in bearer-token pattern only matches tokens of that length.
     */
    private static final String CANARY = "s3cret-canary";

    private MaskingRegistry registry;

    @BeforeEach
    void setUp() {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Arrays.asList("password", "ssn", "apikey"));
        props.setCustomPatterns(Collections.emptyList());
        registry = new MaskingRegistry(props);
    }

    /**
     * A list renders as {@code [a, b]} (Lombok toString, StructuredArguments, Arrays.toString). The
     * value used to stop at the first comma, so only the first element was masked.
     */
    @Test
    void keyValueListIsMaskedAsAWhole() {
        String masked = registry.maskJsonString("login apikey=[key-aaaa1111, key-bbbb2222] user=bob");

        assertThat(masked).isEqualTo("login apikey=*** user=bob");
    }

    @Test
    void freeTextPasswordListIsMaskedAsAWhole() {
        String masked = registry.maskMessage("rotated pwd=[old-aaaa1111, new-bbbb2222] done");

        assertThat(masked).isEqualTo("rotated pwd=*** done");
    }

    @Test
    void masksPasswordField() {
        assertThat(registry.mask("password", CANARY)).isEqualTo("***");
    }

    @Test
    void maskIsCaseInsensitiveForFieldName() {
        assertThat(registry.mask("PASSWORD", CANARY)).isEqualTo("***");
        assertThat(registry.mask("Password", CANARY)).isEqualTo("***");
    }

    @Test
    void doesNotMaskUnregisteredField() {
        assertThat(registry.mask("username", "johndoe")).isEqualTo("johndoe");
    }

    @Test
    void masksNullValueToReplacement() {
        assertThat(registry.mask("password", null)).isEqualTo("***");
    }

    @Test
    void maskJsonStringMasksPasswordInJson() {
        String json = "{\"username\":\"john\",\"password\":\"" + CANARY + "\"}";
        String masked = registry.maskJsonString(json);
        assertThat(masked).contains("\"password\":\"***\"");
        assertThat(masked).contains("\"username\":\"john\"");
    }

    @Test
    void maskMessageRedactsBearerToken() {
        String msg = "Request with Authorization: Bearer " + CANARY;
        String masked = registry.maskMessage(msg);
        assertThat(masked).contains("Bearer ***");
        assertThat(masked).doesNotContain(CANARY);
    }

    @Test
    void maskMessageRedactsCreditCard() {
        String msg = "Card 4111 1111 1111 1234 was charged";
        String masked = registry.maskMessage(msg);
        assertThat(masked).doesNotContain("4111 1111 1111 1234");
    }

    @Test
    void isSensitiveField_returnsTrueForRegisteredField() {
        assertThat(registry.isSensitiveField("password")).isTrue();
        assertThat(registry.isSensitiveField("username")).isFalse();
    }

    @Test
    void disabledMaskingReturnsRawValue() {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskEnabled(false);
        MaskingRegistry disabledRegistry = new MaskingRegistry(props);
        assertThat(disabledRegistry.mask("password", CANARY)).isEqualTo(CANARY);
    }
}
