package io.signoz.e2e.app.servlet;

import io.signoz.e2e.app.common.DbProbe;
import io.signoz.e2e.app.common.FeatureService;
import io.signoz.e2e.app.common.GrpcEcho;
import io.signoz.e2e.app.common.KafkaFlow;
import io.signoz.e2e.app.common.Observations;
import io.signoz.e2e.app.common.SecretsLogger;
import io.signoz.e2e.app.common.SqsOutbound;
import io.signoz.springboot.annotation.SigNozLog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The demo service's HTTP API. Responses are plain business data plus, where a test needs it,
 * the MDC the handler saw, so the tests can check both "works as specified" and "the app's own
 * behaviour is unchanged".
 */
@SigNozLog
@RestController
@RequestMapping("/e2e")
public class ServletEndpoints {

    private final String name;
    private final String peerUrl;
    private final RestTemplate restTemplate;
    private final WebClient webClient;
    private final FeatureService features;
    private final SecretsLogger secrets;
    private final DbProbe db;
    private final GrpcEcho grpc;
    private final StompClient stomp;
    private final Observations observations;
    private final ObjectProvider<KafkaFlow> kafka;
    private final ObjectProvider<SqsOutbound> sqs;

    public ServletEndpoints(@Value("${e2e.name}") String name,
                            @Value("${e2e.peer-url}") String peerUrl,
                            RestTemplateBuilder restTemplateBuilder,
                            WebClient.Builder webClientBuilder,
                            FeatureService features, SecretsLogger secrets, DbProbe db, GrpcEcho grpc,
                            StompClient stomp, Observations observations,
                            ObjectProvider<KafkaFlow> kafka, ObjectProvider<SqsOutbound> sqs) {
        this.name = name;
        this.peerUrl = peerUrl;
        // Built from Spring Boot's builders, as the tracing guide says, so they are traced.
        this.restTemplate = restTemplateBuilder.build();
        this.webClient = webClientBuilder.build();
        this.features = features;
        this.secrets = secrets;
        this.db = db;
        this.grpc = grpc;
        this.stomp = stomp;
        this.observations = observations;
        this.kafka = kafka;
        this.sqs = sqs;
    }

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    /** The MDC the handler sees. */
    @GetMapping("/mdc")
    public Map<String, String> mdc() {
        return withService(Observations.mdc());
    }

    @GetMapping(value = "/hello", produces = MediaType.TEXT_PLAIN_VALUE)
    public String hello(@RequestParam String name) {
        log.info("hello {}", name);
        return "hello " + name;
    }

    /** Returns the request body unchanged: the starter must not alter what the app sees or sends. */
    @PostMapping(value = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String echo(@RequestBody String body) {
        log.info("echo received {} bytes", body.length());
        return body;
    }

    /** The far end of a chain: reports its MDC. */
    @GetMapping("/leaf")
    public Map<String, String> leaf() {
        log.info("leaf reached on {}", name);
        return withService(Observations.mdc());
    }

    /** A → peer over RestTemplate, with a sensitive query parameter on the URL. */
    @GetMapping("/chain/rest")
    @SuppressWarnings("unchecked")
    public Map<String, Object> chainRest() {
        Map<String, String> peer = restTemplate.getForObject(peerUrl + "/e2e/leaf?token=chain-s3cret&tenant=acme", Map.class);
        return chain(peer);
    }

    /** A → peer over WebClient. */
    @GetMapping("/chain/webclient")
    @SuppressWarnings("unchecked")
    public Map<String, Object> chainWebClient() {
        Map<String, String> peer = webClient.get().uri(peerUrl + "/e2e/leaf?token=chain-s3cret").retrieve()
                .bodyToMono(Map.class).block();
        return chain(peer);
    }

    /** A → peer → A: the peer runs its own /chain/rest, which calls back here. */
    @GetMapping("/chain/roundtrip")
    @SuppressWarnings("unchecked")
    public Map<String, Object> roundtrip() {
        Map<String, Object> peer = restTemplate.getForObject(peerUrl + "/e2e/chain/rest", Map.class);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("self", withService(Observations.mdc()));
        out.put("peer", peer);
        return out;
    }

    @PostMapping("/kafka")
    public Map<String, String> kafka(@RequestParam String payload) throws Exception {
        kafka.getObject().send(payload);
        return withService(Observations.mdc());
    }

    @PostMapping("/sqs")
    public Map<String, String> sqs(@RequestParam String payload) {
        sqs.getObject().send(payload);
        return withService(Observations.mdc());
    }

    @PostMapping("/grpc")
    public Map<String, String> grpc(@RequestParam String payload) {
        Map<String, String> out = withService(Observations.mdc());
        out.put("peerTraceId", grpc.callPeer(payload));
        return out;
    }

    @PostMapping("/stomp")
    public Map<String, String> stomp(@RequestParam String payload,
                                     @RequestParam(defaultValue = "false") boolean onFrame) throws Exception {
        stomp.send(payload, onFrame);
        return withService(Observations.mdc());
    }

    @GetMapping("/async")
    public Map<String, String> async(@RequestParam String label) {
        features.async(label);
        return withService(Observations.mdc());
    }

    @GetMapping("/traced")
    public Map<String, String> traced() {
        Map<String, String> out = withService(Observations.mdc());
        out.put("result", features.traced());
        return out;
    }

    @PostMapping(value = "/login", produces = MediaType.TEXT_PLAIN_VALUE)
    public String login(@RequestParam String user, @RequestParam String password) {
        return features.login(user, password);
    }

    @GetMapping(value = "/timed", produces = MediaType.TEXT_PLAIN_VALUE)
    public String timed(@RequestParam long ms) throws InterruptedException {
        return features.timed(ms);
    }

    /** Calls a method that throws, then logs the failure at ERROR (error fingerprinting). */
    @GetMapping(value = "/fail", produces = MediaType.TEXT_PLAIN_VALUE)
    public String fail() {
        try {
            features.fail();
            return "no failure";
        } catch (IllegalStateException e) {
            secrets.logFailure(e);
            return "handled";
        }
    }

    @GetMapping(value = "/db/slow", produces = MediaType.TEXT_PLAIN_VALUE)
    public String dbSlow(@RequestParam(defaultValue = "800") long ms) {
        db.slow(ms);
        return "slow done";
    }

    @GetMapping(value = "/db/fast", produces = MediaType.TEXT_PLAIN_VALUE)
    public String dbFast() {
        return "fast " + db.fast();
    }

    @GetMapping("/secure/me")
    public Map<String, String> me(Principal principal) {
        log.info("secure endpoint for {}", principal.getName());
        Map<String, String> out = withService(Observations.mdc());
        out.put("principal", principal.getName());
        return out;
    }

    @GetMapping(value = "/secrets", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logSecrets(@RequestParam("secret") String canary) {
        secrets.logSecrets(canary);
        return "logged";
    }

    @GetMapping(value = "/levels", produces = MediaType.TEXT_PLAIN_VALUE)
    public String levels(@RequestParam String marker, @RequestParam(defaultValue = "50") int infos) {
        secrets.logLevels(marker, infos);
        return "logged";
    }

    @GetMapping("/last/{channel}")
    public Map<String, String> last(@PathVariable String channel) {
        return observations.last(channel);
    }

    private Map<String, Object> chain(Map<String, String> peer) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("self", withService(Observations.mdc()));
        out.put("peer", peer);
        return out;
    }

    private Map<String, String> withService(Map<String, String> mdc) {
        Map<String, String> out = new LinkedHashMap<String, String>(mdc);
        out.put("service", name);
        return out;
    }
}
