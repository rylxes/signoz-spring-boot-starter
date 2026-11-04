package io.signoz.springboot.webflux;

import io.signoz.springboot.tracing.TraceContextCodec;
import io.signoz.springboot.tracing.TraceIdentity;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive counterpart of the servlet {@code TraceIdMdcFilter}.
 *
 * <p>For each request it resolves a {@link TraceIdentity} (active OTel span, inbound
 * {@code traceparent}, inbound {@code X-Request-ID}, or fresh IDs), then:
 *
 * <ul>
 *   <li>echoes {@code X-Request-ID} and {@code traceparent} on the response;</li>
 *   <li>stores the identity as the exchange attribute {@link #IDENTITY_ATTRIBUTE};</li>
 *   <li>stores the MDC entries ({@code requestId}, and the snake_case trace keys when
 *       no agent owns them) in the Reactor context under {@link ReactiveMdc#CONTEXT_KEY},
 *       where {@code TracingWebClientFilter} picks them up for outbound calls;</li>
 *   <li>puts the same entries on the thread while the rest of the chain is subscribed.</li>
 * </ul>
 */
public class TraceIdWebFilter implements WebFilter, Ordered {

    /** Exchange attribute holding the request's {@link TraceIdentity}. */
    public static final String IDENTITY_ATTRIBUTE = TraceIdWebFilter.class.getName() + ".identity";

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders requestHeaders = exchange.getRequest().getHeaders();
        TraceIdentity identity = TraceIdentity.resolve(
                requestHeaders.getFirst(TraceContextCodec.TRACEPARENT_HEADER),
                requestHeaders.getFirst(TraceIdentity.REQUEST_ID_HEADER));

        exchange.getAttributes().put(IDENTITY_ATTRIBUTE, identity);

        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        responseHeaders.set(TraceIdentity.REQUEST_ID_HEADER, identity.getRequestId());
        responseHeaders.set(TraceContextCodec.TRACEPARENT_HEADER, identity.toTraceparent());

        return ReactiveMdc.scoped(() -> chain.filter(exchange), identity.mdcEntries());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
