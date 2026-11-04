package io.signoz.springboot.webflux;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Runs last in the {@code WebFilter} chain and puts the request's SigNoz MDC entries
 * (from the Reactor context) back on the thread before the handler is invoked.
 *
 * <p>{@link TraceIdWebFilter} sets the MDC while the chain is subscribed, but filters
 * between it and the handler may continue on another thread. Spring Security's
 * WebFlux chain does: it loads the web session on {@code boundedElastic}, so without
 * this filter a handler behind Spring Security logs without its IDs on Reactor 3.4
 * (Spring Boot 2), and on Spring Boot 3 with context propagation turned off.
 */
public class MdcRestoringWebFilter implements WebFilter, Ordered {

    public static final int ORDER = Ordered.LOWEST_PRECEDENCE;

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        return Mono.deferContextual(ctx -> {
            Map<String, String> entries = ReactiveMdc.fromContext(ctx);
            if (entries.isEmpty()) {
                return chain.filter(exchange);
            }
            return ReactiveMdc.scoped(() -> chain.filter(exchange), entries);
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
