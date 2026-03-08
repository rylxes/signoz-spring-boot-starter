package io.signoz.springboot.properties;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SigNozAuditPropertiesTest {

    @Test
    void defaultEnabledIsTrue() {
        assertThat(new SigNozAuditProperties().isEnabled()).isTrue();
    }

    @Test
    void defaultCaptureArgsIsTrue() {
        assertThat(new SigNozAuditProperties().isCaptureArgs()).isTrue();
    }

    @Test
    void defaultCaptureResultIsFalse() {
        assertThat(new SigNozAuditProperties().isCaptureResult()).isFalse();
    }

    @Test
    void defaultIncludeThreadIsFalse() {
        assertThat(new SigNozAuditProperties().isIncludeThread()).isFalse();
    }
}
