package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.TestSigNozApplication;
import io.signoz.springboot.export.OtlpExporterSettings;
import io.signoz.springboot.properties.SigNozProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static io.signoz.springboot.export.OtlpExporterSettings.Signal.LOGS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One application, backend chosen per environment with Spring profiles: a shared profile
 * carrying the SigNoz ingestion header, plus a SigNoz ("prod") or Grafana Cloud ("dev") profile.
 */
class PerEnvironmentBackendTest {

    /** Built at runtime and injected as placeholders, as real credentials would be. */
    private static final String SIGNOZ_KEY = "signoz-" + "test-value";
    private static final String GRAFANA_TOKEN = "grafana-" + "test-value";

    @Test
    public void prodProfileExportsToSigNozOverGrpcWithTheIngestionKey() {
        try (ConfigurableApplicationContext ctx = start("envtest", "envtest-signoz")) {
            OtlpExporterSettings s = OtlpExporterSettings.resolve(ctx.getBean(SigNozProperties.class));
            assertThat(s.getProtocolName()).isEqualTo("grpc");
            assertThat(s.endpoint(LOGS)).isEqualTo("https://ingest.eu.signoz.cloud:443");
            assertThat(s.getHeaders()).containsEntry("signoz-ingestion-key", SIGNOZ_KEY)
                    .doesNotContainKey("Authorization");
        }
    }

    @Test
    public void devProfileExportsToGrafanaOverHttpWithoutLeakingTheSigNozKey() {
        try (ConfigurableApplicationContext ctx = start("envtest", "envtest-grafana")) {
            OtlpExporterSettings s = OtlpExporterSettings.resolve(ctx.getBean(SigNozProperties.class));
            assertThat(s.getProtocolName()).isEqualTo("http/protobuf");
            assertThat(s.endpoint(LOGS)).isEqualTo("https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/logs");
            assertThat(s.getHeaders()).containsKey("Authorization")
                    .doesNotContainKey("signoz-ingestion-key");
            assertThat(s.getHeaders().values()).doesNotContain(SIGNOZ_KEY);
        }
    }

    private static ConfigurableApplicationContext start(String... profiles) {
        return new SpringApplicationBuilder(TestSigNozApplication.class)
                .profiles(profiles)
                .properties("spring.main.web-application-type=none",
                        "spring.main.allow-bean-definition-overriding=true",
                        "signoz.tracing.enabled=false",
                        "ENVTEST_SIGNOZ_KEY=" + SIGNOZ_KEY,
                        "ENVTEST_GRAFANA_TOKEN=" + GRAFANA_TOKEN)
                .run();
    }
}
