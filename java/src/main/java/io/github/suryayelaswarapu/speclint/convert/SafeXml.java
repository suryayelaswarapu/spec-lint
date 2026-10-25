package io.github.suryayelaswarapu.speclint.convert;

import io.github.suryayelaswarapu.speclint.SpecLintException.InputException;
import io.github.suryayelaswarapu.speclint.SpecLintException.SecurityException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Hardened, namespace-aware DOM parsing. DOCTYPE declarations are rejected outright (blocking
 * XXE file disclosure, SSRF and entity-expansion attacks), and external DTD/schema access is
 * disabled as a second line of defence. Follows the OWASP XXE prevention cheat sheet.
 */
public final class SafeXml {
    private SafeXml() {}

    /** A resolved XML qualified name. */
    public record QName(String ns, String local) {}

    static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new ErrorHandler() { // throw instead of printing to stderr
                @Override
                public void warning(SAXParseException e) {}

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser does not support secure settings", e);
        }
    }

    public static Element parse(String text) {
        try {
            Document doc = builder().parse(new InputSource(new StringReader(text)));
            return doc.getDocumentElement();
        } catch (SAXException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("DOCTYPE")) {
                throw new SecurityException("Unsafe XML rejected: DTD/entity declarations are "
                        + "not allowed", e);
            }
            throw new InputException("Invalid XML: " + message, e);
        } catch (IOException e) {
            throw new InputException("Invalid XML: " + e.getMessage(), e);
        }
    }

    // --------------------------------------------------------------- helpers

    public static boolean is(Node node, String ns, String local) {
        return node instanceof Element e && ns.equals(e.getNamespaceURI())
                && local.equals(e.getLocalName());
    }

    public static String nsOf(Element e) {
        return e.getNamespaceURI() == null ? "" : e.getNamespaceURI();
    }

    /** Direct element children. */
    public static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    /** Direct element children with the given namespace and local name. */
    public static List<Element> children(Element parent, String ns, String local) {
        return children(parent).stream().filter(c -> is(c, ns, local)).toList();
    }

    public static Element child(Element parent, String ns, String local) {
        for (Element c : children(parent)) {
            if (is(c, ns, local)) {
                return c;
            }
        }
        return null;
    }

    /** Descendants (excluding {@code root}) in document order. */
    public static List<Element> descendants(Element root, String ns, String local) {
        List<Element> out = new ArrayList<>();
        collect(root, ns, local, out);
        return out;
    }

    private static void collect(Element node, String ns, String local, List<Element> out) {
        for (Element c : children(node)) {
            if (ns == null || is(c, ns, local)) {
                out.add(c);
            }
            collect(c, ns, local, out);
        }
    }

    /** The attribute value, or null when absent (DOM returns "" for missing attributes). */
    public static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    public static String attr(Element e, String name, String fallback) {
        return e.hasAttribute(name) ? e.getAttribute(name) : fallback;
    }

    /** Resolve a QName attribute value ({@code tns:Foo} or {@code Foo}) in {@code e}'s scope. */
    public static QName resolve(Element e, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        int colon = value.lastIndexOf(':');
        String prefix = colon < 0 ? null : value.substring(0, colon);
        String local = colon < 0 ? value : value.substring(colon + 1);
        String ns = e.lookupNamespaceURI(prefix);
        return new QName(ns == null ? "" : ns, local);
    }
}
