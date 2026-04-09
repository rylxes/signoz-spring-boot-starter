package io.signoz.springboot.messaging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TracingProducerInterceptorTest {

    /**
     * Creates a test scope with a valid span context so the interceptor
     * can inject trace headers.
     */
    private Scope activateTestSpan() {
        SpanContext spanContext = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                TraceFlags.getSampled(),
                TraceState.getDefault()
        );
        Context ctx = Context.current().with(Span.wrap(spanContext));
        return ctx.makeCurrent();
    }

    @Test
    void onSendAddsTraceparentHeader() {
        try (Scope ignored = activateTestSpan()) {
            TracingProducerInterceptor interceptor = new TracingProducerInterceptor();
            ProducerRecord<Object, Object> record = new ProducerRecord<>("test-topic", "key", "value");

            ProducerRecord<Object, Object> result = interceptor.onSend(record);

            // The interceptor should add a traceparent header
            Header traceparent = result.headers().lastHeader("traceparent");
            assertThat(traceparent).isNotNull();
            String value = new String(traceparent.value(), StandardCharsets.UTF_8);
            assertThat(value).startsWith("00-");
        }
    }

    @Test
    void onSendAddsTraceIdHeader() {
        try (Scope ignored = activateTestSpan()) {
            TracingProducerInterceptor interceptor = new TracingProducerInterceptor();
            ProducerRecord<Object, Object> record = new ProducerRecord<>("test-topic", "key", "value");

            ProducerRecord<Object, Object> result = interceptor.onSend(record);

            Header traceId = result.headers().lastHeader("traceId");
            assertThat(traceId).isNotNull();
        }
    }

    @Test
    void onSendReturnsOriginalRecord() {
        TracingProducerInterceptor interceptor = new TracingProducerInterceptor();
        ProducerRecord<Object, Object> record = new ProducerRecord<>("test-topic", "key", "value");

        ProducerRecord<Object, Object> result = interceptor.onSend(record);

        assertThat(result).isSameAs(record);
        assertThat(result.topic()).isEqualTo("test-topic");
    }
}
