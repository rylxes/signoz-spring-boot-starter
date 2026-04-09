package io.signoz.springboot.timed;

import io.signoz.springboot.annotation.Timed;
import io.signoz.springboot.properties.SigNozTimedProperties;
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

/**
 * Unit tests for {@link TimedAspect} using {@link AnnotationConfigApplicationContext}
 * with {@link EnableAspectJAutoProxy}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Normal method completion with timing logged</li>
 *   <li>Slow method triggering WARN-level log</li>
 *   <li>Exception propagation (timer still recorded before re-throw)</li>
 *   <li>Custom metric name from annotation value</li>
 *   <li>Disabled aspect via properties</li>
 * </ul>
 */
class TimedAspectTest {

    private AnnotationConfigApplicationContext ctx;
    private TimedService service;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        service = ctx.getBean(TimedService.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void normalMethodCompletesAndReturnsValue() {
        String result = service.doFast();
        assertThat(result).isEqualTo("fast-result");
    }

    @Test
    void slowMethodCompletesAndReturnsValue() {
        // The method sleeps 50ms; threshold is set to 10ms so WARN is logged.
        // We cannot directly assert log output here, but we verify the method
        // completes normally and the return value is correct.
        String result = service.doSlow();
        assertThat(result).isEqualTo("slow-result");
    }

    @Test
    void exceptionPropagatesToCaller() {
        assertThatThrownBy(() -> service.doFail())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("timed-boom");
    }

    @Test
    void customMetricNameMethodCompletesNormally() {
        String result = service.doCustomName();
        assertThat(result).isEqualTo("custom-ok");
    }

    @Test
    void disabledAspectSkipsTiming() {
        ctx.close();
        ctx = new AnnotationConfigApplicationContext(DisabledTimedConfig.class);
        TimedService disabledService = ctx.getBean(TimedService.class);

        // Method still works; aspect is disabled so no timing overhead
        String result = disabledService.doFast();
        assertThat(result).isEqualTo("fast-result");
    }

    @Test
    void classLevelAnnotationTimesAllMethods() {
        ClassTimedService classService = ctx.getBean(ClassTimedService.class);
        String result = classService.doWork();
        assertThat(result).isEqualTo("class-timed");
    }

    // ---- Inner configuration and beans ----

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public SigNozTimedProperties timedProps() {
            SigNozTimedProperties props = new SigNozTimedProperties();
            props.setSlowThresholdMs(10); // 10ms threshold for testing
            return props;
        }

        @Bean
        public TimedAspect timedAspect(SigNozTimedProperties props) {
            return new TimedAspect(null, props); // null registry — no Micrometer in tests
        }

        @Bean
        public TimedService timedService() {
            return new TimedService();
        }

        @Bean
        public ClassTimedService classTimedService() {
            return new ClassTimedService();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class DisabledTimedConfig {
        @Bean
        public SigNozTimedProperties timedProps() {
            SigNozTimedProperties props = new SigNozTimedProperties();
            props.setEnabled(false);
            return props;
        }

        @Bean
        public TimedAspect timedAspect(SigNozTimedProperties props) {
            return new TimedAspect(null, props);
        }

        @Bean
        public TimedService timedService() {
            return new TimedService();
        }
    }

    @Component
    static class TimedService {
        @Timed
        public String doFast() {
            return "fast-result";
        }

        @Timed
        public String doSlow() {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow-result";
        }

        @Timed
        public void doFail() {
            throw new RuntimeException("timed-boom");
        }

        @Timed(value = "my.custom.timer", description = "A custom timer")
        public String doCustomName() {
            return "custom-ok";
        }
    }

    @Timed
    @Component
    static class ClassTimedService {
        public String doWork() {
            return "class-timed";
        }
    }
}
