package io.signoz.springboot.websocket;

import io.signoz.springboot.tracing.TraceContextCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * Spring {@link HandshakeInterceptor} that runs during the HTTP-to-WebSocket
 * upgrade. It reads the W3C {@code traceparent} (and {@code X-Request-ID})
 * from the upgrade request headers and stashes them on the WebSocket session
 * attribute map so downstream handlers can read them via
 * {@code session.getAttributes().get("traceparent")}.
 *
 * <p>For STOMP, per-message propagation continues via
 * {@link TracingStompChannelInterceptor}. For raw WebSocket, applications must
 * propagate trace context inside their message envelope.
 */
public class TracingWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    /** Session attribute key carrying the W3C traceparent captured at handshake time. */
    public static final String SESSION_ATTR_TRACEPARENT = "traceparent";

    /** Session attribute key carrying the X-Request-ID captured at handshake time. */
    public static final String SESSION_ATTR_REQUEST_ID = "requestId";

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_WEBSOCKET");

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String traceparent = firstHeader(request.getHeaders().get(TraceContextCodec.TRACEPARENT_HEADER));
        TraceContextCodec.Parsed parsed = TraceContextCodec.parse(traceparent);
        if (parsed != null) {
            attributes.put(SESSION_ATTR_TRACEPARENT,
                    TraceContextCodec.format(parsed.traceId, parsed.spanId, parsed.traceFlags));
        }
        String requestId = firstHeader(request.getHeaders().get("X-Request-ID"));
        if (requestId != null && !requestId.isEmpty()) {
            attributes.put(SESSION_ATTR_REQUEST_ID, requestId);
        } else if (parsed != null) {
            attributes.put(SESSION_ATTR_REQUEST_ID, parsed.traceId);
        }
        if (logger.isDebugEnabled() && parsed != null) {
            logger.debug("[SigNoz] WebSocket handshake captured traceparent {}", traceparent);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    private static String firstHeader(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        String value = values.get(0);
        return value == null || value.isEmpty() ? null : value;
    }
}
