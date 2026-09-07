package io.signoz.springboot.masking;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Masks sensitive values inside an XML payload, element text and attributes alike.
 *
 * <p>Acquirer and scheme integrations still exchange XML, and a regex over the serialised form is a
 * poor fit for it: element text can be split across nodes, attribute quoting varies, and namespace
 * prefixes move the field name away from its value. Parsing and walking the tree masks by element
 * name reliably.
 *
 * <p>The payloads reaching this class are externally controlled, so the parser is locked down to
 * reject DOCTYPE declarations outright and resolve no external reference of any kind. That closes
 * XXE file disclosure and SSRF (CWE-611) — a masking utility that could be induced to read
 * {@code /etc/passwd} would be a worse problem than the one it solves.
 */
public final class XmlMasker {

    private static final Pattern XML_DECLARATION = Pattern.compile("<\\?xml.*?>", Pattern.DOTALL);

    private static final String DISALLOW_DOCTYPE_DECL =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String NO_EXTERNAL_ACCESS = "";

    /** Returned when masking fails, so a parse error cannot emit the raw payload. */
    public static final String MASKING_FAILED = "[signoz:xml-masking-failed:payload-withheld]";

    private XmlMasker() {
        // utility
    }

    /** Whether the payload carries an XML declaration and is worth parsing. */
    public static boolean looksLikeXml(String candidate) {
        return candidate != null && XML_DECLARATION.matcher(candidate).find();
    }

    /**
     * @return the payload with sensitive element text and attribute values masked, or
     *         {@link #MASKING_FAILED} if it could not be parsed. Never the unmasked input.
     */
    public static String mask(String xml, MaskingRegistry registry) {
        try {
            DocumentBuilder builder = newSecureDocumentBuilderFactory().newDocumentBuilder();
            // Xerces otherwise prints the offending payload straight to stderr, which would route
            // unmasked content around this very masking pipeline. DefaultHandler stays quiet and
            // still rethrows fatal errors for the catch below.
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            maskElement(document.getDocumentElement(), registry);

            Transformer transformer = newSecureTransformerFactory().newTransformer();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString("UTF-8");
        } catch (Exception e) {
            return MASKING_FAILED;
        }
    }

    private static void maskElement(Element element, MaskingRegistry registry) {
        if (element == null) {
            return;
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            if (registry.isSensitiveField(attr.getName())) {
                attr.setValue(registry.mask(attr.getName(), attr.getValue()));
            }
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            String name = child.getTagName();
            if (registry.isSensitiveField(name)) {
                child.setTextContent(registry.mask(name, child.getTextContent()));
            }
            maskElement(child, registry);
        }
    }

    static DocumentBuilderFactory newSecureDocumentBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
        factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
        factory.setFeature(LOAD_EXTERNAL_DTD, false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, NO_EXTERNAL_ACCESS);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, NO_EXTERNAL_ACCESS);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /**
     * Serialisation only ever runs an identity transform over a DOM already parsed above, so denying
     * external DTD and stylesheet access here is defence in depth (CWE-611).
     */
    static TransformerFactory newSecureTransformerFactory()
            throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, NO_EXTERNAL_ACCESS);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, NO_EXTERNAL_ACCESS);
        return factory;
    }
}
