package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.masking.MaskedArgumentAspect;
import io.signoz.springboot.web.HttpLoggingFilter;
import io.signoz.springboot.web.TraceIdMdcFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SigNozWebAutoConfiguration} using {@link WebApplicationContextRunner}.
 *
 * <p>A {@link WebApplicationContextRunner} is required because
 * {@link SigNozWebAutoConfiguration} is guarded by
 * {@code @ConditionalOnWebApplication(type = SERVLET)}.
 *
 * <p>A no-op {@link OpenTelemetry} bean is provided and tracing is disabled so
 * the OTel SDK is never initialised. The full {@link SigNozAutoConfiguration}
 * chain is used so that the {@link io.signoz.springboot.masking.MaskingRegistry}
 * and {@link io.signoz.springboot.properties.SigNozProperties} prerequisites are
 * satisfied automatically.
 */
class SigNozWebAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    @Test
    void traceIdMdcFilterRegistrationBeanPresent() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("traceIdMdcFilter");
            FilterRegistrationBean<?> reg =
                    (FilterRegistrationBean<?>) ctx.getBean("traceIdMdcFilter");
            assertThat(reg.getFilter()).isInstanceOf(TraceIdMdcFilter.class);
        });
    }

    @Test
    void httpLoggingFilterRegistrationBeanPresent() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("httpLoggingFilter");
            FilterRegistrationBean<?> reg =
                    (FilterRegistrationBean<?>) ctx.getBean("httpLoggingFilter");
            assertThat(reg.getFilter()).isInstanceOf(HttpLoggingFilter.class);
        });
    }

    @Test
    void maskedArgumentAspectBeanPresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MaskedArgumentAspect.class));
    }
}
