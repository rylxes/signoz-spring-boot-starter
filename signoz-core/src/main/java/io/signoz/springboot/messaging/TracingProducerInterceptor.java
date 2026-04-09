package io.signoz.springboot.messaging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A Kafka {@link ProducerInterceptor} that injects W3C trace context headers
 * ({@code traceparent}, {@code traceId}, {@code spanId}) into outgoing
 * {@link ProducerRecord} headers as UTF-8 encoded bytes.
 *
 * <p>This enables distributed trace propagation across Kafka message boundaries.
 */
public class TracingProducerInterceptor implements ProducerInterceptor<Object, Object> {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_MESSAGING");

    /**
     * Injects the current span's trace context into the record headers before sending.
     *
     * @param record the record about to be sent
     * @return the record with injected trace headers
     */
    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            String traceId = spanContext.getTraceId();
            String spanId = spanContext.getSpanId();
            String traceparent = String.format("00-%s-%s-%s",
                    traceId,
                    spanId,
                    spanContext.getTraceFlags().asHex());

            record.headers().add("traceId", traceId.getBytes(StandardCharsets.UTF_8));
            record.headers().add("spanId", spanId.getBytes(StandardCharsets.UTF_8));
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    /**
     * No-op acknowledgement callback.
     *
     * @param metadata  the record metadata (null if send failed)
     * @param exception the exception from sending (null if successful)
     */
    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // no-op
    }

    /**
     * No-op close.
     */
    @Override
    public void close() {
        // no-op
    }

    /**
     * No-op configure.
     *
     * @param configs configuration map
     */
    @Override
    public void configure(Map<String, ?> configs) {
        // no-op
    }
}
