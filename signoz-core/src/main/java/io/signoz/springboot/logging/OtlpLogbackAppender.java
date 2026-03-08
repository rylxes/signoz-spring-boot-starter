package io.signoz.springboot.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ResourceAttributes;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Logback {@link AppenderBase} implementation that forwards log records to a
 * SigNoz (or any OpenTelemetry-compatible) collector via OTLP gRPC.
 *
 * <p>Configured programmatically by {@code SigNozLoggingAutoConfiguration} using
 * values from {@code SigNozProperties}. Can also be configured directly in
 * {@code logback-spring.xml} for teams that prefer XML configuration:
 *
 * <pre>{@code
 * <appender name="OTLP" class="io.signoz.springboot.logging.OtlpLogbackAppender">
 *     <endpoint>http://localhost:4317</endpoint>
 *     <serviceName>my-app</serviceName>
 *     <serviceVersion>1.0.0</serviceVersion>
 *     <environment>production</environment>
 * </appender>
 * }</pre>
 *
 * <p>Each Logback log event is converted to an OpenTelemetry {@link io.opentelemetry.api.logs.LogRecord}
 * with:
 * <ul>
 *   <li>Timestamp from the event</li>
 *   <li>Severity mapped from Logback level</li>
 *   <li>Body set to the formatted message</li>
 *   <li>All MDC fields added as log attributes (prefixed with {@code mdc.})</li>
 * </ul>
 */
public class OtlpLogbackAppender extends AppenderBase<ILoggingEvent> {

    private String endpoint = "http://localhost:4317";
    private String serviceName = "application";
    private String serviceVersion = "unknown";
    private String environment = "default";
    private long exportTimeoutMs = 5000L;

    private SdkLoggerProvider loggerProvider;
    private io.opentelemetry.api.logs.Logger otelLogger;

    // --- Logback lifecycle ---

    @Override
    public void start() {
        try {
            OtlpGrpcLogRecordExporter exporter = OtlpGrpcLogRecordExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(exportTimeoutMs, TimeUnit.MILLISECONDS)
                    .build();

            Resource resource = Resource.getDefault().merge(
                    Resource.create(Attributes.of(
                            ResourceAttributes.SERVICE_NAME, serviceName,
                            ResourceAttributes.SERVICE_VERSION, serviceVersion,
                            AttributeKey.stringKey("deployment.environment"), environment
                    )));

            this.loggerProvider = SdkLoggerProvider.builder()
                    .setResource(resource)
                    .addLogRecordProcessor(
                            BatchLogRecordProcessor.builder(exporter).build())
                    .build();

            this.otelLogger = loggerProvider.get(serviceName);
            super.start();
        } catch (Exception e) {
            addError("[SigNoz] Failed to initialize OTLP log appender: " + e.getMessage(), e);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted() || otelLogger == null) {
            return;
        }

        try {
            LogRecordBuilder builder = otelLogger.logRecordBuilder()
                    .setTimestamp(event.getTimeStamp(), TimeUnit.MILLISECONDS)
                    .setSeverity(toSeverity(event.getLevel()))
                    .setSeverityText(event.getLevel().toString())
                    .setBody(event.getFormattedMessage());

            // Attach MDC fields as attributes
            Map<String, String> mdc = event.getMDCPropertyMap();
            if (mdc != null && !mdc.isEmpty()) {
                AttributesBuilder attrs = Attributes.builder();
                for (Map.Entry<String, String> entry : mdc.entrySet()) {
                    attrs.put("mdc." + entry.getKey(), entry.getValue());
                }
                builder.setAllAttributes(attrs.build());
            }

            // Attach logger name
            builder.setAttribute(AttributeKey.stringKey("logger"), event.getLoggerName());
            builder.setAttribute(AttributeKey.stringKey("thread"), event.getThreadName());

            builder.emit();
        } catch (Exception e) {
            addError("[SigNoz] Error emitting log record", e);
        }
    }

    @Override
    public void stop() {
        if (loggerProvider != null) {
            try {
                loggerProvider.shutdown().join(exportTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                addWarn("[SigNoz] Error shutting down OTLP log appender", e);
            }
        }
        super.stop();
    }

    // --- Level mapping ---

    private static Severity toSeverity(Level level) {
        if (level == null) return Severity.INFO;
        switch (level.toInt()) {
            case Level.TRACE_INT:  return Severity.TRACE;
            case Level.DEBUG_INT:  return Severity.DEBUG;
            case Level.INFO_INT:   return Severity.INFO;
            case Level.WARN_INT:   return Severity.WARN;
            case Level.ERROR_INT:  return Severity.ERROR;
            default:               return Severity.INFO;
        }
    }

    // --- Setters (used by XML config and auto-config) ---

    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getEndpoint() { return endpoint; }

    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getServiceName() { return serviceName; }

    public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
    public String getServiceVersion() { return serviceVersion; }

    public void setEnvironment(String environment) { this.environment = environment; }
    public String getEnvironment() { return environment; }

    public void setExportTimeoutMs(long exportTimeoutMs) { this.exportTimeoutMs = exportTimeoutMs; }
    public long getExportTimeoutMs() { return exportTimeoutMs; }
}
