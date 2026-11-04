package io.signoz.springboot.messaging;

import io.signoz.springboot.tracing.TraceContextCodec;
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

    /** Legacy record headers, written alongside {@code traceparent} for older consumers. */
    public static final String LEGACY_TRACE_ID_HEADER = "traceId";
    public static final String LEGACY_SPAN_ID_HEADER = "spanId";

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_MESSAGING");

    /**
     * Injects the current trace context (active OTel span, else the agentless MDC
     * written by the HTTP filters) into the record headers before sending.
     *
     * @param record the record about to be sent
     * @return the record with injected trace headers
     */
    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        // Active OTel span first, then the MDC written by the HTTP filters, so agentless
        // producers propagate too (previously only an active span was used).
        TraceContextCodec.Parsed parsed = TraceContextCodec.parse(TraceContextCodec.currentTraceparent());
        if (parsed != null) {
            record.headers().add(TraceContextCodec.TRACEPARENT_HEADER,
                    TraceContextCodec.format(parsed.traceId, parsed.spanId, parsed.traceFlags)
                            .getBytes(StandardCharsets.UTF_8));
            // Legacy headers, kept so consumers on older starter versions still correlate.
            record.headers().add(LEGACY_TRACE_ID_HEADER, parsed.traceId.getBytes(StandardCharsets.UTF_8));
            record.headers().add(LEGACY_SPAN_ID_HEADER, parsed.spanId.getBytes(StandardCharsets.UTF_8));
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
