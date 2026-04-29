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

        assertThat(MDC.get("traceId")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(MDC.get("spanId")).isEqualTo("b7ad6b7169203331");
        assertThat(MDC.get("requestId")).isEqualTo("req-abc");

        interceptor.afterSendCompletion(message, mock(MessageChannel.class), true, null);
        assertThat(MDC.get("traceId")).isNull();
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

        assertThat(MDC.get("traceId")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void inboundDoesNothingWhenNoTraceparentAvailable() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        TracingStompChannelInterceptor interceptor = new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.INBOUND);

        interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(MDC.get("traceId")).isNull();
    }
}
