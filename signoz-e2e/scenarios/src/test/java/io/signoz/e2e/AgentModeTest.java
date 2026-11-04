package io.signoz.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.e2e.support.Await;
import io.signoz.e2e.support.Http;
import io.signoz.e2e.support.OtlpReceiver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both services run with the OpenTelemetry Java agent, as docs/EXPORTING.md recommends: the agent
 * exports and propagates, the starter keeps JSON logs, masking and audit, and nothing is
 * instrumented twice.
 */
class AgentModeTest extends E2eTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String INBOUND = "00-" + TRACE + "-0102030405060708-01";

    private static Options withAgent(String service, String... jvmArgs) {
        Options o = Options.servlet()
                .with("signoz.logging.mode", "BOTH")
                .jvmArg("-javaagent:" + agentJar())
                .jvmArg("-Dotel.service.name=e2e-" + service)
                .jvmArg("-Dotel.bsp.schedule.delay=200")
                .jvmArg("-Dotel.blrp.schedule.delay=200")
                .jvmArg("-Dotel.metric.export.interval=60000")
                .jvmArg("-Dotel.javaagent.logging=none");
        for (String arg : jvmArgs) {
            o.jvmArg(arg);
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Http.Response r) throws Exception {
        assertThat(r.status).as(r.toString()).isEqualTo(200);
        return JSON.readValue(r.body, Map.class);
    }

    @Test
    void theAgentExportsAndPropagatesWhileTheStarterKeepsItsAppFeatures() throws Exception {
        Pair pair = startPair(withAgent("a"), withAgent("b"));

        assertThat(pair.a.awaitLine(l -> l.contains("agent="), 5_000, "summary")).contains("agent=DETECTED");
        assertThat(pair.a.lines()).anyMatch(l -> l.contains("Agent detected") && l.contains("skipping OTLP log appender"))
                .anyMatch(l -> l.contains("does NOT apply masking"));

        // The agent adds its trace IDs to each logging event rather than to the thread's MDC, so
        // they are checked on the log lines, which is where the starter's docs promise them.
        Http.get(pair.a.baseUrl() + "/e2e/hello?name=agent", headers("traceparent", INBOUND));
        assertThat(json(Http.get(pair.a.baseUrl() + "/e2e/chain/rest", headers("traceparent", INBOUND)))).containsKey("peer");
        assertThat(pair.a.awaitJson(messageContains("hello agent"), 10_000, "A's line"))
                .as("trace IDs from the agent's span").containsEntry("trace_id", TRACE).containsKey("span_id");
        assertThat(pair.b.awaitJson(messageContains("leaf reached on b"), 10_000, "B's line"))
                .as("propagated by the agent").containsEntry("trace_id", TRACE);

        assertThat(Http.post(pair.a.baseUrl() + "/e2e/grpc?payload=ag-1", "text/plain", "",
                headers("traceparent", INBOUND)).status).isEqualTo(200);
        assertThat(pair.b.awaitJson(messageContains("grpc received ag-1"), 10_000, "B's gRPC handler line"))
                .as("gRPC propagated by the agent").containsEntry("trace_id", TRACE);

        assertThat(json(Http.get(pair.a.baseUrl() + "/e2e/traced"))).containsEntry("result", "traced");
        Http.get(pair.a.baseUrl() + "/e2e/secrets?secret=" + CANARY);

        OtlpReceiver.ReceivedSpan traced = Await.until(() -> receiver().spans().stream()
                .filter(s -> "e2e.traced".equals(s.name)).findFirst().orElse(null), 30_000, "@Traced span via the agent");
        assertThat(traced.service()).isEqualTo("e2e-a");
        Await.until(() -> receiver().spans().stream().anyMatch(s -> TRACE.equals(s.traceId) && "e2e-b".equals(s.service())),
                30_000, "B's server span on the inbound trace; got " + receiver().spans());
        assertThat(receiver().requests()).as("the starter does not export alongside the agent")
                .allMatch(r -> "http".equals(r.transport));

        pair.a.awaitJson(messageContains("with a secret in the MDC"), 10_000, "secrets line");
        assertThat(pair.a.lines()).as("console still masked").noneMatch(l -> l.contains(CANARY));
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }

    @Test
    void withTheAgentsLogExportOffTheStarterExportsMaskedLogs() throws Exception {
        Pair pair = startPair(withAgent("a", "-Dotel.instrumentation.logback-appender.enabled=false"),
                withAgent("b", "-Dotel.instrumentation.logback-appender.enabled=false"));

        Http.get(pair.a.baseUrl() + "/e2e/hello?name=agentless-logs", headers("traceparent", INBOUND));
        Http.get(pair.a.baseUrl() + "/e2e/secrets?secret=" + CANARY);

        OtlpReceiver.ReceivedLog log = Await.until(() -> receiver().logs().stream()
                .filter(l -> "hello agentless-logs".equals(l.body)).findFirst().orElse(null), 30_000, "starter-exported log");
        assertThat(log.traceId).isEqualTo(TRACE);
        Await.until(() -> receiver().logs().stream().anyMatch(l -> String.valueOf(l.body).contains("login attempt")), 30_000,
                "secrets exported");
        assertThat(receiver().logs()).noneMatch(l -> String.valueOf(l.body).contains(CANARY));
        assertThat(receiver().requests()).as("logs from the starter over gRPC")
                .anyMatch(r -> r.signal == OtlpReceiver.Signal.LOGS && "grpc".equals(r.transport));
        // The agent may still export library logs it captures through java.util.logging; the
        // application's own Logback logs must come only from the starter, masked.
        assertThat(receiver().logs()).as("application logs not exported by the agent")
                .filteredOn(l -> "http".equals(l.transport))
                .noneMatch(l -> String.valueOf(l.body).contains("hello agentless-logs")
                        || String.valueOf(l.body).contains("login attempt"));
        assertThat(log.transport).isEqualTo("grpc");
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }
}
