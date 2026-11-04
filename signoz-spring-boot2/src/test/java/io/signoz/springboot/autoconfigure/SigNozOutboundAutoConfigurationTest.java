package io.signoz.springboot.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.outbound.TracingRestTemplateInterceptor;
import io.signoz.springboot.outbound.TracingWebClientFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbound clients only mask the query strings they log if the auto-configuration hands them
 * the application's {@link MaskingRegistry}; a client built without one logs the query as is.
 *
 * <p>The WebClient tests make a real call through a customized {@link WebClient} against a
 * Spring-built {@link ClientResponse}, so they fail if the filter calls a method that this Spring
 * version no longer has (as {@code ClientResponse.rawStatusCode()}, removed in Spring 6.1, did on Spring Boot 3).
 */
class SigNozOutboundAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    private ListAppender<ILoggingEvent> appender;
    private Logger outboundLogger;

    @BeforeEach
    void attachAppender() {
        outboundLogger = (Logger) LoggerFactory.getLogger("SIGNOZ_OUTBOUND");
        appender = new ListAppender<ILoggingEvent>();
        appender.start();
        outboundLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        outboundLogger.detachAppender(appender);
    }

    @Test
    void restTemplateInterceptorReceivesTheMaskingRegistry() {
        runner.run(ctx -> {
            RestTemplate restTemplate = new RestTemplate();
            ctx.getBean("sigNozRestTemplateCustomizer", RestTemplateCustomizer.class).customize(restTemplate);

            TracingRestTemplateInterceptor interceptor = restTemplate.getInterceptors().stream()
                    .filter(TracingRestTemplateInterceptor.class::isInstance)
                    .map(TracingRestTemplateInterceptor.class::cast)
                    .findFirst().orElseThrow(AssertionError::new);
            assertThat(ReflectionTestUtils.getField(interceptor, "maskingRegistry"))
                    .isSameAs(ctx.getBean(MaskingRegistry.class));
        });
    }

    @Test
    void webClientFilterReceivesTheMaskingRegistry() {
        runner.run(ctx -> {
            WebClient.Builder builder = WebClient.builder();
            ((WebClientCustomizer) ctx.getBean("sigNozWebClientCustomizer")).customize(builder);

            List<ExchangeFilterFunction> filters = new ArrayList<ExchangeFilterFunction>();
            builder.filters(filters::addAll);
            TracingWebClientFilter filter = filters.stream()
                    .filter(TracingWebClientFilter.class::isInstance)
                    .map(TracingWebClientFilter.class::cast)
                    .findFirst().orElseThrow(AssertionError::new);
            assertThat(ReflectionTestUtils.getField(filter, "maskingRegistry"))
                    .isSameAs(ctx.getBean(MaskingRegistry.class));
        });
    }

    @Test
    void webClientCallLogsTheResponseStatus() {
        runner.run(ctx -> {
            WebClient client = customizedWebClient(ctx.getBean("sigNozWebClientCustomizer", WebClientCustomizer.class),
                    ClientResponse.create(HttpStatus.CREATED).build());

            client.get().uri("https://api.example.com/orders").retrieve().toBodilessEntity().block();

            assertThat(lastLine()).matches(
                    "\\[SigNoz] Outbound GET https://api\\.example\\.com/orders -> 201 in \\d+ms");
        });
    }

    /**
     * A {@link WebClient} customized by {@code customizer} whose exchanges all return
     * {@code response} without touching the network.
     */
    private static WebClient customizedWebClient(WebClientCustomizer customizer, ClientResponse response) {
        // Spring 5's build() looks up a connector even when an exchange function is set, and
        // this module has no HTTP client library on its test classpath, so give it one it won't use.
        WebClient.Builder builder = WebClient.builder()
                .clientConnector((method, uri, requestCallback) ->
                        Mono.error(new IllegalStateException("the exchange function handles every call")))
                .exchangeFunction(request -> Mono.just(response));
        customizer.customize(builder);
        return builder.build();
    }

    private String lastLine() {
        assertThat(appender.list).isNotEmpty();
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }
}
