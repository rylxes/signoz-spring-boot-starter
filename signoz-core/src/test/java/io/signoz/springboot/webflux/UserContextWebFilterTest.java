package io.signoz.springboot.webflux;

import io.signoz.springboot.properties.SigNozUserContextProperties;
import io.signoz.springboot.usercontext.UserContextEnricher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextWebFilterTest {

    private final UserContextWebFilter filter =
            new UserContextWebFilter(new UserContextEnricher(new SigNozUserContextProperties()));

    @Test
    void addsTheAuthenticatedUserAndKeepsTheTraceEntries() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "alice", "n/a", Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
        final AtomicReference<Map<String, String>> seen = new AtomicReference<Map<String, String>>();
        WebFilterChain handler = ex -> Mono.deferContextual(ctx -> {
            seen.set(ReactiveMdc.fromContext(ctx));
            return Mono.<Void>empty();
        });

        filter.filter(exchange(), handler)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .contextWrite(ctx -> ReactiveMdc.withEntries(ctx, Collections.singletonMap("requestId", "r-1")))
                .block();

        assertThat(seen.get())
                .containsEntry("userId", "alice")
                .containsEntry("userRoles", "ROLE_USER,ROLE_ADMIN")
                .containsEntry("requestId", "r-1");
    }

    @Test
    void passesThroughWithoutASecurityContext() {
        final AtomicReference<Map<String, String>> seen = new AtomicReference<Map<String, String>>();
        WebFilterChain handler = ex -> Mono.deferContextual(ctx -> {
            seen.set(ReactiveMdc.fromContext(ctx));
            return Mono.<Void>empty();
        });

        filter.filter(exchange(), handler).block();

        assertThat(seen.get()).doesNotContainKey("userId");
    }

    @Test
    void ignoresUnauthenticatedTokens() {
        UsernamePasswordAuthenticationToken anonymous = new UsernamePasswordAuthenticationToken("bob", "n/a");
        final AtomicReference<Map<String, String>> seen = new AtomicReference<Map<String, String>>();
        WebFilterChain handler = ex -> Mono.deferContextual(ctx -> {
            seen.set(ReactiveMdc.fromContext(ctx));
            return Mono.<Void>empty();
        });

        filter.filter(exchange(), handler)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(anonymous))
                .block();

        assertThat(seen.get()).doesNotContainKey("userId");
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/me"));
    }
}
