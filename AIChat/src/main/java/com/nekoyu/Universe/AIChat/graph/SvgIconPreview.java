package com.nekoyu.Universe.AIChat.graph;

import org.apache.batik.bridge.UserAgent;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.util.ParsedURL;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Only rasterizes individual imported icons for indexing, never a complete graph. */
public final class SvgIconPreview {
    public static final String VERSION = "lucide-black-white-256-padding32-v1";
    public byte[] render(byte[] svg) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            });
            Document document = builder.parse(new InputSource(new ByteArrayInputStream(svg)));
            Element root = document.getDocumentElement();
            if (!"svg".equals(root.getLocalName()) || !"http://www.w3.org/2000/svg".equals(root.getNamespaceURI()))
                throw new IOException("Expected an SVG document");
            var elements = document.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                if ("script".equals(element.getLocalName()) || "foreignObject".equals(element.getLocalName()))
                    throw new IOException("Executable SVG content is not supported");
                for (int a = 0; a < element.getAttributes().getLength(); a++) {
                    var attr = element.getAttributes().item(a);
                    if (attr.getNodeName().startsWith("on")) throw new IOException("SVG event handlers are not supported");
                    if (("href".equals(attr.getLocalName()) || "href".equals(attr.getNodeName())) && !attr.getNodeValue().startsWith("#"))
                        throw new IOException("External SVG resources are not supported");
                }
            }
            root.setAttribute("color", "#000000"); root.setAttribute("stroke", "#000000");
            PNGTranscoder transcoder = new PNGTranscoder() {
                @Override protected UserAgent createUserAgent() {
                    return new UserAgentAdapter() {
                        @Override public void checkLoadScript(String type, ParsedURL script, ParsedURL doc) { throw new SecurityException("SVG scripts disabled"); }
                        @Override public void checkLoadExternalResource(ParsedURL resource, ParsedURL doc) { throw new SecurityException("External SVG resources disabled"); }
                    };
                }
            };
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, 192f);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, 192f);
            ByteArrayOutputStream raster = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(document), new TranscoderOutput(raster));
            BufferedImage icon = ImageIO.read(new ByteArrayInputStream(raster.toByteArray()));
            if (icon == null) throw new IOException("SVG rasterization returned no image");
            BufferedImage preview = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = preview.createGraphics();
            try { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 256, 256); graphics.drawImage(icon, 32, 32, null); }
            finally { graphics.dispose(); }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(preview, "png", output)) throw new IOException("PNG encoder unavailable");
            return output.toByteArray();
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Cannot rasterize SVG icon", e); }
    }
}
