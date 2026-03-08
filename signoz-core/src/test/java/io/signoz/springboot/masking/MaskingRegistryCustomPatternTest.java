package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingRegistryCustomPatternTest {

    private MaskingRegistry registry;

    @BeforeEach
    void setUp() {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Arrays.asList("password", "ssn", "email"));
        props.setCustomPatterns(Collections.<SigNozLoggingProperties.PatternConfig>emptyList());
        registry = new MaskingRegistry(props);
    }

    @Test
    void customPatternMasksMatchingMessage() {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Collections.<String>emptyList());
        SigNozLoggingProperties.PatternConfig pc = new SigNozLoggingProperties.PatternConfig();
        pc.setName("internalToken");
        pc.setRegex("secret-[a-z]+");
        props.setCustomPatterns(Collections.singletonList(pc));

        MaskingRegistry customRegistry = new MaskingRegistry(props);
        String result = customRegistry.maskMessage("found secret-abc here");
        assertThat(result).doesNotContain("secret-abc");
    }

    @Test
    void runtimeRegisteredStrategyOverridesDefault() {
        registry.register("email", new FullMaskingStrategy("EMAIL_REDACTED"));
        assertThat(registry.mask("email", "foo@example.com")).isEqualTo("EMAIL_REDACTED");
    }

    @Test
    void maskJsonStringMasksMultipleFields() {
        String json = "{\"username\":\"john\",\"password\":\"s3cr3t\",\"ssn\":\"123-45-6789\"}";
        String masked = registry.maskJsonString(json);
        assertThat(masked).contains("\"password\":\"***\"");
        assertThat(masked).contains("\"ssn\":\"***\"");
    }

    @Test
    void maskJsonStringDoesNotCorruptUnrelatedFields() {
        String json = "{\"username\":\"john\",\"password\":\"secret\",\"id\":\"42\"}";
        String masked = registry.maskJsonString(json);
        assertThat(masked).contains("\"username\":\"john\"");
        assertThat(masked).contains("\"id\":\"42\"");
    }

    @Test
    void maskMessageRedactsSsn() {
        String result = registry.maskMessage("SSN: 123-45-6789 on file");
        assertThat(result).doesNotContain("123-45-6789");
    }

    @Test
    void maskMessageHandlesEmptyString() {
        assertThat(registry.maskMessage("")).isEqualTo("");
    }

    @Test
    void maskMessageHandlesNull() {
        assertThat(registry.maskMessage(null)).isNull();
    }

    @Test
    void isSensitiveFieldIsCaseInsensitive() {
        assertThat(registry.isSensitiveField("PASSWORD")).isTrue();
        assertThat(registry.isSensitiveField("Password")).isTrue();
        assertThat(registry.isSensitiveField("username")).isFalse();
    }
}
