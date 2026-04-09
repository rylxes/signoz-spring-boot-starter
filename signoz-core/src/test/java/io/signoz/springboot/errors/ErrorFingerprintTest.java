package io.signoz.springboot.errors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ErrorFingerprint}.
 */
class ErrorFingerprintTest {

    @Test
    void sameExceptionAndStackProduceSameFingerprint() {
        Exception ex = new RuntimeException("boom");
        String first = ErrorFingerprint.generate(ex, 3);
        String second = ErrorFingerprint.generate(ex, 3);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentExceptionTypesProduceDifferentFingerprints() {
        Exception runtime = new RuntimeException("boom");
        Exception illegal = new IllegalArgumentException("boom");
        String fpRuntime = ErrorFingerprint.generate(runtime, 3);
        String fpIllegal = ErrorFingerprint.generate(illegal, 3);
        assertThat(fpRuntime).isNotEqualTo(fpIllegal);
    }

    @Test
    void fewerFramesThanDepthStillWorks() {
        Exception ex = new RuntimeException("boom");
        // Requesting depth of 1000, far more than the actual stack depth
        String fingerprint = ErrorFingerprint.generate(ex, 1000);
        assertThat(fingerprint).isNotNull();
        assertThat(fingerprint).hasSize(12);
    }

    @Test
    void nullThrowableReturnsUnknown() {
        String fingerprint = ErrorFingerprint.generate(null, 3);
        assertThat(fingerprint).isEqualTo("unknown");
    }

    @Test
    void fingerprintIsTwelveHexCharacters() {
        Exception ex = new RuntimeException("test");
        String fingerprint = ErrorFingerprint.generate(ex, 3);
        assertThat(fingerprint).hasSize(12);
        assertThat(fingerprint).matches("[0-9a-f]{12}");
    }
}
