package io.signoz.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.e2e.support.Await;
import io.signoz.e2e.support.Http;
import io.signoz.e2e.support.ServiceProcess;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The WebFlux services (on Netty): the reactive filters on their own and all together. */
class ReactiveTest extends E2eTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE = "5af7183fb1d4cf5f6b2c3d4e5f607182";
    private static final String INBOUND = "00-" + TRACE + "-1122334455667788-01";

    private static Options only(String... keyValues) {
        Options o = Options.reactive().with(ALL_FEATURES_OFF);
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

    @Test
    void starterDisabledLeavesTheReactiveAppUntouched() throws Exception {
        ServiceProcess a = startSingle(Options.reactive().with("signoz.enabled", "false"));

        assertThat(Http.get(a.baseUrl() + "/e2e/hello?name=rx").body).isEqualTo("hello rx");
        assertThat(Http.get(a.baseUrl() + "/e2e/mdc").header("X-Request-ID")).isNull();
        assertThat(a.lines()).noneMatch(l -> l.contains("[SigNoz]"));
        assertHealthy(a);
    }

    @Test
    void traceIdsAreResolvedAndReturned() throws Exception {
        ServiceProcess a = startSingle(only());

        Http.Response r = Http.get(a.baseUrl() + "/e2e/mdc", headers("traceparent", INBOUND));
        Map<String, Object> mdc = json(r);
        assertThat(traceIdOf(r.header("traceparent"))).isEqualTo(TRACE);
        assertThat(mdc).containsEntry("trace_id", TRACE).containsEntry("requestId", r.header("X-Request-ID"));

        Http.Response byId = Http.get(a.baseUrl() + "/e2e/mdc", headers("X-Request-ID", "rx-req-1"));
        assertThat(json(byId)).containsEntry("requestId", "rx-req-1");
        assertThat(byId.header("X-Request-ID")).isEqualTo("rx-req-1");
        assertHealthy(a);
    }

    @Test
    void accessLogUsesTheStatusTheResponseCarried() throws Exception {
        ServiceProcess a = startSingle(only("signoz.web.log-requests", "true"));

        Http.get(a.baseUrl() + "/e2e/hello?name=flux");
        Http.get(a.baseUrl() + "/e2e/nope");
        String body = "{\"n\":1}";
        assertThat(Http.post(a.baseUrl() + "/e2e/echo", "application/json", body).body).isEqualTo(body);

        assertThat(a.awaitJson(messageContains("HTTP GET /e2e/hello"), 10_000, "hello").get("message").toString())
                .matches("HTTP GET /e2e/hello\\?name=flux status=200 duration=\\d+ms.*");
        assertThat(a.awaitJson(messageContains("HTTP GET /e2e/nope"), 10_000, "404").get("message").toString())
                .contains("status=404");
        assertThat(a.awaitJson(messageContains("HTTP POST /e2e/echo"), 10_000, "echo").get("message").toString())
                .contains("requestBody=" + body)
                .as("log-response-body is off by default").doesNotContain("responseBody=");
        assertHealthy(a);
    }

    @Test
    void webClientPropagatesAndTheMdcFollowsThreadHops() throws Exception {
        Pair pair = startPair(only("signoz.outbound.enabled", "true"));

        Map<String, Object> chain = json(Http.get(pair.a.baseUrl() + "/e2e/chain/webclient", headers("traceparent", INBOUND)));
        @SuppressWarnings("unchecked")
        Map<String, String> peer = (Map<String, String>) chain.get("peer");
        @SuppressWarnings("unchecked")
        Map<String, String> self = (Map<String, String>) chain.get("self");
        assertThat(self).containsEntry("trace_id", TRACE);
        assertThat(peer).containsEntry("service", "b").containsEntry("trace_id", TRACE)
                .containsEntry("requestId", self.get("requestId"));

        @SuppressWarnings("unchecked")
        Map<String, String> afterHop = (Map<String, String>) chain.get("afterHop");
        Map<String, Object> hopLine = pair.a.awaitJson(messageContains("after the hop"), 10_000, "post-hop log");
        if (bootVersion() >= 3) {
            // Spring Boot 3: automatic context propagation restores the MDC on the new thread.
            assertThat(afterHop).containsEntry("trace_id", TRACE);
            assertThat(hopLine).containsEntry("trace_id", TRACE);
        }
        assertThat(pair.a.awaitJson(messageContains("[SigNoz] Outbound GET"), 10_000, "outbound log").get("message").toString())
                .contains("/e2e/leaf?token=").endsWith("ms");
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    @Test
    void userContextComesFromReactiveSecurity() throws Exception {
        ServiceProcess a = startSingle(only("signoz.user-context.enabled", "true"));

        String basic = "Basic " + Base64.getEncoder().encodeToString("alice:alice-pass".getBytes("UTF-8"));
        Map<String, Object> me = json(Http.get(a.baseUrl() + "/e2e/secure/me", headers("Authorization", basic)));
        assertThat(me).containsEntry("principal", "alice");
        assertThat(a.awaitJson(messageContains("secure endpoint for alice"), 10_000, "handler log"))
                .containsEntry("userId", "alice");
        assertThat(Http.get(a.baseUrl() + "/e2e/secure/me").status).isEqualTo(401);
        assertHealthy(a);
    }

    @Test
    void everythingTogetherOnWebFlux() throws Exception {
        Options all = Options.reactive().with("signoz.logging.mode", "BOTH").with("signoz.web.log-headers", "true");
        Pair pair = startPair(all);

        String body = "{\"user\":\"rx\",\"password\":\"" + CANARY + "\"}";
        assertThat(Http.post(pair.a.baseUrl() + "/e2e/echo", "application/json", body).body).isEqualTo(body);
        Map<String, Object> chain = json(Http.get(pair.a.baseUrl() + "/e2e/chain/webclient",
                headers("traceparent", INBOUND, "Cookie", "SESSION=" + CANARY)));
        @SuppressWarnings("unchecked")
        Map<String, String> peer = (Map<String, String>) chain.get("peer");
        assertThat(peer).containsEntry("trace_id", TRACE);

        pair.a.awaitJson(messageContains("HTTP GET /e2e/chain/webclient"), 10_000, "access log");
        Await.until(() -> receiver().logs().stream().anyMatch(l -> String.valueOf(l.body).contains("HTTP POST /e2e/echo")),
                15_000, "exported access log");
        assertThat(pair.a.lines()).noneMatch(l -> l.contains(CANARY));
        assertThat(receiver().logs()).noneMatch(l -> String.valueOf(l.body).contains(CANARY));
        assertThat(Await.until(() -> receiver().logs().stream()
                .filter(l -> "e2e-b".equals(l.service()) && "leaf reached on b".equals(l.body))
                .findFirst().map(l -> l.traceId).orElse(null), 20_000, "B's exported leaf log"))
                .as("B's exported log is on A's trace").isEqualTo(TRACE);
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }
}
