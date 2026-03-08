package io.signoz.springboot;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingRegistryTest {

    private MaskingRegistry registry;

    @BeforeEach
    void setUp() {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Arrays.asList("password", "ssn", "apikey"));
        props.setCustomPatterns(Collections.emptyList());
        registry = new MaskingRegistry(props);
    }

    @Test
    void masksPasswordField() {
        assertThat(registry.mask("password", "MySecret123")).isEqualTo("***");
    }

    @Test
    void maskIsCaseInsensitiveForFieldName() {
        assertThat(registry.mask("PASSWORD", "secret")).isEqualTo("***");
        assertThat(registry.mask("Password", "secret")).isEqualTo("***");
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
        String json = "{\"username\":\"john\",\"password\":\"supersecret\"}";
        String masked = registry.maskJsonString(json);
        assertThat(masked).contains("\"password\":\"***\"");
        assertThat(masked).contains("\"username\":\"john\"");
    }

    @Test
    void maskMessageRedactsBearerToken() {
        String msg = "Request with Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature";
        String masked = registry.maskMessage(msg);
        assertThat(masked).contains("Bearer ***");
        assertThat(masked).doesNotContain("eyJhbGciOiJSUzI1NiJ9");
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
        assertThat(disabledRegistry.mask("password", "secret")).isEqualTo("secret");
    }
}
