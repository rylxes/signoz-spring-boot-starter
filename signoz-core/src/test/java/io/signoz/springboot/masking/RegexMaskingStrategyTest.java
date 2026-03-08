package io.signoz.springboot.masking;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RegexMaskingStrategyTest {

    @Test
    void fullMatchReplacesEntireMatchWithStars() {
        RegexMaskingStrategy strategy = RegexMaskingStrategy.fullMatch("[0-9]+");
        assertThat(strategy.mask("field", "abc123")).isEqualTo("abc***");
    }

    @Test
    void customReplacementTemplateIsApplied() {
        // Group 1 = "bearer ", group 2 = token -- replacement keeps "bearer " prefix
        RegexMaskingStrategy strategy = new RegexMaskingStrategy(
                "(?i)(bearer\\s+)\\S+", "$1***");
        assertThat(strategy.mask("auth", "bearer eyJtoken123")).isEqualTo("bearer ***");
    }

    @Test
    void nullValueReturnsStars() {
        assertThat(RegexMaskingStrategy.fullMatch("[0-9]+").mask("field", null))
                .isEqualTo("***");
    }

    @Test
    void noMatchReturnsOriginal() {
        RegexMaskingStrategy strategy = RegexMaskingStrategy.fullMatch("[0-9]+");
        assertThat(strategy.mask("field", "abcdef")).isEqualTo("abcdef");
    }

    @Test
    void getPatternReturnsCompiledPatternWithOriginalRegex() {
        String regex = "[0-9]+";
        RegexMaskingStrategy strategy = RegexMaskingStrategy.fullMatch(regex);
        assertThat(strategy.getPattern().pattern()).isEqualTo(regex);
    }
}
