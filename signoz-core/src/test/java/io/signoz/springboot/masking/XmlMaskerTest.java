package io.signoz.springboot.masking;

import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XML masking for acquirer payloads, and the XXE hardening that has to come with it.
 *
 * <p>The payloads are externally controlled, so a masker that parses them is itself an attack
 * surface: an attacker who can influence the XML could otherwise use the very act of masking to read
 * local files or reach internal hosts. These tests pin the parser shut.
 */
class XmlMaskerTest {

    private static final String CANARY = "TOP_SECRET_CANARY_VALUE";

    private static MaskingRegistry registry() {
        SigNozLoggingProperties p = new SigNozLoggingProperties();
        p.setMaskedFields(Collections.emptyList());
        p.setCustomPatterns(Collections.emptyList());
        p.setProfiles(Arrays.asList("pci"));
        return new MaskingRegistry(p);
    }

    @Test
    @DisplayName("an inline file entity discloses nothing")
    void inlineFileEntityIsNotResolved(@TempDir Path tempDir) throws Exception {
        Path secret = tempDir.resolve("secret.txt");
        Files.write(secret, CANARY.getBytes(StandardCharsets.UTF_8));

        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE purchase [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
                + "<purchase><cardPan>5399831234567890</cardPan><note>&xxe;</note></purchase>";

        assertThat(registry().maskJsonString(xml)).doesNotContain(CANARY);
    }

    @Test
    @DisplayName("an external DTD discloses nothing")
    void externalDtdIsNotResolved(@TempDir Path tempDir) throws Exception {
        Path dtd = tempDir.resolve("evil.dtd");
        Files.write(dtd, ("<!ENTITY xxe \"" + CANARY + "\">").getBytes(StandardCharsets.UTF_8));

        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE purchase SYSTEM \"" + dtd.toUri() + "\">"
                + "<purchase><cardPan>5399831234567890</cardPan><note>&xxe;</note></purchase>";

        assertThat(registry().maskJsonString(xml)).doesNotContain(CANARY);
    }

    @Test
    @DisplayName("a rejected DOCTYPE writes nothing to stderr")
    void rejectedDoctypeIsSilent() throws Exception {
        // Xerces prints the offending payload to stderr by default, which would route the raw
        // content around the masking pipeline entirely.
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE purchase [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<purchase><cardPan>5399831234567890</cardPan></purchase>";

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            registry().maskJsonString(xml);
        } finally {
            System.setErr(originalErr);
        }
        assertThat(captured.toString("UTF-8")).isEmpty();
    }

    @Test
    @DisplayName("the transformer factory denies external DTD and stylesheet access")
    void transformerFactoryIsHardened() throws Exception {
        TransformerFactory hardened = XmlMasker.newSecureTransformerFactory();
        TransformerFactory stock = TransformerFactory.newInstance();

        assertThat(hardened.getAttribute(XMLConstants.ACCESS_EXTERNAL_DTD)).isEqualTo("");
        assertThat(hardened.getAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET)).isEqualTo("");
        assertThat(stock.getAttribute(XMLConstants.ACCESS_EXTERNAL_DTD)).isNotEqualTo("");
        assertThat(stock.getAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET)).isNotEqualTo("");
    }

    @Test
    @DisplayName("legitimate XML still has its sensitive elements masked")
    void masksLegitimateXml() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<purchase>"
                + "<cardPan>5399831234567890</cardPan>"
                + "<clearPin>1234</clearPin>"
                + "<narration>lunch</narration>"
                + "</purchase>";

        String masked = registry().maskJsonString(xml);

        assertThat(masked).contains("539983******7890");
        assertThat(masked).contains("<clearPin>***</clearPin>");
        assertThat(masked).contains("<narration>lunch</narration>");
        assertThat(masked).doesNotContain("5399831234567890");
    }

    @Test
    @DisplayName("unparseable XML withholds the payload rather than emitting it raw")
    void malformedXmlFailsClosed() {
        String broken = "<?xml version=\"1.0\"?><purchase><cardPan>5399831234567890</purchase>";
        String masked = registry().maskJsonString(broken);

        assertThat(masked).doesNotContain("5399831234567890");
        assertThat(masked).isEqualTo(XmlMasker.MASKING_FAILED);
    }
}
