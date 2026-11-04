package io.signoz.springboot.autoconfigure;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.metrics.SigNozMetricsConfig;
import org.slf4j.LoggerFactory;
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

    /** Micrometer 1.9 (Spring Boot 2.7) has no way to send headers: say so, without the values. */
    @Test
    void warnsThatTheRegistryCannotSendTheHeaders() {
        Logger logger = (Logger) LoggerFactory.getLogger(SigNozMetricsConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner.run(ctx -> assertThat(ctx).hasSingleBean(OtlpMeterRegistry.class));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("cannot send headers").contains("signoz-ingestion-key").doesNotContain("k-1"));
    }
}
