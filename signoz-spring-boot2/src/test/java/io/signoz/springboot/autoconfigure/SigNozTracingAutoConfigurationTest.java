package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.tracing.SigNozTracer;
import io.signoz.springboot.tracing.TracedAspect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SigNozTracingAutoConfiguration} using {@link ApplicationContextRunner}.
 *
 * <p>The tracing configuration is guarded by
 * {@code @ConditionalOnProperty(name="signoz.tracing.enabled", matchIfMissing=true)}.
 *
 * <p>Uses the full {@link SigNozAutoConfiguration} chain so that
 * {@link io.signoz.springboot.properties.SigNozProperties} is registered via
 * {@code @EnableConfigurationProperties} — required by
 * {@link io.signoz.springboot.tracing.OpenTelemetrySdkConfig}'s constructor.
 *
 * <p>A no-op {@link OpenTelemetry} bean is provided so that
 * {@link io.signoz.springboot.tracing.OpenTelemetrySdkConfig#openTelemetry()} is skipped
 * (thanks to {@code @ConditionalOnMissingBean}) and no real OTLP connection is attempted.
 */
class SigNozTracingAutoConfigurationTest {

    /**
     * Base runner: full auto-configuration chain with a no-op OpenTelemetry bean so
     * the real SDK is never initialised. Tracing is ON by default (matchIfMissing=true).
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop);

    @Test
    public void sigNozTracerBeanCreated() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(SigNozTracer.class));
    }

    @Test
    public void tracedAspectBeanCreated() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(TracedAspect.class));
    }

    @Test
    public void tracingDisabledSkipsAllBeans() {
        runner.withPropertyValues("signoz.tracing.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SigNozTracer.class));
    }
}
