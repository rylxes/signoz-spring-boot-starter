package io.signoz.springboot.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TracingWebSocketHandshakeInterceptorTest {

    @Test
    void capturesValidTraceparentIntoSessionAttributes() {
        TracingWebSocketHandshakeInterceptor interceptor = new TracingWebSocketHandshakeInterceptor();
        HttpHeaders headers = new HttpHeaders();
        headers.add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        headers.add("X-Request-ID", "req-abc");

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);

        Map<String, Object> attributes = new HashMap<>();

        boolean proceed = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertThat(proceed).isTrue();
        assertThat(attributes.get(TracingWebSocketHandshakeInterceptor.SESSION_ATTR_TRACEPARENT))
                .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(attributes.get(TracingWebSocketHandshakeInterceptor.SESSION_ATTR_REQUEST_ID))
                .isEqualTo("req-abc");
    }

    @Test
    void ignoresMalformedTraceparentButStillProceeds() {
        TracingWebSocketHandshakeInterceptor interceptor = new TracingWebSocketHandshakeInterceptor();
        HttpHeaders headers = new HttpHeaders();
        headers.add("traceparent", "garbage");

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);

        Map<String, Object> attributes = new HashMap<>();
        assertThat(interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes)).isTrue();
        assertThat(attributes.get(TracingWebSocketHandshakeInterceptor.SESSION_ATTR_TRACEPARENT)).isNull();
    }

    @Test
    void noHeadersNoAttributesNoCrash() {
        TracingWebSocketHandshakeInterceptor interceptor = new TracingWebSocketHandshakeInterceptor();
        HttpHeaders headers = new HttpHeaders();

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);

        Map<String, Object> attributes = new HashMap<>();
        assertThat(interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes)).isTrue();
        assertThat(attributes).isEmpty();
    }
}
