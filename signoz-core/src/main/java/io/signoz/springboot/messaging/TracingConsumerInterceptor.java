package io.signoz.springboot.messaging;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A Kafka {@link ConsumerInterceptor} that extracts trace context from incoming
 * record headers and populates the SLF4J {@link MDC} with {@code traceId} and
 * {@code spanId} values.
 *
 * <p>This enables log correlation for messages consumed from Kafka topics.
 */
public class TracingConsumerInterceptor implements ConsumerInterceptor<Object, Object> {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_MESSAGING");

    /**
     * Extracts traceId and spanId from each consumed record's headers and puts
     * them into the MDC for log correlation.
     *
     * @param records the records consumed from Kafka
     * @return the same records, unmodified
     */
    @Override
    public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
        for (ConsumerRecord<Object, Object> record : records) {
            String traceId = extractHeader(record, "traceId");
            String spanId = extractHeader(record, "spanId");

            if (traceId != null) {
                MDC.put("traceId", traceId);
            }
            if (spanId != null) {
                MDC.put("spanId", spanId);
            }
        }
        return records;
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

    private String extractHeader(ConsumerRecord<Object, Object> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }
}
