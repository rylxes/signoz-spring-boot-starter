package io.signoz.springboot.outbound;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Outbound RestTemplate and WebClient calls log their URL on the {@code SIGNOZ_OUTBOUND} logger.
 * That line used to carry the raw query string, so {@code ?access_token=abc} reached plain-text
 * appenders in clear. These tests pin the masking of the URL in the log line.
 */
class OutboundUrlMaskingTest {

    /** Value planted in a sensitive query parameter; it must never appear in the log. */
    private static final String CANARY = "s3cret";

    /** Value planted as the password in the URL's user info. */
    private static final String USERINFO_CANARY = "u-s3cret";

    private static final URI SENSITIVE_URI =
            URI.create("https://api.example.com/v1/items?page=2&access_token=" + CANARY);

    private final MaskingRegistry registry = new MaskingRegistry(new SigNozLoggingProperties());

    private Logger outboundLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureOutboundLog() {
        outboundLogger = (Logger) LoggerFactory.getLogger("SIGNOZ_OUTBOUND");
        appender = new ListAppender<ILoggingEvent>();
        appender.start();
        outboundLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        outboundLogger.detachAppender(appender);
        appender.stop();
    }

    // --- OutboundUrl ---

    @Test
    void masksSensitiveQueryParametersAndKeepsTheRest() {
        assertThat(OutboundUrl.forLog(SENSITIVE_URI, registry))
                .isEqualTo("https://api.example.com/v1/items?page=2&access_token=***");
    }

    @Test
    void replacesUserInfoAndDropsTheFragment() {
        URI uri = URI.create("https://svc:" + USERINFO_CANARY + "@api.example.com:8443/p?q=1#frag");

        assertThat(OutboundUrl.forLog(uri, registry)).isEqualTo("https://***@api.example.com:8443/p?q=1");
    }

    @Test
    void keepsHostNamesTheUriGrammarRejects() {
        // getHost() is null for "order_service", so the authority must come from the raw form.
        URI uri = URI.create("http://order_service:8080/orders?token=" + CANARY);

        assertThat(OutboundUrl.forLog(uri, registry)).isEqualTo("http://order_service:8080/orders?token=***");
    }

    @Test
    void leavesUrlsWithNothingSensitiveUnchanged() {
        for (String url : new String[]{"http://downstream/api", "http://[::1]:9000/a/b?x=1&y=two",
                "/relative/path?page=3", "mailto:ops@example.com"}) {
            assertThat(OutboundUrl.forLog(URI.create(url), registry)).isEqualTo(url);
        }
        assertThat(OutboundUrl.forLog(null, registry)).isNull();
    }

    @Test
    void withoutARegistryTheQueryIsKeptButUserInfoIsStillRemoved() {
        URI uri = URI.create("https://svc:" + USERINFO_CANARY + "@h/p?token=t");

        assertThat(OutboundUrl.forLog(uri, null)).isEqualTo("https://***@h/p?token=t");
    }

    @Test
    void replacesTheRawUrlInsideAnExceptionMessage() {
        String masked = OutboundUrl.forLog(SENSITIVE_URI, registry);
        String message = "I/O error on GET request for \"" + SENSITIVE_URI + "\": Connection refused";

        assertThat(OutboundUrl.maskMessage(message, SENSITIVE_URI, masked))
                .doesNotContain(CANARY)
                .contains(masked)
                .endsWith("Connection refused");
        assertThat(OutboundUrl.maskMessage(null, SENSITIVE_URI, masked)).isNull();
    }

    // --- RestTemplate ---

    @Test
    void restTemplateLogLineMasksTheQuery() throws IOException {
        HttpRequest request = restTemplateRequest();
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getRawStatusCode()).thenReturn(200);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any(byte[].class))).thenReturn(response);

        new TracingRestTemplateInterceptor(new SigNozOutboundProperties(), registry)
                .intercept(request, new byte[0], execution);

        assertThat(singleLogLine())
                .contains("Outbound GET https://api.example.com/v1/items?page=2&access_token=*** -> 200")
                .doesNotContain(CANARY);
    }

    @Test
    void restTemplateFailureLineMasksTheQueryInTheExceptionMessageToo() throws IOException {
        HttpRequest request = restTemplateRequest();
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any(byte[].class)))
                .thenThrow(new IOException("connect failed for " + SENSITIVE_URI));

        assertThatThrownBy(() -> new TracingRestTemplateInterceptor(new SigNozOutboundProperties(), registry)
                .intercept(request, new byte[0], execution)).isInstanceOf(IOException.class);

        assertThat(singleLogLine())
                .contains("access_token=***")
                .contains("FAILED")
                .doesNotContain(CANARY);
    }

    // --- WebClient ---

    @Test
    void webClientLogLineMasksTheQuery() {
        ClientRequest request = ClientRequest.create(HttpMethod.GET, SENSITIVE_URI).build();

        new TracingWebClientFilter(new SigNozOutboundProperties(), registry)
                .filter(request, r -> Mono.just(ClientResponse.create(HttpStatus.OK).build()))
                .block();

        assertThat(singleLogLine())
                .contains("Outbound GET https://api.example.com/v1/items?page=2&access_token=*** -> 200")
                .doesNotContain(CANARY);
    }

    @Test
    void webClientFailureLineMasksTheQueryInTheExceptionMessageToo() {
        ClientRequest request = ClientRequest.create(HttpMethod.GET, SENSITIVE_URI).build();
        IllegalStateException failure = new IllegalStateException("connect failed for " + SENSITIVE_URI);

        assertThatThrownBy(() -> new TracingWebClientFilter(new SigNozOutboundProperties(), registry)
                .filter(request, r -> Mono.<ClientResponse>error(failure))
                .block()).isSameAs(failure);

        assertThat(singleLogLine())
                .contains("access_token=***")
                .contains("FAILED")
                .doesNotContain(CANARY);
    }

    // --- Clients built without a MaskingRegistry ---

    @Test
    void restTemplateWithoutARegistryMasksWithTheDefaultRules() throws IOException {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getRawStatusCode()).thenReturn(200);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any(byte[].class))).thenReturn(response);

        new TracingRestTemplateInterceptor(new SigNozOutboundProperties())
                .intercept(restTemplateRequest(), new byte[0], execution);

        assertThat(singleLogLine())
                .contains("Outbound GET https://api.example.com/v1/items?page=2&access_token=*** -> 200")
                .doesNotContain(CANARY);
    }

    @Test
    void webClientWithoutARegistryMasksWithTheDefaultRules() {
        ClientRequest request = ClientRequest.create(HttpMethod.GET, SENSITIVE_URI).build();

        new TracingWebClientFilter(new SigNozOutboundProperties(), null)
                .filter(request, r -> Mono.just(ClientResponse.create(HttpStatus.OK).build()))
                .block();

        assertThat(singleLogLine())
                .contains("Outbound GET https://api.example.com/v1/items?page=2&access_token=*** -> 200")
                .doesNotContain(CANARY);
    }

    @Test
    void anExplicitRegistryStillWinsOverTheDefaultRules() {
        SigNozLoggingProperties logging = new SigNozLoggingProperties();
        logging.setMaskedFields(java.util.Collections.singletonList("page"));
        ClientRequest request = ClientRequest.create(HttpMethod.GET, SENSITIVE_URI).build();

        new TracingWebClientFilter(new SigNozOutboundProperties(), new MaskingRegistry(logging))
                .filter(request, r -> Mono.just(ClientResponse.create(HttpStatus.OK).build()))
                .block();

        assertThat(singleLogLine()).contains("?page=***&");
    }

    private static HttpRequest restTemplateRequest() {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getURI()).thenReturn(SENSITIVE_URI);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        return request;
    }

    private String singleLogLine() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }
}
