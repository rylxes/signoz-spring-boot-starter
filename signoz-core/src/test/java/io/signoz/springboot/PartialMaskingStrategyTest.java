package io.signoz.springboot;

import io.signoz.springboot.masking.PartialMaskingStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartialMaskingStrategyTest {

    private final PartialMaskingStrategy strategy = new PartialMaskingStrategy();

    @Test
    void masksMiddleOfLongValue() {
        // 16-digit card number, show first 2 and last 2
        String result = strategy.mask("card", "4111111111111234");
        assertThat(result).startsWith("41");
        assertThat(result).endsWith("34");
        assertThat(result).hasSize(16);
        assertThat(result.substring(2, 14)).isEqualTo("************");
    }

    @Test
    void fullyMasksShortValue() {
        // "ab" is too short (need at least 2+2+1=5 chars)
        String result = strategy.mask("field", "ab");
        assertThat(result).isEqualTo("**");
    }

    @Test
    void masksNullValue() {
        assertThat(strategy.mask("field", null)).isEqualTo("***");
    }
}
