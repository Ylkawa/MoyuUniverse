package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.GraphSpec;
import com.nekoyu.Universe.AIChat.graph.ResolvedGraph;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Area;
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

/** Bounded native layouts: top-down layers for directed graphs, ring for undirected relations. */
final class NodeGraphLayouts {
    private static final int NODE_WIDTH = 280, GAP = 64;
    private record Box(GraphSpec.Node node, List<GraphRenderContext.Line> text, List<GraphRenderContext.Line> id,
                       float x, float y, float height, boolean crossPage) {}
    private record Link(GraphSpec.Edge edge, List<GraphRenderContext.Line> label, String reference) {}
    private record Route(Link link, Path2D.Float path, Path2D.Float arrow) {}
    private record CrossLink(List<GraphRenderContext.Line> text) {}

    static List<GraphPage> layout(ResolvedGraph resolved, List<GraphSpec.Node> nodes, List<GraphSpec.Edge> edges,
                                boolean directed, boolean ring, GraphRenderContext context) throws IOException {
        Map<String, List<GraphRenderContext.Line>> text = new LinkedHashMap<>();
        Map<String, List<GraphRenderContext.Line>> ids = new LinkedHashMap<>();
        float nodeHeight = 104;
        for (GraphSpec.Node node : nodes) {
            if (node.label().length() > 400 || node.id().length() > 80) throw new IOException("Node label/ID exceeds rendering limit");
            var label = context.text(node.label(), NODE_WIDTH - 36, 22, true);
            var id = context.text(node.id(), NODE_WIDTH - 100, 15, false);
            text.put(node.id(), label); ids.put(node.id(), id);
            nodeHeight = Math.max(nodeHeight, GraphRenderContext.textHeight(label) + Math.max(52, GraphRenderContext.textHeight(id) + 16) + 44);
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
            // The index preserves every labelled relationship even when a dense drawing overlaps a label.
            if (!edge.label().isEmpty() || !pageNumbers.get(edge.from()).equals(pageNumbers.get(edge.to()))) indexedEdges.add(edge);
        }
        List<GraphPage> result = new ArrayList<>();
        var matches = context.matches(resolved);
        for (int start = 0; start < nodes.size(); start += capacity) {
            List<GraphSpec.Node> subset = List.copyOf(nodes.subList(start, Math.min(start + capacity, nodes.size())));
            Set<String> localIds = new HashSet<>(); subset.forEach(n -> localIds.add(n.id()));
            List<GraphSpec.Edge> localEdges = edges.stream().filter(e -> localIds.contains(e.from()) && localIds.contains(e.to())).toList();
            Map<String, Point2D.Float> points = ring ? ring(subset, nodeHeight, context)
                    : resolved.graph().type() == GraphSpec.Type.TREE && graphPages == 1 ? tree(subset, nodeHeight, context)
                    : layered(subset, localEdges, nodeHeight, context);
            List<Box> boxes = new ArrayList<>();
            for (GraphSpec.Node node : subset) {
                Point2D.Float p = points.get(node.id());
                boxes.add(new Box(node, text.get(node.id()), ids.get(node.id()), p.x, p.y, nodeHeight, crossNodes.contains(node.id())));
            }
            List<Link> links = new ArrayList<>();
            for (GraphSpec.Edge edge : localEdges) links.add(new Link(edge, edge.label().isEmpty() ? List.of() : context.text(edge.label(), 240, 16, false), references.get(edge)));
            String heading = title(resolved);
            if (graphPages > 1) heading += " · 节点分图 " + (start / capacity + 1);
            List<Box> snapshot = List.copyOf(boxes); List<Link> linkSnapshot = List.copyOf(links);
            int bottom = (int) Math.ceil(snapshot.stream().mapToDouble(box -> box.y() + box.height()).max().orElse(GraphRenderContext.TOP));
            result.add(new GraphPage(heading, bottom, (graphics, c) -> {
                Map<String, Box> locations = new HashMap<>(); snapshot.forEach(b -> locations.put(b.node().id(), b));
                List<Route> routes = new ArrayList<>();
                int route = 0;
                for (Link link : linkSnapshot) routes.add(routeEdge(c, locations.get(link.edge().from()), locations.get(link.edge().to()), link, directed, ring, route++, snapshot));
                List<Shape> obstacles = new ArrayList<>();
                snapshot.forEach(box -> obstacles.add(new Rectangle2D.Float(box.x() - 4, box.y() - 4, NODE_WIDTH + 8, box.height() + 8)));
                var clearance = new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
                graphics.setColor(GraphRenderContext.ACCENT); graphics.setStroke(new BasicStroke(2.2f));
                for (Route edge : routes) {
                    obstacles.add(clearance.createStrokedShape(edge.path()));
                    graphics.draw(edge.path());
                    if (edge.arrow() != null) {
                        Area protectedArrow = new Area(edge.arrow());
                        protectedArrow.add(new Area(clearance.createStrokedShape(edge.arrow())));
                        obstacles.add(protectedArrow); graphics.fill(edge.arrow());
                    }
                }
                List<Rectangle2D.Float> labels = new ArrayList<>();
                for (Route edge : routes) paintLabel(graphics, c, edge, snapshot, obstacles, labels);
                for (Box box : snapshot) {
                    GraphRenderContext.box(graphics, box.x(), box.y(), NODE_WIDTH, box.height());
                    String slot = "node:" + box.node().id();
                    c.slotIcon(graphics, slot, matches.get(slot), box.x() + 14, box.y() + 12, 48);
                    GraphRenderContext.drawText(graphics, box.id(), box.x() + 78, box.y() + 20, GraphRenderContext.MUTED);
                    GraphRenderContext.drawText(graphics, box.text(), box.x() + 18, box.y() + Math.max(52, GraphRenderContext.textHeight(box.id()) + 16) + 12, GraphRenderContext.INK);
                    if (box.crossPage()) {
                        graphics.setColor(GraphRenderContext.ACCENT); graphics.setFont(c.font(13, false));
                        graphics.drawString("跨页连接见关系索引", box.x() + 18, box.y() + box.height() - 10);
                    }
                }
            }));
        }
        if (!indexedEdges.isEmpty()) appendCrossLinks(resolved, indexedEdges, pageNumbers, context, result, directed, edges);
        if (result.size() > context.maxPages()) throw new IOException("Graph exceeds maximum page count");
        return List.copyOf(result);
    }

    /** Preserve sibling groups: a parent occupies the horizontal span of its own leaves. */
    private static Map<String, Point2D.Float> tree(List<GraphSpec.Node> nodes, float height, GraphRenderContext context) {
        GraphSpec.Node root = nodes.get(0);
        Map<String, Integer> leaves = new HashMap<>();
        int count = leafCount(root, leaves);
        float span = count * (NODE_WIDTH + GAP) - GAP;
        Map<String, Point2D.Float> result = new LinkedHashMap<>();
        treePoints(root, (context.width() - span) / 2, 0, height, leaves, result);
        return result;
    }
    private static boolean treeFits(GraphSpec.Node root, float height, GraphRenderContext context) {
        float span = leafCount(root, new HashMap<>()) * (NODE_WIDTH + GAP) - GAP;
        float bottom = GraphRenderContext.TOP + 48 + depth(root) * (height + GAP) + height;
        return span <= context.bodyWidth() && bottom <= context.height() - GraphRenderContext.FOOTER;
    }
    private static int depth(GraphSpec.Node node) {
        return node.children().isEmpty() ? 0 : 1 + node.children().stream().mapToInt(NodeGraphLayouts::depth).max().orElse(0);
    }
    private static int leafCount(GraphSpec.Node node, Map<String, Integer> counts) {
        int value = node.children().isEmpty() ? 1 : node.children().stream().mapToInt(child -> leafCount(child, counts)).sum();
        counts.put(node.id(), value); return value;
    }
    private static void treePoints(GraphSpec.Node node, float left, int depth, float height, Map<String, Integer> leaves,
                                   Map<String, Point2D.Float> result) {
        float span = leaves.get(node.id()) * (NODE_WIDTH + GAP) - GAP;
        result.put(node.id(), new Point2D.Float(left + (span - NODE_WIDTH) / 2, GraphRenderContext.TOP + 48 + depth * (height + GAP)));
        for (GraphSpec.Node child : node.children()) {
            treePoints(child, left, depth + 1, height, leaves, result);
            left += leaves.get(child.id()) * (NODE_WIDTH + GAP);
        }
    }

    private static Map<String, Point2D.Float> ring(List<GraphSpec.Node> nodes, float height, GraphRenderContext context) {
        Map<String, Point2D.Float> positions = new LinkedHashMap<>();
        float rx = (context.bodyWidth() - NODE_WIDTH - 20) / 2f;
        float ry = Math.min((context.bodyHeight() - height - 32) / 2f, Math.max(height + GAP, nodes.size() * height / 3));
        float cx = context.width() / 2f, cy = GraphRenderContext.TOP + 16 + ry + height / 2;
        for (int i = 0; i < nodes.size(); i++) {
            double angle = -Math.PI / 2 + 2 * Math.PI * i / nodes.size();
            float x = nodes.size() == 1 ? cx : cx + (float) Math.cos(angle) * rx;
            float y = nodes.size() == 1 ? cy : cy + (float) Math.sin(angle) * ry;
            positions.put(nodes.get(i).id(), new Point2D.Float(x - NODE_WIDTH / 2f, y - height / 2));
        }
        var points = new ArrayList<>(positions.values());
        for (int i = 0; i < points.size(); i++) for (int j = i + 1; j < points.size(); j++) {
            if (Math.abs(points.get(i).x - points.get(j).x) < NODE_WIDTH + 16 && Math.abs(points.get(i).y - points.get(j).y) < height + 16)
                return layered(nodes, List.of(), height, context);
        }
        return positions;
    }

    private static Map<String, Point2D.Float> layered(List<GraphSpec.Node> nodes, List<GraphSpec.Edge> edges, float height, GraphRenderContext context) {
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
        Map<Integer, List<GraphSpec.Node>> layers = new java.util.TreeMap<>();
        for (GraphSpec.Node node : nodes) layers.computeIfAbsent(rank[group.get(node.id())], ignored -> new ArrayList<>()).add(node);
        int columns = Math.max(1, (context.bodyWidth() + GAP) / (NODE_WIDTH + GAP));
        Map<String, Point2D.Float> result = new LinkedHashMap<>(); int row = 0;
        for (List<GraphSpec.Node> layer : layers.values()) {
            for (int start = 0; start < layer.size(); start += columns) {
                int count = Math.min(columns, layer.size() - start);
                float x = (context.width() - (count * NODE_WIDTH + (count - 1) * GAP)) / 2f;
                int top = edges.stream().anyMatch(e -> group.get(e.from()).equals(group.get(e.to())) && !e.from().equals(e.to())) ? 112 : 48;
                for (int i = 0; i < count; i++) result.put(layer.get(start + i).id(), new Point2D.Float(x + i * (NODE_WIDTH + GAP), GraphRenderContext.TOP + top + row * (height + GAP)));
                row++;
            }
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

    private static Route routeEdge(GraphRenderContext context, Box from, Box to, Link link, boolean directed,
                                   boolean ring, int route, List<Box> boxes) {
        float sx, sy, tx, ty;
        Path2D.Float path = new Path2D.Float();
        Point2D.Float start = borderPoint(from, to), end = borderPoint(to, from);
        boolean clear = ring && from != to && boxes.stream().noneMatch(box -> box != from && box != to
                && new Rectangle2D.Float(box.x() - 8, box.y() - 8, NODE_WIDTH + 16, box.height() + 16).intersectsLine(start.x, start.y, end.x, end.y));
        if (clear) {
            sx = start.x; sy = start.y; tx = end.x; ty = end.y;
            path.moveTo(sx, sy); path.lineTo(tx, ty);
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
    private static void paintLabel(Graphics2D graphics, GraphRenderContext context, Route route, List<Box> boxes,
                                    List<Shape> obstacles, List<Rectangle2D.Float> labels) throws IOException {
        if (!route.link().label().isEmpty()) {
            var text = route.link().label();
            var placement = labelArea(text, route.path(), boxes, obstacles, labels, context);
            if (placement == null) {
                // Compact references are explained by the complete relationship index.
                text = context.text(route.link().reference(), 80, 15, true);
                placement = labelArea(text, route.path(), boxes, obstacles, labels, context);
            }
            if (placement != null) {
                Rectangle2D.Float area = placement.area();
                labels.add(area);
                // No background eraser: captions never remove a route or arrowhead.
                GraphRenderContext.drawText(graphics, text, area.x + 4, area.y + 2, GraphRenderContext.MUTED);
            }
        }
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
        for (GraphSpec.Edge edge : edges) {
            String text = references.get(edge) + " · " + edge.from() + " [第 " + pages.get(edge.from()) + " 页] " + (directed ? "→" : "—") + " "
                    + edge.to() + " [第 " + pages.get(edge.to()) + " 页]" + (edge.label().isEmpty() ? "" : "  ·  " + edge.label());
            var lines = context.text(text, context.bodyWidth() - 48, 23, false);
            float height = GraphRenderContext.textHeight(lines) + 24;
            if (height > context.bodyHeight()) throw new IOException("Relationship cannot fit on a page");
            if (!current.isEmpty() && used + height > context.bodyHeight()) {
                output.add(indexPage(graph, current)); current = new ArrayList<>(); used = 0;
            }
            current.add(new CrossLink(lines)); used += height;
        }
        if (!current.isEmpty()) output.add(indexPage(graph, current));
    }
    private static GraphPage indexPage(ResolvedGraph graph, List<CrossLink> links) {
        List<CrossLink> snapshot = List.copyOf(links);
        int bottom = GraphRenderContext.TOP + (int) Math.ceil(snapshot.stream().mapToDouble(link -> GraphRenderContext.textHeight(link.text()) + 24).sum());
        return new GraphPage(title(graph) + " · 关系索引", bottom, (graphics, context) -> {
            float y = GraphRenderContext.TOP;
            for (CrossLink link : snapshot) {
                float height = GraphRenderContext.textHeight(link.text()) + 24;
                GraphRenderContext.box(graphics, GraphRenderContext.MARGIN, y, context.bodyWidth(), height);
                GraphRenderContext.drawText(graphics, link.text(), GraphRenderContext.MARGIN + 24, y + 12, GraphRenderContext.INK); y += height;
            }
        });
    }
    static String title(ResolvedGraph graph) {
        if (!graph.graph().title().isEmpty()) return graph.graph().title();
        return switch (graph.graph().type()) { case TREE -> "层级结构"; case FLOW -> "流程图"; default -> "关系图"; };
    }
}
