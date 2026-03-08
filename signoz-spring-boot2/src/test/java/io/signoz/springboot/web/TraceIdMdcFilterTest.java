package io.signoz.springboot.web;

import io.signoz.springboot.TestSigNozApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TraceIdMdcFilter}.
 *
 * <p>Starts the full application context on a random port using
 * {@link TestSigNozApplication}. Verifies that the filter correctly injects a
 * per-request {@code X-Request-ID} header into every HTTP response.
 *
 * <p>The {@code test} profile is active so that {@code application-test.yml}
 * disables real OTel SDK initialisation ({@code signoz.tracing.enabled=false}).
 */
@SpringBootTest(
        classes = TestSigNozApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TraceIdMdcFilterTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void responseHasXRequestIdHeader() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        String requestId = response.getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isNotNull().isNotEmpty();
    }

    @Test
    public void differentRequestsHaveDifferentRequestIds() {
        ResponseEntity<String> first = restTemplate.getForEntity("/actuator/health", String.class);
        ResponseEntity<String> second = restTemplate.getForEntity("/actuator/health", String.class);

        String firstId = first.getHeaders().getFirst("X-Request-ID");
        String secondId = second.getHeaders().getFirst("X-Request-ID");

        assertThat(firstId).isNotNull().isNotEmpty();
        assertThat(secondId).isNotNull().isNotEmpty();
        assertThat(firstId).isNotEqualTo(secondId);
    }

    @Test
    public void requestIdIsHexWithExpectedLength() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        String requestId = response.getHeaders().getFirst("X-Request-ID");

        assertThat(requestId).isNotNull();
        // UUID without hyphens = 32 hex characters
        assertThat(requestId.length()).isGreaterThanOrEqualTo(32);
        assertThat(requestId).matches("[a-fA-F0-9]+");
    }
}
