package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.GraphSpec;
import com.nekoyu.Universe.AIChat.graph.ResolvedGraph;
import java.awt.BasicStroke;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact outline for trees which cannot fit intact; cross-page parents stay explicit. */
final class OutlineTreeLayout {
    private static final int INDENT = 36;
    private record Row(GraphSpec.Node node, String parent, int depth, float x, float y, float height,
                       List<GraphRenderContext.Line> label, List<GraphRenderContext.Line> metadata) {}
    static List<GraphPage> layout(ResolvedGraph graph, List<GraphSpec.Node> nodes, List<GraphSpec.Edge> edges, GraphRenderContext c) throws IOException {
        Map<String, String> parents = new HashMap<>(); for (var e : edges) parents.put(e.to(), e.from());
        Map<String, GraphSpec.Node> byId = new HashMap<>(); nodes.forEach(n -> byId.put(n.id(), n));
        Map<String, Integer> depths = new HashMap<>(), pageNumbers = new LinkedHashMap<>();
        List<List<Row>> groups = new ArrayList<>(); List<Row> current = new ArrayList<>();
        int maxIndent = Math.min(10, (c.bodyWidth() - 280) / INDENT); float y = GraphRenderContext.TOP;
        for (var node : nodes) {
            String parent = parents.get(node.id()); int depth = parent == null ? 0 : depths.get(parent) + 1; depths.put(node.id(), depth);
            float x = GraphRenderContext.MARGIN + Math.min(depth, maxIndent) * INDENT;
            var label = c.text(node.label(), c.width() - GraphRenderContext.MARGIN - x - 92, 24, true);
            float basicHeight = Math.max(40, GraphRenderContext.textHeight(label)) + 24;
            List<GraphRenderContext.Line> detail = List.of();
            // Recalculate metadata after a page break, since the parent may now be on a previous page.
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean cross = parent != null && pageNumbers.get(parent) != groups.size() + 1;
                if (parent != null && (cross || depth > maxIndent)) detail = c.text("层级 " + depth + " · 父节点 " + byId.get(parent).label() + " (" + parent + ") · 第 " + pageNumbers.get(parent) + " 页",
                        c.width() - GraphRenderContext.MARGIN - x - 92, 16, false);
                float height = basicHeight + (detail.isEmpty() ? 0 : GraphRenderContext.textHeight(detail) + 8);
                if (height > c.bodyHeight()) throw new IOException("Tree node cannot fit on a page");
                if (!current.isEmpty() && y + height > c.height() - GraphRenderContext.FOOTER) {
                    groups.add(List.copyOf(current)); current.clear(); y = GraphRenderContext.TOP; continue;
                }
                current.add(new Row(node, parent, depth, x, y, height, label, detail));
                pageNumbers.put(node.id(), groups.size() + 1); y += height + 8; break;
            }
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        List<GraphPage> pages = new ArrayList<>(); var matches = c.matches(graph);
        for (var group : groups) {
            var rows = List.copyOf(group); int bottom = (int) Math.ceil(rows.get(rows.size() - 1).y() + rows.get(rows.size() - 1).height());
            pages.add(new GraphPage(NodeGraphLayouts.title(graph) + " · 层级展开", bottom, (g, context) -> {
                Map<String, Row> locations = new HashMap<>(); rows.forEach(r -> locations.put(r.node().id(), r));
                g.setColor(GraphRenderContext.BORDER); g.setStroke(new BasicStroke(2));
                for (var row : rows) {
                    var parent = locations.get(row.parent()); if (parent == null || row.depth() > maxIndent) continue;
                    float lane = parent.x() + 15, target = row.y() + 30;
                    var path = new Path2D.Float(); path.moveTo(lane, parent.y() + parent.height()); path.lineTo(lane, target); path.lineTo(row.x() + 8, target); g.draw(path);
                }
                for (var row : rows) {
                    float textX = row.x() + 54;
                    String slot = "node:" + row.node().id();
                    context.slotIcon(g, slot, matches.get(slot), row.x() + 4, row.y() + 14, 30);
                    if (row.node().icon().isEmpty()) {
                        g.setColor(GraphRenderContext.ACCENT); g.fillOval((int) row.x() + 11, (int) row.y() + 26, 7, 7); textX = row.x() + 32;
                    }
                    GraphRenderContext.drawText(g, row.label(), textX, row.y() + 12, GraphRenderContext.INK);
                    if (!row.metadata().isEmpty()) GraphRenderContext.drawText(g, row.metadata(), textX,
                            row.y() + 20 + Math.max(40, GraphRenderContext.textHeight(row.label())), GraphRenderContext.MUTED);
                }
            }));
        }
        var cross = edges.stream().filter(e -> !pageNumbers.get(e.from()).equals(pageNumbers.get(e.to()))).toList();
        if (!cross.isEmpty()) NodeGraphLayouts.appendCrossLinks(graph, cross, pageNumbers, c, pages, true, edges);
        if (pages.size() > c.maxPages()) throw new IOException("Graph exceeds maximum page count");
        return List.copyOf(pages);
    }
}
