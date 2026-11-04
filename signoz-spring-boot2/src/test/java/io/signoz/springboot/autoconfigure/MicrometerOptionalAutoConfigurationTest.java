package io.signoz.springboot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.alerts.AlertOnFailureAspect;
import io.signoz.springboot.timed.TimedAspect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Micrometer is an optional dependency: an application using only the starter, without Actuator,
 * must still start, and {@code @Timed} / {@code @AlertOnFailure} must still log.
 *
 * <p>{@code FilteredClassLoader} cannot reproduce the failure this guards against. The
 * auto-configuration classes would already be loaded by the application class loader, where
 * Micrometer is visible, so resolving a bean method's {@code MeterRegistry} parameter would
 * succeed. The context here therefore runs in a class loader whose class path has the Micrometer
 * jars removed.
 */
class MicrometerOptionalAutoConfigurationTest {

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
                .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
                .withPropertyValues("signoz.tracing.enabled=false");
    }

    @Test
    void contextStartsWithoutMicrometerOnTheClassPath() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader withoutMicrometer = new URLClassLoader(classPathWithoutMicrometer(),
                ClassLoader.getSystemClassLoader().getParent())) {
            Thread.currentThread().setContextClassLoader(withoutMicrometer);
            withoutMicrometer.loadClass("io.signoz.springboot.autoconfigure.MicrometerAbsentProbe")
                    .getMethod("run")
                    .invoke(null);
        } catch (InvocationTargetException e) {
            // Rethrow the probe's own failure rather than the reflection wrapper.
            throw (Error) new AssertionError(e.getCause().getMessage()).initCause(e.getCause());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void aspectsUseThePrimaryRegistry() {
        // Actuator's CompositeMeterRegistry is primary whenever there is more than one registry.
        MeterRegistry registry = new SimpleMeterRegistry();
        runner().withBean(MeterRegistry.class, () -> registry, d -> d.setPrimary(true)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(ctx.getBean(TimedAspect.class), "meterRegistry"))
                    .isSameAs(registry);
            assertThat(ReflectionTestUtils.getField(ctx.getBean(AlertOnFailureAspect.class), "meterRegistry"))
                    .isSameAs(registry);
        });
    }

    @Test
    void aspectsUseTheStartersOtlpRegistryWhenItIsTheOnlyOne() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(ctx.getBean(TimedAspect.class), "meterRegistry"))
                    .isSameAs(ctx.getBean(OtlpMeterRegistry.class));
        });
    }

    @Test
    void severalRegistriesWithoutAPrimaryDoNotStopTheApplication() {
        runner().withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(ctx.getBean(TimedAspect.class), "meterRegistry")).isNull();
            assertThat(ReflectionTestUtils.getField(ctx.getBean(AlertOnFailureAspect.class), "meterRegistry")).isNull();
        });
    }

    @Test
    void timedAndAlertAspectsCanStillBeDisabled() {
        runner().withPropertyValues("signoz.timed.enabled=false", "signoz.alerts.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(TimedAspect.class);
                    assertThat(ctx).doesNotHaveBean(AlertOnFailureAspect.class);
                });
    }

    private static URL[] classPathWithoutMicrometer() throws Exception {
        String classPath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<URL> urls = new ArrayList<URL>();
        for (String entry : classPath.split(File.pathSeparator)) {
            String name = new File(entry).getName();
            if (!name.startsWith("micrometer-core") && !name.startsWith("micrometer-registry")) {
                urls.add(new File(entry).toURI().toURL());
            }
        }
        return urls.toArray(new URL[0]);
    }
}
