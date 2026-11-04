package io.signoz.springboot.websocket;

import io.signoz.springboot.tracing.TraceContextCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.support.NativeMessageHeaderAccessor;

/**
 * STOMP {@link ChannelInterceptor} that propagates trace context across STOMP
 * frames. A single instance behaves as either an inbound extractor or an
 * outbound injector depending on the {@link Direction} it was constructed with.
 *
 * <ul>
 *   <li><b>{@link Direction#INBOUND}</b> on the client-inbound channel —
 *       extracts the {@code traceparent} STOMP native header (or the value
 *       captured at handshake time) and populates MDC on the thread that runs
 *       the {@code @MessageMapping} handler, clearing it afterwards. Spring's
 *       client-inbound channel is executor-backed by default, so that is not the
 *       thread that sent the frame into the channel: the MDC is set in
 *       {@link #beforeHandle} (handler thread) as well as in {@link #preSend},
 *       which covers synchronous channels.</li>
 *   <li><b>{@link Direction#OUTBOUND}</b> on the client-outbound channel and
 *       the broker channel — injects the current {@code traceparent}
 *       (active span or MDC) into the STOMP frame headers before send.</li>
 * </ul>
 *
 * <p>The {@code WebSocketMessageBrokerConfigurer} in the auto-config registers
 * one {@code INBOUND} instance on {@code configureClientInboundChannel} and
 * one {@code OUTBOUND} instance on {@code configureClientOutboundChannel}.
 */
public class TracingStompChannelInterceptor implements ExecutorChannelInterceptor {

    public enum Direction { INBOUND, OUTBOUND }

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_WEBSOCKET");

    private static final ThreadLocal<Boolean> POPULATED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Direction direction;

    public TracingStompChannelInterceptor(Direction direction) {
        this.direction = direction;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (direction == Direction.INBOUND) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null) {
                handleInbound(accessor);
            }
            return message;
        }
        // OUTBOUND: returns a new Message with a mutable copy of the headers, because messages
        // produced by the broker channel typically carry immutable ones.
        return injectOutbound(message);
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel,
                                    boolean sent, Exception ex) {
        clearIfPopulated();
    }

    /**
     * Runs on the thread that invokes the handler. With an executor-backed channel (Spring's
     * default for the client-inbound channel) this is a worker thread, where {@link #preSend}'s
     * MDC is not visible.
     */
    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        if (direction == Direction.INBOUND) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null) {
                handleInbound(accessor);
            }
        }
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
                                    Exception ex) {
        clearIfPopulated();
    }

    private void clearIfPopulated() {
        if (direction == Direction.INBOUND && Boolean.TRUE.equals(POPULATED.get())) {
            TraceContextCodec.clearMdc();
            MDC.remove("requestId");
            POPULATED.remove();
        }
    }

    private void handleInbound(StompHeaderAccessor accessor) {
        String traceparent = accessor.getFirstNativeHeader(TraceContextCodec.TRACEPARENT_HEADER);
        TraceContextCodec.Parsed parsed = TraceContextCodec.parse(traceparent);
        if (parsed == null && accessor.getSessionAttributes() != null) {
            Object handshakeTraceparent = accessor.getSessionAttributes()
                    .get(TracingWebSocketHandshakeInterceptor.SESSION_ATTR_TRACEPARENT);
            if (handshakeTraceparent != null) {
                parsed = TraceContextCodec.parse(handshakeTraceparent.toString());
            }
        }
        if (parsed == null) {
            return;
        }
        TraceContextCodec.putMdc(parsed);

        String requestId = accessor.getFirstNativeHeader("X-Request-ID");
        if (requestId == null && accessor.getSessionAttributes() != null) {
            Object handshakeRequestId = accessor.getSessionAttributes()
                    .get(TracingWebSocketHandshakeInterceptor.SESSION_ATTR_REQUEST_ID);
            if (handshakeRequestId != null) requestId = handshakeRequestId.toString();
        }
        MDC.put("requestId", (requestId != null && !requestId.isEmpty()) ? requestId : parsed.traceId);
        POPULATED.set(Boolean.TRUE);

        if (logger.isDebugEnabled()) {
            logger.debug("[SigNoz] Extracted traceparent from inbound STOMP frame");
        }
    }

    private Message<?> injectOutbound(Message<?> message) {
        String traceparent = TraceContextCodec.currentTraceparent();
        if (traceparent == null) {
            return message;
        }
        // Keep the message's own accessor type. Frames the broker sends (CONNECT_ACK, MESSAGE to
        // subscribers) carry a SimpMessageHeaderAccessor that StompSubProtocolHandler turns into
        // a STOMP frame itself; re-wrapping them as StompHeaderAccessor made it skip that step,
        // fail with "Missing STOMP command", drop the frame and close the session.
        MessageHeaderAccessor accessor = MessageHeaderAccessor.getMutableAccessor(message);
        if (!(accessor instanceof NativeMessageHeaderAccessor)) {
            return message;
        }
        NativeMessageHeaderAccessor nativeAccessor = (NativeMessageHeaderAccessor) accessor;
        nativeAccessor.setNativeHeader(TraceContextCodec.TRACEPARENT_HEADER, traceparent);
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isEmpty()) {
            nativeAccessor.setNativeHeader("X-Request-ID", requestId);
        }
        return MessageBuilder.createMessage(message.getPayload(), nativeAccessor.getMessageHeaders());
    }
}
