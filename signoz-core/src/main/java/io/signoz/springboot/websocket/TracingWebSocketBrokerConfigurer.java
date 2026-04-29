package io.signoz.springboot.websocket;

import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * {@link WebSocketMessageBrokerConfigurer} that registers a
 * {@link TracingStompChannelInterceptor#Direction#INBOUND} interceptor on the
 * client-inbound channel and a {@link TracingStompChannelInterceptor#Direction#OUTBOUND}
 * interceptor on the client-outbound channel.
 *
 * <p>Spring composes multiple {@code WebSocketMessageBrokerConfigurer} beans
 * additively, so this configurer cooperates with any user-provided one.
 */
public class TracingWebSocketBrokerConfigurer implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.INBOUND));
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new TracingStompChannelInterceptor(
                TracingStompChannelInterceptor.Direction.OUTBOUND));
    }
}
