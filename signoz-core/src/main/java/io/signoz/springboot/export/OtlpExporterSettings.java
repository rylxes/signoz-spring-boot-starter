package io.signoz.springboot.export;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.signoz.springboot.properties.SigNozExporterProperties;
import io.signoz.springboot.properties.SigNozProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves {@code signoz.endpoint}, {@code signoz.headers} and {@code signoz.exporter.*} into
 * the concrete OTLP settings every exporter uses (logs, traces, SDK metrics, Micrometer), so
 * all signals agree on protocol, URLs and credentials.
 *
 * <ul>
 *   <li><b>Protocol</b>: {@code signoz.exporter.protocol}, else {@code http/protobuf} for
 *       {@code grafana-cloud}, else {@code grpc}.</li>
 *   <li><b>Endpoints</b>: a per-signal {@code *-endpoint} wins. Otherwise, over HTTP the
 *       standard OTLP path is appended to {@code signoz.endpoint} ({@code /v1/logs},
 *       {@code /v1/traces}, {@code /v1/metrics}); over gRPC the endpoint is used as is. When
 *       {@code signoz.endpoint} is left at its gRPC default ({@code http://localhost:4317})
 *       and the protocol is HTTP, the OTLP HTTP default port 4318 is used instead.</li>
 *   <li><b>Headers</b>: {@code signoz.headers}, plus {@code Authorization: Basic …} built from
 *       {@code signoz.exporter.basic-auth} unless an {@code Authorization} header is already
 *       configured. For a backend other than {@code signoz}, the SigNoz-only
 *       {@code signoz-ingestion-key} header is dropped (with a one-time warning), so an
 *       environment that switches backend by profile never leaks the SigNoz key.</li>
 * </ul>
 */
public final class OtlpExporterSettings {

    public enum Protocol { GRPC, HTTP_PROTOBUF }

    public enum Signal {
        LOGS("logs"), TRACES("traces"), METRICS("metrics");

        final String path;

        Signal(String path) {
            this.path = path;
        }
    }

    static final String DEFAULT_GRPC_ENDPOINT = "http://localhost:4317";
    /** SigNoz Cloud's ingestion header; meaningless (and a credential leak) for other backends. */
    static final String SIGNOZ_KEY_HEADER = "signoz-ingestion-key";

    private static final Logger log = LoggerFactory.getLogger(OtlpExporterSettings.class);
    private static final AtomicBoolean WARNED_DROPPED_SIGNOZ_KEY = new AtomicBoolean();
    static final String DEFAULT_HTTP_ENDPOINT = "http://localhost:4318";

    private final Protocol protocol;
    private final SigNozExporterProperties.Backend backend;
    private final String baseEndpoint;
    private final String logsEndpoint;
    private final String tracesEndpoint;
    private final String metricsEndpoint;
    private final Map<String, String> headers;

    private OtlpExporterSettings(Protocol protocol, SigNozExporterProperties.Backend backend,
                                 String baseEndpoint, SigNozExporterProperties exporter,
                                 Map<String, String> headers) {
        this.protocol = protocol;
        this.backend = backend;
        this.baseEndpoint = baseEndpoint;
        this.logsEndpoint = signalEndpoint(Signal.LOGS, exporter.getLogsEndpoint());
        this.tracesEndpoint = signalEndpoint(Signal.TRACES, exporter.getTracesEndpoint());
        this.metricsEndpoint = signalEndpoint(Signal.METRICS, exporter.getMetricsEndpoint());
        this.headers = Collections.unmodifiableMap(headers);
    }

    public static OtlpExporterSettings resolve(SigNozProperties props) {
        SigNozExporterProperties exporter = props.getExporter() != null
                ? props.getExporter() : new SigNozExporterProperties();
        SigNozExporterProperties.Backend backend = exporter.getBackend() != null
                ? exporter.getBackend() : SigNozExporterProperties.Backend.SIGNOZ;

        Protocol protocol = parseProtocol(exporter.getProtocol(), backend);

        String base = blankToNull(props.getEndpoint());
        if (base == null) {
            base = DEFAULT_GRPC_ENDPOINT;
        }
        if (protocol == Protocol.HTTP_PROTOBUF && stripSlash(base).equals(DEFAULT_GRPC_ENDPOINT)) {
            base = DEFAULT_HTTP_ENDPOINT;
        }

        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (props.getHeaders() != null) {
            headers.putAll(props.getHeaders());
        }
        // signoz.headers usually lives in the shared application.yml, and Spring merges map
        // properties across profiles, so a profile that switches to another backend would
        // otherwise send the SigNoz ingestion key to it (e.g. dev on Grafana, prod on SigNoz).
        if (backend != SigNozExporterProperties.Backend.SIGNOZ && removeIgnoreCase(headers, SIGNOZ_KEY_HEADER)
                && WARNED_DROPPED_SIGNOZ_KEY.compareAndSet(false, true)) {
            log.warn("[SigNoz] Not sending the '{}' header: signoz.exporter.backend is {}, not signoz. "
                    + "Move signoz.headers into the profile that exports to SigNoz.", SIGNOZ_KEY_HEADER,
                    backend.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        SigNozExporterProperties.BasicAuth auth = exporter.getBasicAuth();
        if (auth != null && blankToNull(auth.getUsername()) != null && !hasAuthorization(headers)) {
            String credentials = auth.getUsername() + ":" + (auth.getPassword() == null ? "" : auth.getPassword());
            headers.put("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }

        return new OtlpExporterSettings(protocol, backend, base, exporter, headers);
    }

    /** {@code grpc}, {@code http/protobuf}, {@code http} (case-insensitive); unset → backend default. */
    public static Protocol parseProtocol(String value, SigNozExporterProperties.Backend backend) {
        String v = blankToNull(value);
        if (v == null) {
            return backend == SigNozExporterProperties.Backend.GRAFANA_CLOUD
                    ? Protocol.HTTP_PROTOBUF : Protocol.GRPC;
        }
        String normalized = v.trim().toLowerCase(Locale.ROOT).replace('_', '/').replace('-', '/');
        if ("grpc".equals(normalized)) {
            return Protocol.GRPC;
        }
        if ("http".equals(normalized) || "http/protobuf".equals(normalized)) {
            return Protocol.HTTP_PROTOBUF;
        }
        throw new IllegalArgumentException("Unsupported signoz.exporter.protocol '" + value
                + "': use grpc or http/protobuf");
    }

    private String signalEndpoint(Signal signal, String explicit) {
        String e = blankToNull(explicit);
        if (e != null) {
            return e.trim();
        }
        return protocol == Protocol.HTTP_PROTOBUF
                ? stripSlash(baseEndpoint) + "/v1/" + signal.path
                : baseEndpoint;
    }

    public Protocol getProtocol() { return protocol; }

    /** {@code grpc} or {@code http/protobuf}, as accepted by the appenders' {@code protocol} setting. */
    public String getProtocolName() {
        return protocol == Protocol.GRPC ? "grpc" : "http/protobuf";
    }

    public SigNozExporterProperties.Backend getBackend() { return backend; }

    public String getBaseEndpoint() { return baseEndpoint; }

    public String endpoint(Signal signal) {
        switch (signal) {
            case LOGS: return logsEndpoint;
            case TRACES: return tracesEndpoint;
            default: return metricsEndpoint;
        }
    }

    /** All export headers, including the Basic {@code Authorization} header when configured. */
    public Map<String, String> getHeaders() { return headers; }

    /**
     * The URL for Micrometer's OTLP registry, which only speaks OTLP over HTTP. Over HTTP it is
     * the metrics endpoint. Over gRPC it keeps the previous derivation from
     * {@code signoz.endpoint}: port 4317 becomes 4318 and {@code /v1/metrics} is appended.
     */
    public String micrometerMetricsUrl() {
        if (protocol == Protocol.HTTP_PROTOBUF) {
            return metricsEndpoint;
        }
        String endpoint = metricsEndpoint;
        if (endpoint.contains(":4317")) {
            endpoint = endpoint.replace(":4317", ":4318");
        }
        return stripSlash(endpoint) + "/v1/metrics";
    }

    public LogRecordExporter logExporter(long timeoutMs) {
        return logExporter(protocol, endpoint(Signal.LOGS), headers, timeoutMs);
    }

    public SpanExporter spanExporter(long timeoutMs) {
        return spanExporter(protocol, endpoint(Signal.TRACES), headers, timeoutMs);
    }

    public MetricExporter metricExporter(long timeoutMs) {
        return metricExporter(protocol, endpoint(Signal.METRICS), headers, timeoutMs);
    }

    /** OTLP log exporter for {@code protocol}; {@code endpoint} is used exactly as given. */
    public static LogRecordExporter logExporter(Protocol protocol, String endpoint,
                                                Map<String, String> headers, long timeoutMs) {
        Map<String, String> h = headers == null ? Collections.<String, String>emptyMap() : headers;
        if (protocol == Protocol.HTTP_PROTOBUF) {
            OtlpHttpLogRecordExporterBuilder b = OtlpHttpLogRecordExporter.builder()
                    .setEndpoint(endpoint).setTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            for (Map.Entry<String, String> e : h.entrySet()) b.addHeader(e.getKey(), e.getValue());
            return b.build();
        }
        OtlpGrpcLogRecordExporterBuilder b = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(endpoint).setTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        for (Map.Entry<String, String> e : h.entrySet()) b.addHeader(e.getKey(), e.getValue());
        return b.build();
    }

    /** OTLP span exporter for {@code protocol}; {@code endpoint} is used exactly as given. */
    public static SpanExporter spanExporter(Protocol protocol, String endpoint,
                                            Map<String, String> headers, long timeoutMs) {
        Map<String, String> h = headers == null ? Collections.<String, String>emptyMap() : headers;
        if (protocol == Protocol.HTTP_PROTOBUF) {
            OtlpHttpSpanExporterBuilder b = OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint).setTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            for (Map.Entry<String, String> e : h.entrySet()) b.addHeader(e.getKey(), e.getValue());
            return b.build();
        }
        OtlpGrpcSpanExporterBuilder b = OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint).setTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        for (Map.Entry<String, String> e : h.entrySet()) b.addHeader(e.getKey(), e.getValue());
        return b.build();
    }

    /** OTLP metric exporter for {@code protocol}; {@code endpoint} is used exactly as given. */
    public static MetricExporter metricExporter(Protocol protocol, String endpoint,
                                                Map<String, String> headers, long timeoutMs) {
        Map<String, String> h = headers == null ? Collections.<String, String>emptyMap() : headers;
        if (protocol == Protocol.HTTP_PROTOBUF) {
            OtlpHttpMetricExporterBuilder b = OtlpHttpMetricExporter.builder()
                    .setEndpoint(endpoint).setTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            for (Map.Entry<String, String> e : h.entrySet()) b.addHeader(e.getKey(), e.getValue());
            return b.build();
        }
        OtlpGrpcMetricExporterBuilder b = OtlpGrpcMetricExporter.builder()
                .setEndpoint(endpoint).setTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        for (Map.Entry<String, String> e : h.entrySet()) b.addHeader(e.getKey(), e.getValue());
        return b.build();
    }

    /** One line for startup diagnostics; never includes header values. */
    public String describe() {
        return "backend=" + backend.name().toLowerCase(Locale.ROOT).replace('_', '-')
                + ", protocol=" + getProtocolName()
                + ", logs=" + logsEndpoint + ", traces=" + tracesEndpoint + ", metrics=" + metricsEndpoint
                + (hasAuthorization(headers) ? ", auth=yes" : "");
    }

    private static boolean removeIgnoreCase(Map<String, String> headers, String name) {
        boolean removed = false;
        for (java.util.Iterator<String> it = headers.keySet().iterator(); it.hasNext(); ) {
            if (name.equalsIgnoreCase(it.next())) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    private static boolean hasAuthorization(Map<String, String> headers) {
        for (String key : headers.keySet()) {
            if ("authorization".equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static String stripSlash(String s) {
        String r = s.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /** Trimmed value, or {@code null} when blank, so stray whitespace never reaches an exporter. */
    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
