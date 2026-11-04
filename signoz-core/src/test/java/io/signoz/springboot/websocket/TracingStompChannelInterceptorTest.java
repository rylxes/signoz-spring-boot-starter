package io.signoz.springboot.websocket;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TracingStompChannelInterceptorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void inboundExtractsTraceparentIntoMdc() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setNativeHeader("traceparent",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        accessor.setNativeHeader("X-Request-ID", "req-abc");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        TracingStompChannelInterceptor interceptor = new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.INBOUND);

        interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(MDC.get("trace_id")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(MDC.get("span_id")).isEqualTo("b7ad6b7169203331");
        assertThat(MDC.get("requestId")).isEqualTo("req-abc");

        interceptor.afterSendCompletion(message, mock(MessageChannel.class), true, null);
        assertThat(MDC.get("trace_id")).isNull();
    }

    @Test
    void outboundInjectsTraceparentFromActiveSpan() {
        SpanContext spanContext = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                TraceFlags.getSampled(),
                TraceState.getDefault());

        try (Scope ignored = Context.current().with(Span.wrap(spanContext)).makeCurrent()) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
            Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            TracingStompChannelInterceptor interceptor = new TracingStompChannelInterceptor(
                    TracingStompChannelInterceptor.Direction.OUTBOUND);

            Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

            // Outbound returns a new Message with a fresh mutable StompHeaderAccessor.
            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            assertThat(resultAccessor.getFirstNativeHeader("traceparent"))
                    .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        }
    }

    @Test
    void outboundReturnsOriginalMessageWhenNoIdentity() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        TracingStompChannelInterceptor interceptor = new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.OUTBOUND);

        // No active span, no MDC → no injection, original message passes through.
        assertThat(interceptor.preSend(message, mock(MessageChannel.class))).isSameAs(message);
    }

    @Test
    void inboundFallsBackToHandshakeAttributeWhenStompHeaderAbsent() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        java.util.Map<String, Object> sessionAttrs = new java.util.HashMap<>();
        sessionAttrs.put(TracingWebSocketHandshakeInterceptor.SESSION_ATTR_TRACEPARENT,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        accessor.setSessionAttributes(sessionAttrs);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        TracingStompChannelInterceptor interceptor = new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.INBOUND);

        interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(MDC.get("trace_id")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void inboundDoesNothingWhenNoTraceparentAvailable() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        TracingStompChannelInterceptor interceptor = new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.INBOUND);

        interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(MDC.get("trace_id")).isNull();
    }

    /**
     * Spring's client-inbound channel is executor-backed by default: the frame is sent in on the
     * WebSocket session's thread and the {@code @MessageMapping} handler runs on a worker. The
     * handler must see the frame's trace, and the worker must be left clean afterwards.
     */
    @Test
    void handlerOnAnExecutorChannelSeesTheFramesTrace() throws Exception {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(worker);
            inbound.addInterceptor(new TracingStompChannelInterceptor(TracingStompChannelInterceptor.Direction.INBOUND));
            java.util.concurrent.BlockingQueue<java.util.Map<String, String>> seen =
                    new java.util.concurrent.LinkedBlockingQueue<java.util.Map<String, String>>();
            inbound.subscribe(m -> {
                java.util.Map<String, String> mdc = MDC.getCopyOfContextMap();
                seen.add(mdc != null ? mdc : new java.util.HashMap<String, String>());
            });

            StompHeaderAccessor withHeader = StompHeaderAccessor.create(StompCommand.SEND);
            withHeader.setNativeHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            withHeader.setLeaveMutable(true);
            inbound.send(MessageBuilder.createMessage(new byte[0], withHeader.getMessageHeaders()));
            assertThat(seen.poll(5, java.util.concurrent.TimeUnit.SECONDS))
                    .containsEntry("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");

            StompHeaderAccessor viaHandshake = StompHeaderAccessor.create(StompCommand.SEND);
            java.util.Map<String, Object> session = new java.util.HashMap<String, Object>();
            session.put(TracingWebSocketHandshakeInterceptor.SESSION_ATTR_TRACEPARENT,
                    "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
            viaHandshake.setSessionAttributes(session);
            viaHandshake.setLeaveMutable(true);
            inbound.send(MessageBuilder.createMessage(new byte[0], viaHandshake.getMessageHeaders()));
            assertThat(seen.poll(5, java.util.concurrent.TimeUnit.SECONDS))
                    .containsEntry("trace_id", "0af7651916cd43dd8448eb211c80319c");

            // The worker is reused for unrelated frames: nothing may linger on it.
            StompHeaderAccessor bare = StompHeaderAccessor.create(StompCommand.SEND);
            bare.setLeaveMutable(true);
            inbound.send(MessageBuilder.createMessage(new byte[0], bare.getMessageHeaders()));
            assertThat(seen.poll(5, java.util.concurrent.TimeUnit.SECONDS)).doesNotContainKey("trace_id");
        } finally {
            worker.shutdownNow();
        }
    }
}
