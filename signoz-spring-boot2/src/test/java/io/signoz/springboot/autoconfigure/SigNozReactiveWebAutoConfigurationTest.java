package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.web.UserContextMdcFilter;
import io.signoz.springboot.webflux.HttpLoggingWebFilter;
import io.signoz.springboot.webflux.MdcRestoringWebFilter;
import io.signoz.springboot.webflux.TraceIdWebFilter;
import io.signoz.springboot.webflux.UserContextWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SigNozReactiveWebAutoConfiguration} and the reactive half of
 * {@link SigNozUserContextAutoConfiguration}.
 */
class SigNozReactiveWebAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner reactive = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    @Test
    public void registersTheReactiveFilters() {
        reactive.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(TraceIdWebFilter.class);
            assertThat(ctx).hasSingleBean(HttpLoggingWebFilter.class);
            assertThat(ctx).hasSingleBean(MdcRestoringWebFilter.class);
            assertThat(ctx).hasSingleBean(UserContextWebFilter.class);
            assertThat(ctx).doesNotHaveBean(UserContextMdcFilter.class);
            assertThat(ctx).doesNotHaveBean("traceIdMdcFilter");
            assertThat(ctx).doesNotHaveBean("httpLoggingFilter");
        });
    }

    @Test
    public void skipsTheReactiveUserFilterWithoutSpringSecurity() {
        reactive.withClassLoader(new FilteredClassLoader("org.springframework.security"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(UserContextWebFilter.class);
                    assertThat(ctx).hasSingleBean(TraceIdWebFilter.class);
                });
    }

    @Test
    public void userDefinedFiltersWin() {
        reactive.withBean("myTraceFilter", TraceIdWebFilter.class, TraceIdWebFilter::new)
                .run(ctx -> assertThat(ctx).getBean(TraceIdWebFilter.class)
                        .isSameAs(ctx.getBean("myTraceFilter")));
    }

    @Test
    public void servletAppsGetNoReactiveFilters() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
                .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
                .withPropertyValues("signoz.tracing.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(TraceIdWebFilter.class);
                    assertThat(ctx).doesNotHaveBean(HttpLoggingWebFilter.class);
                    assertThat(ctx).doesNotHaveBean(MdcRestoringWebFilter.class);
                    assertThat(ctx).doesNotHaveBean(UserContextWebFilter.class);
                    assertThat(ctx).hasSingleBean(UserContextMdcFilter.class);
                });
    }
}
