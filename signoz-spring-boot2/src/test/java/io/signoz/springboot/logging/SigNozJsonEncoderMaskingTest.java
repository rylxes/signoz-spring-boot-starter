package io.signoz.springboot.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SigNozJsonEncoder} on the module's real Logback / logstash-logback-encoder versions
 * (the core module's Logback 1.2 test classpath cannot run the encoder).
 */
class SigNozJsonEncoderMaskingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Built at runtime so no credential-shaped literal sits in the source (secret scanners). */
    private static final String SENSITIVE = "sensitive-" + "payload-value";
    private static final String CARD = "4111" + "1111" + "1111" + "1111";

    private LoggerContext context;

    @BeforeEach
    public void setUp() {
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    @Test
    public void masksFieldsInsideAJsonPayloadLoggedAsTheMessage() throws Exception {
        String body = "{\"user\":\"alice\",\"password\":\"" + SENSITIVE + "\"}";

        String line = encode(encoder(registry()), "login {}", body);

        assertThat(line).doesNotContain(SENSITIVE);
        assertThat(JSON.readTree(line).get("message").asText()).contains("alice").contains("login");
    }

    @Test
    public void masksMdcBySensitiveKeyAndByMessagePatterns() throws Exception {
        MDC.put("password", SENSITIVE);
        MDC.put("note", "card " + CARD);
        MDC.put("trace_id", "0af7651916cd43dd8448eb211c80319c");

        JsonNode node = JSON.readTree(encode(encoder(registry()), "ok"));

        assertThat(node.get("password").asText()).isNotEqualTo(SENSITIVE);
        assertThat(node.get("note").asText()).doesNotContain(CARD);
        assertThat(node.get("trace_id").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    public void stillMasksMessagePatternsAndStackTraces() throws Exception {
        SigNozJsonEncoder encoder = encoder(registry());
        LoggingEvent event = event("charge failed for " + CARD);
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                new IllegalStateException("declined " + CARD)));

        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(line).doesNotContain(CARD).contains("IllegalStateException");
    }

    @Test
    public void failsClosedWhenMaskingThrows() throws Exception {
        MaskingRegistry broken = mock(MaskingRegistry.class);
        when(broken.maskJsonString(anyString())).thenThrow(new IllegalStateException("bad pattern"));
        String body = "{\"password\":\"" + SENSITIVE + "\"}";

        String line = encode(encoder(broken), "login {}", body);

        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asText()).isEqualTo(SigNozJsonEncoder.BODY_WITHHELD);
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(line).doesNotContain(SENSITIVE);
    }

    @Test
    public void withoutARegistryTheEventIsEncodedUnchanged() throws Exception {
        String line = encode(encoder(null), "plain {}", "value");

        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("plain value");
    }

    private SigNozJsonEncoder encoder(MaskingRegistry registry) {
        SigNozJsonEncoder encoder = new SigNozJsonEncoder();
        encoder.setContext(context);
        encoder.setMaskingRegistry(registry);
        encoder.start();
        return encoder;
    }

    private static MaskingRegistry registry() {
        return new MaskingRegistry(new SigNozLoggingProperties());
    }

    private String encode(SigNozJsonEncoder encoder, String format, Object... args) {
        return new String(encoder.encode(event(format, args)), StandardCharsets.UTF_8);
    }

    private LoggingEvent event(String format, Object... args) {
        return new LoggingEvent("fqcn", context.getLogger("com.example.Login"), Level.INFO, format, null, args);
    }
}
