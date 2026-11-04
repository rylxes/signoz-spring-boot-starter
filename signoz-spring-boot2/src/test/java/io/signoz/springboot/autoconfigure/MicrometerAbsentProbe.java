package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.alerts.AlertOnFailureAspect;
import io.signoz.springboot.timed.TimedAspect;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Started by {@link MicrometerOptionalAutoConfigurationTest} inside a class loader without
 * Micrometer. This is a separate class because it must not reference any Micrometer type itself:
 * loading one here would fail for the wrong reason and hide what is being tested.
 */
public class MicrometerAbsentProbe {

    public static void run() {
        // Guards the test itself: without this it could pass while Micrometer was still visible.
        assertThatThrownBy(() -> Class.forName("io.micrometer.core.instrument.MeterRegistry"))
                .isInstanceOf(ClassNotFoundException.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
                .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
                .withPropertyValues("signoz.tracing.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(TimedAspect.class);
                    assertThat(ctx).hasSingleBean(AlertOnFailureAspect.class);
                });
    }
}
