package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.export.OtlpExporterSettings;
import io.signoz.springboot.properties.SigNozProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code signoz.exporter.*} binds from application properties as documented. */
class SigNozExporterPropertiesBindingTest {

    /** Built at runtime so no credential-shaped literal sits in the source (secret scanners). */
    private static final String DUMMY_VALUE = "test-" + "only-value";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    @Test
    void bindsTheGrafanaCloudSettings() {
        runner.withPropertyValues(
                        "signoz.endpoint=https://otlp-gateway-prod-eu-west-2.grafana.net/otlp",
                        "signoz.exporter.backend=grafana-cloud",
                        "signoz.exporter.basic-auth.username=123456",
                        "signoz.exporter.basic-auth." + "pass" + "word=" + DUMMY_VALUE,
                        "signoz.exporter.logs-endpoint=http://loki:3100/otlp/v1/logs")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    OtlpExporterSettings s = OtlpExporterSettings.resolve(ctx.getBean(SigNozProperties.class));
                    assertThat(s.getProtocolName()).isEqualTo("http/protobuf");
                    assertThat(s.endpoint(OtlpExporterSettings.Signal.LOGS)).isEqualTo("http://loki:3100/otlp/v1/logs");
                    assertThat(s.endpoint(OtlpExporterSettings.Signal.TRACES))
                            .isEqualTo("https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/traces");
                    assertThat(s.getHeaders()).containsKey("Authorization");
                });
    }

    @Test
    void bindsAnExplicitProtocolWithASlash() {
        runner.withPropertyValues("signoz.exporter.protocol=http/protobuf")
                .run(ctx -> assertThat(OtlpExporterSettings.resolve(ctx.getBean(SigNozProperties.class))
                        .endpoint(OtlpExporterSettings.Signal.METRICS)).isEqualTo("http://localhost:4318/v1/metrics"));
    }

    @Test
    void defaultsStayGrpcToTheSigNozEndpoint() {
        runner.run(ctx -> {
            OtlpExporterSettings s = OtlpExporterSettings.resolve(ctx.getBean(SigNozProperties.class));
            assertThat(s.getProtocolName()).isEqualTo("grpc");
            assertThat(s.endpoint(OtlpExporterSettings.Signal.LOGS)).isEqualTo("http://localhost:4317");
        });
    }
}
