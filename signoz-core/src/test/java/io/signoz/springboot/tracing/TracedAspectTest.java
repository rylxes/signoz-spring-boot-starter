package io.signoz.springboot.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.annotation.Traced;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracedAspectTest {

    private AnnotationConfigApplicationContext ctx;
    private TracedService service;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        service = ctx.getBean(TracedService.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void tracedMethodCompletesNormallyAndReturnsValue() {
        String result = service.doTraced();
        assertThat(result).isEqualTo("traced-result");
    }

    @Test
    void exceptionPropagatesToCallerAndSpanEnds() {
        assertThatThrownBy(() -> service.doTracedException())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("recorded-boom");
    }

    @Test
    void recordExceptionFalseStillPropagatesException() {
        assertThatThrownBy(() -> service.doTracedNoRecord())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("no-record-boom");
    }

    @Test
    void invalidTagFormatIsSilentlySkipped() {
        // tag without '=' should not cause exception
        assertThat(service.doTracedBadTag()).isEqualTo("ok");
    }

    @Test
    void tracedMethodWithDefaultOperationNameCompletesNormally() {
        // operationName defaults to "" which causes TracedAspect to use method name
        assertThat(service.doTracedDefaultName()).isEqualTo("default-ok");
    }

    // ---- Inner configuration and beans ----

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public OpenTelemetry openTelemetry() {
            return OpenTelemetry.noop();
        }

        @Bean
        public SigNozTracer sigNozTracer(OpenTelemetry openTelemetry) {
            return new SigNozTracer(openTelemetry);
        }

        @Bean
        public TracedAspect tracedAspect(SigNozTracer tracer) {
            return new TracedAspect(tracer);
        }

        @Bean
        public TracedService tracedService() {
            return new TracedService();
        }
    }

    @Component
    static class TracedService {
        @Traced(operationName = "myOp", tags = {"domain=test"})
        public String doTraced() {
            return "traced-result";
        }

        @Traced(recordException = true)
        public void doTracedException() {
            throw new RuntimeException("recorded-boom");
        }

        @Traced(recordException = false)
        public void doTracedNoRecord() {
            throw new RuntimeException("no-record-boom");
        }

        @Traced(operationName = "badTagOp", tags = {"invaliddomain"})
        public String doTracedBadTag() {
            return "ok";
        }

        @Traced
        public String doTracedDefaultName() {
            return "default-ok";
        }
    }
}
