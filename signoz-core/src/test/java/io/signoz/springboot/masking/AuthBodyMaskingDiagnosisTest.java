package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down why a real deployment shipped {@code clientSecret} in clear text.
 *
 * <p>{@code HttpLoggingFilter} already routes request and response bodies through
 * {@link MaskingRegistry#maskJsonString(String)}, so the filter was never the defect. The gap was in
 * configuration: the service's {@code masked-fields} list named {@code secretKey} but not
 * {@code clientSecret}, and {@code maskJsonString} only masks fields it has a rule for. The observed
 * production line was an {@code /api/auth/token} body with the merchant's secret intact.
 *
 * <p>These tests encode both halves — the gap, and the profile that closes it — so nobody
 * "fixes" the filter later chasing the wrong cause.
 */
class AuthBodyMaskingDiagnosisTest {

    /** The exact body shape observed in production, with the secret replaced. */
    private static final String AUTH_BODY =
            "{\"clientID\":\"MEGALEK\",\"clientSecret\":\"6a1702fe58564cabc80157aa3076de3a\"}";

    private static final String SECRET = "6a1702fe58564cabc80157aa3076de3a";

    /** Reproduces the deployment's actual signoz.logging.masked-fields list. */
    private static MaskingRegistry asDeployed() {
        SigNozLoggingProperties p = new SigNozLoggingProperties();
        p.setMaskedFields(Arrays.asList(
                "password", "token", "secretKey", "pinBlock", "iccData", "cardPan",
                "clearPin", "track2Data", "creditcard", "cvv", "ssn", "authorization"));
        p.setCustomPatterns(Collections.emptyList());
        return new MaskingRegistry(p);
    }

    private static MaskingRegistry withPciProfile() {
        SigNozLoggingProperties p = new SigNozLoggingProperties();
        p.setMaskedFields(Collections.emptyList());
        p.setCustomPatterns(Collections.emptyList());
        p.setProfiles(Arrays.asList("pci"));
        return new MaskingRegistry(p);
    }

    @Test
    @DisplayName("the deployed config leaks clientSecret - 'secretKey' does not cover it")
    void reproducesTheGap() {
        // Documents the defect rather than asserting desired behaviour: "secretKey" is a distinct
        // field name from "clientSecret", and field-name masking is exact.
        assertThat(asDeployed().maskJsonString(AUTH_BODY)).contains(SECRET);
    }

    @Test
    @DisplayName("the pci profile closes it without touching the filter")
    void pciProfileClosesTheGap() {
        String masked = withPciProfile().maskJsonString(AUTH_BODY);
        assertThat(masked).doesNotContain(SECRET);
        assertThat(masked).as("the non-secret half stays readable").contains("MEGALEK");
    }

    @Test
    @DisplayName("form-encoded credential bodies are masked too, not just JSON")
    void masksFormEncodedBodies() {
        // OAuth2 token requests are conventionally application/x-www-form-urlencoded, so a
        // JSON-only masker would miss the most common shape of this exact request.
        String form = "grant_type=client_credentials&clientId=MEGALEK&clientSecret=" + SECRET;
        String masked = withPciProfile().maskJsonString(form);

        assertThat(masked).doesNotContain(SECRET);
        assertThat(masked).contains("grant_type=client_credentials");
    }
}
