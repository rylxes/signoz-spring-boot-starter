package io.signoz.springboot;

import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.signoz.springboot.grpc.TracingGrpcClientInterceptor;
import io.signoz.springboot.grpc.TracingGrpcServerInterceptor;
import io.signoz.springboot.messaging.TracingConsumerInterceptor;
import io.signoz.springboot.messaging.TracingProducerInterceptor;
import io.signoz.springboot.sqs.SqsTraceContext;
import io.signoz.springboot.sqs.TracingSqsRequestHandler;
import io.signoz.springboot.tracing.TraceContextCodec;
import io.signoz.springboot.tracing.TraceIdentity;
import io.signoz.springboot.websocket.TracingStompChannelInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end QA for distributed trace propagation: simulates a service chain
 * <pre>
 *     Service A (HTTP, agentless)
 *         → Service B (SQS producer → consumer)
 *             → Service C (gRPC client → server)
 *                 → Service D (STOMP outbound → inbound)
 *                     → Service E (Kafka producer → consumer)
 * </pre>
 * and asserts that the same {@code trace_id} arrives at every hop, with no OTel span
 * active anywhere, i.e. purely through the starter's agentless MDC propagation.
 *
 * <p>Service A's MDC comes from {@link TraceIdentity}, the resolver both HTTP filters
 * (servlet and WebFlux) use, rather than being seeded by hand, so a key-name mismatch
 * between the filters and the outbound propagators fails this test.
 *
 * <p>The test does not stand up real network or message brokers — it composes
 * the actual production interceptors and helpers, feeding the wire-format
 * artefact each one produces (HTTP header / SQS attribute / gRPC Metadata /
 * STOMP native header) into the next hop's extractor.
 *
 * <p>This test is the load-bearing QA assertion that <em>cross-protocol</em>
 * propagation works — the per-protocol unit tests verify each hop in
 * isolation; this test verifies the chain.
 */
class EndToEndTracePropagationTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void traceIdSurvivesHttpToSqsToGrpcToStompToKafkaChain() throws Exception {
        // ---------- Service A: HTTP server, agentless. The MDC is exactly what the
        // HTTP filters write for an inbound traceparent. ----------
        TraceIdentity identity = TraceIdentity.resolve("00-" + TRACE_ID + "-" + SPAN_ID + "-01", null);
        for (Map.Entry<String, String> e : identity.mdcEntries().entrySet()) {
            MDC.put(e.getKey(), e.getValue());
        }

        String serviceAOutboundTraceparent = TraceContextCodec.currentTraceparent();
        assertThat(serviceAOutboundTraceparent)
                .isEqualTo("00-" + TRACE_ID + "-" + identity.getSpanId() + "-01");

        // ---------- Service B (SQS producer): inject into MessageAttributes from the MDC ----------
        TracingSqsRequestHandler producer = new TracingSqsRequestHandler();
        SendMessageRequest sqsReq = new SendMessageRequest("queue-url", "payload");
        producer.beforeExecution(sqsReq);
        MessageAttributeValue traceparentAttr = sqsReq.getMessageAttributes()
                .get(SqsTraceContext.TRACEPARENT_ATTRIBUTE);
        assertThat(traceparentAttr.getStringValue()).contains(TRACE_ID);

        // ---------- Service B (SQS consumer): extract into MDC ----------
        // Simulate the consumer aspect: pull MessageAttribute → headers map → MDC.
        MDC.clear();
        Map<String, String> consumerHeaders = new HashMap<>();
        consumerHeaders.put(SqsTraceContext.TRACEPARENT_ATTRIBUTE, traceparentAttr.getStringValue());
        boolean populated = SqsTraceContext.populateMdcFromHeaders(consumerHeaders);
        assertThat(populated).isTrue();
        assertThat(MDC.get("trace_id")).isEqualTo(TRACE_ID);

        // ---------- Service C (gRPC client): inject into Metadata, using the MDC
        // populated by the SQS consumer (no active span — falls back to MDC). ----------
        TracingGrpcClientInterceptor grpcClient = new TracingGrpcClientInterceptor();
        ClientCall mockCall = mock(ClientCall.class);
        AtomicReference<Metadata> grpcWireMetadata = new AtomicReference<>();
        doAnswer(invocation -> {
            grpcWireMetadata.set(invocation.getArgument(1));
            return null;
        }).when(mockCall).start(any(), any(Metadata.class));

        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any(CallOptions.class))).thenReturn(mockCall);
        ClientCall wrapped = grpcClient.interceptCall(mock(MethodDescriptor.class), CallOptions.DEFAULT, channel);
        wrapped.start(mock(ClientCall.Listener.class), new Metadata());

        Metadata.Key<String> traceparentKey = Metadata.Key.of(
                TraceContextCodec.TRACEPARENT_HEADER, Metadata.ASCII_STRING_MARSHALLER);
        String grpcTraceparent = grpcWireMetadata.get().get(traceparentKey);
        assertThat(grpcTraceparent).isNotNull().contains(TRACE_ID);

        // ---------- Service C (gRPC server): extract from Metadata ----------
        MDC.clear();
        TracingGrpcServerInterceptor grpcServer = new TracingGrpcServerInterceptor();
        AtomicReference<String> traceIdInsideHandler = new AtomicReference<>();
        ServerCallHandler handler = (call, h) -> {
            traceIdInsideHandler.set(MDC.get("trace_id"));
            return mock(ServerCall.Listener.class);
        };
        grpcServer.interceptCall(mock(ServerCall.class), grpcWireMetadata.get(), handler);
        assertThat(traceIdInsideHandler.get()).isEqualTo(TRACE_ID);

        // ---------- Service D (STOMP outbound): inject from MDC into native header ----------
        // The gRPC handler's MDC (as the server interceptor wrote it) drives outbound STOMP.
        TraceContextCodec.putMdc(TraceContextCodec.parse(grpcTraceparent));

        StompHeaderAccessor outboundAcc = StompHeaderAccessor.create(StompCommand.MESSAGE);
        Message<byte[]> outboundMsg = MessageBuilder.createMessage(new byte[0], outboundAcc.getMessageHeaders());
        TracingStompChannelInterceptor stompOut = new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.OUTBOUND);
        Message<?> wireMessage = stompOut.preSend(outboundMsg, mock(MessageChannel.class));

        StompHeaderAccessor wireAccessor = StompHeaderAccessor.wrap(wireMessage);
        String stompTraceparent = wireAccessor.getFirstNativeHeader(TraceContextCodec.TRACEPARENT_HEADER);
        assertThat(stompTraceparent).contains(TRACE_ID);

        // ---------- Service D (STOMP inbound): extract from native header ----------
        MDC.clear();
        StompHeaderAccessor inboundAcc = StompHeaderAccessor.create(StompCommand.SEND);
        inboundAcc.setNativeHeader(TraceContextCodec.TRACEPARENT_HEADER, stompTraceparent);
        Message<byte[]> inboundMsg = MessageBuilder.createMessage(new byte[0], inboundAcc.getMessageHeaders());
        TracingStompChannelInterceptor stompIn = new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.INBOUND);
        stompIn.preSend(inboundMsg, mock(MessageChannel.class));

        assertThat(MDC.get("trace_id")).isEqualTo(TRACE_ID);

        // ---------- Service E (Kafka producer): inject from the STOMP listener's MDC ----------
        ProducerRecord<Object, Object> kafkaRecord = new TracingProducerInterceptor()
                .onSend(new ProducerRecord<Object, Object>("orders", "key", "value"));
        Header kafkaTraceparent = kafkaRecord.headers().lastHeader(TraceContextCodec.TRACEPARENT_HEADER);
        assertThat(kafkaTraceparent).isNotNull();

        // ---------- Service E (Kafka consumer): extract into MDC ----------
        MDC.clear();
        ConsumerRecord<Object, Object> consumed = new ConsumerRecord<Object, Object>("orders", 0, 0L, "key", "value");
        consumed.headers().add(kafkaTraceparent);
        new TracingConsumerInterceptor().onConsume(new ConsumerRecords<Object, Object>(
                Collections.singletonMap(new TopicPartition("orders", 0),
                        Collections.singletonList(consumed))));

        // The trace ID that started at Service A reached Service E's Kafka listener.
        assertThat(MDC.get("trace_id")).isEqualTo(TRACE_ID);
    }

    @Test
    void agentlessFallbackBuildsTraceparentFromMdcAlone() {
        // No active OTel span — MDC is the only source. This is the path
        // exercised when neither the agent nor the starter SDK is initialised.
        MDC.put("trace_id", TRACE_ID);
        MDC.put("span_id", SPAN_ID);
        MDC.put("trace_flags", "01");

        String traceparent = TraceContextCodec.currentTraceparent();
        assertThat(traceparent).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
    }

    @Test
    void noIdentityNoPropagation() {
        // No active span, no MDC → returns null so producers know to skip injection.
        assertThat(TraceContextCodec.currentTraceparent()).isNull();
    }
}
