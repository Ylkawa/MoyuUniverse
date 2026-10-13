package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.AIChat.XmlMarks;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GraphParser {
    private record Child(Element element, String path) {}

    public GraphSpec parse(String xml) {
        Element element;
        try { element = XmlMarks.parse(xml); }
        catch (SAXParseException e) {
            throw new GraphParseException("/", "XML line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " + e.getMessage(), e);
        } catch (Exception e) { throw new GraphParseException("/", "Invalid XML: " + e.getMessage(), e); }
        return parse(element);
    }

    public GraphSpec parse(Element root) {
        String path = "/graph";
        if (root == null || !root.getTagName().equals("graph")) throw error(path, "Expected graph root");
        String type = required(root, "type", path);
        Set<String> supported = Set.of("list", "compare", "timeline", "tree", "flow", "relation");
        if (!supported.contains(type)) throw error(path + "/@type", "Unsupported graph type: " + type);
        attributes(root, type.equals("relation") ? Set.of("type", "title", "directed") : Set.of("type", "title"), path);
        String title = root.getAttribute("title");
        List<Child> children = children(root, path);
        if (Set.of("list", "compare", "timeline").contains(type)) {
            List<GraphSpec.Item> items = new ArrayList<>();
            for (Child child : children) {
                expect(child, "line");
                Set<String> attrs = type.equals("timeline") ? Set.of("time", "icon")
                        : type.equals("compare") ? Set.of("label", "icon") : Set.of("icon");
                attributes(child.element(), attrs, child.path());
                String text = lineText(child);
                String time = type.equals("timeline") ? required(child.element(), "time", child.path()) : "";
                items.add(new GraphSpec.Item(child.element().getAttribute("label"), text, time, icon(child)));
            }
            if (items.size() < (type.equals("compare") ? 2 : 1)) throw error(path, "Not enough line items for " + type);
            return switch (type) {
                case "list" -> new GraphSpec.ListGraph(title, items);
                case "compare" -> new GraphSpec.CompareGraph(title, items);
                default -> new GraphSpec.TimelineGraph(title, items);
            };
        }
        Map<String, String> ids = new HashMap<>();
        if (type.equals("tree")) {
            if (children.size() != 1) throw error(path, "Tree requires exactly one root node");
            return new GraphSpec.TreeGraph(title, node(children.get(0), true, ids));
        }
        List<GraphSpec.Node> nodes = new ArrayList<>();
        List<GraphSpec.Edge> edges = new ArrayList<>();
        List<Child> edgeChildren = new ArrayList<>();
        for (Child child : children) {
            if (child.element().getTagName().equals("node")) nodes.add(node(child, false, ids));
            else {
                expect(child, "edge");
                attributes(child.element(), Set.of("from", "to", "label"), child.path());
                if (!children(child.element(), child.path()).isEmpty()) throw error(child.path(), "Edge cannot contain elements");
                edges.add(new GraphSpec.Edge(required(child.element(), "from", child.path()),
                        required(child.element(), "to", child.path()), child.element().getAttribute("label")));
                edgeChildren.add(child);
            }
        }
        if (nodes.isEmpty()) throw error(path, "At least one node is required");
        for (int i = 0; i < edges.size(); i++) {
            GraphSpec.Edge edge = edges.get(i);
            if (!ids.containsKey(edge.from())) throw error(edgeChildren.get(i).path() + "/@from", "Unknown node: " + edge.from());
            if (!ids.containsKey(edge.to())) throw error(edgeChildren.get(i).path() + "/@to", "Unknown node: " + edge.to());
        }
        if (type.equals("flow")) return new GraphSpec.FlowGraph(title, nodes, edges);
        boolean directed = false;
        if (root.hasAttribute("directed")) {
            String value = root.getAttribute("directed");
            if (!value.equals("true") && !value.equals("false")) throw error(path + "/@directed", "Expected true or false");
            directed = Boolean.parseBoolean(value);
        }
        return new GraphSpec.RelationGraph(title, directed, nodes, edges);
    }

    private GraphSpec.Node node(Child child, boolean tree, Map<String, String> ids) {
        expect(child, "node");
        attributes(child.element(), Set.of("id", "label", "icon"), child.path());
        String id = required(child.element(), "id", child.path());
        String previous = ids.putIfAbsent(id, child.path());
        if (previous != null) throw error(child.path() + "/@id", "Duplicate node ID " + id + " (first at " + previous + ")");
        String label = required(child.element(), "label", child.path());
        List<Child> children = children(child.element(), child.path());
        if (!tree && !children.isEmpty()) throw error(child.path(), "Nodes must be flat for this graph type");
        List<GraphSpec.Node> nested = new ArrayList<>();
        for (Child value : children) nested.add(node(value, true, ids));
        return new GraphSpec.Node(id, label, icon(child), nested);
    }

    private static String icon(Child child) {
        if (!child.element().hasAttribute("icon")) return "";
        return required(child.element(), "icon", child.path());
    }
    private static String lineText(Child child) {
        StringBuilder text = new StringBuilder();
        for (Node n = child.element().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) text.append(n.getNodeValue());
            else if (n.getNodeType() != Node.COMMENT_NODE) throw error(child.path(), "Line accepts only text and CDATA");
        }
        if (text.toString().isBlank()) throw error(child.path(), "Line text is required");
        return text.toString();
    }
    private static List<Child> children(Element element, String path) {
        List<Child> result = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                int index = counts.merge(child.getTagName(), 1, Integer::sum);
                result.add(new Child(child, path + "/" + child.getTagName() + "[" + index + "]"));
            } else if (n.getNodeType() != Node.COMMENT_NODE
                    && !((n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) && n.getNodeValue().isBlank())) {
                throw error(path, "Unexpected content outside an item");
            }
        }
        return result;
    }
    private static void attributes(Element element, Set<String> allowed, String path) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            String name = element.getAttributes().item(i).getNodeName();
            if (!allowed.contains(name)) throw error(path + "/@" + name, "Unsupported attribute");
        }
    }
    private static String required(Element element, String name, String path) {
        String value = element.getAttribute(name);
        if (value.isBlank()) throw error(path + "/@" + name, "Required attribute is missing or blank");
        return value;
    }
    private static void expect(Child child, String tag) {
        if (!child.element().getTagName().equals(tag)) throw error(child.path(), "Expected " + tag);
    }
    private static GraphParseException error(String path, String message) { return new GraphParseException(path, message); }
}
