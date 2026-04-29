package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.detect.AgentDetector;
import io.signoz.springboot.grpc.TracingGrpcClientInterceptor;
import io.signoz.springboot.grpc.TracingGrpcServerInterceptor;
import io.signoz.springboot.sqs.AwspringV2SqsListenerTraceAspect;
import io.signoz.springboot.sqs.TracingSqsRequestHandler;
import io.signoz.springboot.websocket.TracingWebSocketBrokerConfigurer;
import io.signoz.springboot.websocket.TracingWebSocketHandshakeInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA tests for the agentless-path auto-configurations:
 * {@link SigNozSqsAutoConfiguration}, {@link SigNozGrpcAutoConfiguration},
 * and {@link SigNozWebSocketAutoConfiguration}.
 *
 * <p>For each protocol we assert:
 * <ul>
 *   <li>Bean registered when (no agent) + (protocol API on classpath) + (signoz.&lt;x&gt;.enabled=true).</li>
 *   <li>Bean skipped when {@code signoz.&lt;x&gt;.enabled=false}.</li>
 *   <li>Bean skipped when the protocol API is absent from the classpath
 *       (simulated via {@link FilteredClassLoader}).</li>
 *   <li>Bean skipped when the OpenTelemetry Java Agent is detected
 *       (simulated by setting {@code otel.javaagent.version}).</li>
 * </ul>
 */
class SigNozProtocolAutoConfigurationTest {

    /**
     * Reset agent-detection state before and after every test so cross-test
     * pollution can't make the OnMissingAgentCondition flap. This includes
     * {@link GlobalOpenTelemetry#resetForTest()} because earlier tests may
     * have called {@code OpenTelemetrySdkBuilder.buildAndRegisterGlobal()},
     * which would otherwise make {@link AgentDetector}'s strategy 3 ("global
     * OTel is non-noop") return {@code true} for the rest of the JVM.
     */
    @BeforeEach
    void resetAgentState() {
        System.clearProperty("otel.javaagent.version");
        GlobalOpenTelemetry.resetForTest();
        AgentDetector.resetCache();
    }

    @AfterEach
    void clearAgentState() {
        System.clearProperty("otel.javaagent.version");
        GlobalOpenTelemetry.resetForTest();
        AgentDetector.resetCache();
    }

    /**
     * Loads the root {@link SigNozAutoConfiguration} (which {@code @Import}s every
     * sub-configuration including the three protocol auto-configs) and pre-supplies
     * a no-op {@link OpenTelemetry} bean so the SDK is never initialised.
     * Tracing import is suppressed to keep the bean graph minimal for these tests.
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
                .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
                .withPropertyValues("signoz.tracing.enabled=false");
    }

    // ---------- SQS ----------

    @Nested
    class Sqs {

        @Test
        void registersTracingHandlerWhenAgentAbsentAndAwsSdkPresent() {
            runner()
                    .run(ctx -> assertThat(ctx).hasSingleBean(TracingSqsRequestHandler.class));
        }

        @Test
        void registersAwspringV2AspectWhenItsAnnotationIsPresent() {
            runner()
                    .run(ctx -> assertThat(ctx).hasSingleBean(AwspringV2SqsListenerTraceAspect.class));
        }

        @Test
        void doesNotRegisterWhenPropertyDisabled() {
            runner()
                    .withPropertyValues("signoz.sqs.enabled=false")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(TracingSqsRequestHandler.class));
        }

        @Test
        void doesNotRegisterWhenAwsSdkAbsent() {
            runner()
                    .withClassLoader(new FilteredClassLoader("com.amazonaws.handlers.RequestHandler2"))
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(TracingSqsRequestHandler.class));
        }

        @Test
        void doesNotRegisterWhenAgentDetected() {
            System.setProperty("otel.javaagent.version", "test-version");
            AgentDetector.resetCache();

            runner()
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(TracingSqsRequestHandler.class));
        }
    }

    // ---------- gRPC ----------

    @Nested
    class Grpc {

        @Test
        void registersBothInterceptorsWhenAgentAbsentAndGrpcPresent() {
            runner()
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(TracingGrpcClientInterceptor.class);
                        assertThat(ctx).hasSingleBean(TracingGrpcServerInterceptor.class);
                    });
        }

        @Test
        void doesNotRegisterWhenPropertyDisabled() {
            runner()
                    .withPropertyValues("signoz.grpc.enabled=false")
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(TracingGrpcClientInterceptor.class);
                        assertThat(ctx).doesNotHaveBean(TracingGrpcServerInterceptor.class);
                    });
        }

        @Test
        void doesNotRegisterWhenGrpcAbsent() {
            runner()
                    .withClassLoader(new FilteredClassLoader("io.grpc.ClientInterceptor"))
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(TracingGrpcClientInterceptor.class));
        }

        @Test
        void doesNotRegisterWhenAgentDetected() {
            System.setProperty("otel.javaagent.version", "test-version");
            AgentDetector.resetCache();

            runner()
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(TracingGrpcClientInterceptor.class));
        }
    }

    // ---------- WebSocket / STOMP ----------

    @Nested
    class WebSocket {

        @Test
        void registersHandshakeAndConfigurerWhenAgentAbsentAndStompPresent() {
            runner()
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(TracingWebSocketHandshakeInterceptor.class);
                        assertThat(ctx).hasSingleBean(TracingWebSocketBrokerConfigurer.class);
                    });
        }

        @Test
        void doesNotRegisterWhenPropertyDisabled() {
            runner()
                    .withPropertyValues("signoz.websocket.enabled=false")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(TracingWebSocketHandshakeInterceptor.class));
        }

        @Test
        void doesNotRegisterWhenSpringWebSocketAbsent() {
            runner()
                    .withClassLoader(new FilteredClassLoader(
                            "org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer"))
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(TracingWebSocketHandshakeInterceptor.class));
        }

        @Test
        void doesNotRegisterWhenAgentDetected() {
            System.setProperty("otel.javaagent.version", "test-version");
            AgentDetector.resetCache();

            runner()
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(TracingWebSocketHandshakeInterceptor.class));
        }
    }
}
