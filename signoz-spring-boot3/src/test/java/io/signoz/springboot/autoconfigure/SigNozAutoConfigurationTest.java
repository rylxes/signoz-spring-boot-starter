package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.audit.AuditLogAspect;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SigNozAutoConfiguration} using {@link ApplicationContextRunner}.
 *
 * <p>The root auto-configuration is guarded by
 * {@code @ConditionalOnProperty(name="signoz.enabled", matchIfMissing=true)},
 * so the context loads by default without any property overrides.
 *
 * <p>A no-op {@link OpenTelemetry} bean is registered on every runner to prevent
 * the real OTel SDK from being initialised in test runs.
 */
class SigNozAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    @Test
    void contextLoadsWithDefaults() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void contextDoesNotLoadWhenDisabledFalse() {
        runner.withPropertyValues("signoz.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SigNozProperties.class));
    }

    @Test
    void sigNozPropertiesBeanIsRegistered() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(SigNozProperties.class));
    }

    @Test
    void maskingRegistryBeanIsRegistered() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MaskingRegistry.class));
    }

    @Test
    void auditLogAspectBeanIsPresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(AuditLogAspect.class));
    }
}
