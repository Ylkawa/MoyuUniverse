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

/** Wide/deep trees retain preorder and explicit parent IDs instead of becoming an ambiguous grid. */
final class OutlineTreeLayout {
    private static final int INDENT = 28;
    private record Row(GraphSpec.Node node, String parent, int depth, float x, float y, float width, float height,
                       List<GraphRenderContext.Line> id, List<GraphRenderContext.Line> label,
                       List<GraphRenderContext.Line> metadata) {}

    static List<GraphPage> layout(ResolvedGraph graph, List<GraphSpec.Node> nodes, List<GraphSpec.Edge> edges,
                                  GraphRenderContext context) throws IOException {
        Map<String, String> parents = new HashMap<>();
        for (var edge : edges) parents.put(edge.to(), edge.from());
        Map<String, Integer> depths = new HashMap<>(), pageNumbers = new LinkedHashMap<>();
        List<List<Row>> groups = new ArrayList<>();
        List<Row> current = new ArrayList<>();
        int maxIndent = Math.min(12, (context.bodyWidth() - 280) / INDENT);
        float y = GraphRenderContext.TOP;
        for (var node : nodes) {
            String parent = parents.get(node.id());
            int depth = parent == null ? 0 : depths.get(parent) + 1;
            depths.put(node.id(), depth);
            float offset = Math.min(depth, maxIndent) * INDENT;
            float width = context.bodyWidth() - offset;
            var id = context.text(node.id(), width - 100, 15, false);
            var label = context.text(node.label(), width - 36, 22, true);
            String detail = parent == null ? "根节点" : "层级 " + depth + " · 父节点 " + parent + " [第 " + pageNumbers.get(parent) + " 页]";
            var metadata = context.text(detail, width - 36, 15, false);
            float header = Math.max(52, GraphRenderContext.textHeight(id) + 16) + 12;
            float height = header + GraphRenderContext.textHeight(label) + GraphRenderContext.textHeight(metadata) + 28;
            if (height > context.bodyHeight()) throw new IOException("Tree node cannot fit on a page");
            if (!current.isEmpty() && y + height > context.height() - GraphRenderContext.FOOTER) {
                groups.add(List.copyOf(current)); current.clear(); y = GraphRenderContext.TOP;
            }
            current.add(new Row(node, parent, depth, GraphRenderContext.MARGIN + offset, y, width, height, id, label, metadata));
            pageNumbers.put(node.id(), groups.size() + 1);
            y += height + 16;
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        List<GraphPage> pages = new ArrayList<>();
        var matches = context.matches(graph);
        for (int i = 0; i < groups.size(); i++) {
            List<Row> rows = groups.get(i);
            int bottom = (int) Math.ceil(rows.get(rows.size() - 1).y() + rows.get(rows.size() - 1).height());
            pages.add(new GraphPage(NodeGraphLayouts.title(graph) + " · 层级展开 " + (i + 1), bottom, (g, c) -> {
                Map<String, Row> locations = new HashMap<>(); rows.forEach(row -> locations.put(row.node().id(), row));
                g.setColor(GraphRenderContext.ACCENT); g.setStroke(new BasicStroke(2.2f));
                for (Row row : rows) {
                    Row parent = locations.get(row.parent());
                    // At capped indentation, explicit parent text replaces potentially merged guide lines.
                    if (parent == null || row.depth() > maxIndent) continue;
                    float lane = parent.x() + 14, target = row.y() + row.height() / 2;
                    Path2D.Float line = new Path2D.Float();
                    line.moveTo(lane, parent.y() + parent.height()); line.lineTo(lane, target); line.lineTo(row.x(), target);
                    g.draw(line);
                    Path2D.Float arrow = new Path2D.Float(); arrow.moveTo(row.x(), target);
                    arrow.lineTo(row.x() - 9, target - 4); arrow.lineTo(row.x() - 9, target + 4); arrow.closePath(); g.fill(arrow);
                }
                for (Row row : rows) {
                    GraphRenderContext.box(g, row.x(), row.y(), row.width(), row.height());
                    String slot = "node:" + row.node().id();
                    c.slotIcon(g, slot, matches.get(slot), row.x() + 14, row.y() + 12, 48);
                    GraphRenderContext.drawText(g, row.id(), row.x() + 78, row.y() + 20, GraphRenderContext.MUTED);
                    float labelTop = row.y() + Math.max(52, GraphRenderContext.textHeight(row.id()) + 16) + 12;
                    GraphRenderContext.drawText(g, row.label(), row.x() + 18, labelTop, GraphRenderContext.INK);
                    GraphRenderContext.drawText(g, row.metadata(), row.x() + 18,
                            labelTop + GraphRenderContext.textHeight(row.label()) + 12, GraphRenderContext.MUTED);
                }
            }));
        }
        var crossEdges = edges.stream().filter(edge -> !pageNumbers.get(edge.from()).equals(pageNumbers.get(edge.to()))).toList();
        if (!crossEdges.isEmpty()) NodeGraphLayouts.appendCrossLinks(graph, crossEdges, pageNumbers, context, pages, true, edges);
        if (pages.size() > context.maxPages()) throw new IOException("Graph exceeds maximum page count");
        return List.copyOf(pages);
    }
}
