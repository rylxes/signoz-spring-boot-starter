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
 * "fixes" the filter later chasing the wrong cause. Since name-suffix matching
 * ({@code signoz.logging.masked-field-suffixes}) the same deployed list masks it too, because
 * {@code clientSecret} ends with {@code secret}; the gap is still reproduced with suffix matching
 * turned off.
 */
class AuthBodyMaskingDiagnosisTest {

    /** Value planted as the client secret; whether it survives masking is what each test checks. */
    private static final String CANARY = "s3cret";

    /** The exact body shape observed in production, with the secret replaced by {@link #CANARY}. */
    private static final String AUTH_BODY =
            "{\"clientID\":\"MEGALEK\",\"clientSecret\":\"" + CANARY + "\"}";

    /** Reproduces the deployment's actual signoz.logging.masked-fields list. */
    private static MaskingRegistry asDeployed() {
        return new MaskingRegistry(deployedProperties());
    }

    /** The deployment's list, as it behaved before name-suffix matching existed. */
    private static MaskingRegistry asDeployedWithoutSuffixMatching() {
        SigNozLoggingProperties p = deployedProperties();
        p.setMaskedFieldSuffixes(Collections.<String>emptyList());
        return new MaskingRegistry(p);
    }

    private static SigNozLoggingProperties deployedProperties() {
        SigNozLoggingProperties p = new SigNozLoggingProperties();
        p.setMaskedFields(Arrays.asList(
                "password", "token", "secretKey", "pinBlock", "iccData", "cardPan",
                "clearPin", "track2Data", "creditcard", "cvv", "ssn", "authorization"));
        p.setCustomPatterns(Collections.emptyList());
        return p;
    }

    private static MaskingRegistry withPciProfile() {
        SigNozLoggingProperties p = new SigNozLoggingProperties();
        p.setMaskedFields(Collections.emptyList());
        p.setCustomPatterns(Collections.emptyList());
        p.setProfiles(Arrays.asList("pci"));
        return new MaskingRegistry(p);
    }

    @Test
    @DisplayName("without suffix matching the deployed config leaks clientSecret - 'secretKey' does not cover it")
    void reproducesTheGap() {
        // Documents the defect rather than asserting desired behaviour: "secretKey" is a distinct
        // field name from "clientSecret", and a listed name only covers its own spellings.
        assertThat(asDeployedWithoutSuffixMatching().maskJsonString(AUTH_BODY)).contains(CANARY);
    }

    @Test
    @DisplayName("suffix matching closes it for the deployed config as it stands")
    void suffixMatchingClosesTheGap() {
        String masked = asDeployed().maskJsonString(AUTH_BODY);
        assertThat(masked).doesNotContain(CANARY);
        assertThat(masked).as("the non-secret half stays readable").contains("MEGALEK");
    }

    @Test
    @DisplayName("the pci profile closes it without touching the filter")
    void pciProfileClosesTheGap() {
        String masked = withPciProfile().maskJsonString(AUTH_BODY);
        assertThat(masked).doesNotContain(CANARY);
        assertThat(masked).as("the non-secret half stays readable").contains("MEGALEK");
    }

    @Test
    @DisplayName("form-encoded credential bodies are masked too, not just JSON")
    void masksFormEncodedBodies() {
        // OAuth2 token requests are conventionally application/x-www-form-urlencoded, so a
        // JSON-only masker would miss the most common shape of this exact request.
        String form = "grant_type=client_credentials&clientId=MEGALEK&clientSecret=" + CANARY;
        String masked = withPciProfile().maskJsonString(form);

        assertThat(masked).doesNotContain(CANARY);
        assertThat(masked).contains("grant_type=client_credentials");
    }
}
