package io.signoz.e2e.app.servlet;

import io.signoz.e2e.app.common.Observations;
import io.signoz.springboot.annotation.SigNozLog;
import io.signoz.springboot.websocket.TracingWebSocketHandshakeInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Collections;

/** A STOMP endpoint at {@code /ws}, with the starter's handshake interceptor as the guide shows. */
public final class StompServer {

    private StompServer() {
    }

    @Configuration
    @EnableWebSocketMessageBroker
    public static class Config implements WebSocketMessageBrokerConfigurer {

        private final ObjectProvider<TracingWebSocketHandshakeInterceptor> handshake;

        public Config(ObjectProvider<TracingWebSocketHandshakeInterceptor> handshake) {
            this.handshake = handshake;
        }

        @Override
        public void registerStompEndpoints(StompEndpointRegistry registry) {
            StompWebSocketEndpointRegistration endpoint = registry.addEndpoint("/ws");
            TracingWebSocketHandshakeInterceptor interceptor = handshake.getIfAvailable();
            if (interceptor != null) {
                endpoint.addInterceptors(interceptor);
            }
        }

        @Override
        public void configureMessageBroker(MessageBrokerRegistry registry) {
            registry.enableSimpleBroker("/topic");
            registry.setApplicationDestinationPrefixes("/app");
        }
    }

    @SigNozLog
    @Controller
    public static class Inbound {

        private final Observations observations;

        public Inbound(Observations observations) {
            this.observations = observations;
        }

        @MessageMapping("/hello")
        public void hello(String payload) {
            log.info("stomp received {}", payload);
            observations.record("stomp", Collections.singletonMap("payload", payload));
        }
    }
}
