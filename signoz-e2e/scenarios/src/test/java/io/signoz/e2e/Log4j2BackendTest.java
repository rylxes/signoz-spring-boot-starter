package io.signoz.e2e;

import io.signoz.e2e.support.Await;
import io.signoz.e2e.support.Http;
import io.signoz.e2e.support.OtlpReceiver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Both services on Log4j2 instead of Logback, as the logging guide's exclusion recipe sets them up. */
class Log4j2BackendTest extends E2eTestBase {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String INBOUND = "00-" + TRACE + "-b7ad6b7169203331-01";

    @Test
    void jsonOutputExportMaskingAndFingerprintsOnLog4j2() throws Exception {
        Pair pair = startPair(Options.servlet().log4j2().with("signoz.logging.mode", "BOTH").with("signoz.web.log-requests", "true"));

        assertThat(pair.a.lines()).as("Logback is not running").noneMatch(l -> l.contains("ch.qos.logback"));
        Http.get(pair.a.baseUrl() + "/e2e/hello?name=l4j", headers("traceparent", INBOUND));
        Http.get(pair.a.baseUrl() + "/e2e/secrets?secret=" + CANARY);
        Http.get(pair.a.baseUrl() + "/e2e/fail");
        Http.get(pair.a.baseUrl() + "/e2e/fail");
        Http.get(pair.a.baseUrl() + "/e2e/chain/rest", headers("traceparent", INBOUND));

        Map<String, Object> hello = pair.a.awaitJson(messageContains("hello l4j"), 10_000, "hello line");
        assertThat(hello).containsKeys("@timestamp", "level", "logger_name", "thread_name").containsEntry("trace_id", TRACE);
        OtlpReceiver.ReceivedLog exported = Await.until(() -> receiver().logs().stream()
                .filter(l -> "hello l4j".equals(l.body)).findFirst().orElse(null), 20_000, "exported hello");
        assertThat(exported.traceId).isEqualTo(TRACE);
        assertThat(exported.service()).isEqualTo("e2e-a");

        pair.a.awaitJson(messageContains("with a secret in the MDC"), 10_000, "secrets logged");
        Await.until(() -> receiver().logs().stream().anyMatch(l -> String.valueOf(l.body).contains("login attempt")), 20_000,
                "exported secrets line");
        assertThat(pair.a.lines()).noneMatch(l -> l.contains(CANARY));
        assertThat(receiver().logs()).noneMatch(l -> String.valueOf(l.body).contains(CANARY));

        String errorId = Await.until(() -> {
            long withId = pair.a.jsonLines().stream().filter(messageContains("operation failed"))
                    .filter(j -> j.get("errorId") != null).count();
            return withId >= 2 ? str(pair.a.awaitJson(messageContains("operation failed"), 1000, ""), "errorId") : null;
        }, 10_000, "two fingerprinted errors");
        assertThat(pair.a.jsonLines().stream().filter(messageContains("operation failed")))
                .allMatch(j -> errorId.equals(j.get("errorId")));

        assertThat(pair.b.awaitJson(messageContains("leaf reached on b"), 10_000, "peer line")).containsEntry("trace_id", TRACE);
        assertHealthy(pair.a, "operation failed");
        assertHealthy(pair.b);
    }
}
