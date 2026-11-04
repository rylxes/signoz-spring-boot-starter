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
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TracingRestTemplateInterceptorTest {

    /** Sensitive query parameters and a URL password; none of these values may reach the log. */
    private static final String SENSITIVE_URL = "https://user:u-canary@api.example.com:8443/x"
            + "?tenant=acme&token=q-canary&access%5Ftoken=a-canary,tail-canary&api_key=k-canary";

    private SigNozOutboundProperties props;
    private ClientHttpRequestExecution execution;
    private ClientHttpResponse mockResponse;
    private org.springframework.http.HttpRequest mockRequest;
    private ListAppender<ILoggingEvent> appender;
    private Logger outboundLogger;

    @BeforeEach
    void setUp() throws IOException {
        props = new SigNozOutboundProperties();

        execution = mock(ClientHttpRequestExecution.class);
        mockResponse = mock(ClientHttpResponse.class);
        mockRequest = mock(org.springframework.http.HttpRequest.class);

        when(mockRequest.getMethod()).thenReturn(HttpMethod.GET);
        when(mockRequest.getURI()).thenReturn(URI.create("http://example.com/api/test"));
        when(mockRequest.getHeaders()).thenReturn(new HttpHeaders());
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.OK);
        when(mockResponse.getRawStatusCode()).thenReturn(200);
        when(execution.execute(any(), any(byte[].class))).thenReturn(mockResponse);

        outboundLogger = (Logger) LoggerFactory.getLogger("SIGNOZ_OUTBOUND");
        appender = new ListAppender<ILoggingEvent>();
        appender.start();
        outboundLogger.addAppender(appender);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
        outboundLogger.detachAppender(appender);
    }

    @Test
    void masksSensitiveQueryParametersAndKeepsOthers() throws IOException {
        when(mockRequest.getURI()).thenReturn(URI.create(SENSITIVE_URL));

        new TracingRestTemplateInterceptor(props, new MaskingRegistry(new SigNozLoggingProperties()))
                .intercept(mockRequest, new byte[0], execution);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("Outbound GET https://api.example.com:8443/x?tenant=acme&token=***&")
                .contains("access%5Ftoken=***")
                .contains(" -> 200 in ")
                .doesNotContain("q-canary", "a-canary", "tail-canary", "k-canary", "u-canary");
    }

    @Test
    void masksSensitiveQueryParametersWhenTheRequestFails() throws IOException {
        when(mockRequest.getURI()).thenReturn(URI.create(SENSITIVE_URL));
        when(execution.execute(any(), any(byte[].class))).thenThrow(new IOException("refused"));

        TracingRestTemplateInterceptor interceptor =
                new TracingRestTemplateInterceptor(props, new MaskingRegistry(new SigNozLoggingProperties()));
        assertThatThrownBy(() -> interceptor.intercept(mockRequest, new byte[0], execution))
                .isInstanceOf(IOException.class);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("tenant=acme&token=***&")
                .contains("FAILED")
                .doesNotContain("q-canary", "a-canary", "tail-canary", "k-canary", "u-canary");
    }

    @Test
    void usesTheInjectedMaskingRules() throws IOException {
        SigNozLoggingProperties logging = new SigNozLoggingProperties();
        logging.setMaskedFields(Collections.singletonList("tenant"));
        when(mockRequest.getURI()).thenReturn(URI.create("https://api.example.com/x?tenant=acme&page=2"));

        new TracingRestTemplateInterceptor(props, new MaskingRegistry(logging))
                .intercept(mockRequest, new byte[0], execution);

        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("https://api.example.com/x?tenant=***&page=2")
                .doesNotContain("acme");
    }

    @Test
    void masksWithTheDefaultRulesWhenNoRegistryIsGiven() throws IOException {
        when(mockRequest.getURI()).thenReturn(URI.create(SENSITIVE_URL));

        new TracingRestTemplateInterceptor(props).intercept(mockRequest, new byte[0], execution);

        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("tenant=acme&token=***&")
                .doesNotContain("q-canary", "a-canary", "tail-canary", "k-canary", "u-canary");
    }

    @Test
    void propagatesTraceparentFromHttpFilterMdcKeys() throws IOException {
        // TraceIdMdcFilter writes OTel-native snake_case keys when no agent is present.
        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");
        MDC.put("trace_flags", "01");
        MDC.put("requestId", "req-123");

        HttpHeaders headers = new HttpHeaders();
        when(mockRequest.getHeaders()).thenReturn(headers);

        new TracingRestTemplateInterceptor(props).intercept(mockRequest, new byte[0], execution);

        assertThat(headers.getFirst("traceparent"))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(headers.getFirst("X-Request-ID")).isEqualTo("req-123");
    }

    @Test
    void interceptExecutesRequest() throws IOException {
        TracingRestTemplateInterceptor interceptor = new TracingRestTemplateInterceptor(props);
        ClientHttpResponse response = interceptor.intercept(mockRequest, new byte[0], execution);

        assertThat(response).isNotNull();
        verify(execution).execute(eq(mockRequest), any(byte[].class));
    }

    @Test
    void interceptWorksWhenLoggingDisabled() throws IOException {
        props.setLogRequests(false);
        TracingRestTemplateInterceptor interceptor = new TracingRestTemplateInterceptor(props);
        ClientHttpResponse response = interceptor.intercept(mockRequest, new byte[0], execution);

        assertThat(response).isSameAs(mockResponse);
    }

    @Test
    void interceptWorksWhenHeaderPropagationDisabled() throws IOException {
        props.setPropagateHeaders(false);
        TracingRestTemplateInterceptor interceptor = new TracingRestTemplateInterceptor(props);
        ClientHttpResponse response = interceptor.intercept(mockRequest, new byte[0], execution);

        assertThat(response).isSameAs(mockResponse);
    }
}
