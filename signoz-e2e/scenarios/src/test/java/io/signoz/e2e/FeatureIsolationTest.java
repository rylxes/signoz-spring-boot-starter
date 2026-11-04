package io.signoz.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.e2e.support.Await;
import io.signoz.e2e.support.Http;
import io.signoz.e2e.support.OtlpReceiver;
import io.signoz.e2e.support.ServiceProcess;
import io.signoz.e2e.support.SqsServer;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each feature on its own: every other feature switch is off ({@link #ALL_FEATURES_OFF}), the
 * feature under test is on, and the test checks that it does what the guides in {@code docs/}
 * say, across two services where it involves a hop, and that both services stay healthy.
 */
class FeatureIsolationTest extends E2eTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INBOUND = "00-" + TRACE + "-00f067aa0ba902b7-01";

    private static Options only(String... keyValues) {
        Options o = Options.servlet().with(ALL_FEATURES_OFF);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            o.with(keyValues[i], keyValues[i + 1]);
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Http.Response r) throws Exception {
        assertThat(r.status).as(r.toString()).isEqualTo(200);
        return JSON.readValue(r.body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> peerOf(Map<String, Object> chain) {
        return (Map<String, String>) chain.get("peer");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> selfOf(Map<String, Object> chain) {
        return (Map<String, String>) chain.get("self");
    }

    // --- the starter switched off ---

    @Test
    void starterDisabledLeavesTheApplicationUntouched() throws Exception {
        Pair pair = startPair(Options.servlet().with("signoz.enabled", "false"));

        assertThat(Http.get(pair.a.baseUrl() + "/e2e/hello?name=zed").body).isEqualTo("hello zed");
        Http.Response r = Http.get(pair.a.baseUrl() + "/e2e/mdc");
        assertThat(r.header("X-Request-ID")).as("no SigNoz response headers").isNull();
        assertThat(json(Http.get(pair.a.baseUrl() + "/e2e/chain/rest"))).containsKey("peer");

        Thread.sleep(2000);
        assertThat(pair.a.lines()).as("no JSON, no [SigNoz] lines")
                .noneMatch(l -> l.trim().startsWith("{\"@timestamp\"") || l.contains("[SigNoz]"));
        assertThat(receiver().requests()).as("nothing exported").isEmpty();
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    // --- logging ---

    @Test
    void jsonConsoleOutputIsStructuredAndFreeOfBootstrapNoise() throws Exception {
        ServiceProcess a = startSingle(only());

        Http.get(a.baseUrl() + "/e2e/hello?name=alice");

        Map<String, Object> line = a.awaitJson(messageContains("hello alice"), 10_000, "hello line");
        assertThat(line).containsKeys("@timestamp", "message", "level", "logger_name", "thread_name");
        assertThat(line.get("level")).isEqualTo("INFO");
        assertThat(str(line, "logger_name")).endsWith("ServletEndpoints");
        assertThat(line).doesNotContainKeys("CONSOLE_LOG_PATTERN", "FILE_LOG_PATTERN", "PID", "CONSOLE_LOG_CHARSET");
        Thread.sleep(1500);
        assertThat(receiver().logs()).as("mode JSON exports nothing").isEmpty();
        assertHealthy(a);
    }

    @Test
    void otlpModeExportsLogsLinkedToTheRequestAndLeavesTheConsoleAlone() throws Exception {
        ServiceProcess a = startSingle(only("signoz.logging.mode", "OTLP"));

        Http.Response r = Http.get(a.baseUrl() + "/e2e/hello?name=carol", headers("traceparent", INBOUND));
        assertThat(r.body).isEqualTo("hello carol");

        OtlpReceiver.ReceivedLog log = Await.until(() -> receiver().logs().stream()
                .filter(l -> "hello carol".equals(l.body)).findFirst().orElse(null), 15_000, "exported log");
        assertThat(log.service()).isEqualTo("e2e-a");
        assertThat(log.resource).containsEntry("service.version", "9.9.9").containsEntry("deployment.environment", "e2e");
        assertThat(log.severity).isEqualTo("INFO");
        assertThat(log.traceId).as("log linked to the request's trace").isEqualTo(TRACE);
        assertThat(log.spanId).hasSize(16);

        String console = a.awaitLine(l -> l.contains("hello carol"), 5_000, "console line");
        assertThat(console.trim()).as("console keeps Spring Boot's own format").doesNotStartWith("{");
        assertHealthy(a);
    }

    // --- trace identity on inbound requests ---

    @Test
    void inboundTraceparentIsReusedWithANewSpan() throws Exception {
        ServiceProcess a = startSingle(only());

        Http.Response r = Http.get(a.baseUrl() + "/e2e/mdc", headers("traceparent", INBOUND));
        Map<String, Object> mdc = json(r);
        assertThat(traceIdOf(r.header("traceparent"))).isEqualTo(TRACE);
        assertThat(r.header("traceparent")).doesNotContain("00f067aa0ba902b7").endsWith("-01");
        assertThat(mdc.get("trace_id")).isEqualTo(TRACE);
        assertThat(str(mdc, "span_id")).hasSize(16).isNotEqualTo("00f067aa0ba902b7");
        assertThat(r.header("X-Request-ID")).isNotBlank().isEqualTo(mdc.get("requestId"));

        Http.get(a.baseUrl() + "/e2e/hello?name=traced", headers("traceparent", INBOUND));
        assertThat(a.awaitJson(messageContains("hello traced"), 10_000, "hello line").get("trace_id")).isEqualTo(TRACE);
        assertHealthy(a);
    }

    @Test
    void inboundRequestIdIsUsedWithoutATraceparent() throws Exception {
        ServiceProcess a = startSingle(only());

        Http.Response r = Http.get(a.baseUrl() + "/e2e/mdc", headers("X-Request-ID", "order-7731"));
        Map<String, Object> mdc = json(r);
        assertThat(r.header("X-Request-ID")).isEqualTo("order-7731");
        assertThat(mdc.get("requestId")).isEqualTo("order-7731");
        assertThat(str(mdc, "trace_id")).matches(HEX32);
        assertThat(traceIdOf(r.header("traceparent"))).isEqualTo(mdc.get("trace_id"));
        assertHealthy(a);
    }

    @Test
    void freshIdsAtTheEdgeAndInvalidTraceparentsIgnored() throws Exception {
        ServiceProcess a = startSingle(only());

        Map<String, Object> fresh = json(Http.get(a.baseUrl() + "/e2e/mdc"));
        Map<String, Object> other = json(Http.get(a.baseUrl() + "/e2e/mdc"));
        assertThat(str(fresh, "trace_id")).matches(HEX32).isNotEqualTo(other.get("trace_id"));

        Http.Response bad = Http.get(a.baseUrl() + "/e2e/mdc", headers("traceparent", "00-not-a-trace-01"));
        assertThat(str(json(bad), "trace_id")).matches(HEX32);
        assertThat(bad.header("traceparent")).matches(TRACEPARENT);
        assertHealthy(a);
    }

    // --- outbound HTTP ---

    @Test
    void restTemplateCallsCarryTheTraceToThePeer() throws Exception {
        Pair pair = startPair(only("signoz.outbound.enabled", "true"));

        Map<String, Object> chain = json(Http.get(pair.a.baseUrl() + "/e2e/chain/rest", headers("traceparent", INBOUND)));
        assertThat(peerOf(chain)).containsEntry("service", "b").containsEntry("trace_id", TRACE)
                .containsEntry("requestId", selfOf(chain).get("requestId"));
        String outbound = pair.a.awaitJson(messageContains("[SigNoz] Outbound GET"), 10_000, "outbound log").get("message").toString();
        assertThat(outbound).matches("\\[SigNoz\\] Outbound GET http://localhost:" + pair.b.port()
                + "/e2e/leaf\\?token=.*&tenant=acme -> 200 in \\d+ms");
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    @Test
    void webClientCallsCarryTheTraceToThePeer() throws Exception {
        Pair pair = startPair(only("signoz.outbound.enabled", "true"));

        Map<String, Object> chain = json(Http.get(pair.a.baseUrl() + "/e2e/chain/webclient", headers("traceparent", INBOUND)));
        assertThat(peerOf(chain)).containsEntry("trace_id", TRACE).containsEntry("requestId", selfOf(chain).get("requestId"));
        assertThat(pair.a.awaitJson(messageContains("[SigNoz] Outbound GET"), 10_000, "outbound log").get("message").toString())
                .matches("\\[SigNoz\\] Outbound GET http://localhost:" + pair.b.port() + "/e2e/leaf\\?token=.* -> 200 in \\d+ms");
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    @Test
    void oneTraceAcrossARoundTripAToBToA() throws Exception {
        Pair pair = startPair(only("signoz.outbound.enabled", "true"));

        Map<String, Object> trip = json(Http.get(pair.a.baseUrl() + "/e2e/chain/roundtrip", headers("traceparent", INBOUND)));
        @SuppressWarnings("unchecked")
        Map<String, Object> viaB = (Map<String, Object>) trip.get("peer");
        assertThat(selfOf(trip)).containsEntry("service", "a").containsEntry("trace_id", TRACE);
        assertThat(selfOf(viaB)).containsEntry("service", "b").containsEntry("trace_id", TRACE);
        assertThat(peerOf(viaB)).containsEntry("service", "a").containsEntry("trace_id", TRACE);
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    @Test
    void outboundSwitchedOffPropagatesNothing() throws Exception {
        Pair pair = startPair(only());   // signoz.outbound.enabled=false

        Map<String, Object> chain = json(Http.get(pair.a.baseUrl() + "/e2e/chain/rest", headers("traceparent", INBOUND)));
        assertThat(peerOf(chain).get("trace_id")).isNotEqualTo(TRACE);
        Thread.sleep(1000);
        assertThat(pair.a.jsonLines()).noneMatch(messageContains("[SigNoz] Outbound"));
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    // --- messaging and RPC ---

    @Test
    void kafkaCarriesTheTraceToTheConsumer() throws Exception {
        try (KafkaBroker kafka = KafkaBroker.start("e2e-to-a", "e2e-to-b")) {
            Options a = only("signoz.messaging.enabled", "true", "e2e.kafka.enabled", "true",
                    "e2e.kafka.bootstrap", kafka.bootstrapServers(), "e2e.kafka.in-topic", "e2e-to-a", "e2e.kafka.out-topic", "e2e-to-b");
            Options b = only("signoz.messaging.enabled", "true", "e2e.kafka.enabled", "true",
                    "e2e.kafka.bootstrap", kafka.bootstrapServers(), "e2e.kafka.in-topic", "e2e-to-b", "e2e.kafka.out-topic", "e2e-to-a");
            Pair pair = startPair(a, b);

            assertThat(Http.post(pair.a.baseUrl() + "/e2e/kafka?payload=k-1", "text/plain", "", headers("traceparent", INBOUND)).status)
                    .isEqualTo(200);
            Map<String, Object> seen = awaitObservation(pair.b, "kafka", "k-1");
            assertThat(seen).containsEntry("trace_id", TRACE).containsKey("span_id");
            assertThat(pair.b.awaitJson(messageContains("kafka received k-1"), 5_000, "consumer log").get("trace_id")).isEqualTo(TRACE);
            assertHealthy(pair.a);
            assertHealthy(pair.b);
        }
    }

    @Test
    void sqsCarriesTheTraceToTheListener() throws Exception {
        try (SqsServer sqs = SqsServer.start()) {
            String toA = sqs.createQueue("e2e-to-a");
            String toB = sqs.createQueue("e2e-to-b");
            Options a = only("signoz.sqs.enabled", "true", "e2e.sqs.enabled", "true", "e2e.sqs.endpoint", sqs.endpoint(),
                    "e2e.sqs.in-queue", "e2e-to-a", "e2e.sqs.out-queue-url", toB);
            Options b = only("signoz.sqs.enabled", "true", "e2e.sqs.enabled", "true", "e2e.sqs.endpoint", sqs.endpoint(),
                    "e2e.sqs.in-queue", "e2e-to-b", "e2e.sqs.out-queue-url", toA);
            Pair pair = startPair(a, b);

            assertThat(Http.post(pair.a.baseUrl() + "/e2e/sqs?payload=q-1", "text/plain", "", headers("traceparent", INBOUND)).status)
                    .isEqualTo(200);
            Map<String, Object> seen = awaitObservation(pair.b, "sqs", "q-1");
            assertThat(seen).containsEntry("trace_id", TRACE);
            assertThat(pair.b.awaitJson(messageContains("sqs received q-1"), 5_000, "listener log").get("trace_id")).isEqualTo(TRACE);
            assertHealthy(pair.a);
            assertHealthy(pair.b);
        }
    }

    @Test
    void grpcCarriesTheTraceToTheServer() throws Exception {
        Pair pair = startPair(only("signoz.grpc.enabled", "true"));

        Map<String, Object> reply = json(Http.post(pair.a.baseUrl() + "/e2e/grpc?payload=g-1", "text/plain", "",
                headers("traceparent", INBOUND)));
        assertThat(reply.get("peerTraceId")).as("trace ID the gRPC handler saw").isEqualTo(TRACE);
        assertThat(pair.b.awaitJson(messageContains("grpc received g-1"), 5_000, "server log").get("trace_id")).isEqualTo(TRACE);
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    @Test
    void stompFramesCarryTheTraceFromTheHandshakeOrTheFrame() throws Exception {
        Pair pair = startPair(only("signoz.websocket.enabled", "true"));

        Http.post(pair.a.baseUrl() + "/e2e/stomp?payload=s-1&onFrame=false", "text/plain", "", headers("traceparent", INBOUND));
        assertThat(awaitObservation(pair.b, "stomp", "s-1")).as("frame without traceparent: handshake's is used")
                .containsEntry("trace_id", TRACE);

        String otherTrace = "11111111111111111111111111111111";
        String other = "00-" + otherTrace + "-2222222222222222-01";
        Http.post(pair.a.baseUrl() + "/e2e/stomp?payload=s-2&onFrame=true", "text/plain", "", headers("traceparent", other));
        assertThat(awaitObservation(pair.b, "stomp", "s-2")).as("traceparent on the frame")
                .containsEntry("trace_id", otherTrace);
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    @Test
    void asyncMethodsKeepTheRequestsIds() throws Exception {
        ServiceProcess a = startSingle(only("signoz.async.enabled", "true"));

        Map<String, Object> request = json(Http.get(a.baseUrl() + "/e2e/async?label=x-1", headers("traceparent", INBOUND)));
        Map<String, Object> seen = awaitObservation(a, "async", "x-1");
        assertThat(seen).containsEntry("trace_id", TRACE).containsEntry("requestId", request.get("requestId"));
        assertThat(str(seen, "thread")).as("ran on another thread").doesNotStartWith("http-nio");
        assertHealthy(a);
    }

    @Test
    void asyncSwitchedOffDoesNotCopyTheMdc() throws Exception {
        ServiceProcess a = startSingle(only());

        Http.get(a.baseUrl() + "/e2e/async?label=x-2", headers("traceparent", INBOUND));
        assertThat(awaitObservation(a, "async", "x-2")).doesNotContainKey("trace_id");
        assertHealthy(a);
    }

    // --- spans ---

    @Test
    void tracedMethodsAreExportedAsSpans() throws Exception {
        ServiceProcess a = startSingle(only("signoz.tracing.enabled", "true"));

        assertThat(json(Http.get(a.baseUrl() + "/e2e/traced"))).containsEntry("result", "traced");

        OtlpReceiver.ReceivedSpan span = Await.until(() -> receiver().spans().stream()
                .filter(s -> "e2e.traced".equals(s.name)).findFirst().orElse(null), 15_000, "span e2e.traced; got " + receiver().spans());
        assertThat(span.service()).isEqualTo("e2e-a");
        assertThat(span.attributes).containsEntry("feature", "traced");
        assertThat(span.traceId).matches(HEX32);
        assertHealthy(a);
    }

    // --- web ---

    @Test
    void httpAccessLogRecordsEachRequestOnce() throws Exception {
        ServiceProcess a = startSingle(only("signoz.web.log-requests", "true"));

        Http.get(a.baseUrl() + "/e2e/hello?name=dave");
        String body = "{\"order\":42,\"items\":[\"a\",\"b\"]}";
        Http.Response echo = Http.post(a.baseUrl() + "/e2e/echo", "application/json", body);
        Http.get(a.baseUrl() + "/actuator/health");
        Http.get(a.baseUrl() + "/e2e/secure/me");   // 401

        assertThat(echo.body).as("response body untouched").isEqualTo(body);
        assertThat(a.awaitJson(messageContains("HTTP GET /e2e/hello"), 10_000, "access log").get("message").toString())
                .matches("HTTP GET /e2e/hello\\?name=dave status=200 duration=\\d+ms.*");
        assertThat(a.awaitJson(messageContains("HTTP POST /e2e/echo"), 5_000, "echo access log").get("message").toString())
                .contains("status=200").contains("requestBody=" + body);
        assertThat(a.awaitJson(messageContains("HTTP GET /e2e/secure/me"), 5_000, "401 access log").get("message").toString())
                .contains("status=401");
        Thread.sleep(500);
        assertThat(a.jsonLines()).as("excluded path").noneMatch(messageContains("/actuator/health"));
        assertThat(a.jsonLines().stream().filter(messageContains("HTTP GET /e2e/hello")).count()).isEqualTo(1);
        assertHealthy(a);
    }

    @Test
    void userContextComesFromSpringSecurity() throws Exception {
        ServiceProcess a = startSingle(only("signoz.user-context.enabled", "true"));

        String basic = "Basic " + Base64.getEncoder().encodeToString("alice:alice-pass".getBytes("UTF-8"));
        Map<String, Object> me = json(Http.get(a.baseUrl() + "/e2e/secure/me", headers("Authorization", basic)));
        assertThat(me).containsEntry("principal", "alice");
        assertThat(me).as("handler MDC").containsEntry("userId", "alice");
        assertThat(str(me, "userRoles")).contains("USER");

        Map<String, Object> line = a.awaitJson(messageContains("secure endpoint for alice"), 10_000, "handler log");
        assertThat(line).containsEntry("userId", "alice");
        assertThat(Http.get(a.baseUrl() + "/e2e/secure/me").status).isEqualTo(401);
        assertHealthy(a);
    }

    // --- annotations ---

    @Test
    void auditLogRecordsTheCallWithMaskedArguments() throws Exception {
        ServiceProcess a = startSingle(only("signoz.audit.enabled", "true"));

        Http.Response r = Http.post(a.baseUrl() + "/e2e/login?user=bob&password=" + CANARY, "text/plain", "");
        assertThat(r.body).as("the method got the real password").isEqualTo("ok:bob:" + CANARY.length());

        String audit = a.awaitJson(messageContains("AUDIT action=E2E_LOGIN"), 10_000, "audit line").get("message").toString();
        assertThat(audit).contains("resourceType=User").contains("resourceId=bob").contains("outcome=SUCCESS")
                .doesNotContain(CANARY);
        assertHealthy(a);
    }

    @Test
    void timedLogsDurationsAndFlagsSlowCalls() throws Exception {
        ServiceProcess a = startSingle(only("signoz.timed.enabled", "true", "signoz.timed.slow-threshold-ms", "300"));

        assertThat(Http.get(a.baseUrl() + "/e2e/timed?ms=20").body).isEqualTo("slept 20");
        assertThat(Http.get(a.baseUrl() + "/e2e/timed?ms=400").body).isEqualTo("slept 400");

        assertThat(a.awaitJson(messageContains("[SigNoz] Timed e2e.timed completed in"), 10_000, "timed line")).isNotNull();
        Map<String, Object> slow = a.awaitJson(messageContains("[SigNoz] SLOW method e2e.timed"), 10_000, "slow line");
        assertThat(slow.get("level")).isEqualTo("WARN");
        assertThat(slow.get("message").toString()).contains("(threshold: 300ms)");
        assertHealthy(a);
    }

    @Test
    void alertOnFailureCountsFailuresAndTheAppKeepsGoing() throws Exception {
        ServiceProcess a = startSingle(only("signoz.alerts.enabled", "true"));

        assertThat(Http.get(a.baseUrl() + "/e2e/fail").body).isEqualTo("handled");
        Map<String, Object> warn = a.awaitJson(messageContains("[SigNoz] AlertOnFailure"), 10_000, "alert line");
        assertThat(warn.get("message").toString()).contains("FeatureService.fail").contains("IllegalStateException");
        assertHealthy(a, "operation failed");
    }

    @Test
    void errorsGetAStableFingerprint() throws Exception {
        ServiceProcess a = startSingle(only("signoz.errors.enabled", "true"));

        Http.get(a.baseUrl() + "/e2e/fail");
        Http.get(a.baseUrl() + "/e2e/fail");

        List<Map<String, Object>> errors = Await.until(() -> {
            List<Map<String, Object>> found = a.jsonLines().stream().filter(messageContains("operation failed"))
                    .collect(Collectors.toList());
            return found.size() >= 2 ? found : null;
        }, 10_000, "two failure lines");
        assertThat(str(errors.get(0), "errorId")).isNotBlank().isEqualTo(str(errors.get(1), "errorId"));
        assertThat(str(errors.get(0), "stack_trace")).contains("IllegalStateException: e2e deliberate failure");
        assertHealthy(a, "operation failed");
    }

    @Test
    void errorsWithoutFingerprintingHaveNoErrorId() throws Exception {
        ServiceProcess a = startSingle(only());

        Http.get(a.baseUrl() + "/e2e/fail");
        assertThat(a.awaitJson(messageContains("operation failed"), 10_000, "failure line")).doesNotContainKey("errorId");
        assertHealthy(a, "operation failed");
    }

    @Test
    void slowQueriesAreLoggedAndFastOnesAreNot() throws Exception {
        ServiceProcess a = startSingle(only("signoz.database.enabled", "true"));

        assertThat(Http.get(a.baseUrl() + "/e2e/db/fast").body).isEqualTo("fast 1");
        assertThat(Http.get(a.baseUrl() + "/e2e/db/slow?ms=700").body).isEqualTo("slow done");

        Map<String, Object> slow = a.awaitJson(messageContains("[SigNoz] SLOW QUERY"), 10_000, "slow query line");
        assertThat(slow.get("level")).isEqualTo("WARN");
        assertThat(slow.get("message").toString()).matches("\\[SigNoz\\] SLOW QUERY \\(\\d+ms > 500ms\\): CALL SLEEP\\(700\\)");
        assertThat(a.jsonLines()).noneMatch(l -> String.valueOf(l.get("message")).contains("SLOW QUERY") && String.valueOf(l.get("message")).contains("SELECT 1"));
        assertHealthy(a);
    }

    // --- masking and sampling ---

    @Test
    void sensitiveValuesNeverReachTheConsoleOrTheExport() throws Exception {
        Pair pair = startPair(only("signoz.logging.mask-enabled", "true", "signoz.logging.mode", "BOTH",
                "signoz.web.log-requests", "true", "signoz.web.log-headers", "true", "signoz.outbound.enabled", "true"));
        ServiceProcess a = pair.a;

        Http.get(a.baseUrl() + "/e2e/secrets?secret=" + CANARY);
        Http.get(a.baseUrl() + "/e2e/hello?name=erin&access_token=" + CANARY,
                headers("Cookie", "SESSION=" + CANARY, "Authorization", "Bearer " + CANARY));
        String body = "{\"user\":\"erin\",\"password\":\"" + CANARY + "\"}";
        assertThat(Http.post(a.baseUrl() + "/e2e/echo", "application/json", body).body)
                .as("the app still receives and returns the real value").isEqualTo(body);
        Http.get(a.baseUrl() + "/e2e/chain/rest");

        a.awaitJson(messageContains("with a secret in the MDC"), 10_000, "last secrets line");
        a.awaitJson(messageContains("HTTP POST /e2e/echo"), 10_000, "echo access log");
        a.awaitJson(messageContains("[SigNoz] Outbound GET"), 10_000, "outbound line");
        Await.until(() -> receiver().logs().stream().anyMatch(l -> l.body != null && l.body.contains("HTTP POST /e2e/echo")),
                15_000, "exported access log");

        assertThat(a.lines()).as("console").noneMatch(l -> l.contains(CANARY));
        assertThat(receiver().logs()).as("export").noneMatch(l -> String.valueOf(l.body).contains(CANARY)
                || l.attributes.values().stream().anyMatch(v -> String.valueOf(v).contains(CANARY)));
        assertThat(a.jsonLines()).anyMatch(messageContains("password=***"))
                .anyMatch(messageContains("Bearer ***"))
                .anyMatch(messageContains("***-**-****"));
        assertThat(a.awaitJson(messageContains("HTTP GET /e2e/hello"), 1000, "").get("message").toString())
                .contains("access_token=***").containsIgnoringCase("cookie=***").containsIgnoringCase("authorization=***");
        assertThat(a.awaitJson(messageContains("[SigNoz] Outbound GET"), 1000, "").get("message").toString())
                .contains("token=***").contains("tenant=acme").doesNotContain("chain-s3cret");
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    @Test
    void maskingProfilesAndFieldStrategiesApply() throws Exception {
        ServiceProcess a = startSingle(only("signoz.logging.mask-enabled", "true", "signoz.logging.profiles", "pci",
                "signoz.logging.field-strategies.cvv", "partial:0:1"));

        Http.get(a.baseUrl() + "/e2e/secrets?secret=" + CANARY);
        String pci = a.awaitJson(messageContains("pci payload"), 10_000, "pci line").get("message").toString();
        assertThat(pci).as("PAN keeps BIN and last four").contains("506105").contains("6567").doesNotContain("5061051234566567");
        assertThat(pci).as("field-strategies wins over the profile").contains("\"cvv\":\"****************y\"");
        assertHealthy(a);
    }

    @Test
    void maskingSwitchedOffLogsValuesAsIs() throws Exception {
        ServiceProcess a = startSingle(only());   // mask-enabled=false

        Http.get(a.baseUrl() + "/e2e/secrets?secret=" + CANARY);
        assertThat(a.awaitJson(messageContains("login attempt"), 10_000, "line").get("message").toString()).contains(CANARY);
        assertHealthy(a);
    }

    @Test
    void samplingDropsInfoButKeepsWarnAndError() throws Exception {
        ServiceProcess a = startSingle(only("signoz.logging.sampling.enabled", "true", "signoz.logging.sampling.rate", "0.0"));

        Http.get(a.baseUrl() + "/e2e/levels?marker=smp&infos=40");
        a.awaitJson(messageContains("smp error"), 10_000, "error line");
        assertThat(a.jsonLines()).anyMatch(messageContains("smp warn")).noneMatch(messageContains("smp info"));
        assertHealthy(a, "smp error");
    }

    @Test
    void withoutSamplingEveryLineIsKept() throws Exception {
        ServiceProcess a = startSingle(only());

        Http.get(a.baseUrl() + "/e2e/levels?marker=all&infos=40");
        a.awaitJson(messageContains("all error"), 10_000, "error line");
        assertThat(a.jsonLines().stream().filter(messageContains("all info")).count()).isEqualTo(40);
        assertHealthy(a, "all error");
    }

    // --- helpers ---

    private static Map<String, Object> awaitObservation(ServiceProcess service, String channel, String payloadOrLabel) {
        return Await.until(() -> {
            try {
                Map<String, Object> seen = json(Http.get(service.baseUrl() + "/e2e/last/" + channel));
                return payloadOrLabel.equals(seen.get("payload")) || payloadOrLabel.equals(seen.get("label")) ? seen : null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 20_000, service.name() + " to see " + channel + " " + payloadOrLabel + "\n" + service.tail(30));
    }
}
