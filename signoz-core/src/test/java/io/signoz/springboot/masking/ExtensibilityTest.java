package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two extension points consuming services will reach for: stacking profiles, and declaring
 * many per-field strategies. Both are advertised as configuration-only, so they are worth proving
 * rather than assuming.
 */
class ExtensibilityTest {

    private static final String PAN = "5061051800019666567";

    private static SigNozLoggingProperties props() {
        SigNozLoggingProperties p = new SigNozLoggingProperties();
        p.setMaskedFields(Collections.emptyList());
        p.setCustomPatterns(Collections.emptyList());
        return p;
    }

    @Test
    @DisplayName("several profiles can be listed together and are applied additively")
    void profilesStack() {
        SigNozLoggingProperties p = props();
        p.setProfiles(Arrays.asList("pci", "pii"));
        MaskingRegistry r = new MaskingRegistry(p);

        // from pci
        assertThat(r.mask("cardPan", PAN)).isEqualTo("506105*********6567");
        assertThat(r.mask("clearPin", "1234")).isEqualTo("***");
        // from pii
        assertThat(r.mask("bvn", "22123456789")).isEqualTo("***");
        assertThat(r.mask("merchantEmail", "ada@example.com")).isEqualTo("***");
        // neither
        assertThat(r.mask("terminalId", "2076SA59")).isEqualTo("2076SA59");
    }

    @Test
    @DisplayName("stacking is order-independent where profiles do not overlap")
    void stackingIsAdditive() {
        SigNozLoggingProperties oneWay = props();
        oneWay.setProfiles(Arrays.asList("pci", "pii"));
        SigNozLoggingProperties other = props();
        other.setProfiles(Arrays.asList("pii", "pci"));

        for (String field : Arrays.asList("cardPan", "clearPin", "bvn", "accountNumber")) {
            assertThat(new MaskingRegistry(oneWay).mask(field, "5061051800019666567"))
                    .as("%s must mask the same regardless of profile order", field)
                    .isEqualTo(new MaskingRegistry(other).mask(field, "5061051800019666567"));
        }
    }

    @Test
    @DisplayName("many field-strategies can be declared, each with its own rule")
    void manyFieldStrategies() {
        SigNozLoggingProperties p = props();
        Map<String, String> strategies = new LinkedHashMap<>();
        strategies.put("cardPan", "partial:6:4");
        strategies.put("accountNumber", "partial:0:4");
        strategies.put("bvn", "full");
        strategies.put("phoneNumber", "partial:3:2");
        strategies.put("email", "regex:^[^@]+"); // anchored: unanchored would also eat the domain
        p.setFieldStrategies(strategies);
        MaskingRegistry r = new MaskingRegistry(p);

        assertThat(r.mask("cardPan", PAN)).isEqualTo("506105*********6567");
        assertThat(r.mask("accountNumber", "2081980035")).isEqualTo("******0035");
        assertThat(r.mask("bvn", "22123456789")).isEqualTo("***");
        assertThat(r.mask("phoneNumber", "08031234567")).isEqualTo("080******67");
        assertThat(r.mask("email", "ada@example.com")).isEqualTo("***@example.com");
    }

    @Test
    @DisplayName("field-strategies compose with profiles, overriding only the fields they name")
    void fieldStrategiesComposeWithProfiles() {
        SigNozLoggingProperties p = props();
        p.setProfiles(Arrays.asList("pci"));
        Map<String, String> strategies = new LinkedHashMap<>();
        strategies.put("cardPan", "partial:0:4"); // house rule: no BIN retained
        p.setFieldStrategies(strategies);
        MaskingRegistry r = new MaskingRegistry(p);

        assertThat(r.mask("cardPan", PAN)).isEqualTo("***************6567");
        // Everything else the profile set is untouched.
        assertThat(r.mask("clearPin", "1234")).isEqualTo("***");
    }

    @Test
    @DisplayName("a service can register a bespoke strategy at runtime without a starter change")
    void runtimeRegistrationEscapeHatch() {
        // The notation parsed from YAML is a closed set. register() is the escape hatch for a rule
        // that cannot be expressed in it -- a Luhn-aware masker, a tokenising lookup, and so on.
        MaskingRegistry r = new MaskingRegistry(props());
        r.register("walletRef", (field, value) -> "wallet:" + value.substring(value.length() - 2));

        assertThat(r.mask("walletRef", "WX-88231")).isEqualTo("wallet:31");
    }
}
