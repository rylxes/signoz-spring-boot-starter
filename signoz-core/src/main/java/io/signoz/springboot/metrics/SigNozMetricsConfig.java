package io.signoz.springboot.metrics;

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.registry.otlp.OtlpConfig;
import io.signoz.springboot.export.OtlpExporterSettings;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.signoz.springboot.properties.SigNozProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the Micrometer OTLP registry that exports metrics to {@code signoz.endpoint}.
 *
 * <p>The registry:
 * <ul>
 *   <li>sends to the metrics endpoint resolved from the {@code signoz.*} exporter settings,
 *       with their headers (the SigNoz ingestion key, or Grafana Cloud's Basic auth)</li>
 *   <li>binds JVM metrics: memory, GC, threads, classes, CPU, and process uptime</li>
 *   <li>carries {@code service.name}, {@code service.version} and
 *       {@code deployment.environment} as resource attributes</li>
 * </ul>
 *
 * <p>This is a plain factory, not a configuration class: the Boot modules'
 * {@code SigNozMetricsAutoConfiguration} registers {@link #otlpMeterRegistry()} as a bean, and
 * Actuator then adds HTTP server metrics to it.
 */
public class SigNozMetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(SigNozMetricsConfig.class);

    private final SigNozProperties props;

    public SigNozMetricsConfig(SigNozProperties props) {
        this.props = props;
    }

    public OtlpMeterRegistry otlpMeterRegistry() {
        final OtlpExporterSettings exportSettings = OtlpExporterSettings.resolve(props);
        if (!exportSettings.getHeaders().isEmpty() && !registrySendsHeaders()) {
            // Header names only: their values are credentials.
            log.warn("[SigNoz] micrometer-registry-otlp {} cannot send headers, so metrics go to {} without {}. "
                    + "A collector that requires them will reject the metrics. Upgrade "
                    + "micrometer-registry-otlp to 1.11 or later, or send metrics through a local collector.",
                    OtlpMeterRegistry.class.getPackage().getImplementationVersion(),
                    exportSettings.micrometerMetricsUrl(), exportSettings.getHeaders().keySet());
        }
        OtlpConfig config = new OtlpConfig() {
            @Override
            public String get(String key) {
                return null; // Use method overrides below
            }

            @Override
            public String url() {
                // Micrometer's registry speaks OTLP over HTTP only; see
                // OtlpExporterSettings.micrometerMetricsUrl() for how the URL is derived.
                return exportSettings.micrometerMetricsUrl();
            }

            @Override
            public Duration step() {
                return Duration.ofSeconds(60);
            }

            @Override
            public Map<String, String> resourceAttributes() {
                Map<String, String> attrs = new HashMap<String, String>();
                attrs.put("service.name", props.getServiceName());
                attrs.put("service.version", props.getServiceVersion());
                attrs.put("deployment.environment", props.getEnvironment());
                return attrs;
            }

            @Override
            public Map<String, String> headers() {
                return exportSettings.getHeaders();
            }
        };

        OtlpMeterRegistry registry = new OtlpMeterRegistry(config,
                io.micrometer.core.instrument.Clock.SYSTEM);

        // Bind JVM metrics
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);

        return registry;
    }

    /** {@code OtlpConfig.headers()} arrived in Micrometer 1.11; Spring Boot 2.7 ships 1.9. */
    static boolean registrySendsHeaders() {
        try {
            OtlpConfig.class.getMethod("headers");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
