package io.signoz.springboot.logging.log4j2;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.signoz.springboot.errors.ErrorFingerprint;
import io.signoz.springboot.logging.OtlpLogSupport;
import io.signoz.springboot.export.OtlpExporterSettings;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozExporterProperties;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Log4j2 counterpart of {@code OtlpLogbackAppender}: forwards log events to SigNoz, Grafana or
 * any OpenTelemetry receiver over OTLP ({@code grpc} by default, or {@code http/protobuf} via
 * {@link #setProtocol(String)}), masking bodies and context values through the
 * shared {@link OtlpLogSupport}, so export behaves identically on both backends.
 *
 * <p>Each event becomes an OTel log record with the event timestamp, mapped severity, the
 * masked formatted message as body, every thread-context entry as a masked {@code mdc.*}
 * attribute, {@code logger} and {@code thread}, and {@code errorId} for ERROR events with a
 * throwable when error fingerprinting is enabled.
 *
 * <p>Attached programmatically by {@link SigNozLog4j2Configurer}.
 */
public class OtlpLog4j2Appender extends AbstractAppender {

    private String endpoint = "http://localhost:4317";
    private String serviceName = "application";
    private String serviceVersion = "unknown";
    private String environment = "default";
    private long exportTimeoutMs = 5000L;
    private Map<String, String> headers = new HashMap<String, String>();
    private String protocol;
    private MaskingRegistry maskingRegistry;
    private int errorFingerprintDepth;

    private SdkLoggerProvider loggerProvider;
    private io.opentelemetry.api.logs.Logger otelLogger;

    private final OtlpLogSupport.ErrorSink errors = (message, error) -> LOGGER.error(message, error);

    public OtlpLog4j2Appender(String name) {
        super(name, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void start() {
        if (otelLogger == null) {
            try {
                loggerProvider = OtlpLogSupport.createLoggerProvider(
                        endpoint, headers,
                    OtlpExporterSettings.parseProtocol(protocol, SigNozExporterProperties.Backend.SIGNOZ),
                    exportTimeoutMs, serviceName, serviceVersion, environment);
                otelLogger = loggerProvider.get(serviceName);
            } catch (Exception e) {
                LOGGER.error("[SigNoz] Failed to initialize OTLP log appender: " + e.getMessage(), e);
                return;
            }
        }
        if (maskingRegistry == null) {
            LOGGER.warn("[SigNoz] OTLP Log4j2 appender started with no MaskingRegistry - "
                    + "log bodies will be exported UNMASKED.");
        }
        super.start();
    }

    @Override
    public void append(LogEvent event) {
        if (otelLogger == null) {
            return;
        }
        try {
            Level level = event.getLevel();
            String body = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            LogRecordBuilder builder = otelLogger.logRecordBuilder()
                    .setTimestamp(event.getTimeMillis(), TimeUnit.MILLISECONDS)
                    .setSeverity(toSeverity(level))
                    .setSeverityText(level == null ? "INFO" : level.name())
                    .setBody(OtlpLogSupport.maskBody(maskingRegistry, body, errors));

            Map<String, String> context = event.getContextData() == null
                    ? null : event.getContextData().toMap();
            if (context != null && !context.isEmpty()) {
                AttributesBuilder attrs = Attributes.builder();
                for (Map.Entry<String, String> entry : context.entrySet()) {
                    attrs.put("mdc." + entry.getKey(),
                            OtlpLogSupport.maskField(maskingRegistry, entry.getKey(), entry.getValue(), errors));
                }
                builder.setAllAttributes(attrs.build());
            }
            // Trace context from the event's MDC, so backends link the record to its trace.
            OtlpLogSupport.applyTraceContext(builder, context);

            builder.setAttribute(AttributeKey.stringKey("logger"), event.getLoggerName());
            builder.setAttribute(AttributeKey.stringKey("thread"), event.getThreadName());
            if (errorFingerprintDepth > 0 && Level.ERROR.equals(level) && event.getThrown() != null) {
                builder.setAttribute(AttributeKey.stringKey("errorId"),
                        ErrorFingerprint.generate(event.getThrown(), errorFingerprintDepth));
            }
            builder.emit();
        } catch (Exception e) {
            errors.error("[SigNoz] Error emitting log record", e);
        }
    }

    /**
     * Flushes and shuts down the exporter within the caller's {@code timeout}. Log4j2's no-arg
     * {@code stop()} passes {@code 0}; then the configured export timeout applies, so buffered
     * records are still flushed at shutdown rather than dropped.
     */
    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        if (loggerProvider != null) {
            try {
                long waitMs = timeout > 0 ? timeUnit.toMillis(timeout) : exportTimeoutMs;
                loggerProvider.shutdown().join(waitMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOGGER.warn("[SigNoz] Error shutting down OTLP log appender", e);
            }
        }
        return super.stop(timeout, timeUnit);
    }

    static Severity toSeverity(Level level) {
        if (level == null) return Severity.INFO;
        if (Level.FATAL.equals(level)) return Severity.FATAL;
        if (Level.ERROR.equals(level)) return Severity.ERROR;
        if (Level.WARN.equals(level)) return Severity.WARN;
        if (Level.INFO.equals(level)) return Severity.INFO;
        if (Level.DEBUG.equals(level)) return Severity.DEBUG;
        if (Level.TRACE.equals(level)) return Severity.TRACE;
        return Severity.INFO;
    }

    /** Test hook: emit through {@code otelLogger} instead of building a live gRPC exporter. */
    void useOtelLogger(io.opentelemetry.api.logs.Logger otelLogger) {
        this.otelLogger = otelLogger;
    }

    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public void setExportTimeoutMs(long exportTimeoutMs) { this.exportTimeoutMs = exportTimeoutMs; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    /** {@code grpc} (default) or {@code http/protobuf}. */
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public void setMaskingRegistry(MaskingRegistry maskingRegistry) { this.maskingRegistry = maskingRegistry; }
    public void setErrorFingerprintDepth(int errorFingerprintDepth) { this.errorFingerprintDepth = errorFingerprintDepth; }
}
