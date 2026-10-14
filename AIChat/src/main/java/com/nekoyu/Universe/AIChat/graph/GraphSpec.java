package com.nekoyu.Universe.AIChat.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Rendering-independent graph structures. Strings and encounter order are preserved. */
public sealed interface GraphSpec permits GraphSpec.ListGraph, GraphSpec.CompareGraph,
        GraphSpec.TimelineGraph, GraphSpec.TreeGraph, GraphSpec.FlowGraph, GraphSpec.RelationGraph {
    enum Type { LIST, COMPARE, TIMELINE, TREE, FLOW, RELATION }
    Type type();
    String title();
    List<IconSlot> iconSlots();

    record IconSlot(String key, String query) {
        public IconSlot { Objects.requireNonNull(key); Objects.requireNonNull(query); }
    }
    record Item(String label, String text, String time, String icon) {
        public Item { Objects.requireNonNull(label); Objects.requireNonNull(text); Objects.requireNonNull(time); Objects.requireNonNull(icon); }
    }
    record Node(String id, String label, String icon, List<Node> children) {
        public Node { Objects.requireNonNull(id); Objects.requireNonNull(label); Objects.requireNonNull(icon); children = List.copyOf(children); }
    }
    record Edge(String from, String to, String label) {
        public Edge { Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(label); }
    }
    record ListGraph(String title, List<Item> items) implements GraphSpec {
        public ListGraph { Objects.requireNonNull(title); items = List.copyOf(items); }
        public Type type() { return Type.LIST; }
        public List<IconSlot> iconSlots() { return itemSlots(items); }
    }
    record CompareObject(String id, String label, String icon) {
        public CompareObject { Objects.requireNonNull(id); Objects.requireNonNull(label); Objects.requireNonNull(icon); }
    }
    record CompareCell(String object, String text) {
        public CompareCell { Objects.requireNonNull(object); Objects.requireNonNull(text); }
    }
    record CompareRow(String label, List<CompareCell> cells) {
        public CompareRow { Objects.requireNonNull(label); cells = List.copyOf(cells); }
    }
    record CompareGraph(String title, List<CompareObject> objects, List<CompareRow> rows) implements GraphSpec {
        public CompareGraph { Objects.requireNonNull(title); objects = List.copyOf(objects); rows = List.copyOf(rows); }
        public Type type() { return Type.COMPARE; }
        public List<IconSlot> iconSlots() {
            return objects.stream().filter(o -> !o.icon().isEmpty())
                    .map(o -> new IconSlot("object:" + o.id(), o.icon())).toList();
        }
    }
    record TimelineGraph(String title, List<Item> items) implements GraphSpec {
        public TimelineGraph { Objects.requireNonNull(title); items = List.copyOf(items); }
        public Type type() { return Type.TIMELINE; }
        public List<IconSlot> iconSlots() { return itemSlots(items); }
    }
    record TreeGraph(String title, Node root) implements GraphSpec {
        public TreeGraph { Objects.requireNonNull(title); Objects.requireNonNull(root); }
        public Type type() { return Type.TREE; }
        public List<IconSlot> iconSlots() { return nodeSlots(List.of(root)); }
    }
    record FlowGraph(String title, List<Node> nodes, List<Edge> edges) implements GraphSpec {
        public FlowGraph { Objects.requireNonNull(title); nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
        public Type type() { return Type.FLOW; }
        public List<IconSlot> iconSlots() { return nodeSlots(nodes); }
    }
    record RelationGraph(String title, boolean directed, List<Node> nodes, List<Edge> edges) implements GraphSpec {
        public RelationGraph { Objects.requireNonNull(title); nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
        public Type type() { return Type.RELATION; }
        public List<IconSlot> iconSlots() { return nodeSlots(nodes); }
    }
    private static List<IconSlot> itemSlots(List<Item> items) {
        List<IconSlot> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).icon().isEmpty()) slots.add(new IconSlot("line[" + (i + 1) + "]", items.get(i).icon()));
        }
        return List.copyOf(slots);
    }
    private static List<IconSlot> nodeSlots(List<Node> nodes) {
        List<IconSlot> slots = new ArrayList<>();
        for (Node node : nodes) {
            if (!node.icon().isEmpty()) slots.add(new IconSlot("node:" + node.id(), node.icon()));
            slots.addAll(nodeSlots(node.children()));
        }
        return List.copyOf(slots);
    }
}
