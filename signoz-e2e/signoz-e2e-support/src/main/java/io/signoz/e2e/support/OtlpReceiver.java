package io.signoz.e2e.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/**
 * An in-process OTLP collector stand-in. It accepts logs, traces and metrics over OTLP/gRPC and
 * OTLP/HTTP (protobuf), records the request headers, and keeps what it received as flattened
 * records the tests can assert on. Nothing is forwarded anywhere.
 */
public final class OtlpReceiver implements AutoCloseable {

    /** The three OTLP signals. */
    public enum Signal { LOGS, TRACES, METRICS }

    private static final MethodDescriptor.Marshaller<byte[]> BYTES = new MethodDescriptor.Marshaller<byte[]>() {
        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return readAll(stream);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    private final List<ReceivedLog> logs = new CopyOnWriteArrayList<ReceivedLog>();
    private final List<ReceivedSpan> spans = new CopyOnWriteArrayList<ReceivedSpan>();
    private final List<ReceivedMetric> metrics = new CopyOnWriteArrayList<ReceivedMetric>();
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<ReceivedRequest>();

    private final Server grpcServer;
    private final HttpServer httpServer;

    /** The ports a SigNoz or OpenTelemetry collector listens on: OTLP/gRPC and OTLP/HTTP. */
    public static final int DEFAULT_GRPC_PORT = 4317;
    public static final int DEFAULT_HTTP_PORT = 4318;

    private OtlpReceiver(int grpcPort, int httpPort) throws IOException {
        ServerInterceptor headerRecorder = new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                               ServerCallHandler<Q, R> next) {
                Map<String, String> copy = new LinkedHashMap<String, String>();
                for (String key : headers.keys()) {
                    if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                        copy.put(key.toLowerCase(Locale.ROOT),
                                headers.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)));
                    }
                }
                requests.add(new ReceivedRequest(signalOfService(call.getMethodDescriptor().getServiceName()),
                        "grpc", call.getMethodDescriptor().getFullMethodName(), copy));
                return next.startCall(call, headers);
            }
        };
        grpcServer = Grpc.newServerBuilderForPort(grpcPort, InsecureServerCredentials.create())
                .addService(ServerInterceptors.intercept(service("opentelemetry.proto.collector.logs.v1.LogsService", Signal.LOGS), headerRecorder))
                .addService(ServerInterceptors.intercept(service("opentelemetry.proto.collector.trace.v1.TraceService", Signal.TRACES), headerRecorder))
                .addService(ServerInterceptors.intercept(service("opentelemetry.proto.collector.metrics.v1.MetricsService", Signal.METRICS), headerRecorder))
                .build()
                .start();

        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", this::handleHttp);
        httpServer.start();
    }

    /**
     * Starts a receiver on the collector's standard ports, 4317 (gRPC) and 4318 (HTTP), so the
     * starter's defaults, including the Micrometer registry's 4317 → 4318 derivation, are what
     * gets tested.
     */
    public static OtlpReceiver start() {
        try {
            return new OtlpReceiver(DEFAULT_GRPC_PORT, DEFAULT_HTTP_PORT);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the OTLP receiver on ports 4317/4318;"
                    + " is a collector already running?", e);
        }
    }

    /** OTLP/gRPC endpoint, for {@code signoz.endpoint}. */
    public String grpcEndpoint() {
        return "http://localhost:" + grpcServer.getPort();
    }

    /** OTLP/HTTP base URL; signals are accepted on any path ending in {@code /v1/logs} etc. */
    public String httpBase() {
        return "http://localhost:" + httpServer.getAddress().getPort();
    }

    public List<ReceivedLog> logs() {
        return Collections.unmodifiableList(new ArrayList<ReceivedLog>(logs));
    }

    public List<ReceivedSpan> spans() {
        return Collections.unmodifiableList(new ArrayList<ReceivedSpan>(spans));
    }

    public List<ReceivedMetric> metrics() {
        return Collections.unmodifiableList(new ArrayList<ReceivedMetric>(metrics));
    }

    public List<ReceivedRequest> requests() {
        return Collections.unmodifiableList(new ArrayList<ReceivedRequest>(requests));
    }

    /** Drops everything received so far. */
    public void clear() {
        logs.clear();
        spans.clear();
        metrics.clear();
        requests.clear();
    }

    @Override
    public void close() {
        grpcServer.shutdownNow();
        httpServer.stop(0);
    }

    // --- transport ---

    private ServerServiceDefinition service(String serviceName, final Signal signal) {
        MethodDescriptor<byte[], byte[]> export = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, "Export"))
                .setRequestMarshaller(BYTES)
                .setResponseMarshaller(BYTES)
                .build();
        return ServerServiceDefinition.builder(serviceName)
                .addMethod(export, ServerCalls.asyncUnaryCall(
                        (ServerCalls.UnaryMethod<byte[], byte[]>) (request, observer) -> {
                            try {
                                accept(signal, request, "grpc");
                            } catch (Exception e) {
                                observer.onError(e);
                                return;
                            }
                            observer.onNext(new byte[0]);   // an empty Export*ServiceResponse
                            observer.onCompleted();
                        }))
                .build();
    }

    private void handleHttp(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            Signal signal = path.endsWith("/v1/logs") ? Signal.LOGS
                    : path.endsWith("/v1/traces") ? Signal.TRACES
                    : path.endsWith("/v1/metrics") ? Signal.METRICS : null;
            if (signal == null || !"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Map<String, String> headers = new LinkedHashMap<String, String>();
            for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
                headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().isEmpty() ? "" : e.getValue().get(0));
            }
            requests.add(new ReceivedRequest(signal, "http", path, headers));
            InputStream body = exchange.getRequestBody();
            if ("gzip".equalsIgnoreCase(headers.get("content-encoding"))) {
                body = new GZIPInputStream(body);
            }
            accept(signal, readAll(body), "http");
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.close();
        } catch (Exception e) {
            exchange.sendResponseHeaders(400, -1);
        } finally {
            exchange.close();
        }
    }

    // --- decoding ---

    private void accept(Signal signal, byte[] payload, String transport) throws IOException {
        switch (signal) {
            case LOGS:
                for (ResourceLogs rl : ExportLogsServiceRequest.parseFrom(payload).getResourceLogsList()) {
                    Map<String, String> resource = attributes(rl.getResource().getAttributesList());
                    for (ScopeLogs sl : rl.getScopeLogsList()) {
                        for (LogRecord r : sl.getLogRecordsList()) {
                            logs.add(new ReceivedLog(transport, resource, r.getSeverityText(), text(r.getBody()),
                                    hex(r.getTraceId().toByteArray()), hex(r.getSpanId().toByteArray()),
                                    attributes(r.getAttributesList())));
                        }
                    }
                }
                break;
            case TRACES:
                for (ResourceSpans rs : ExportTraceServiceRequest.parseFrom(payload).getResourceSpansList()) {
                    Map<String, String> resource = attributes(rs.getResource().getAttributesList());
                    for (ScopeSpans ss : rs.getScopeSpansList()) {
                        for (Span s : ss.getSpansList()) {
                            spans.add(new ReceivedSpan(resource, s.getName(), hex(s.getTraceId().toByteArray()),
                                    hex(s.getSpanId().toByteArray()), hex(s.getParentSpanId().toByteArray()),
                                    attributes(s.getAttributesList())));
                        }
                    }
                }
                break;
            default:
                for (ResourceMetrics rm : ExportMetricsServiceRequest.parseFrom(payload).getResourceMetricsList()) {
                    Map<String, String> resource = attributes(rm.getResource().getAttributesList());
                    for (ScopeMetrics sm : rm.getScopeMetricsList()) {
                        for (Metric m : sm.getMetricsList()) {
                            metrics.add(new ReceivedMetric(resource, m.getName()));
                        }
                    }
                }
        }
    }

    private static Signal signalOfService(String service) {
        if (service.contains(".logs.")) {
            return Signal.LOGS;
        }
        return service.contains(".trace.") ? Signal.TRACES : Signal.METRICS;
    }

    private static Map<String, String> attributes(List<KeyValue> kvs) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (KeyValue kv : kvs) {
            out.put(kv.getKey(), text(kv.getValue()));
        }
        return out;
    }

    private static String text(AnyValue v) {
        switch (v.getValueCase()) {
            case STRING_VALUE: return v.getStringValue();
            case BOOL_VALUE: return String.valueOf(v.getBoolValue());
            case INT_VALUE: return String.valueOf(v.getIntValue());
            case DOUBLE_VALUE: return String.valueOf(v.getDoubleValue());
            case VALUE_NOT_SET: return null;
            default: return v.toString();
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // --- received records ---

    /** One exported log record. */
    public static final class ReceivedLog {
        /** {@code grpc} or {@code http}: how the record arrived. */
        public final String transport;
        public final Map<String, String> resource;
        public final String severity;
        public final String body;
        public final String traceId;
        public final String spanId;
        public final Map<String, String> attributes;

        ReceivedLog(String transport, Map<String, String> resource, String severity, String body, String traceId,
                    String spanId, Map<String, String> attributes) {
            this.transport = transport;
            this.resource = resource;
            this.severity = severity;
            this.body = body;
            this.traceId = traceId;
            this.spanId = spanId;
            this.attributes = attributes;
        }

        public String service() {
            return resource.get("service.name");
        }

        @Override
        public String toString() {
            return "log[" + transport + " " + service() + " " + severity + " trace=" + traceId + " body=" + body + " attrs=" + attributes + "]";
        }
    }

    /** One exported span. */
    public static final class ReceivedSpan {
        public final Map<String, String> resource;
        public final String name;
        public final String traceId;
        public final String spanId;
        public final String parentSpanId;
        public final Map<String, String> attributes;

        ReceivedSpan(Map<String, String> resource, String name, String traceId, String spanId, String parentSpanId,
                     Map<String, String> attributes) {
            this.resource = resource;
            this.name = name;
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.attributes = attributes;
        }

        public String service() {
            return resource.get("service.name");
        }

        @Override
        public String toString() {
            return "span[" + service() + " " + name + " trace=" + traceId + " attrs=" + attributes + "]";
        }
    }

    /** One exported metric (name only; values are not needed by the tests). */
    public static final class ReceivedMetric {
        public final Map<String, String> resource;
        public final String name;

        ReceivedMetric(Map<String, String> resource, String name) {
            this.resource = resource;
            this.name = name;
        }

        public String service() {
            return resource.get("service.name");
        }

        @Override
        public String toString() {
            return "metric[" + service() + " " + name + "]";
        }
    }

    /** One export request and its headers. */
    public static final class ReceivedRequest {
        public final Signal signal;
        public final String transport;
        public final String path;
        public final Map<String, String> headers;

        ReceivedRequest(Signal signal, String transport, String path, Map<String, String> headers) {
            this.signal = signal;
            this.transport = transport;
            this.path = path;
            this.headers = headers;
        }

        @Override
        public String toString() {
            return transport + " " + signal + " " + path + " " + headers;
        }
    }
}
