package io.signoz.springboot.properties;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SigNozLoggingPropertiesTest {

    @Test
    void defaultModeIsBoth() {
        assertThat(new SigNozLoggingProperties().getMode())
                .isEqualTo(SigNozLoggingProperties.LoggingMode.BOTH);
    }

    @Test
    void defaultMaskEnabledIsTrue() {
        assertThat(new SigNozLoggingProperties().isMaskEnabled()).isTrue();
    }

    @Test
    void defaultMaskedFieldsContainsPassword() {
        assertThat(new SigNozLoggingProperties().getMaskedFields()).contains("password");
    }

    @Test
    void defaultMaskedFieldsContainsToken() {
        assertThat(new SigNozLoggingProperties().getMaskedFields()).contains("token");
    }

    @Test
    void defaultMaskedFieldsContainsAuthorization() {
        assertThat(new SigNozLoggingProperties().getMaskedFields()).contains("authorization");
    }

    @Test
    void defaultIncludeMdcIsTrue() {
        assertThat(new SigNozLoggingProperties().isIncludeMdc()).isTrue();
    }

    @Test
    void defaultIncludeCallerDataIsFalse() {
        assertThat(new SigNozLoggingProperties().isIncludeCallerData()).isFalse();
    }

    @Test
    void customPatternsDefaultsToEmptyList() {
        assertThat(new SigNozLoggingProperties().getCustomPatterns()).isEmpty();
    }

    @Test
    void loggingModeEnumHasThreeValues() {
        assertThat(SigNozLoggingProperties.LoggingMode.values()).hasSize(3);
    }
}
