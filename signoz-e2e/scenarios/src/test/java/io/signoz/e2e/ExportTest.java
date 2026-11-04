package io.signoz.e2e;

import io.signoz.e2e.support.Await;
import io.signoz.e2e.support.Http;
import io.signoz.e2e.support.OtlpReceiver;
import io.signoz.e2e.support.ServiceProcess;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Where telemetry goes: the exporter settings from docs/EXPORTING.md, end to end. */
class ExportTest extends E2eTestBase {

    /**
     * Planted credentials, built at run time (the repository's convention) so secret scanners
     * don't mistake test values for real ones. They must never appear in output.
     */
    private static final String KEY = canary("ingest");
    private static final String GRAFANA_CANARY = canary("grafana");

    private static String canary(String label) {
        return label + "-canary-" + Long.toHexString(Double.doubleToLongBits(Math.PI));
    }

    private static Options exporting(String... keyValues) {
        Options o = Options.servlet().with("signoz.logging.mode", "BOTH").with("signoz.tracing.enabled", "true");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            o.with(keyValues[i], keyValues[i + 1]);
        }
        return o;
    }

    private void exercise(ServiceProcess a, String name) throws Exception {
        assertThat(Http.get(a.baseUrl() + "/e2e/hello?name=" + name).status).isEqualTo(200);
        assertThat(Http.get(a.baseUrl() + "/e2e/traced").status).isEqualTo(200);
    }

    private static OtlpReceiver.ReceivedLog awaitLog(String body) {
        return Await.until(() -> receiver().logs().stream().filter(l -> body.equals(l.body)).findFirst().orElse(null),
                20_000, "exported log '" + body + "'; got " + receiver().logs());
    }

    private static OtlpReceiver.ReceivedSpan awaitSpan(String name) {
        return Await.until(() -> receiver().spans().stream().filter(s -> name.equals(s.name)).findFirst().orElse(null),
                20_000, "exported span " + name + "; got " + receiver().spans());
    }

    private static List<OtlpReceiver.ReceivedRequest> requests(OtlpReceiver.Signal signal) {
        return receiver().requests().stream().filter(r -> r.signal == signal).collect(Collectors.toList());
    }

    @Test
    void defaultsExportOverGrpcToTheLocalCollector() throws Exception {
        // No signoz.endpoint at all: the default, http://localhost:4317.
        ServiceProcess a = startSingle(exporting());
        exercise(a, "defaults");

        assertThat(awaitLog("hello defaults").service()).isEqualTo("e2e-a");
        assertThat(awaitSpan("e2e.traced").service()).isEqualTo("e2e-a");
        assertThat(requests(OtlpReceiver.Signal.LOGS)).allMatch(r -> "grpc".equals(r.transport));
        assertThat(requests(OtlpReceiver.Signal.TRACES)).allMatch(r -> "grpc".equals(r.transport));

        String summary = a.awaitLine(l -> l.contains("export: "), 5_000, "startup summary");
        assertThat(summary).contains("backend=signoz").contains("protocol=grpc");
        assertHealthy(a);
    }

    @Test
    void signozIngestionKeyIsSentButNeverPrinted() throws Exception {
        ServiceProcess a = startSingle(exporting("signoz.endpoint", "http://localhost:4317",
                "signoz.headers.signoz-ingestion-key", KEY));
        exercise(a, "keyed");

        awaitLog("hello keyed");
        awaitSpan("e2e.traced");
        assertThat(requests(OtlpReceiver.Signal.LOGS)).isNotEmpty().allMatch(r -> KEY.equals(r.headers.get("signoz-ingestion-key")));
        assertThat(requests(OtlpReceiver.Signal.TRACES)).isNotEmpty().allMatch(r -> KEY.equals(r.headers.get("signoz-ingestion-key")));
        assertThat(a.lines()).as("credentials never logged").noneMatch(l -> l.contains(KEY));
        assertHealthy(a);
    }

    @Test
    void otlpOverHttpWithPerSignalEndpoints() throws Exception {
        ServiceProcess a = startSingle(exporting(
                "signoz.exporter.backend", "otlp",
                "signoz.exporter.protocol", "http/protobuf",
                "signoz.exporter.logs-endpoint", "http://localhost:4318/loki/otlp/v1/logs",
                "signoz.exporter.traces-endpoint", "http://localhost:4318/tempo/v1/traces",
                "signoz.exporter.metrics-endpoint", "http://localhost:4318/mimir/otlp/v1/metrics"));
        exercise(a, "http");

        awaitLog("hello http");
        awaitSpan("e2e.traced");
        assertThat(requests(OtlpReceiver.Signal.LOGS)).isNotEmpty()
                .allMatch(r -> "http".equals(r.transport) && "/loki/otlp/v1/logs".equals(r.path));
        assertThat(requests(OtlpReceiver.Signal.TRACES)).isNotEmpty()
                .allMatch(r -> "http".equals(r.transport) && "/tempo/v1/traces".equals(r.path));
        assertHealthy(a);
    }

    @Test
    void grafanaCloudUsesBasicAuthAndNeverSendsTheSigNozKey() throws Exception {
        ServiceProcess a = startSingle(exporting(
                "signoz.endpoint", "http://localhost:4318/otlp",
                "signoz.exporter.backend", "grafana-cloud",
                "signoz.exporter.basic-auth.username", "123456",
                "signoz.exporter.basic-auth.password", GRAFANA_CANARY,
                // Left over from a shared config: must not reach Grafana.
                "signoz.headers.signoz-ingestion-key", KEY));
        exercise(a, "grafana");

        awaitLog("hello grafana");
        awaitSpan("e2e.traced");
        String basic = "Basic " + Base64.getEncoder().encodeToString(("123456:" + GRAFANA_CANARY).getBytes("UTF-8"));
        assertThat(requests(OtlpReceiver.Signal.LOGS)).isNotEmpty()
                .allMatch(r -> "/otlp/v1/logs".equals(r.path) && basic.equals(r.headers.get("authorization")));
        assertThat(requests(OtlpReceiver.Signal.TRACES)).isNotEmpty()
                .allMatch(r -> "/otlp/v1/traces".equals(r.path) && basic.equals(r.headers.get("authorization")));
        assertThat(receiver().requests()).noneMatch(r -> r.headers.containsKey("signoz-ingestion-key"));
        assertThat(a.lines()).anyMatch(l -> l.contains("signoz-ingestion-key"))   // the one-time warning
                .noneMatch(l -> l.contains(GRAFANA_CANARY) || l.contains(KEY));
        assertHealthy(a);
    }

    @Test
    void metricsAreExportedThroughMicrometer() throws Exception {
        ServiceProcess a = startSingle(exporting("signoz.headers.signoz-ingestion-key", KEY));
        for (int i = 0; i < 3; i++) {
            Http.get(a.baseUrl() + "/e2e/hello?name=m" + i);
        }
        Http.get(a.baseUrl() + "/e2e/timed?ms=10");
        Http.get(a.baseUrl() + "/e2e/fail");
        // The registry pushes every 60 seconds and once more on shutdown.
        a.stopGracefully();

        Set<String> names = Await.until(() -> {
            Set<String> found = receiver().metrics().stream().filter(m -> "e2e-a".equals(m.service()))
                    .map(m -> m.name).collect(Collectors.toSet());
            return found.contains("http.server.requests") ? found : null;
        }, 20_000, "metrics from e2e-a; got " + receiver().metrics());
        assertThat(names).contains("jvm.memory.used", "http.server.requests", "e2e.timed", "e2e.failures");
        assertThat(requests(OtlpReceiver.Signal.METRICS)).anyMatch(r -> "http".equals(r.transport) && "/v1/metrics".equals(r.path));
        // The starter's registry, not Spring Boot's: it carries the signoz.* resource attributes.
        assertThat(receiver().metrics()).filteredOn(m -> "e2e-a".equals(m.service()))
                .allMatch(m -> "9.9.9".equals(m.resource.get("service.version"))
                        && "e2e".equals(m.resource.get("deployment.environment")));
        if (bootVersion() >= 3) {
            assertThat(requests(OtlpReceiver.Signal.METRICS)).isNotEmpty()
                    .allMatch(r -> KEY.equals(r.headers.get("signoz-ingestion-key")));
        } else {
            // Micrometer 1.9 (Spring Boot 2.7) cannot send headers; the starter says so once.
            assertThat(a.lines()).anyMatch(l -> l.contains("cannot send headers") && l.contains("signoz-ingestion-key"));
        }
        assertThat(a.lines()).as("credentials never logged").noneMatch(l -> l.contains(KEY));
    }

    @Test
    void withoutTheOtlpRegistryNoMetricsAreSentAndTheAppIsFine() throws Exception {
        ServiceProcess a = startSingle(exporting().withoutJars("micrometer-registry-otlp"));
        exercise(a, "nometrics");
        Http.get(a.baseUrl() + "/e2e/timed?ms=10");
        assertThat(a.awaitJson(messageContains("[SigNoz] Timed e2e.timed completed"), 10_000, "timed log")).isNotNull();
        awaitLog("hello nometrics");
        assertHealthy(a);
        a.stopGracefully();
        Thread.sleep(1000);
        assertThat(receiver().metrics()).as("no Micrometer metrics without the registry")
                .noneMatch(m -> "http.server.requests".equals(m.name));
    }

    @Test
    void anUnreachableCollectorDoesNotAffectTheApplication() throws Exception {
        ServiceProcess a = startSingle(exporting("signoz.endpoint", "http://localhost:1"));

        long start = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            assertThat(Http.get(a.baseUrl() + "/e2e/hello?name=down" + i).body).isEqualTo("hello down" + i);
        }
        assertThat(System.currentTimeMillis() - start).as("requests are not slowed by export").isLessThan(10_000);
        Thread.sleep(3000);
        assertThat(a.isAlive()).isTrue();
        assertThat(Http.get(a.baseUrl() + "/e2e/ping").body).isEqualTo("pong");
    }
}
