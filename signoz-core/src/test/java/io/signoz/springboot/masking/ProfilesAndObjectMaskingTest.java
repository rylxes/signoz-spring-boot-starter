package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the configurable per-field strategies, the curated profiles, and {@link ObjectMasker}.
 *
 * <p>Together these replace the pattern every consuming service was otherwise forced to hand-roll:
 * a static field-name → strategy table plus a reflective masker.
 */
class ProfilesAndObjectMaskingTest {

    private static final String PAN = "5061051800019666567";

    private static SigNozLoggingProperties props() {
        SigNozLoggingProperties p = new SigNozLoggingProperties();
        p.setMaskedFields(Collections.emptyList());
        p.setCustomPatterns(Collections.emptyList());
        return p;
    }

    /** Mirrors the shape of a real ISO-8583 derived request, serialVersionUID included. */
    public static class PurchaseRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long posTransactionId;
        private String cardPan;
        private String clearPin;
        private String cardHolderName;
        private String transactionRRN;
        private Map<String, String> otherTxnDetails;
        private Nested nested;

        public static class Nested {
            private String secretKey;
            private String terminalId;
            public String getSecretKey() { return secretKey; }
            public String getTerminalId() { return terminalId; }
        }

        public Long getPosTransactionId() { return posTransactionId; }
        public String getCardPan() { return cardPan; }
        public String getClearPin() { return clearPin; }
        public String getCardHolderName() { return cardHolderName; }
        public String getTransactionRRN() { return transactionRRN; }
        public Map<String, String> getOtherTxnDetails() { return otherTxnDetails; }
        public Nested getNested() { return nested; }
    }

    private static PurchaseRequest sample() {
        PurchaseRequest r = new PurchaseRequest();
        r.posTransactionId = 45448449L;
        r.cardPan = PAN;
        r.clearPin = "CD25A90058F7DAD4";
        r.cardHolderName = "BOLAJI/KEHINDE";
        r.transactionRRN = "878418815534";
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("cardPan", PAN);
        extras.put("channel", "POS");
        r.otherTxnDetails = extras;
        PurchaseRequest.Nested n = new PurchaseRequest.Nested();
        n.secretKey = "sk_live_do_not_log";
        n.terminalId = "2076SA59";
        r.nested = n;
        return r;
    }

    // --- field-strategies ---

    @Test
    @DisplayName("partial:6:4 keeps the BIN and last four, which a flat masked-fields list cannot")
    void perFieldPartialStrategy() {
        SigNozLoggingProperties p = props();
        Map<String, String> strategies = new LinkedHashMap<>();
        strategies.put("cardPan", "partial:6:4");
        p.setFieldStrategies(strategies);

        assertThat(new MaskingRegistry(p).mask("cardPan", PAN)).isEqualTo("506105*********6567");
    }

    @Test
    @DisplayName("field-strategies overrides both masked-fields and any profile")
    void fieldStrategiesWinsOverEverything() {
        SigNozLoggingProperties p = props();
        p.setMaskedFields(Arrays.asList("cardPan"));
        p.setProfiles(Arrays.asList("pci"));
        Map<String, String> strategies = new LinkedHashMap<>();
        strategies.put("cardPan", "partial:4:2");
        p.setFieldStrategies(strategies);

        assertThat(new MaskingRegistry(p).mask("cardPan", PAN)).isEqualTo("5061*************67");
    }

    @Test
    @DisplayName("a bad strategy spec fails at startup rather than degrading to no masking")
    void badSpecFailsFast() {
        SigNozLoggingProperties p = props();
        Map<String, String> strategies = new LinkedHashMap<>();
        strategies.put("cardPan", "partail:6:4"); // typo
        p.setFieldStrategies(strategies);

        assertThatThrownBy(() -> new MaskingRegistry(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown masking strategy");
    }

    // --- profiles ---

    @Test
    @DisplayName("the pci profile masks SAD entirely and keeps the PAN's BIN + last four")
    void pciProfile() {
        SigNozLoggingProperties p = props();
        p.setProfiles(Arrays.asList("pci"));
        MaskingRegistry r = new MaskingRegistry(p);

        assertThat(r.mask("cardPan", PAN)).isEqualTo("506105*********6567");
        assertThat(r.mask("clearPin", "1234")).isEqualTo("***");
        assertThat(r.mask("iccData", "5F340101")).isEqualTo("***");
        assertThat(r.mask("cardHolderName", "BOLAJI/KEHINDE")).isEqualTo("***");
        assertThat(r.mask("track2Data", "5061=2807")).isEqualTo("***");
        // Not sensitive - must survive.
        assertThat(r.mask("terminalId", "2076SA59")).isEqualTo("2076SA59");
    }

    @Test
    @DisplayName("the profile refines a flat masked-fields entry rather than being shadowed by it")
    void profileRefinesMaskedFields() {
        SigNozLoggingProperties p = props();
        p.setMaskedFields(Arrays.asList("cardpan"));
        p.setProfiles(Arrays.asList("pci"));
        // Without the documented ordering this would be "***" and the BIN would be lost.
        assertThat(new MaskingRegistry(p).mask("cardPan", PAN)).isEqualTo("506105*********6567");
    }

    @Test
    @DisplayName("an unknown profile is rejected, not silently ignored")
    void unknownProfileRejected() {
        SigNozLoggingProperties p = props();
        p.setProfiles(Arrays.asList("pcii"));
        assertThatThrownBy(() -> new MaskingRegistry(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown masking profile");
    }

    @Test
    @DisplayName("the pci profile reproduces a hand-rolled MaskingParameter table field for field")
    void pciProfileIsALosslessReplacementForAHandRolledTable() {
        // Every entry from the static field -> strategy table this profile is meant to retire.
        // If any of these drifts, a service migrating onto `profiles: [pci]` would silently lose
        // masking it previously had, so pin all of them.
        SigNozLoggingProperties p = props();
        p.setProfiles(Arrays.asList("pci"));
        MaskingRegistry r = new MaskingRegistry(p);

        // PARTIAL(6,4) - BIN + last four retained.
        assertThat(r.mask("cardPan", PAN)).isEqualTo("506105*********6567");
        assertThat(r.mask("cardNumber", PAN)).isEqualTo("506105*********6567");

        // FULL - nothing retained.
        for (String field : Arrays.asList(
                "clearPin", "pinBlock", "track2Data", "iccData", "cardExpiryDate",
                "cardHolderName", "secretKey", "cardPinEnc", "clientSecret",
                "apiKeyValue", "token", "otp")) {
            assertThat(r.mask(field, "some-sensitive-value"))
                    .as("%s must be fully masked", field)
                    .isEqualTo("***");
        }
    }

    // --- ObjectMasker ---

    @Nested
    class ObjectMasking {

        private ObjectMasker masker() {
            SigNozLoggingProperties p = props();
            p.setProfiles(Arrays.asList("pci"));
            return new ObjectMasker(new MaskingRegistry(p));
        }

        @Test
        @DisplayName("a DTO is masked field by field, diagnostics preserved")
        void masksDtoFields() {
            PurchaseRequest masked = masker().mask(sample());

            assertThat(masked).isNotNull();
            assertThat(masked.getCardPan()).isEqualTo("506105*********6567");
            assertThat(masked.getClearPin()).isEqualTo("***");
            assertThat(masked.getCardHolderName()).isEqualTo("***");
            assertThat(masked.getTransactionRRN()).isEqualTo("878418815534");
            assertThat(masked.getPosTransactionId()).isEqualTo(45448449L);
        }

        @Test
        @DisplayName("serialVersionUID does not abort the copy")
        void staticFieldsSkipped() {
            // Setting a static final via reflection throws; if that escaped, the masker would
            // return nothing useful and every Serializable DTO would go unmasked.
            assertThat(masker().mask(sample())).isNotNull();
        }

        @Test
        @DisplayName("nested objects and map values are masked too")
        void masksNestedAndMapValues() {
            PurchaseRequest masked = masker().mask(sample());

            assertThat(masked.getNested().getSecretKey()).isEqualTo("***");
            assertThat(masked.getNested().getTerminalId()).isEqualTo("2076SA59");
            assertThat(masked.getOtherTxnDetails().get("cardPan")).isEqualTo("506105*********6567");
            assertThat(masked.getOtherTxnDetails().get("channel")).isEqualTo("POS");
        }

        @Test
        @DisplayName("the original object is not mutated")
        void doesNotMutateSource() {
            PurchaseRequest source = sample();
            masker().mask(source);
            assertThat(source.getCardPan()).isEqualTo(PAN);
            assertThat(source.getOtherTxnDetails().get("cardPan")).isEqualTo(PAN);
        }

        @Test
        @DisplayName("an un-copyable object fails closed to null, never to the original")
        void failsClosed() {
            Object noNoArgCtor = new Object() {
                @SuppressWarnings("unused")
                private final String cardPan = PAN;
            };
            assertThat(masker().mask(noNoArgCtor)).isNull();
        }

        @Test
        @DisplayName("strings and opaque values pass through sensibly")
        void handlesStringsAndOpaqueTypes() {
            ObjectMasker m = masker();
            String maskedJson = m.mask("{\"cardPan\":\"" + PAN + "\"}");
            assertThat(maskedJson).doesNotContain(PAN);

            Long number = m.mask(42L);
            assertThat(number).isEqualTo(42L);

            List<String> list = m.mask(Arrays.asList("a", "b"));
            assertThat(list).containsExactly("a", "b");

            Object nothing = m.mask(null);
            assertThat(nothing).isNull();
        }
    }
}
