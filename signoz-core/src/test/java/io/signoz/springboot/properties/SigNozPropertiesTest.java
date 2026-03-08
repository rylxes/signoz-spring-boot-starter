package io.signoz.springboot.properties;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SigNozPropertiesTest {

    @Test
    void defaultEnabledIsTrue() {
        assertThat(new SigNozProperties().isEnabled()).isTrue();
    }

    @Test
    void defaultEndpointIsLocalhost4317() {
        assertThat(new SigNozProperties().getEndpoint()).isEqualTo("http://localhost:4317");
    }

    @Test
    void defaultServiceNameIsApplication() {
        assertThat(new SigNozProperties().getServiceName()).isEqualTo("application");
    }

    @Test
    void defaultServiceVersionIsUnknown() {
        assertThat(new SigNozProperties().getServiceVersion()).isEqualTo("unknown");
    }

    @Test
    void defaultEnvironmentIsDefault() {
        assertThat(new SigNozProperties().getEnvironment()).isEqualTo("default");
    }

    @Test
    void defaultLoggingPropertiesNotNull() {
        assertThat(new SigNozProperties().getLogging()).isNotNull();
    }

    @Test
    void defaultTracingPropertiesNotNull() {
        assertThat(new SigNozProperties().getTracing()).isNotNull();
    }

    @Test
    void defaultWebPropertiesNotNull() {
        assertThat(new SigNozProperties().getWeb()).isNotNull();
    }

    @Test
    void defaultAuditPropertiesNotNull() {
        assertThat(new SigNozProperties().getAudit()).isNotNull();
    }

    @Test
    void settersAndGettersRoundTrip() {
        SigNozProperties props = new SigNozProperties();
        props.setEnabled(false);
        props.setEndpoint("http://signoz:4317");
        props.setServiceName("my-service");
        props.setServiceVersion("2.0.0");
        props.setEnvironment("staging");

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getEndpoint()).isEqualTo("http://signoz:4317");
        assertThat(props.getServiceName()).isEqualTo("my-service");
        assertThat(props.getServiceVersion()).isEqualTo("2.0.0");
        assertThat(props.getEnvironment()).isEqualTo("staging");
    }
}
