package io.signoz.springboot.masking;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named, curated field → strategy sets, enabled with {@code signoz.logging.profiles}.
 *
 * <p>The flat {@code masked-fields} list makes every service rediscover the same domain knowledge:
 * which fields exist, and which of them may keep a readable remnant. A card processor that forgets
 * {@code cardHolderName}, or masks {@code cardPan} entirely and loses the BIN its support team needs,
 * has no way to tell from the config that anything is wrong. A profile encodes that policy once.
 *
 * <pre>
 * signoz:
 *   logging:
 *     profiles: [pci]
 * </pre>
 *
 * <p>Profiles are additive and applied in the order listed. They lose to explicit
 * {@code field-strategies} entries — see {@link MaskingRegistry}.
 */
public final class MaskingProfiles {

    /**
     * Payment-card fields, named as they appear on ISO-8583 derived DTOs.
     *
     * <p>PCI-DSS 3.2 forbids retaining Sensitive Authentication Data (PIN, PIN block, full track
     * data, chip/ICC data) after authorisation under any circumstance, so those are masked whole.
     * The PAN keeps its first six (BIN) and last four digits, which 3.3 permits and which is what
     * makes a log line usable for reconciliation.
     */
    public static final String PCI = "pci";

    /**
     * Personal data that is not card data: government identifiers, bank account numbers, contact
     * details and names.
     *
     * <p>Kept separate from {@link #PCI} because the two are driven by different obligations and
     * different services need different halves. A card processor wants both; a merchant-onboarding
     * service wants only this one. Profiles are additive, so {@code profiles: [pci, pii]} applies
     * each in turn.
     */
    public static final String PII = "pii";

    private static final Map<String, Map<String, MaskingStrategy>> PROFILES;

    static {
        Map<String, MaskingStrategy> pci = new LinkedHashMap<String, MaskingStrategy>();

        // PCI 3.3: BIN + last 4 may be retained.
        MaskingStrategy pan = new PartialMaskingStrategy(6, 4, '*');
        pci.put("cardpan", pan);
        pci.put("cardnumber", pan);
        pci.put("card_number", pan);
        pci.put("pan", pan);
        pci.put("primaryaccountnumber", pan);

        // PCI 3.2: Sensitive Authentication Data - never retainable in any form.
        MaskingStrategy full = new FullMaskingStrategy();
        pci.put("clearpin", full);
        pci.put("pinblock", full);
        pci.put("pin", full);
        pci.put("track2data", full);
        pci.put("track2", full);
        pci.put("iccdata", full);
        pci.put("cardexpirydate", full);
        pci.put("expirydate", full);
        pci.put("cvv", full);
        pci.put("cvv2", full);
        pci.put("cardpinenc", full);

        // Cardholder data.
        pci.put("cardholdername", full);

        // Credentials and secrets that travel with card traffic.
        pci.put("secretkey", full);
        pci.put("clientsecret", full);
        pci.put("apikeyvalue", full);
        pci.put("apikey", full);
        pci.put("token", full);
        pci.put("otp", full);

        Map<String, MaskingStrategy> pii = new LinkedHashMap<String, MaskingStrategy>();

        // Government and financial identifiers.
        pii.put("bvn", full);
        pii.put("nin", full);
        pii.put("ssn", full);
        pii.put("taxid", full);
        pii.put("passportnumber", full);

        // Bank account numbers keep the last four, which is what reconciliation and support need
        // and what customers are used to seeing.
        MaskingStrategy accountNumber = new PartialMaskingStrategy(0, 4, '*');
        pii.put("accountnumber", accountNumber);
        pii.put("account_number", accountNumber);
        pii.put("originatoraccountnumber", accountNumber);
        pii.put("destinationaccountnumber", accountNumber);

        // Contact details and names.
        for (String field : new String[]{
                "email", "emailaddress", "merchantemail",
                "phone", "phonenumber", "msisdn", "merchantphone",
                "address", "merchantaddress", "residentialaddress",
                "dateofbirth", "dob",
                "accountname", "customername", "firstname", "lastname", "originatorname"}) {
            pii.put(field, full);
        }

        Map<String, Map<String, MaskingStrategy>> all =
                new LinkedHashMap<String, Map<String, MaskingStrategy>>();
        all.put(PCI, Collections.unmodifiableMap(pci));
        all.put(PII, Collections.unmodifiableMap(pii));
        PROFILES = Collections.unmodifiableMap(all);
    }

    private MaskingProfiles() {
        // utility
    }

    /**
     * @throws IllegalArgumentException for an unknown profile name. A silently ignored profile would
     *         read as "masking configured" while masking nothing.
     */
    public static Map<String, MaskingStrategy> get(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Profile name must not be null");
        }
        Map<String, MaskingStrategy> profile = PROFILES.get(name.trim().toLowerCase());
        if (profile == null) {
            throw new IllegalArgumentException(
                    "Unknown masking profile '" + name + "'. Available: " + PROFILES.keySet());
        }
        return profile;
    }

    public static java.util.Set<String> available() {
        return PROFILES.keySet();
    }
}
