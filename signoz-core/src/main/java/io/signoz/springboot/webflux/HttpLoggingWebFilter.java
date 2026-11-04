package io.signoz.springboot.webflux;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozWebProperties;
import io.signoz.springboot.tracing.TraceIdentity;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reactive counterpart of the servlet {@code HttpLoggingFilter}: logs one
 * {@code SIGNOZ_HTTP} line per request with method, path, query, status, duration,
 * and optionally masked headers and bounded, masked request/response bodies. The
 * line carries the request's {@code requestId} and trace IDs in the MDC.
 *
 * <p>Successful requests are logged when the chain completes. Failed requests are
 * logged when the error response is committed, so the status recorded is the one
 * the client actually received (e.g. 404 for a {@code ResponseStatusException}), not
 * a guess made before the error handler ran.
 *
 * <p>Multipart and streaming ({@code writeAndFlushWith}) bodies are never captured.
 */
public class HttpLoggingWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger("SIGNOZ_HTTP");

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    private final SigNozWebProperties webProps;
    private final MaskingRegistry maskingRegistry;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public HttpLoggingWebFilter(SigNozWebProperties webProps, MaskingRegistry maskingRegistry) {
        this.webProps = webProps;
        this.maskingRegistry = maskingRegistry;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, WebFilterChain chain) {
        final ServerHttpRequest request = exchange.getRequest();
        final String path = request.getPath().pathWithinApplication().value();
        if (!webProps.isLogRequests() || isExcluded(path)) {
            return chain.filter(exchange);
        }

        final long startNanos = System.nanoTime();
        final String method = methodName(request);

        final BodyCapture requestBody = webProps.isLogRequestBody()
                && isBodyMethod(method) && !isMultipart(request)
                ? new BodyCapture(webProps.getMaxBodyBytes()) : null;
        final BodyCapture responseBody = webProps.isLogResponseBody()
                ? new BodyCapture(webProps.getMaxBodyBytes()) : null;

        ServerWebExchange.Builder mutated = exchange.mutate();
        if (requestBody != null) {
            mutated.request(new ServerHttpRequestDecorator(request) {
                @Override
                public Flux<DataBuffer> getBody() {
                    return super.getBody().doOnNext(requestBody::append);
                }
            });
        }
        if (responseBody != null) {
            mutated.response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    return super.writeWith(Flux.from(body).doOnNext(responseBody::append));
                }
            });
        }

        final AtomicBoolean logged = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final Runnable logOnce = new Runnable() {
            @Override
            public void run() {
                if (logged.compareAndSet(false, true)) {
                    log(exchange, method, path, startNanos, requestBody, responseBody, failure.get());
                }
            }
        };

        // The error handler writes the error response to the original response after
        // this filter's chain has failed; log then, once the final status is known.
        exchange.getResponse().beforeCommit(() -> {
            if (failure.get() != null) {
                logOnce.run();
            }
            return Mono.empty();
        });

        return chain.filter(mutated.build())
                .doOnError(failure::set)
                .doFinally(signal -> {
                    if (signal != SignalType.ON_ERROR || exchange.getResponse().isCommitted()) {
                        logOnce.run();
                    }
                });
    }

    private void log(ServerWebExchange exchange, String method, String path, long startNanos,
                     BodyCapture requestBody, BodyCapture responseBody, Throwable failure) {
        final StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(method).append(' ').append(path);

        String query = exchange.getRequest().getURI().getRawQuery();
        if (webProps.isLogQueryString() && query != null) {
            sb.append('?').append(query);
        }

        ServerHttpResponse response = exchange.getResponse();
        Integer status = response.getRawStatusCode();
        sb.append(" status=").append(status != null ? status : (failure != null ? 500 : 200));
        sb.append(" duration=").append((System.nanoTime() - startNanos) / 1_000_000L).append("ms");

        if (failure != null) {
            sb.append(" error=").append(failure.getClass().getName());
        }
        if (webProps.isLogHeaders()) {
            sb.append(" headers=").append(maskedHeaders(exchange.getRequest().getHeaders()));
        }
        if (requestBody != null && requestBody.size() > 0) {
            sb.append(" requestBody=").append(maskingRegistry.maskJsonString(requestBody.asString()));
        }
        if (responseBody != null && responseBody.size() > 0) {
            sb.append(" responseBody=").append(maskingRegistry.maskJsonString(responseBody.asString()));
        }

        // This may run on whichever thread completed or committed the response, so
        // put the request's MDC entries in place explicitly for the log call.
        Object identity = exchange.getAttribute(TraceIdWebFilter.IDENTITY_ATTRIBUTE);
        Map<String, String> mdc = identity instanceof TraceIdentity
                ? ((TraceIdentity) identity).mdcEntries()
                : Collections.<String, String>emptyMap();
        ReactiveMdc.runWithMdc(mdc, () -> log.info(sb.toString()));
    }

    private Map<String, String> maskedHeaders(HttpHeaders headers) {
        Map<String, String> masked = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String value = e.getValue().isEmpty() ? "" : e.getValue().get(0);
            masked.put(e.getKey(), maskingRegistry.mask(e.getKey().toLowerCase(), value));
        }
        return masked;
    }

    private boolean isExcluded(String path) {
        for (String pattern : webProps.getExcludePaths()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static String methodName(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        return method != null ? method.name() : "UNKNOWN";
    }

    private static boolean isBodyMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private static boolean isMultipart(ServerHttpRequest request) {
        MediaType contentType = request.getHeaders().getContentType();
        return contentType != null && "multipart".equalsIgnoreCase(contentType.getType());
    }

    /** Copies at most {@code maxBytes} of a body as it streams past, without consuming it. */
    static final class BodyCapture {
        private final int maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        BodyCapture(int maxBytes) {
            this.maxBytes = Math.max(0, maxBytes);
        }

        synchronized void append(DataBuffer buffer) {
            int n = Math.min(buffer.readableByteCount(), maxBytes - bytes.size());
            int start = buffer.readPosition();
            for (int i = 0; i < n; i++) {
                bytes.write(buffer.getByte(start + i));
            }
        }

        synchronized int size() {
            return bytes.size();
        }

        synchronized String asString() {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
