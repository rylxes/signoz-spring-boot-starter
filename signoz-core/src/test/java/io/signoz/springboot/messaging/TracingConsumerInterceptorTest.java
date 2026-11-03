package io.signoz.springboot.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class TracingConsumerInterceptorTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writesSnakeCaseKeysFromTheTraceparentHeader() {
        ConsumerRecord<Object, Object> record = record();
        record.headers().add("traceparent", bytes("00-" + TRACE_ID + "-" + SPAN_ID + "-00"));

        consume(record);

        assertThat(MDC.get("trace_id")).isEqualTo(TRACE_ID);
        assertThat(MDC.get("span_id")).isEqualTo(SPAN_ID);
        assertThat(MDC.get("trace_flags")).isEqualTo("00");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void fallsBackToLegacyHeadersFromOlderProducers() {
        ConsumerRecord<Object, Object> record = record();
        record.headers().add("traceId", bytes(TRACE_ID));
        record.headers().add("spanId", bytes(SPAN_ID));

        consume(record);

        assertThat(MDC.get("trace_id")).isEqualTo(TRACE_ID);
        assertThat(MDC.get("span_id")).isEqualTo(SPAN_ID);
        assertThat(MDC.get("trace_flags")).isEqualTo("01");
    }

    @Test
    void prefersTraceparentOverLegacyHeaders() {
        ConsumerRecord<Object, Object> record = record();
        record.headers().add("traceId", bytes("11111111111111111111111111111111"));
        record.headers().add("spanId", bytes("2222222222222222"));
        record.headers().add("traceparent", bytes("00-" + TRACE_ID + "-" + SPAN_ID + "-01"));

        consume(record);

        assertThat(MDC.get("trace_id")).isEqualTo(TRACE_ID);
    }

    @Test
    void ignoresMalformedHeaders() {
        ConsumerRecord<Object, Object> record = record();
        record.headers().add("traceparent", bytes("garbage"));
        record.headers().add("traceId", bytes("x"));
        record.headers().add("spanId", bytes("y"));

        consume(record);

        assertThat(MDC.get("trace_id")).isNull();
    }

    private static ConsumerRecord<Object, Object> record() {
        return new ConsumerRecord<Object, Object>("orders", 0, 0L, "key", "value");
    }

    private static void consume(ConsumerRecord<Object, Object> record) {
        new TracingConsumerInterceptor().onConsume(new ConsumerRecords<Object, Object>(
                Collections.singletonMap(new TopicPartition("orders", 0),
                        Collections.singletonList(record))));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
