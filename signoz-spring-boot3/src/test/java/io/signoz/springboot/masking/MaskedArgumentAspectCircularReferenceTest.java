package io.signoz.springboot.masking;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.autoconfigure.SigNozAutoConfiguration;
import io.signoz.springboot.properties.SigNozProperties;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Before;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for GitHub issue #1: "there is an unresolvable circular reference"
 * ({@code BeanCurrentlyInCreationException} on {@code maskedArgumentAspect}).
 *
 * <p>The original {@link MaskedArgumentAspect} declared an {@code @Around} advice on
 * every public method of every {@code @Component}. Spring instantiates an aspect lazily,
 * on the first advised call. If that first call happened while the aspect itself was
 * still being created (for example, a {@code @Component} invoked while building the
 * aspect's {@link MaskingRegistry} dependency), the advice asked the bean factory for
 * the aspect instance that was currently in creation, and startup failed.
 *
 * <p>The aspect now declares no advice at all (masking moved to the log/audit
 * serialization boundary), so no advised call can re-enter its creation.
 */
class MaskedArgumentAspectCircularReferenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class, SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    /** A plain user {@code @Component}; the original aspect proxied every one of these. */
    @Component
    static class MaskedFieldCatalog {
        public List<String> extraMaskedFields() {
            return Arrays.asList("nationalId", "taxNumber");
        }
    }

    /**
     * The "custom masking registry" override documented in docs/CUSTOMIZING.md, reading its field
     * list from a {@code @Component}. Building this bean is part of creating
     * {@code maskedArgumentAspect}, so the call below runs while the aspect is in creation.
     */
    static class UserMaskingConfig {
        @Bean
        public MaskedFieldCatalog maskedFieldCatalog() {
            return new MaskedFieldCatalog();
        }

        @Bean
        public MaskingRegistry maskingRegistry(SigNozProperties props, MaskedFieldCatalog catalog) {
            MaskingRegistry registry = new MaskingRegistry(props.getLogging());
            for (String field : catalog.extraMaskedFields()) {
                registry.register(field, new FullMaskingStrategy());
            }
            return registry;
        }
    }

    @Test
    void contextStartsWithDefaultConfigAndAopEnabled() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MaskedArgumentAspect.class);
        });
    }

    @Test
    void componentCalledWhileAspectIsInCreationDoesNotCauseCircularReference() {
        runner.withUserConfiguration(UserMaskingConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MaskedArgumentAspect.class);
            MaskingRegistry registry = ctx.getBean(MaskingRegistry.class);
            assertThat(registry.isSensitiveField("nationalId")).isTrue();
            assertThat(registry.mask("nationalId", "A1234567")).isNotEqualTo("A1234567");
        });
    }

    @Test
    void aspectDeclaresNoAdvice() {
        for (Method method : MaskedArgumentAspect.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Around.class)
                    || method.isAnnotationPresent(Before.class)
                    || method.isAnnotationPresent(After.class)
                    || method.isAnnotationPresent(AfterReturning.class)
                    || method.isAnnotationPresent(AfterThrowing.class))
                    .as("MaskedArgumentAspect must not advise methods (%s)", method.getName())
                    .isFalse();
        }
    }
}
