package io.signoz.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.e2e.support.Await;
import io.signoz.e2e.support.Http;
import io.signoz.e2e.support.ServiceProcess;
import io.signoz.e2e.support.SqsServer;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every feature at once, on both services, over every channel: the features must work together
 * exactly as they do alone, and the application's own behaviour must be identical to running
 * without the starter.
 */
class CombinedFeaturesTest extends E2eTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE = "c0ffee00c0ffee00c0ffee00c0ffee00";
    private static final String INBOUND = "00-" + TRACE + "-feedfacefeedface-01";
    private static final Map<String, String> TRACED = headers("traceparent", INBOUND);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Http.Response r) throws Exception {
        assertThat(r.status).as(r.toString()).isEqualTo(200);
        return JSON.readValue(r.body, Map.class);
    }

    /**
     * A and B wired to each other over Kafka and SQS. {@code prefix} names the topics and queues
     * ({@code prefix-to-a}, {@code prefix-to-b}), which must already exist on the brokers.
     */
    private static Options[] channels(KafkaBroker kafka, SqsServer sqs, String prefix, Options base) {
        String toA = sqs.queueUrl(prefix + "-to-a");
        String toB = sqs.queueUrl(prefix + "-to-b");
        Options a = base.copy().with("e2e.kafka.enabled", "true").with("e2e.kafka.bootstrap", kafka.bootstrapServers())
                .with("e2e.kafka.in-topic", prefix + "-to-a").with("e2e.kafka.out-topic", prefix + "-to-b")
                .with("e2e.sqs.enabled", "true").with("e2e.sqs.endpoint", sqs.endpoint())
                .with("e2e.sqs.in-queue", prefix + "-to-a").with("e2e.sqs.out-queue-url", toB);
        Options b = base.copy().with("e2e.kafka.enabled", "true").with("e2e.kafka.bootstrap", kafka.bootstrapServers())
                .with("e2e.kafka.in-topic", prefix + "-to-b").with("e2e.kafka.out-topic", prefix + "-to-a")
                .with("e2e.sqs.enabled", "true").with("e2e.sqs.endpoint", sqs.endpoint())
                .with("e2e.sqs.in-queue", prefix + "-to-b").with("e2e.sqs.out-queue-url", toA);
        return new Options[]{a, b};
    }

    @Test
    void everyFeatureTogetherAcrossTwoServices() throws Exception {
        try (KafkaBroker kafka = KafkaBroker.start("all-to-a", "all-to-b"); SqsServer sqs = SqsServer.start()) {
            sqs.createQueue("all-to-a");
            sqs.createQueue("all-to-b");
            Options base = Options.servlet()
                    .with("signoz.logging.mode", "BOTH")
                    .with("signoz.web.log-headers", "true")
                    .with("signoz.timed.slow-threshold-ms", "200");
            Options[] ab = channels(kafka, sqs, "all", base);
            Pair pair = startPair(ab[0], ab[1]);
            ServiceProcess a = pair.a;
            ServiceProcess b = pair.b;
            String basic = "Basic " + Base64.getEncoder().encodeToString("alice:alice-pass".getBytes("UTF-8"));

            // HTTP, both directions, with secrets in every place a request can carry them.
            assertThat(Http.get(a.baseUrl() + "/e2e/hello?name=all&access_token=" + CANARY,
                    headers("traceparent", INBOUND, "Cookie", "SESSION=" + CANARY)).body).isEqualTo("hello all");
            String body = "{\"user\":\"all\",\"password\":\"" + CANARY + "\",\"cardPan\":\"5061051234566567\"}";
            assertThat(Http.post(a.baseUrl() + "/e2e/echo", "application/json", body, TRACED).body).isEqualTo(body);
            Map<String, Object> trip = json(Http.get(a.baseUrl() + "/e2e/chain/roundtrip", TRACED));
            @SuppressWarnings("unchecked")
            Map<String, Object> viaB = (Map<String, Object>) trip.get("peer");
            assertThat(String.valueOf(viaB)).contains("trace_id=" + TRACE);
            @SuppressWarnings("unchecked")
            Map<String, String> wc = (Map<String, String>) json(Http.get(a.baseUrl() + "/e2e/chain/webclient", TRACED)).get("peer");
            assertThat(wc).containsEntry("trace_id", TRACE);

            // Messaging and RPC.
            Http.post(a.baseUrl() + "/e2e/kafka?payload=all-k", "text/plain", "", TRACED);
            Http.post(a.baseUrl() + "/e2e/sqs?payload=all-q", "text/plain", "", TRACED);
            assertThat(json(Http.post(a.baseUrl() + "/e2e/grpc?payload=all-g", "text/plain", "", TRACED)).get("peerTraceId"))
                    .isEqualTo(TRACE);
            Http.post(a.baseUrl() + "/e2e/stomp?payload=all-s", "text/plain", "", TRACED);
            Http.get(a.baseUrl() + "/e2e/async?label=all-x", TRACED);
            for (String[] channel : new String[][]{{"kafka", "all-k"}, {"sqs", "all-q"}, {"stomp", "all-s"}}) {
                assertThat(observation(b, channel[0], channel[1])).as(channel[0]).containsEntry("trace_id", TRACE);
            }
            assertThat(observation(a, "async", "all-x")).containsEntry("trace_id", TRACE);

            // Annotations, security, database, errors, secrets.
            assertThat(Http.post(a.baseUrl() + "/e2e/login?user=bob&password=" + CANARY, "text/plain", "", TRACED).body)
                    .isEqualTo("ok:bob:" + CANARY.length());
            assertThat(Http.get(a.baseUrl() + "/e2e/timed?ms=300", TRACED).body).isEqualTo("slept 300");
            assertThat(Http.get(a.baseUrl() + "/e2e/fail", TRACED).body).isEqualTo("handled");
            assertThat(Http.get(a.baseUrl() + "/e2e/db/slow?ms=700", TRACED).body).isEqualTo("slow done");
            assertThat(json(Http.get(a.baseUrl() + "/e2e/secure/me", headers("Authorization", basic, "traceparent", INBOUND))))
                    .containsEntry("userId", "alice").containsEntry("trace_id", TRACE);
            assertThat(json(Http.get(a.baseUrl() + "/e2e/traced", TRACED))).containsEntry("result", "traced");
            Http.get(a.baseUrl() + "/e2e/secrets?secret=" + CANARY, TRACED);

            // What each feature should have produced.
            a.awaitJson(messageContains("with a secret in the MDC"), 10_000, "secrets logged");
            assertThat(a.awaitJson(messageContains("AUDIT action=E2E_LOGIN"), 10_000, "audit"))
                    .containsEntry("trace_id", TRACE);
            assertThat(a.awaitJson(messageContains("[SigNoz] SLOW method e2e.timed"), 10_000, "slow method")).isNotNull();
            assertThat(a.awaitJson(messageContains("[SigNoz] AlertOnFailure"), 10_000, "alert")).isNotNull();
            assertThat(str(a.awaitJson(messageContains("operation failed"), 10_000, "error"), "errorId")).isNotBlank();
            assertThat(a.awaitJson(messageContains("[SigNoz] SLOW QUERY"), 10_000, "slow query")).containsEntry("trace_id", TRACE);
            assertThat(a.awaitJson(messageContains("secure endpoint for alice"), 10_000, "user line")).containsEntry("userId", "alice");
            String access = a.awaitJson(messageContains("HTTP GET /e2e/hello"), 10_000, "access log").get("message").toString();
            assertThat(access).contains("access_token=***").containsIgnoringCase("cookie=***");
            String echoed = a.awaitJson(messageContains("HTTP POST /e2e/echo"), 10_000, "echo log").get("message").toString();
            assertThat(echoed).as("PAN keeps nothing it shouldn't").doesNotContain("5061051234566567");
            assertThat(b.awaitJson(messageContains("kafka received all-k"), 10_000, "kafka log")).containsEntry("trace_id", TRACE);
            assertThat(b.awaitJson(messageContains("grpc received all-g"), 10_000, "grpc log")).containsEntry("trace_id", TRACE);

            // Exported: both services' logs on the one trace, the span, and no secret anywhere.
            Await.until(() -> receiver().logs().stream().anyMatch(l -> "e2e-b".equals(l.service()) && TRACE.equals(l.traceId)),
                    20_000, "B's logs exported on A's trace");
            Await.until(() -> receiver().spans().stream().anyMatch(s -> "e2e.traced".equals(s.name)), 20_000, "traced span");
            Await.until(() -> receiver().logs().stream().anyMatch(l -> String.valueOf(l.body).contains("with a secret in the MDC")),
                    20_000, "secrets exported");
            assertThat(a.lines()).noneMatch(l -> l.contains(CANARY));
            assertThat(b.lines()).noneMatch(l -> l.contains(CANARY));
            assertThat(receiver().logs()).noneMatch(l -> String.valueOf(l.body).contains(CANARY)
                    || l.attributes.values().stream().anyMatch(v -> String.valueOf(v).contains(CANARY)));

            assertHealthy(a, "operation failed");
            assertHealthy(b);
        }
    }

    @Test
    void theApplicationBehavesTheSameWithAndWithoutTheStarter() throws Exception {
        try (KafkaBroker kafka = KafkaBroker.start("same-to-a", "same-to-b"); SqsServer sqs = SqsServer.start()) {
            sqs.createQueue("same-to-a");
            sqs.createQueue("same-to-b");
            Map<String, String> without = runBusinessScript(kafka, sqs, Options.servlet().with("signoz.enabled", "false"));
            stopServices();
            Map<String, String> with = runBusinessScript(kafka, sqs, Options.servlet().with("signoz.logging.mode", "BOTH"));
            assertThat(with).isEqualTo(without);
        }
    }

    @Test
    void samplingAtZeroStillLetsEveryWarningAndErrorThrough() throws Exception {
        Pair pair = startPair(Options.servlet().with("signoz.logging.sampling.enabled", "true")
                .with("signoz.logging.sampling.rate", "0.0").with("signoz.timed.slow-threshold-ms", "100"));
        ServiceProcess a = pair.a;

        Http.get(a.baseUrl() + "/e2e/hello?name=sampled");
        Http.get(a.baseUrl() + "/e2e/timed?ms=200");
        Http.get(a.baseUrl() + "/e2e/db/slow?ms=600");
        Http.get(a.baseUrl() + "/e2e/fail");

        assertThat(a.awaitJson(messageContains("[SigNoz] SLOW method"), 10_000, "slow method")).isNotNull();
        assertThat(a.awaitJson(messageContains("[SigNoz] SLOW QUERY"), 10_000, "slow query")).isNotNull();
        assertThat(str(a.awaitJson(messageContains("operation failed"), 10_000, "error"), "errorId")).isNotBlank();
        assertThat(a.jsonLines()).noneMatch(messageContains("hello sampled")).noneMatch(messageContains("HTTP GET /e2e/hello"));
        assertHealthy(a, "operation failed");
        assertHealthy(pair.b);
    }

    /** What the application does, with no telemetry in it: the results a user would see. */
    private Map<String, String> runBusinessScript(KafkaBroker kafka, SqsServer sqs, Options base) throws Exception {
        Options[] ab = channels(kafka, sqs, "same", base);
        Pair pair = startPair(ab[0], ab[1]);
        ServiceProcess a = pair.a;
        String run = String.valueOf(System.nanoTime());
        String basic = "Basic " + Base64.getEncoder().encodeToString("alice:alice-pass".getBytes("UTF-8"));
        String body = "{\"user\":\"same\",\"password\":\"" + CANARY + "\",\"list\":[1,2,3]}";
        Map<String, String> out = new LinkedHashMap<String, String>();

        out.put("hello", Http.get(a.baseUrl() + "/e2e/hello?name=same").body);
        Http.Response echo = Http.post(a.baseUrl() + "/e2e/echo", "application/json", body);
        out.put("echo", echo.status + " " + echo.body);
        out.put("chainPeer", String.valueOf(json(Http.get(a.baseUrl() + "/e2e/chain/rest")).get("peer") != null));
        out.put("webclientPeer", String.valueOf(json(Http.get(a.baseUrl() + "/e2e/chain/webclient")).get("peer") != null));
        out.put("login", Http.post(a.baseUrl() + "/e2e/login?user=bob&password=" + CANARY, "text/plain", "").body);
        out.put("timed", Http.get(a.baseUrl() + "/e2e/timed?ms=20").body);
        out.put("fail", Http.get(a.baseUrl() + "/e2e/fail").body);
        out.put("dbFast", Http.get(a.baseUrl() + "/e2e/db/fast").body);
        out.put("dbSlow", Http.get(a.baseUrl() + "/e2e/db/slow?ms=50").body);
        out.put("me", String.valueOf(json(Http.get(a.baseUrl() + "/e2e/secure/me", headers("Authorization", basic))).get("principal")));
        out.put("meUnauthenticated", String.valueOf(Http.get(a.baseUrl() + "/e2e/secure/me").status));
        out.put("missing", String.valueOf(Http.get(a.baseUrl() + "/e2e/nope").status));
        out.put("grpc", String.valueOf(Http.post(a.baseUrl() + "/e2e/grpc?payload=same-g", "text/plain", "").status));
        Http.post(a.baseUrl() + "/e2e/kafka?payload=k-" + run, "text/plain", "");
        Http.post(a.baseUrl() + "/e2e/sqs?payload=q-" + run, "text/plain", "");
        Http.post(a.baseUrl() + "/e2e/stomp?payload=s-" + run, "text/plain", "");
        Http.get(a.baseUrl() + "/e2e/async?label=x-" + run);
        out.put("kafkaDelivered", String.valueOf(observation(pair.b, "kafka", "k-" + run) != null));
        out.put("sqsDelivered", String.valueOf(observation(pair.b, "sqs", "q-" + run) != null));
        out.put("stompDelivered", String.valueOf(observation(pair.b, "stomp", "s-" + run) != null));
        out.put("asyncRan", String.valueOf(observation(a, "async", "x-" + run) != null));
        assertThat(a.isAlive()).isTrue();
        assertThat(pair.b.isAlive()).isTrue();
        return out;
    }

    private static Map<String, Object> observation(ServiceProcess service, String channel, String payloadOrLabel) {
        return Await.until(() -> {
            try {
                Map<String, Object> seen = json(Http.get(service.baseUrl() + "/e2e/last/" + channel));
                return payloadOrLabel.equals(seen.get("payload")) || payloadOrLabel.equals(seen.get("label")) ? seen : null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 30_000, service.name() + " to see " + channel + " " + payloadOrLabel + "\n" + service.tail(30));
    }
}
