package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.detect.OnMissingAgentCondition;
import io.signoz.springboot.websocket.TracingWebSocketBrokerConfigurer;
import io.signoz.springboot.websocket.TracingWebSocketHandshakeInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures WebSocket / STOMP trace-context propagation for the
 * agentless path.
 *
 * <p>Enabled when:
 * <ul>
 *   <li>The OpenTelemetry Java Agent is <em>not</em> present.</li>
 *   <li>{@code WebSocketMessageBrokerConfigurer} is on the classpath
 *       (i.e. spring-websocket + spring-messaging are present).</li>
 *   <li>{@code signoz.websocket.enabled} is {@code true} (default).</li>
 * </ul>
 *
 * <p>Registers two beans:
 * <ul>
 *   <li>{@link TracingWebSocketHandshakeInterceptor} — exposed for users to
 *       attach to their {@code WebSocketHandlerRegistry} via
 *       {@code .addInterceptors(handshakeInterceptor)} (cannot be
 *       auto-attached because handler registration is user-defined).</li>
 *   <li>{@link TracingWebSocketBrokerConfigurer} — auto-attached because
 *       Spring composes multiple {@code WebSocketMessageBrokerConfigurer}
 *       beans additively, so this one cooperates with any user-provided one.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OnMissingAgentCondition.class)
@ConditionalOnProperty(name = "signoz.websocket.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(name = "org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer")
public class SigNozWebSocketAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TracingWebSocketHandshakeInterceptor tracingWebSocketHandshakeInterceptor() {
        return new TracingWebSocketHandshakeInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public TracingWebSocketBrokerConfigurer tracingWebSocketBrokerConfigurer() {
        return new TracingWebSocketBrokerConfigurer();
    }
}
