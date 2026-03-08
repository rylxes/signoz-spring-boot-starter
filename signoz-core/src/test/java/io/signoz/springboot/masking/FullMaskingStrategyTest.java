package io.signoz.springboot.masking;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FullMaskingStrategyTest {

    @Test
    void defaultReplacementIsTripleAsterisk() {
        assertThat(new FullMaskingStrategy().mask("field", "anyValue")).isEqualTo("***");
    }

    @Test
    void customReplacementIsUsed() {
        assertThat(new FullMaskingStrategy("REDACTED").mask("field", "anyValue"))
                .isEqualTo("REDACTED");
    }

    @Test
    void nullValueReturnsReplacement() {
        assertThat(new FullMaskingStrategy().mask("field", null)).isEqualTo("***");
    }

    @Test
    void nullCustomReplacementDefaultsToTripleAsterisk() {
        assertThat(new FullMaskingStrategy(null).mask("field", "value")).isEqualTo("***");
    }
}
