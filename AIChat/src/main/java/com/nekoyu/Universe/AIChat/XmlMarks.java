package com.nekoyu.Universe.AIChat;

import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.regex.Pattern;

/** Locates outer XML elements without parsing the surrounding prose. */
public final class XmlMarks {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]*");
    private XmlMarks() {}

    /** complete is true only for a closed element (or opaque markup), never an orphan closing tag. */
    public record Span(int start, int end, String name, boolean selfClosing, boolean complete) {
        public Span(int start, int end, String name, boolean selfClosing) {
            this(start, end, name, selfClosing, true);
        }
    }

    /** Child elements belong to their outer element; they are never separate dispatch spans. */
    public static List<Span> scan(String text) {
        List<Span> spans = new ArrayList<>();
        ArrayDeque<String> paired = new ArrayDeque<>();
        int pairStart = -1;
        String pairName = "";
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf('<', cursor);
            if (start < 0) break;
            String special = text.startsWith("<!--", start) ? "-->"
                    : text.startsWith("<![CDATA[", start) ? "]]>"
                    : text.startsWith("<?", start) ? "?>" : null;
            if (special != null) {
                int end = text.indexOf(special, start + 2);
                boolean complete = end >= 0;
                end = complete ? end + special.length() : text.length();
                if (paired.isEmpty()) spans.add(new Span(start, end, "", false, complete));
                cursor = end;
                continue;
            }
            // Declarations may contain quoted markup and an internal subset. Keep them opaque.
            if (text.startsWith("<!", start)) {
                int end = declarationEnd(text, start + 2);
                boolean complete = end >= 0;
                end = complete ? end + 1 : text.length();
                if (paired.isEmpty()) spans.add(new Span(start, end, "", false, complete));
                cursor = end;
                continue;
            }
            int nameStart = start + 1;
            boolean closing = nameStart < text.length() && text.charAt(nameStart) == '/';
            if (closing) nameStart++;
            var matcher = NAME.matcher(text).region(nameStart, text.length());
            if (!matcher.lookingAt()) { cursor = start + 1; continue; }
            String name = matcher.group();
            if (name.contains(":")) { cursor = matcher.end(); continue; }
            int nameEnd = matcher.end();
            if (nameEnd < text.length() && !Character.isWhitespace(text.charAt(nameEnd))
                    && text.charAt(nameEnd) != '/' && text.charAt(nameEnd) != '>') {
                cursor = nameEnd;
                continue; // The non-XML ReferenceId label is not a marker.
            }
            int end = tagEnd(text, nameEnd);
            if (end < 0) {
                if (paired.isEmpty()) spans.add(new Span(start, text.length(), name, false, false));
                cursor = text.length();
                break;
            }
            boolean selfClosing = !closing && end > start && text.charAt(end - 1) == '/';
            if (closing) {
                if (!paired.isEmpty() && paired.contains(name)) {
                    // Recover at an ancestor's closing tag; DOM parsing still rejects bad nesting.
                    while (!paired.pop().equals(name)) { }
                    if (paired.isEmpty()) spans.add(new Span(pairStart, end + 1, pairName, false));
                } else if (paired.isEmpty()) spans.add(new Span(start, end + 1, name, false, false));
            } else if (selfClosing) {
                if (paired.isEmpty()) spans.add(new Span(start, end + 1, name, true));
            } else {
                if (paired.isEmpty()) { pairStart = start; pairName = name; }
                paired.push(name);
            }
            cursor = end + 1;
        }
        if (!paired.isEmpty()) spans.add(new Span(pairStart, text.length(), pairName, false, false));
        return spans;
    }

    private static int tagEnd(String text, int start) {
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return i;
        }
        return -1;
    }

    private static int declarationEnd(String text, int start) {
        char quote = 0;
        int brackets = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
            else if (c == '>' && brackets == 0) return i;
        }
        return -1;
    }

    /** Namespace-free marker names, matching the scanner rather than legacy colon labels. */
    public static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.contains(":")) {
            throw new IllegalArgumentException("Invalid XML marker name: " + name);
        }
    }

    public static Element parse(String marker) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        });
        return builder.parse(new InputSource(new StringReader(marker))).getDocumentElement();
    }

    public static String requiredAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (value.isBlank()) throw new IllegalArgumentException("Missing marker attribute: " + name);
        return value;
    }

    public static String marker(String name, String attribute, String value) {
        requireName(name);
        requireName(attribute);
        return "<" + name + " " + attribute + "=\"" + escapeAttribute(value) + "\"/>";
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;")
                .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
    }

    /** Returns a paragraph separator outside markup, or -1 while an element is incomplete. */
    public static int paragraphEnd(String text) {
        if (text.indexOf("\n\n") < 0) return -1;
        List<Span> spans = scan(text);
        int from = 0;
        for (Span span : spans) {
            int separator = text.indexOf("\n\n", from);
            if (separator >= 0 && separator + 2 <= span.start()) return separator;
            from = span.end();
        }
        return text.indexOf("\n\n", from);
    }
}
