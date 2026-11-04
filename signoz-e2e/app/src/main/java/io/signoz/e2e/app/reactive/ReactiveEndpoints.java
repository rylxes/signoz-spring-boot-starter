package io.signoz.e2e.app.reactive;

import io.signoz.e2e.app.common.Observations;
import io.signoz.springboot.annotation.SigNozLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/** The reactive demo service's API: the same contract as the servlet one, where it applies. */
@SigNozLog
@RestController
@RequestMapping("/e2e")
public class ReactiveEndpoints {

    private final String name;
    private final String peerUrl;
    private final WebClient webClient;

    public ReactiveEndpoints(@Value("${e2e.name}") String name, @Value("${e2e.peer-url}") String peerUrl,
                             WebClient.Builder webClientBuilder) {
        this.name = name;
        this.peerUrl = peerUrl;
        this.webClient = webClientBuilder.build();
    }

    @GetMapping("/ping")
    public Mono<String> ping() {
        return Mono.just("pong");
    }

    @GetMapping("/mdc")
    public Mono<Map<String, String>> mdc() {
        return Mono.fromCallable(() -> withService(Observations.mdc()));
    }

    @GetMapping(value = "/hello", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> hello(@RequestParam String name) {
        log.info("hello {}", name);
        return Mono.just("hello " + name);
    }

    @PostMapping(value = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> echo(@RequestBody String body) {
        log.info("echo received {} bytes", body.length());
        return Mono.just(body);
    }

    @GetMapping("/leaf")
    public Mono<Map<String, String>> leaf() {
        log.info("leaf reached on {}", name);
        return Mono.fromCallable(() -> withService(Observations.mdc()));
    }

    /**
     * Calls the peer, then continues on another thread and logs there: on Spring Boot 3 the MDC
     * must have followed the request across that hop.
     */
    @GetMapping("/chain/webclient")
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> chainWebClient() {
        final Map<String, String> before = withService(Observations.mdc());
        return webClient.get().uri(peerUrl + "/e2e/leaf?token=chain-s3cret").retrieve().bodyToMono(Map.class)
                .publishOn(Schedulers.boundedElastic())
                .map(peer -> {
                    log.info("after the hop on {}", Thread.currentThread().getName());
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("self", before);
                    out.put("afterHop", withService(Observations.mdc()));
                    out.put("peer", peer);
                    return out;
                });
    }

    @GetMapping("/secure/me")
    public Mono<Map<String, String>> me(Mono<Principal> principal) {
        return principal.map(p -> {
            log.info("secure endpoint for {}", p.getName());
            Map<String, String> out = withService(Observations.mdc());
            out.put("principal", p.getName());
            return out;
        });
    }

    private Map<String, String> withService(Map<String, String> mdc) {
        Map<String, String> out = new LinkedHashMap<String, String>(mdc);
        out.put("service", name);
        return out;
    }
}
