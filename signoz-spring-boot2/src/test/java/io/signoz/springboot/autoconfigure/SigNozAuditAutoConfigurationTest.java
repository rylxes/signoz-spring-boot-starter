package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.audit.AuditLogAspect;
import io.signoz.springboot.audit.SigNozAuditHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SigNozAuditAutoConfiguration} using {@link ApplicationContextRunner}.
 *
 * <p>The audit configuration is guarded by
 * {@code @ConditionalOnProperty(name="signoz.audit.enabled", matchIfMissing=true)},
 * so both beans are registered by default. Setting {@code signoz.audit.enabled=false}
 * disables the entire audit module.
 *
 * <p>A no-op {@link OpenTelemetry} bean is provided and tracing is disabled so
 * the real OTel SDK is never initialised during these tests.
 */
class SigNozAuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    @Test
    public void auditLogAspectBeanPresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(AuditLogAspect.class));
    }

    @Test
    public void sigNozAuditHandlerBeanPresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(SigNozAuditHandler.class));
    }

    @Test
    public void auditDisabledSkipsBeans() {
        runner.withPropertyValues("signoz.audit.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AuditLogAspect.class);
                    assertThat(ctx).doesNotHaveBean(SigNozAuditHandler.class);
                });
    }
}
