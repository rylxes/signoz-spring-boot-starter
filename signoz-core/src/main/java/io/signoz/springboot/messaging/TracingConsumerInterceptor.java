package io.signoz.springboot.messaging;

import io.signoz.springboot.detect.AgentDetector;
import io.signoz.springboot.tracing.TraceContextCodec;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A Kafka {@link ConsumerInterceptor} that extracts trace context from incoming
 * record headers and populates the SLF4J MDC with the OTel-native {@code trace_id},
 * {@code span_id} and {@code trace_flags} keys, the same keys the HTTP filters write.
 *
 * <p>The W3C {@code traceparent} header is preferred; the legacy {@code traceId} /
 * {@code spanId} headers sent by older producers are the fallback. Skipped when the
 * OTel Java agent is present, since its Kafka instrumentation owns those keys.
 *
 * <p>This enables log correlation for messages consumed from Kafka topics.
 */
public class TracingConsumerInterceptor implements ConsumerInterceptor<Object, Object> {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_MESSAGING");

    /**
     * Extracts each consumed record's trace context and puts it into the MDC for log
     * correlation (the last record of the batch wins, as before).
     *
     * @param records the records consumed from Kafka
     * @return the same records, unmodified
     */
    @Override
    public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
        // With the OTel agent, its Kafka instrumentation owns the trace MDC keys.
        if (AgentDetector.isAgentPresent()) {
            return records;
        }
        for (ConsumerRecord<Object, Object> record : records) {
            TraceContextCodec.Parsed parsed = extract(record);
            if (parsed != null) {
                TraceContextCodec.putMdc(parsed);
            }
        }
        return records;
    }

    /**
     * The record's trace context: the W3C {@code traceparent} header, or else the
     * legacy {@code traceId}/{@code spanId} headers sent by older producers.
     */
    static TraceContextCodec.Parsed extract(ConsumerRecord<Object, Object> record) {
        TraceContextCodec.Parsed parsed =
                TraceContextCodec.parse(extractHeader(record, TraceContextCodec.TRACEPARENT_HEADER));
        if (parsed != null) {
            return parsed;
        }
        String traceId = extractHeader(record, TracingProducerInterceptor.LEGACY_TRACE_ID_HEADER);
        String spanId = extractHeader(record, TracingProducerInterceptor.LEGACY_SPAN_ID_HEADER);
        if (traceId == null || spanId == null) {
            return null;
        }
        return TraceContextCodec.parse(TraceContextCodec.format(traceId, spanId, "01"));
    }

    /**
     * No-op commit callback.
     *
     * @param offsets the committed offsets
     */
    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
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

    private static String extractHeader(ConsumerRecord<Object, Object> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }
}
