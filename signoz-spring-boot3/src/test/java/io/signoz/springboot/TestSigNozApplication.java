package io.signoz.springboot;

import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Spring Boot application used as the test application context root
 * for all {@code @SpringBootTest} integration tests in the signoz-spring-boot3 module.
 *
 * <p>Supplies a no-op {@link OpenTelemetry} bean so the real OTel SDK is never
 * initialised during tests, even when {@code signoz.tracing.enabled} is not
 * explicitly set to {@code false} in a particular test profile.
 */
@SpringBootApplication
public class TestSigNozApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestSigNozApplication.class, args);
    }

    /**
     * Provides a no-op {@link OpenTelemetry} instance so that beans which depend
     * on {@code OpenTelemetry} (e.g. {@code SigNozTracer}) can be satisfied without
     * connecting to any real OTLP endpoint.
     */
    @Bean
    public OpenTelemetry openTelemetry() {
        return OpenTelemetry.noop();
    }
}
