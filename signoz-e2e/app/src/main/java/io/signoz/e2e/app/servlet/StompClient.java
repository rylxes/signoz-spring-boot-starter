package io.signoz.e2e.app.servlet;

import io.signoz.springboot.tracing.TraceContextCodec;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.util.concurrent.TimeUnit;

/**
 * Connects to the peer's STOMP endpoint as a browser or another service would. The client side is
 * not instrumented by the starter, so it sends {@code traceparent} on the handshake itself.
 */
@Component
public class StompClient {

    private final String peerUrl;

    public StompClient(@Value("${e2e.peer-url}") String peerUrl) {
        this.peerUrl = peerUrl;
    }

    /**
     * Sends one frame to the peer's {@code /app/hello}.
     *
     * @param traceparentOnFrame also put {@code traceparent} on the frame; otherwise the peer has
     *                           only the handshake's to go on
     */
    public void send(String payload, boolean traceparentOnFrame) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        String traceparent = TraceContextCodec.currentTraceparent();
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (traceparent != null) {
            handshake.add("traceparent", traceparent);
        }
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            handshake.add("X-Request-ID", requestId);
        }
        StompSession session = client.connect(peerUrl.replaceFirst("^http", "ws") + "/ws", handshake,
                new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
        try {
            StompHeaders frame = new StompHeaders();
            frame.setDestination("/app/hello");
            if (traceparentOnFrame && traceparent != null) {
                frame.add("traceparent", traceparent);
            }
            session.send(frame, payload);
            Thread.sleep(300);   // let the frame leave before closing
        } finally {
            session.disconnect();
            client.stop();
        }
    }
}
