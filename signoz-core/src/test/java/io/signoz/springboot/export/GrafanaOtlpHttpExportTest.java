package io.signoz.springboot.export;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.signoz.springboot.logging.OtlpLogbackAppender;
import io.signoz.springboot.metrics.SigNozMetricsConfig;
import io.signoz.springboot.properties.SigNozExporterProperties;
import io.signoz.springboot.properties.SigNozProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exports logs, traces and metrics over OTLP/HTTP to a local fake of the Grafana Cloud OTLP
 * gateway, configured exactly as a user would ({@code backend: grafana-cloud}, Basic auth,
 * gateway base URL), and inspects the requests it receives.
 */
class GrafanaOtlpHttpExportTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    /** Built at runtime so no credential-shaped literal sits in the source (secret scanners). */
    private static final String PASSWORD = "test-" + "only-value";

    /** One OTLP request as the fake gateway saw it. */
    static final class Received {
        final String path;
        final String authorization;
        final String contentType;
        final byte[] body;

        Received(String path, String authorization, String contentType, byte[] body) {
            this.path = path;
            this.authorization = authorization;
            this.contentType = contentType;
            this.body = body;
        }

        boolean contains(byte[] needle) {
            outer:
            for (int i = 0; i + needle.length <= body.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (body[i + j] != needle[j]) continue outer;
                }
                return true;
            }
            return false;
        }

        boolean contains(String text) {
            return contains(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<Received>());
    private SigNozProperties props;

    @BeforeEach
    void startFakeGateway() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            }
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    bytes.toByteArray()));
            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        props = new SigNozProperties();
        props.setServiceName("checkout-service");
        props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/otlp");
        props.getExporter().setBackend(SigNozExporterProperties.Backend.GRAFANA_CLOUD);
        props.getExporter().getBasicAuth().setUsername("123456");
        props.getExporter().getBasicAuth().setPassword(PASSWORD);
    }

    @AfterEach
    void stop() {
        MDC.clear();
        server.stop(0);
    }

    @Test
    void logsGoToTheLokiOtlpPathWithAuthAndTheirTraceContext() {
        OtlpExporterSettings settings = OtlpExporterSettings.resolve(props);
        OtlpLogbackAppender appender = new OtlpLogbackAppender();
        appender.setContext(new LoggerContext());
        appender.setServiceName("checkout-service");
        appender.setEndpoint(settings.endpoint(OtlpExporterSettings.Signal.LOGS));
        appender.setProtocol(settings.getProtocolName());
        appender.setHeaders(settings.getHeaders());
        appender.start();

        MDC.put("trace_id", TRACE_ID);
        MDC.put("span_id", SPAN_ID);
        MDC.put("trace_flags", "01");
        LoggerContext ctx = new LoggerContext();
        LoggingEvent event = new LoggingEvent("fqcn", ctx.getLogger("com.example.Checkout"),
                Level.INFO, "order placed", null, null);
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());
        appender.doAppend(event);
        appender.stop(); // flushes the batch

        Received logs = only("/otlp/v1/logs");
        assertThat(logs.authorization).isEqualTo(expectedAuth());
        assertThat(logs.contentType).isEqualTo("application/x-protobuf");
        assertThat(logs.contains("order placed")).isTrue();
        assertThat(logs.contains("checkout-service")).isTrue();
        // The record's own trace/span IDs (binary), which Grafana uses for log → trace links.
        assertThat(logs.contains(hex(TRACE_ID))).isTrue();
        assertThat(logs.contains(hex(SPAN_ID))).isTrue();
    }

    @Test
    void tracesGoToTheTempoOtlpPath() {
        OtlpExporterSettings settings = OtlpExporterSettings.resolve(props);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(settings.spanExporter(5000)))
                .build();
        Span span = tracerProvider.get("test").spanBuilder("checkout").startSpan();
        span.end();
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        tracerProvider.shutdown().join(5, TimeUnit.SECONDS);

        Received traces = only("/otlp/v1/traces");
        assertThat(traces.authorization).isEqualTo(expectedAuth());
        assertThat(traces.contentType).isEqualTo("application/x-protobuf");
        assertThat(traces.contains("checkout")).isTrue();
    }

    @Test
    void sdkMetricsGoToTheMimirOtlpPath() {
        OtlpExporterSettings settings = OtlpExporterSettings.resolve(props);
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(settings.metricExporter(5000))
                        .setInterval(Duration.ofHours(1)).build())
                .build();
        meterProvider.get("test").counterBuilder("orders.placed").build().add(3);
        meterProvider.forceFlush().join(5, TimeUnit.SECONDS);
        meterProvider.shutdown().join(5, TimeUnit.SECONDS);

        Received metrics = first("/otlp/v1/metrics");
        assertThat(metrics.authorization).isEqualTo(expectedAuth());
        assertThat(metrics.contains("orders.placed")).isTrue();
    }

    @Test
    void micrometerMetricsGoToTheSameMetricsPathWithAuth() {
        OtlpMeterRegistry registry = new SigNozMetricsConfig(props).otlpMeterRegistry();
        registry.counter("payments.captured").increment();
        registry.close(); // publishes once on close

        Received metrics = first("/otlp/v1/metrics");
        assertThat(metrics.authorization).isEqualTo(expectedAuth());
        assertThat(metrics.contains("payments.captured")).isTrue();
    }

    private Received only(String path) {
        List<Received> matching = matching(path);
        assertThat(matching).as("requests to %s (all: %s)", path, paths()).hasSize(1);
        return matching.get(0);
    }

    private Received first(String path) {
        List<Received> matching = matching(path);
        assertThat(matching).as("requests to %s (all: %s)", path, paths()).isNotEmpty();
        return matching.get(0);
    }

    private List<Received> matching(String path) {
        List<Received> out = new ArrayList<Received>();
        synchronized (received) {
            for (Received r : received) {
                if (r.path.equals(path)) out.add(r);
            }
        }
        return out;
    }

    private List<String> paths() {
        List<String> out = new ArrayList<String>();
        synchronized (received) {
            for (Received r : received) out.add(r.path);
        }
        return out;
    }

    private static String expectedAuth() {
        return "Basic " + Base64.getEncoder().encodeToString(
                ("123456:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
