package io.signoz.springboot.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The OTLP appender is an export path in its own right and must mask on its own.
 *
 * <p>Before this was fixed, masking existed only inside {@link SigNozJsonEncoder}, which sits in the
 * console appender's encoder. Every other appender — this one, and the OpenTelemetry Java Agent's —
 * received the untouched {@code ILoggingEvent} and shipped it verbatim. The effect in production was
 * that {@code signoz.logging.masked-fields} appeared to be working (stdout was clean) while full
 * card numbers, PINs and secrets were exported to the collector.
 *
 * <p>These tests assert on what is handed to {@link LogRecordBuilder#setBody(String)} — the value
 * that actually leaves the process.
 */
class OtlpLogbackAppenderMaskingTest {

    private static final String PAN_16 = "4111111111111234";
    private static final String PAN_19 = "5061051800019666567";

    private OtlpLogbackAppender appender;
    private LogRecordBuilder recordBuilder;

    private static MaskingRegistry registryWith(String... maskedFields) {
        SigNozLoggingProperties props = new SigNozLoggingProperties();
        props.setMaskedFields(Arrays.asList(maskedFields));
        props.setCustomPatterns(Collections.emptyList());
        return new MaskingRegistry(props);
    }

    /**
     * Drives the real {@link OtlpLogbackAppender#append} without standing up a collector: the OTel
     * logger is replaced with a mock and the started flag is set directly, so {@code start()} (which
     * would build a live gRPC exporter) is bypassed.
     */
    private void primeAppender(OtlpLogbackAppender target) throws Exception {
        io.opentelemetry.api.logs.Logger otelLogger = mock(io.opentelemetry.api.logs.Logger.class);
        recordBuilder = mock(LogRecordBuilder.class, RETURNS_SELF);
        when(otelLogger.logRecordBuilder()).thenReturn(recordBuilder);

        Field loggerField = OtlpLogbackAppender.class.getDeclaredField("otelLogger");
        loggerField.setAccessible(true);
        loggerField.set(target, otelLogger);

        Field startedField = ch.qos.logback.core.AppenderBase.class.getDeclaredField("started");
        startedField.setAccessible(true);
        startedField.set(target, Boolean.TRUE);
    }

    private static ILoggingEvent eventWith(String message, Map<String, String> mdc) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getLevel()).thenReturn(Level.INFO);
        when(event.getTimeStamp()).thenReturn(System.currentTimeMillis());
        when(event.getMDCPropertyMap()).thenReturn(mdc);
        when(event.getLoggerName()).thenReturn("com.example.Payments");
        when(event.getThreadName()).thenReturn("http-nio-8090-exec-1");
        return event;
    }

    private String capturedBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(recordBuilder).setBody(body.capture());
        return body.getValue();
    }

    @BeforeEach
    void setUp() throws Exception {
        appender = new OtlpLogbackAppender();
        appender.setMaskingRegistry(registryWith("password", "cardpan", "clearpin", "secretkey"));
        primeAppender(appender);
    }

    @Test
    @DisplayName("a JSON body has its sensitive fields masked before export")
    void masksJsonBody() {
        appender.doAppend(eventWith(
                "{\"cardPan\":\"" + PAN_19 + "\",\"clearPin\":\"1234\",\"rrn\":\"878418815534\"}",
                Collections.emptyMap()));

        String body = capturedBody();
        assertThat(body).doesNotContain(PAN_19).doesNotContain("\"clearPin\":\"1234\"");
        assertThat(body).as("non-sensitive fields must survive").contains("878418815534");
    }

    @Test
    @DisplayName("a 19-digit PAN in free text is redacted, not just 16-digit ones")
    void masksNineteenDigitPanInFreeText() {
        appender.doAppend(eventWith("charging card " + PAN_19 + " now", Collections.emptyMap()));
        assertThat(capturedBody()).doesNotContain(PAN_19);
    }

    @Test
    @DisplayName("a 16-digit PAN in free text is still redacted")
    void masksSixteenDigitPanInFreeText() {
        appender.doAppend(eventWith("charging card " + PAN_16 + " now", Collections.emptyMap()));
        assertThat(capturedBody()).doesNotContain(PAN_16);
    }

    @Test
    @DisplayName("MDC values are masked by key, not exported raw as attributes")
    void masksMdcValues() {
        Map<String, String> mdc = new HashMap<>();
        mdc.put("password", "hunter2");
        mdc.put("traceId", "d4df5a34d93a7d12");
        appender.doAppend(eventWith("login attempt", mdc));

        ArgumentCaptor<io.opentelemetry.api.common.Attributes> attrs =
                ArgumentCaptor.forClass(io.opentelemetry.api.common.Attributes.class);
        verify(recordBuilder).setAllAttributes(attrs.capture());

        String rendered = attrs.getValue().toString();
        assertThat(rendered).doesNotContain("hunter2");
        assertThat(rendered).as("non-sensitive MDC must survive").contains("d4df5a34d93a7d12");
    }

    @Test
    @DisplayName("without a registry the body is passed through - the start() warning covers this")
    void noRegistryIsPassThrough() throws Exception {
        OtlpLogbackAppender bare = new OtlpLogbackAppender();
        primeAppender(bare);
        bare.doAppend(eventWith("card " + PAN_19, Collections.emptyMap()));
        // Documents the XML-configured fallback: no registry means no masking, which is why
        // start() emits a warning and the auto-configuration always injects one.
        assertThat(capturedBody()).contains(PAN_19);
    }

    @Test
    @DisplayName("epoch-nanosecond timestamps are not mistaken for card numbers")
    void doesNotRedactTimestamps() {
        appender.doAppend(eventWith("took 1788784291216000000 ns", Collections.emptyMap()));
        assertThat(capturedBody()).contains("1788784291216000000");
    }
}
