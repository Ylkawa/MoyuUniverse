package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.GraphSpec;
import com.nekoyu.Universe.AIChat.graph.ResolvedGraph;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded native layouts: horizontal trees, directed layers and degree-centred relations. */
final class NodeGraphLayouts {
    static final int NODE_WIDTH = 320, GAP = 88;
    record Box(GraphSpec.Node node, List<GraphRenderContext.Line> text,
                       float x, float y, float height, boolean crossPage) {}
    record Link(GraphSpec.Edge edge, List<GraphRenderContext.Line> label, String reference) {}
    record Route(Link link, Path2D.Float path, Path2D.Float arrow) {}
    private record CrossLink(List<GraphRenderContext.Line> text) {}
    private record Caption(List<GraphRenderContext.Line> text, Rectangle2D.Float area) {}

    static List<GraphPage> layout(ResolvedGraph resolved, List<GraphSpec.Node> nodes, List<GraphSpec.Edge> edges,
                                boolean directed, boolean ring, GraphRenderContext context) throws IOException {
        Map<String, List<GraphRenderContext.Line>> text = new LinkedHashMap<>();
        float nodeHeight = 80;
        for (GraphSpec.Node node : nodes) {
            if (node.label().length() > 400 || node.id().length() > 80) throw new IOException("Node label/ID exceeds rendering limit");
            var label = context.text(node.label(), NODE_WIDTH - (node.icon().isEmpty() ? 40 : 88), 24, true);
            text.put(node.id(), label);
            nodeHeight = Math.max(nodeHeight, GraphRenderContext.textHeight(label) + 40);
        }
        boolean tree = resolved.graph().type() == GraphSpec.Type.TREE;
        if (tree && !treeFits(nodes.get(0), nodeHeight, context)) {
            return OutlineTreeLayout.layout(resolved, nodes, edges, context);
        }
        if (nodeHeight + GAP > context.bodyHeight()) throw new IOException("Node cannot fit on a page");
        // Worst-case one node per layer still fits; no shrinking text or cropping nodes.
        int capacity = tree ? nodes.size() : Math.max(1, Math.min(10, (int) ((context.bodyHeight() - 128) / (nodeHeight + GAP))));
        int graphPages = (nodes.size() + capacity - 1) / capacity;
        Map<String, Integer> pageNumbers = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) pageNumbers.put(nodes.get(i).id(), i / capacity + 1);
        List<GraphSpec.Edge> indexedEdges = new ArrayList<>();
        Map<GraphSpec.Edge, String> references = references(edges);
        Set<String> crossNodes = new HashSet<>();
        for (GraphSpec.Edge edge : edges) {
            if (edge.label().length() > 200) throw new IOException("Edge label exceeds rendering limit");
            if (!pageNumbers.get(edge.from()).equals(pageNumbers.get(edge.to()))) {
                crossNodes.add(edge.from()); crossNodes.add(edge.to());
            }
            if (!pageNumbers.get(edge.from()).equals(pageNumbers.get(edge.to()))) indexedEdges.add(edge);
        }
        List<GraphPage> result = new ArrayList<>();
        var matches = context.matches(resolved);
        for (int start = 0; start < nodes.size(); start += capacity) {
            List<GraphSpec.Node> subset = List.copyOf(nodes.subList(start, Math.min(start + capacity, nodes.size())));
            Set<String> localIds = new HashSet<>(); subset.forEach(n -> localIds.add(n.id()));
            List<GraphSpec.Edge> localEdges = edges.stream().filter(e -> localIds.contains(e.from()) && localIds.contains(e.to())).toList();
            Map<String, Point2D.Float> points = ring ? relations(subset, localEdges, nodeHeight, context)
                    : resolved.graph().type() == GraphSpec.Type.TREE && graphPages == 1 ? tree(subset, nodeHeight, context)
                    : layered(subset, localEdges, nodeHeight, context);
            List<Box> boxes = new ArrayList<>();
            for (GraphSpec.Node node : subset) {
                Point2D.Float p = points.get(node.id());
                boxes.add(new Box(node, text.get(node.id()), p.x, p.y, nodeHeight, crossNodes.contains(node.id())));
            }
            List<Link> links = new ArrayList<>();
            for (GraphSpec.Edge edge : localEdges) links.add(new Link(edge, edge.label().isEmpty() ? List.of() : context.text(edge.label(), 240, 16, false), references.get(edge)));
            String heading = title(resolved);
            if (graphPages > 1) heading += " · 节点分图 " + (start / capacity + 1);
            List<Box> snapshot = List.copyOf(boxes); List<Link> linkSnapshot = List.copyOf(links);
            String focalId = tree ? nodes.get(0).id() : ring ? subset.stream()
                    .max(java.util.Comparator.comparingInt(n -> degree(n.id(), localEdges))).orElseThrow().id() : "";
            Map<String, Box> locations = new HashMap<>(); snapshot.forEach(b -> locations.put(b.node().id(), b));
            List<Route> routes = new ArrayList<>(); int route = 0;
            for (Link link : linkSnapshot) routes.add(tree ? treeRoute(locations.get(link.edge().from()), locations.get(link.edge().to()), link)
                    : routeEdge(context, locations.get(link.edge().from()), locations.get(link.edge().to()), link, directed, ring, route++, snapshot));
            List<Shape> obstacles = new ArrayList<>();
            snapshot.forEach(box -> obstacles.add(new Rectangle2D.Float(box.x() - 5, box.y() - 5, NODE_WIDTH + 10, box.height() + 10)));
            var clearance = new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            for (Route edge : routes) {
                obstacles.add(clearance.createStrokedShape(edge.path()));
                if (edge.arrow() != null) { obstacles.add(edge.arrow()); obstacles.add(clearance.createStrokedShape(edge.arrow())); }
            }
            List<Rectangle2D.Float> occupied = new ArrayList<>(); List<Caption> captions = new ArrayList<>();
            for (Route edge : routes) {
                if (edge.link().label().isEmpty()) continue;
                var lines = edge.link().label(); var place = labelArea(lines, edge.path(), snapshot, obstacles, occupied, context);
                if (place == null) {
                    indexedEdges.add(edge.link().edge());
                    lines = context.text(edge.link().reference(), 80, 16, true);
                    place = labelArea(lines, edge.path(), snapshot, obstacles, occupied, context);
                }
                if (place != null) { occupied.add(place.area()); captions.add(new Caption(lines, place.area())); }
            }
            int bottom = (int) Math.ceil(snapshot.stream().mapToDouble(box -> box.y() + box.height()).max().orElse(GraphRenderContext.TOP));
            result.add(new GraphPage(heading, bottom, (graphics, c) -> {
                graphics.setColor(GraphRenderContext.ACCENT); graphics.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (Route edge : routes) {
                    graphics.draw(edge.path());
                    if (edge.arrow() != null) graphics.fill(edge.arrow());
                }
                for (Caption caption : captions) GraphRenderContext.drawText(graphics, caption.text(), caption.area().x + 4, caption.area().y + 2, GraphRenderContext.MUTED);
                for (Box box : snapshot) {
                    GraphRenderContext.box(graphics, box.x(), box.y(), NODE_WIDTH, box.height());
                    boolean prominent = box.node().id().equals(focalId);
                    if (prominent) { graphics.setColor(GraphRenderContext.SOFT); graphics.fillRoundRect((int) box.x() + 1, (int) box.y() + 1, NODE_WIDTH - 2, (int) box.height() - 2, 16, 16); }
                    String slot = "node:" + box.node().id();
                    c.slotIcon(graphics, slot, matches.get(slot), box.x() + 18, box.y() + (box.height() - 34) / 2, 34);
                    float textY = box.y() + (box.height() - GraphRenderContext.textHeight(box.text())) / 2;
                    if (c.visibleIcon(slot, matches.get(slot))) GraphRenderContext.drawText(graphics, box.text(), box.x() + 68, textY, GraphRenderContext.INK);
                    else {
                        graphics.setColor(GraphRenderContext.INK);
                        for (var line : box.text()) {
                            line.layout().draw(graphics, box.x() + (NODE_WIDTH - line.layout().getAdvance()) / 2, textY + line.layout().getAscent());
                            textY += line.height();
                        }
                    }
                    if (box.crossPage()) {
                        graphics.setColor(GraphRenderContext.ACCENT); graphics.setFont(c.font(13, false));
                        graphics.drawString("↗", box.x() + NODE_WIDTH - 18, box.y() + 17);
                    }
                }
            }));
        }
        if (!indexedEdges.isEmpty()) appendCrossLinks(resolved, indexedEdges, pageNumbers, context, result, directed, edges);
        if (result.size() > context.maxPages()) throw new IOException("Graph exceeds maximum page count");
        return List.copyOf(result);
    }

    /** Each subtree owns a disjoint vertical interval, so unrelated parents never share a bus. */
    static Map<String, Point2D.Float> tree(List<GraphSpec.Node> nodes, float height, GraphRenderContext c) {
        var counts = new HashMap<String, Integer>();
        leafCount(nodes.get(0), counts);
        Map<String, Point2D.Float> result = new LinkedHashMap<>();
        float left = (c.width() - ((depth(nodes.get(0)) + 1) * NODE_WIDTH + depth(nodes.get(0)) * GAP)) / 2f;
        treePoints(nodes.get(0), left, GraphRenderContext.TOP + 16, 0, height, counts, result);
        return result;
    }
    private static boolean treeFits(GraphSpec.Node root, float height, GraphRenderContext c) {
        int depth = depth(root), leaves = leafCount(root, new HashMap<>());
        return (depth + 1) * NODE_WIDTH + depth * GAP <= c.bodyWidth()
                && leaves * (height + 28) - 28 + 16 <= c.bodyHeight();
    }
    private static int depth(GraphSpec.Node node) {
        return node.children().isEmpty() ? 0 : 1 + node.children().stream().mapToInt(NodeGraphLayouts::depth).max().orElse(0);
    }
    private static int leafCount(GraphSpec.Node node, Map<String, Integer> counts) {
        int value = node.children().isEmpty() ? 1 : node.children().stream().mapToInt(child -> leafCount(child, counts)).sum();
        counts.put(node.id(), value); return value;
    }
    private static void treePoints(GraphSpec.Node node, float left, float top, int depth, float height, Map<String, Integer> counts,
                                   Map<String, Point2D.Float> result) {
        float span = counts.get(node.id()) * (height + 28) - 28;
        result.put(node.id(), new Point2D.Float(left + depth * (NODE_WIDTH + GAP), top + (span - height) / 2));
        for (var child : node.children()) {
            treePoints(child, left, top, depth + 1, height, counts, result);
            top += counts.get(child.id()) * (height + 28);
        }
    }
    private static Route treeRoute(Box from, Box to, Link link) {
        float sx = from.x() + NODE_WIDTH, sy = from.y() + from.height() / 2;
        float tx = to.x(), ty = to.y() + to.height() / 2, bus = (sx + tx) / 2;
        Path2D.Float path = new Path2D.Float(); path.moveTo(sx, sy); path.lineTo(bus, sy); path.lineTo(bus, ty); path.lineTo(tx, ty);
        return new Route(link, path, null);
    }
    private static int degree(String id, List<GraphSpec.Edge> edges) {
        return (int) edges.stream().filter(e -> e.from().equals(id) || e.to().equals(id)).count();
    }
    /** Keep connected satellite groups together around the highest-degree node. */
    private static Map<String, Point2D.Float> relations(List<GraphSpec.Node> nodes, List<GraphSpec.Edge> edges, float height, GraphRenderContext c) {
        if (c.bodyWidth() < 3 * NODE_WIDTH + 240 || nodes.size() < 3) return layered(nodes, List.of(), height, c);
        GraphSpec.Node hub = nodes.stream().max(java.util.Comparator.comparingInt(n -> degree(n.id(), edges))).orElseThrow();
        List<GraphSpec.Node> left = new ArrayList<>(), right = new ArrayList<>(); Set<String> placed = new HashSet<>(); placed.add(hub.id());
        for (var seed : nodes) {
            if (!placed.add(seed.id())) continue;
            List<GraphSpec.Node> group = new ArrayList<>(); group.add(seed);
            for (int i = 0; i < group.size(); i++) {
                String id = group.get(i).id();
                for (var candidate : nodes) if (!placed.contains(candidate.id()) && edges.stream().anyMatch(e ->
                        e.from().equals(id) && e.to().equals(candidate.id()) || e.to().equals(id) && e.from().equals(candidate.id()))) {
                    placed.add(candidate.id()); group.add(candidate);
                }
            }
            (left.size() <= right.size() ? left : right).addAll(group);
        }
        // A large connected satellite group is split only when it would overflow the page.
        int capacity = Math.max(1, (int) ((c.bodyHeight() - 32) / (height + GAP)));
        while (left.size() > capacity) right.add(left.remove(left.size() - 1));
        while (right.size() > capacity) left.add(right.remove(right.size() - 1));
        if (left.size() > capacity) return layered(nodes, List.of(), height, c);
        int rows = Math.max(left.size(), right.size()); float span = rows * (height + GAP) - GAP;
        Map<String, Point2D.Float> result = new LinkedHashMap<>();
        result.put(hub.id(), new Point2D.Float((c.width() - NODE_WIDTH) / 2f, GraphRenderContext.TOP + 32 + (span - height) / 2));
        for (var side : List.of(left, right)) {
            float x = side == left ? GraphRenderContext.MARGIN + 20 : c.width() - GraphRenderContext.MARGIN - NODE_WIDTH - 20;
            float top = GraphRenderContext.TOP + 32 + (span - (side.size() * (height + GAP) - GAP)) / 2;
            for (int i = 0; i < side.size(); i++) result.put(side.get(i).id(), new Point2D.Float(x, top + i * (height + GAP)));
        }
        return result;
    }

    static Map<String, Point2D.Float> layered(List<GraphSpec.Node> nodes, List<GraphSpec.Edge> edges, float height, GraphRenderContext context) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>(); nodes.forEach(n -> adjacency.put(n.id(), new ArrayList<>()));
        for (GraphSpec.Edge edge : edges) adjacency.get(edge.from()).add(edge.to());
        // Collapse strongly connected components before ranking, so loops never cause unbounded ranking.
        List<List<String>> components = new ArrayList<>();
        Map<String, Integer> numbers = new HashMap<>(), low = new HashMap<>();
        List<String> stack = new ArrayList<>(); Set<String> active = new HashSet<>(); int[] next = {0};
        for (GraphSpec.Node node : nodes) if (!numbers.containsKey(node.id())) scc(node.id(), adjacency, numbers, low, stack, active, next, components);
        Map<String, Integer> group = new HashMap<>();
        for (int i = 0; i < components.size(); i++) for (String id : components.get(i)) group.put(id, i);
        int[] rank = new int[components.size()];
        for (int iteration = 0; iteration < components.size(); iteration++) {
            boolean changed = false;
            for (GraphSpec.Edge edge : edges) {
                int a = group.get(edge.from()), b = group.get(edge.to());
                if (a != b && rank[b] < rank[a] + 1) { rank[b] = rank[a] + 1; changed = true; }
            }
            if (!changed) break;
        }
        // A shared terminal is a side destination, not another step in the main sequence.
        var terminals = nodes.stream().filter(n -> adjacency.get(n.id()).isEmpty()
                && edges.stream().filter(e -> e.to().equals(n.id())).count() > 1).toList();
        GraphSpec.Node side = context.width() >= 1100 && terminals.size() == 1
                && components.stream().allMatch(component -> component.size() == 1) ? terminals.get(0) : null;
        Map<Integer, List<GraphSpec.Node>> layers = new java.util.TreeMap<>();
        for (GraphSpec.Node node : nodes) if (node != side) layers.computeIfAbsent(rank[group.get(node.id())], ignored -> new ArrayList<>()).add(node);
        if (side != null && layers.values().stream().anyMatch(layer -> layer.size() > 1)) {
            layers.computeIfAbsent(rank[group.get(side.id())], ignored -> new ArrayList<>()).add(side); side = null;
        }
        int columns = Math.max(1, (context.bodyWidth() + GAP) / (NODE_WIDTH + GAP));
        Map<String, Point2D.Float> result = new LinkedHashMap<>(); int row = 0;
        for (List<GraphSpec.Node> layer : layers.values()) {
            for (int start = 0; start < layer.size(); start += columns) {
                int count = Math.min(columns, layer.size() - start);
                float x = (context.width() - (count * NODE_WIDTH + (count - 1) * GAP)) / 2f - (side == null ? 0 : 220);
                int top = edges.stream().anyMatch(e -> group.get(e.from()).equals(group.get(e.to())) && !e.from().equals(e.to())) ? 112 : 16;
                for (int i = 0; i < count; i++) result.put(layer.get(start + i).id(), new Point2D.Float(x + i * (NODE_WIDTH + GAP), GraphRenderContext.TOP + top + row * (height + GAP)));
                row++;
            }
        }
        if (side != null) {
            String sideId = side.id();
            var incoming = edges.stream().filter(e -> e.to().equals(sideId)).map(e -> result.get(e.from()).y).sorted().toList();
            float y = incoming.get(incoming.size() / 2);
            result.put(side.id(), new Point2D.Float(context.width() - GraphRenderContext.MARGIN - NODE_WIDTH - 40, y));
        }
        return result;
    }
    private static void scc(String id, Map<String, List<String>> adjacency, Map<String, Integer> numbers, Map<String, Integer> low,
                            List<String> stack, Set<String> active, int[] next, List<List<String>> components) {
        numbers.put(id, next[0]); low.put(id, next[0]++); stack.add(id); active.add(id);
        for (String target : adjacency.get(id)) {
            if (!numbers.containsKey(target)) { scc(target, adjacency, numbers, low, stack, active, next, components); low.put(id, Math.min(low.get(id), low.get(target))); }
            else if (active.contains(target)) low.put(id, Math.min(low.get(id), numbers.get(target)));
        }
        if (low.get(id).equals(numbers.get(id))) {
            List<String> component = new ArrayList<>(); String value;
            do { value = stack.remove(stack.size() - 1); active.remove(value); component.add(value); } while (!value.equals(id));
            components.add(component);
        }
    }

    static Route routeEdge(GraphRenderContext context, Box from, Box to, Link link, boolean directed,
                                   boolean ring, int route, List<Box> boxes) {
        float sx, sy, tx, ty;
        Path2D.Float path = new Path2D.Float();
        Point2D.Float start = borderPoint(from, to), end = borderPoint(to, from);
        boolean clear = ring && from != to && boxes.stream().noneMatch(box -> box != from && box != to
                && new Rectangle2D.Float(box.x() - 8, box.y() - 8, NODE_WIDTH + 16, box.height() + 16).intersectsLine(start.x, start.y, end.x, end.y));
        if (clear) {
            sx = start.x; sy = start.y; tx = end.x; ty = end.y;
            path.moveTo(sx, sy); path.lineTo(tx, ty);
        } else if (directed && !ring && to.x() - from.x() > NODE_WIDTH + GAP) {
            sx = from.x() + NODE_WIDTH; sy = from.y() + from.height() / 2;
            tx = to.x(); ty = to.y() + to.height() * (.25f + .25f * (route % 3));
            float lane = sx + (tx - sx) * (.3f + .2f * (route % 3));
            path.moveTo(sx, sy); path.lineTo(lane, sy); path.lineTo(lane, ty); path.lineTo(tx, ty);
        } else if (from.node().id().equals(to.node().id())) {
            sx = from.x() + NODE_WIDTH; sy = from.y() + from.height() * .35f;
            tx = sx; ty = from.y() + from.height() * .7f;
            path.moveTo(sx, sy); path.curveTo(sx + 40, sy - 36, tx + 40, ty + 36, tx, ty);
        } else if (to.y() > from.y() + from.height()) {
            sx = from.x() + NODE_WIDTH / 2f; sy = from.y() + from.height(); tx = to.x() + NODE_WIDTH / 2f; ty = to.y();
            path.moveTo(sx, sy);
            if (ty - sy > GAP + 1) {
                // A skip-level edge travels outside all node columns, with entry/exit in row gaps.
                // A centre-to-centre vertical path would disappear underneath intervening cards.
                float left = (float) boxes.stream().mapToDouble(Box::x).min().orElse(GraphRenderContext.MARGIN);
                float right = (float) boxes.stream().mapToDouble(box -> box.x() + NODE_WIDTH).max().orElse(context.width() - GraphRenderContext.MARGIN);
                float offset = 12 + route % 3 * 12;
                float lane = tx >= (left + right) / 2 ? Math.min(context.width() - 16, right + offset) : Math.max(16, left - offset);
                float exit = sy + 12 + route % 3 * 8, entry = ty - 12 - route % 3 * 8;
                path.lineTo(sx, exit); path.lineTo(lane, exit); path.lineTo(lane, entry); path.lineTo(tx, entry); path.lineTo(tx, ty);
            } else {
                // Separate trunks from different parents within the same row gap.
                float bus = sy + (ty - sy) * (.25f + .5f * boxes.indexOf(from) / Math.max(1, boxes.size() - 1));
                path.lineTo(sx, bus); path.lineTo(tx, bus); path.lineTo(tx, ty);
            }
        } else {
            boolean right = to.x() >= from.x();
            sx = from.x() + (right ? NODE_WIDTH : 0); sy = from.y() + from.height() / 2;
            tx = to.x() + (right ? 0 : NODE_WIDTH); ty = to.y() + to.height() / 2;
            if (Math.abs(to.y() - from.y()) < 1) {
                path.moveTo(sx, sy);
                boolean obstructed = boxes.stream().anyMatch(box -> box != from && box != to
                        && box.y() == from.y() && box.x() < Math.max(from.x(), to.x()) && box.x() > Math.min(from.x(), to.x()));
                if (!directed && !obstructed) {
                    path.lineTo(tx, ty);
                } else {
                    float bus = from.y() - 32 - route % 3 * 36;
                    path.lineTo(sx + (right ? 12 : -12), sy);
                    path.lineTo(sx + (right ? 12 : -12), bus); path.lineTo(tx + (right ? -12 : 12), bus);
                    path.lineTo(tx + (right ? -12 : 12), ty); path.lineTo(tx, ty);
                }
            } else {
                float offset = (right ? 1 : -1) * (28 + route % 3 * 10);
                path.moveTo(sx, sy); path.curveTo(sx + offset, sy, tx - offset, ty, tx, ty);
            }
        }
        Path2D.Float arrow = null;
        if (directed) {
            var iterator = path.getPathIterator(null, 1); float[] coords = new float[6]; float px = sx, py = sy, lastX = sx, lastY = sy;
            while (!iterator.isDone()) { int segment = iterator.currentSegment(coords); if (segment == java.awt.geom.PathIterator.SEG_LINETO) { px = lastX; py = lastY; } lastX = coords[0]; lastY = coords[1]; iterator.next(); }
            double angle = Math.atan2(ty - py, tx - px);
            arrow = new Path2D.Float(); arrow.moveTo(tx, ty);
            arrow.lineTo(tx - 11 * Math.cos(angle - .45), ty - 11 * Math.sin(angle - .45));
            arrow.lineTo(tx - 11 * Math.cos(angle + .45), ty - 11 * Math.sin(angle + .45)); arrow.closePath();
        }
        return new Route(link, path, arrow);
    }
    private static Point2D.Float borderPoint(Box from, Box toward) {
        float cx = from.x() + NODE_WIDTH / 2f, cy = from.y() + from.height() / 2;
        float dx = toward.x() + NODE_WIDTH / 2f - cx, dy = toward.y() + toward.height() / 2 - cy;
        if (dx == 0 && dy == 0) return new Point2D.Float(cx, cy);
        float factor = Math.min(dx == 0 ? Float.POSITIVE_INFINITY : NODE_WIDTH / 2f / Math.abs(dx),
                dy == 0 ? Float.POSITIVE_INFINITY : from.height() / 2 / Math.abs(dy));
        return new Point2D.Float(cx + dx * factor, cy + dy * factor);
    }
    private static EdgeLabelPlacement.Placement labelArea(List<GraphRenderContext.Line> text, Path2D.Float path, List<Box> boxes,
                                                           List<Shape> obstacles, List<Rectangle2D.Float> labels, GraphRenderContext context) {
        float width = (float) Math.ceil(text.stream().mapToDouble(line -> line.layout().getAdvance()).max().orElse(0)) + 8;
        float height = GraphRenderContext.textHeight(text) + 4;
        float bottom = Math.min(context.height() - GraphRenderContext.FOOTER,
                (float) boxes.stream().mapToDouble(box -> box.y() + box.height()).max().orElse(GraphRenderContext.TOP) + 20);
        var bounds = new Rectangle2D.Float(GraphRenderContext.MARGIN, GraphRenderContext.TOP, context.bodyWidth(), bottom - GraphRenderContext.TOP);
        return EdgeLabelPlacement.beside(width, height, path, bounds, obstacles, labels);
    }
    private static Map<GraphSpec.Edge, String> references(List<GraphSpec.Edge> edges) {
        Map<GraphSpec.Edge, String> result = new IdentityHashMap<>();
        for (int i = 0; i < edges.size(); i++) result.put(edges.get(i), String.format(java.util.Locale.ROOT, "R%02d", i + 1));
        return result;
    }
    static void appendCrossLinks(ResolvedGraph graph, List<GraphSpec.Edge> edges, Map<String, Integer> pages,
                                         GraphRenderContext context, List<GraphPage> output, boolean directed, List<GraphSpec.Edge> allEdges) throws IOException {
        List<CrossLink> current = new ArrayList<>(); float used = 0;
        Map<GraphSpec.Edge, String> references = references(allEdges);
        Map<String, String> labels = new LinkedHashMap<>();
        if (graph.graph() instanceof GraphSpec.TreeGraph tree) collectLabels(tree.root(), labels);
        else if (graph.graph() instanceof GraphSpec.FlowGraph flow) flow.nodes().forEach(n -> labels.put(n.id(), n.label()));
        else if (graph.graph() instanceof GraphSpec.RelationGraph relation) relation.nodes().forEach(n -> labels.put(n.id(), n.label()));
        for (GraphSpec.Edge edge : edges) {
            String text = references.get(edge) + " · " + labels.get(edge.from()) + " (" + edge.from() + ", 第 " + pages.get(edge.from()) + " 页) "
                    + (directed ? "→" : "—") + " " + labels.get(edge.to()) + " (" + edge.to() + ", 第 " + pages.get(edge.to()) + " 页)"
                    + (edge.label().isEmpty() ? "" : "\n" + edge.label());
            var lines = context.text(text, context.bodyWidth() - 32, 21, false);
            int cursor = 0;
            while (cursor < lines.size()) {
                int begin = cursor; float height = 24;
                while (cursor < lines.size() && used + height + lines.get(cursor).height() <= context.bodyHeight()) height += lines.get(cursor++).height();
                if (cursor > begin) { current.add(new CrossLink(List.copyOf(lines.subList(begin, cursor)))); used += height; }
                if (cursor < lines.size()) {
                    if (current.isEmpty()) throw new IOException("Relationship cannot fit on a page");
                    output.add(indexPage(graph, current)); current = new ArrayList<>(); used = 0;
                    if (output.size() >= context.maxPages()) throw new IOException("Graph exceeds maximum page count");
                }
            }
        }
        if (!current.isEmpty()) output.add(indexPage(graph, current));
    }
    private static void collectLabels(GraphSpec.Node node, Map<String, String> labels) {
        labels.put(node.id(), node.label()); node.children().forEach(n -> collectLabels(n, labels));
    }
    private static GraphPage indexPage(ResolvedGraph graph, List<CrossLink> links) {
        List<CrossLink> snapshot = List.copyOf(links);
        int bottom = GraphRenderContext.TOP + (int) Math.ceil(snapshot.stream().mapToDouble(link -> GraphRenderContext.textHeight(link.text()) + 24).sum());
        return new GraphPage(title(graph) + " · 关系索引", bottom, (graphics, context) -> {
            float y = GraphRenderContext.TOP;
            for (CrossLink link : snapshot) {
                float height = GraphRenderContext.textHeight(link.text()) + 24;
                GraphRenderContext.drawText(graphics, link.text(), GraphRenderContext.MARGIN + 16, y + 12, GraphRenderContext.INK); y += height;
                graphics.setColor(GraphRenderContext.BORDER); graphics.setStroke(new BasicStroke(1));
                graphics.draw(new java.awt.geom.Line2D.Float(GraphRenderContext.MARGIN, y, context.width() - GraphRenderContext.MARGIN, y));
            }
        });
    }
    static String title(ResolvedGraph graph) {
        if (!graph.graph().title().isEmpty()) return graph.graph().title();
        return switch (graph.graph().type()) { case TREE -> "层级结构"; case FLOW -> "流程图"; default -> "关系图"; };
    }
}
