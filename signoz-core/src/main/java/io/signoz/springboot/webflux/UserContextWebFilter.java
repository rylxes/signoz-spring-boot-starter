package io.signoz.springboot.webflux;

import io.signoz.springboot.usercontext.UserContextEnricher;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reactive counterpart of the servlet {@code UserContextMdcFilter}: adds
 * {@code userId}, {@code userEmail} and {@code userRoles} for the authenticated user
 * to the request's SigNoz MDC entries.
 *
 * <p>Spring Security's WebFlux filter chain ({@code WebFilterChainProxy}, order -100)
 * publishes the security context into the Reactor context, so this filter must run
 * after it; {@link #ORDER} is 0. Unauthenticated requests pass through untouched.
 */
public class UserContextWebFilter implements WebFilter, Ordered {

    public static final int ORDER = 0;

    private final UserContextEnricher enricher;

    public UserContextWebFilter(UserContextEnricher enricher) {
        this.enricher = enricher;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(securityContext -> Mono.justOrEmpty(securityContext.getAuthentication()))
                .map(enricher::extract)
                .defaultIfEmpty(Collections.<String, String>emptyMap())
                .flatMap(entries -> filterWith(exchange, chain, entries));
    }

    /**
     * Resolving the security context may complete on another thread, so re-apply all
     * of the request's entries (trace IDs from the context plus the user's), not just
     * the user keys, around the rest of the chain.
     */
    private static Mono<Void> filterWith(final ServerWebExchange exchange, final WebFilterChain chain,
                                         final Map<String, String> userEntries) {
        return Mono.deferContextual(ctx -> {
            Map<String, String> all = new LinkedHashMap<String, String>(ReactiveMdc.fromContext(ctx));
            all.putAll(userEntries);
            return ReactiveMdc.scoped(() -> chain.filter(exchange), all);
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
