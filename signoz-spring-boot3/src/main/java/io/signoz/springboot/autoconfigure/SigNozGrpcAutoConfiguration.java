package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.detect.OnMissingAgentCondition;
import io.signoz.springboot.grpc.TracingGrpcClientInterceptor;
import io.signoz.springboot.grpc.TracingGrpcServerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures gRPC trace-context propagation for the agentless path.
 *
 * <p>Enabled when:
 * <ul>
 *   <li>The OpenTelemetry Java Agent is <em>not</em> present.</li>
 *   <li>{@code io.grpc.ClientInterceptor} is on the classpath.</li>
 *   <li>{@code signoz.grpc.enabled} is {@code true} (default).</li>
 * </ul>
 *
 * <p><b>Wiring:</b> the starter exposes the interceptors as beans, but does
 * not bind them to a specific gRPC server / channel — it stays agnostic of
 * any third-party gRPC-Spring starter. Wire them yourself:
 * <pre>{@code
 * ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
 *     .intercept(tracingGrpcClientInterceptor)
 *     .build();
 *
 * Server server = ServerBuilder.forPort(port)
 *     .intercept(tracingGrpcServerInterceptor)
 *     .addService(...)
 *     .build();
 * }</pre>
 *
 * <p>Or, if using {@code net.devh:grpc-spring-boot-starter}, annotate any
 * @{@code Bean} method that returns these with
 * {@code @GrpcGlobalClientInterceptor} / {@code @GrpcGlobalServerInterceptor}.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OnMissingAgentCondition.class)
@ConditionalOnProperty(name = "signoz.grpc.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(name = "io.grpc.ClientInterceptor")
public class SigNozGrpcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TracingGrpcClientInterceptor tracingGrpcClientInterceptor() {
        return new TracingGrpcClientInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public TracingGrpcServerInterceptor tracingGrpcServerInterceptor() {
        return new TracingGrpcServerInterceptor();
    }
}
