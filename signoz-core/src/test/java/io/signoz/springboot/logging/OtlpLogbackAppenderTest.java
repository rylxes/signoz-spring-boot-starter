package io.signoz.springboot.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OtlpLogbackAppenderTest {

    @Test
    void defaultEndpointIsLocalhost4317() {
        OtlpLogbackAppender appender = new OtlpLogbackAppender();
        assertThat(appender.getEndpoint()).isEqualTo("http://localhost:4317");
    }

    @Test
    void defaultServiceNameIsApplication() {
        OtlpLogbackAppender appender = new OtlpLogbackAppender();
        assertThat(appender.getServiceName()).isEqualTo("application");
    }

    @Test
    void allSettersAndGettersRoundTrip() {
        OtlpLogbackAppender appender = new OtlpLogbackAppender();
        appender.setEndpoint("http://signoz:4317");
        appender.setServiceName("my-service");
        appender.setServiceVersion("2.0.0");
        appender.setEnvironment("production");

        assertThat(appender.getEndpoint()).isEqualTo("http://signoz:4317");
        assertThat(appender.getServiceName()).isEqualTo("my-service");
        assertThat(appender.getServiceVersion()).isEqualTo("2.0.0");
        assertThat(appender.getEnvironment()).isEqualTo("production");
    }

    @Test
    void stopOnNeverStartedAppenderDoesNotThrow() {
        OtlpLogbackAppender appender = new OtlpLogbackAppender();
        assertThatCode(appender::stop).doesNotThrowAnyException();
    }

    @Test
    void isStartedReturnsFalseForNewAppender() {
        OtlpLogbackAppender appender = new OtlpLogbackAppender();
        assertThat(appender.isStarted()).isFalse();
    }
}
