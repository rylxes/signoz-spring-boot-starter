package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.logging.SigNozJsonEncoder;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SigNozLoggingAutoConfiguration} using {@link ApplicationContextRunner}.
 *
 * <p>Each test targets the logging auto-configuration in isolation. A no-op
 * {@link OpenTelemetry} bean is provided so the OTel SDK is never initialised,
 * and {@code signoz.tracing.enabled=false} suppresses the tracing import.
 */
class SigNozLoggingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    @Test
    public void maskingRegistryBeanCreated() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MaskingRegistry.class));
    }

    @Test
    public void sigNozJsonEncoderBeanCreated() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(SigNozJsonEncoder.class));
    }

    @Test
    public void customMaskingRegistryPreventsBeanCreation() {
        SigNozLoggingProperties loggingProps = new SigNozLoggingProperties();
        MaskingRegistry customRegistry = new MaskingRegistry(loggingProps);

        runner.withBean("maskingRegistry", MaskingRegistry.class, () -> customRegistry)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MaskingRegistry.class);
                    assertThat(ctx.getBean(MaskingRegistry.class)).isSameAs(customRegistry);
                });
    }

    @Test
    public void maskingRegistryUsesCustomMaskedFields() {
        runner.withPropertyValues("signoz.logging.masked-fields=myfield")
                .run(ctx -> {
                    MaskingRegistry registry = ctx.getBean(MaskingRegistry.class);
                    assertThat(registry.isSensitiveField("myfield")).isTrue();
                });
    }

    @Test
    public void logbackConfigurerBeanIsCreated() {
        runner.run(ctx -> {
            assertThat(ctx.containsBean("sigNozLogbackConfigurer")).isTrue();
            assertThat(ctx.getBean("sigNozLogbackConfigurer"))
                    .isInstanceOf(SmartInitializingSingleton.class);
        });
    }
}
