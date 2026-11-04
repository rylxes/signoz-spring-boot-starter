package io.signoz.springboot.autoconfigure;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SigNozMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false", "signoz.endpoint=http://collector:4317",
                    "signoz.service-name=orders", "signoz.headers.signoz-ingestion-key=k-1");

    @Test
    void registersTheOtlpRegistryWithTheSigNozSettings() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(OtlpMeterRegistry.class);
            OtlpConfig config = (OtlpConfig) ReflectionTestUtils.getField(
                    ctx.getBean(OtlpMeterRegistry.class), "config");
            assertThat(config.url()).isEqualTo("http://collector:4318/v1/metrics");
            assertThat(config.resourceAttributes()).containsEntry("service.name", "orders");
            assertThat(config.headers()).containsEntry("signoz-ingestion-key", "k-1");
            assertThat(ctx.getBean(OtlpMeterRegistry.class).find("jvm.memory.used").gauges()).isNotEmpty();
        });
    }

    @Test
    void anApplicationsOwnRegistryWins() {
        OtlpMeterRegistry own = new OtlpMeterRegistry(OtlpConfig.DEFAULT, Clock.SYSTEM);
        runner.withBean("myRegistry", OtlpMeterRegistry.class, () -> own)
                .run(ctx -> assertThat(ctx).getBean(OtlpMeterRegistry.class).isSameAs(own));
        own.close();
    }
}
