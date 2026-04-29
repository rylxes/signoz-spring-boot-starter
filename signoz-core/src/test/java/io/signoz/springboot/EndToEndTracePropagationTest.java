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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.signoz.springboot.grpc.TracingGrpcClientInterceptor;
import io.signoz.springboot.grpc.TracingGrpcServerInterceptor;
import io.signoz.springboot.sqs.SqsTraceContext;
import io.signoz.springboot.sqs.TracingSqsRequestHandler;
import io.signoz.springboot.tracing.TraceContextCodec;
import io.signoz.springboot.websocket.TracingStompChannelInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

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
 *     Service A (HTTP)
 *         → Service B (SQS producer → consumer)
 *             → Service C (gRPC client → server)
 *                 → Service D (STOMP outbound → inbound)
 * </pre>
 * and asserts that the same {@code traceId} arrives at every hop.
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
    void traceIdSurvivesHttpToSqsToGrpcToStompChain() throws Exception {
        SpanContext rootSpanContext = SpanContext.create(
                TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

        // ---------- Service A: HTTP server. The TraceIdMdcFilter would have
        // populated MDC from the inbound traceparent. We simulate that here. ----------
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        MDC.put("traceFlags", "01");
        MDC.put("requestId", TRACE_ID);

        String serviceAOutboundTraceparent;
        try (Scope ignored = Context.current().with(Span.wrap(rootSpanContext)).makeCurrent()) {
            serviceAOutboundTraceparent = TraceContextCodec.currentTraceparent();
        }
        assertThat(serviceAOutboundTraceparent)
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");

        // ---------- Service B (SQS producer): inject into MessageAttributes ----------
        SendMessageRequest sqsReq;
        try (Scope ignored = Context.current().with(Span.wrap(rootSpanContext)).makeCurrent()) {
            TracingSqsRequestHandler producer = new TracingSqsRequestHandler();
            sqsReq = new SendMessageRequest("queue-url", "payload");
            producer.beforeExecution(sqsReq);
        }
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
        assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID);

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
            traceIdInsideHandler.set(MDC.get("traceId"));
            return mock(ServerCall.Listener.class);
        };
        grpcServer.interceptCall(mock(ServerCall.class), grpcWireMetadata.get(), handler);
        assertThat(traceIdInsideHandler.get()).isEqualTo(TRACE_ID);

        // ---------- Service D (STOMP outbound): inject from MDC into native header ----------
        // MDC is currently set inside-handler; use it for outbound STOMP.
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        MDC.put("traceFlags", "01");

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

        // The traceId that started at Service A reached Service D's STOMP listener.
        assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID);
    }

    @Test
    void agentlessFallbackBuildsTraceparentFromMdcAlone() {
        // No active OTel span — MDC is the only source. This is the path
        // exercised when neither the agent nor the starter SDK is initialised.
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        MDC.put("traceFlags", "01");

        String traceparent = TraceContextCodec.currentTraceparent();
        assertThat(traceparent).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
    }

    @Test
    void noIdentityNoPropagation() {
        // No active span, no MDC → returns null so producers know to skip injection.
        assertThat(TraceContextCodec.currentTraceparent()).isNull();
    }
}
