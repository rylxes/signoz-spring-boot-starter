package io.signoz.springboot.logging.log4j2;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static io.signoz.springboot.logging.log4j2.SigNozLog4j2JsonLayoutTest.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtlpLog4j2AppenderTest {

    /** Built at runtime so no credential-shaped literal sits in the source (secret scanners). */
    private static final String SENSITIVE = "sensitive-" + "payload-value";

    private LogRecordBuilder record;
    private OtlpLog4j2Appender appender;

    @BeforeEach
    void setUp() {
        io.opentelemetry.api.logs.Logger otelLogger = mock(io.opentelemetry.api.logs.Logger.class);
        record = mock(LogRecordBuilder.class, RETURNS_SELF);
        when(otelLogger.logRecordBuilder()).thenReturn(record);

        appender = new OtlpLog4j2Appender("SIGNOZ_OTLP");
        appender.setMaskingRegistry(new MaskingRegistry(new SigNozLoggingProperties()));
        appender.setErrorFingerprintDepth(3);
        appender.useOtelLogger(otelLogger);
        appender.start();
    }

    @Test
    void exportsAMaskedRecordWithSeverityAndContext() {
        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("trace_id", "0af7651916cd43dd8448eb211c80319c");
        context.putValue("password", "hunter2");

        appender.append(event(Level.WARN, "{\"user\":\"alice\",\"password\":\"" + SENSITIVE + "\"}", context, null));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(record).setBody(body.capture());
        assertThat(body.getValue()).contains("alice").doesNotContain(SENSITIVE);
        verify(record).setSeverity(Severity.WARN);
        verify(record).setSeverityText("WARN");

        ArgumentCaptor<Attributes> attrs = ArgumentCaptor.forClass(Attributes.class);
        verify(record).setAllAttributes(attrs.capture());
        assertThat(attrs.getValue().get(AttributeKey.stringKey("mdc.trace_id")))
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(attrs.getValue().get(AttributeKey.stringKey("mdc.password"))).isNotEqualTo("hunter2");
        verify(record).setAttribute(AttributeKey.stringKey("logger"), "com.example.Orders");
        verify(record).emit();
    }

    @Test
    void setsTheRecordTraceContextFromTheThreadContext() {
        SortedArrayStringMap context = new SortedArrayStringMap();
        context.putValue("trace_id", "0af7651916cd43dd8448eb211c80319c");
        context.putValue("span_id", "b7ad6b7169203331");

        appender.append(event(Level.INFO, "ok", context, null));

        ArgumentCaptor<io.opentelemetry.context.Context> ctx =
                ArgumentCaptor.forClass(io.opentelemetry.context.Context.class);
        verify(record).setContext(ctx.capture());
        assertThat(io.opentelemetry.api.trace.Span.fromContext(ctx.getValue()).getSpanContext().getTraceId())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void addsErrorIdOnlyForErrorsWithAThrowable() {
        appender.append(event(Level.ERROR, "failed", new SortedArrayStringMap(), new IllegalStateException()));
        ArgumentCaptor<String> errorId = ArgumentCaptor.forClass(String.class);
        verify(record).setAttribute(org.mockito.ArgumentMatchers.eq(AttributeKey.stringKey("errorId")),
                errorId.capture());
        assertThat(errorId.getValue()).matches("[0-9a-f]{12}");
    }

    @Test
    void noErrorIdForWarnings() {
        appender.append(event(Level.WARN, "failed", new SortedArrayStringMap(), new IllegalStateException()));
        verify(record, never()).setAttribute(org.mockito.ArgumentMatchers.eq(AttributeKey.stringKey("errorId")),
                anyString());
    }

    @Test
    void mapsEveryLevel() {
        assertThat(OtlpLog4j2Appender.toSeverity(Level.FATAL)).isEqualTo(Severity.FATAL);
        assertThat(OtlpLog4j2Appender.toSeverity(Level.ERROR)).isEqualTo(Severity.ERROR);
        assertThat(OtlpLog4j2Appender.toSeverity(Level.INFO)).isEqualTo(Severity.INFO);
        assertThat(OtlpLog4j2Appender.toSeverity(Level.DEBUG)).isEqualTo(Severity.DEBUG);
        assertThat(OtlpLog4j2Appender.toSeverity(Level.TRACE)).isEqualTo(Severity.TRACE);
        assertThat(OtlpLog4j2Appender.toSeverity(null)).isEqualTo(Severity.INFO);
    }
}
