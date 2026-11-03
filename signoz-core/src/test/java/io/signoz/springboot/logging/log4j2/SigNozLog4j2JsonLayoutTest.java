package io.signoz.springboot.logging.log4j2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SigNozLog4j2JsonLayoutTest {

    /** Built at runtime so no credential-shaped literal sits in the source (secret scanners). */
    private static final String SENSITIVE = "sensitive-" + "payload-value";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MaskingRegistry registry = new MaskingRegistry(new SigNozLoggingProperties());

    @Test
    void writesLogstashCompatibleFieldsAndContextAsTopLevelFields() throws Exception {
        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("trace_id", "0af7651916cd43dd8448eb211c80319c");
        context.putValue("requestId", "req-1");
        context.putValue("message", "must not clobber the real message");

        String line = new SigNozLog4j2JsonLayout(registry, 3).toSerializable(event(Level.INFO,
                "order \"42\" placed\nnext line", context, null));

        assertThat(line).endsWith("\n").doesNotContain("\nnext");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("@timestamp").asText()).isEqualTo("2026-11-03T22:00:00Z");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("logger_name").asText()).isEqualTo("com.example.Orders");
        assertThat(node.get("thread_name").asText()).isEqualTo("main");
        assertThat(node.get("message").asText()).isEqualTo("order \"42\" placed\nnext line");
        assertThat(node.get("trace_id").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(node.get("requestId").asText()).isEqualTo("req-1");
        assertThat(node.has("errorId")).isFalse();
        assertThat(node.has("stack_trace")).isFalse();
    }

    @Test
    void masksSensitiveFieldsInTheMessageAndContext() throws Exception {
        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("password", "hunter2");

        String line = new SigNozLog4j2JsonLayout(registry, 3).toSerializable(event(Level.INFO,
                "login {\"user\":\"alice\",\"password\":\"" + SENSITIVE + "\"} card 4111111111111111", context, null));

        assertThat(line).doesNotContain("hunter2").doesNotContain(SENSITIVE).doesNotContain("4111111111111111");
        assertThat(JSON.readTree(line).get("message").asText()).contains("alice");
    }

    @Test
    void addsErrorIdAndStackTraceForErrorsWithAThrowable() throws Exception {
        IllegalStateException boom = new IllegalStateException("boom");

        JsonNode error = JSON.readTree(new SigNozLog4j2JsonLayout(registry, 3)
                .toSerializable(event(Level.ERROR, "failed", new SortedArrayStringMap(), boom)));
        JsonNode warn = JSON.readTree(new SigNozLog4j2JsonLayout(registry, 3)
                .toSerializable(event(Level.WARN, "failed", new SortedArrayStringMap(), boom)));
        JsonNode disabled = JSON.readTree(new SigNozLog4j2JsonLayout(registry, 0)
                .toSerializable(event(Level.ERROR, "failed", new SortedArrayStringMap(), boom)));

        assertThat(error.get("errorId").asText()).matches("[0-9a-f]{12}");
        assertThat(error.get("stack_trace").asText()).contains("IllegalStateException: boom");
        assertThat(warn.has("errorId")).isFalse();
        assertThat(warn.has("stack_trace")).isTrue();
        assertThat(disabled.has("errorId")).isFalse();
    }

    @Test
    void failsClosedWhenMaskingThrows() throws Exception {
        MaskingRegistry broken = mock(MaskingRegistry.class);
        when(broken.maskJsonString(anyString())).thenThrow(new IllegalStateException("bad pattern"));
        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("password", "hunter2");

        String line = new SigNozLog4j2JsonLayout(broken, 3)
                .toSerializable(event(Level.INFO, "secret body", context, new RuntimeException("x")));

        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asText()).isEqualTo("[signoz:masking-failed:body-withheld]");
        assertThat(line).doesNotContain("secret body").doesNotContain("hunter2").doesNotContain("stack_trace");
    }

    @Test
    void masksMdcValuesOnNonSensitiveKeysWithMessagePatterns() throws Exception {
        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("note", "card 4111111111111111");

        String line = new SigNozLog4j2JsonLayout(registry, 3)
                .toSerializable(event(Level.INFO, "ok", context, null));

        assertThat(line).doesNotContain("4111111111111111");
    }

    @Test
    void escapesControlCharacters() {
        StringBuilder sb = new StringBuilder();
        SigNozLog4j2JsonLayout.quote(sb, "a\u0001b\\c\t");
        assertThat(sb.toString()).isEqualTo("\"a\\u0001b\\\\c\\t\"");
    }

    static LogEvent event(Level level, String message, SortedArrayStringMap context, Throwable thrown) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("com.example.Orders")
                .setLevel(level)
                .setMessage(new SimpleMessage(message))
                .setThreadName("main")
                .setTimeMillis(1793743200000L)
                .setContextData(context)
                .setThrown(thrown)
                .build();
    }
}
