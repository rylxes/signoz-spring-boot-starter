package io.signoz.springboot.logging;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the encoder's post-serialisation pass directly on hand-written lines, so prefix shapes
 * that are awkward to produce through a Logback layout are covered on signoz-core's Logback 1.2.
 */
class SigNozJsonEncoderSerialisedLineTest {

    private final MaskingRegistry registry = new MaskingRegistry(new SigNozLoggingProperties());

    @Test
    void prefixContainingABraceStillFindsTheObject() throws Exception {
        String masked = SigNozJsonEncoder.maskSerialisedLine(
                "{app} {\"message\":\"hi\",\"token\":[\"tok-1111aaaa\",\"tok-2222bbbb\"]}\n", registry);

        assertThat(masked).isEqualTo("{app} {\"message\":\"hi\",\"token\":[\"***\",\"***\"]}\n");
    }

    @Test
    void prefixStartingWithANumberIsNotMistakenForTheValue() throws Exception {
        String masked = SigNozJsonEncoder.maskSerialisedLine(
                "42 {\"message\":\"hi\",\"token\":\"tok-3333cccc\"}\n", registry);

        assertThat(masked).isEqualTo("42 {\"message\":\"hi\",\"token\":\"***\"}\n");
    }

    @Test
    void lineWithoutAnObjectIsLeftToTheCaller() throws Exception {
        assertThat(SigNozJsonEncoder.maskSerialisedLine("plain text token=tok-4444dddd\n", registry)).isNull();
    }
}
