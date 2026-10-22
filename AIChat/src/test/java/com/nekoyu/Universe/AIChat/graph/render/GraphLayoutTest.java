package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.*;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GraphLayoutTest {
    private GraphSpec.Node node(String id, GraphSpec.Node... children) { return new GraphSpec.Node(id, id, "", List.of(children)); }
    private void flatten(GraphSpec.Node node, List<GraphSpec.Node> nodes) { nodes.add(node); node.children().forEach(n -> flatten(n, nodes)); }

    @Test void horizontalTreeKeepsFiveLeavesAndSeparateSubtreeIntervals() throws Exception {
        var root = node("root", node("left", node("a"), node("b")), node("right", node("c"), node("d"), node("e")));
        var nodes = new ArrayList<GraphSpec.Node>(); flatten(root, nodes);
        try (var c = new GraphRenderContext(new GraphSettings())) {
            var p = NodeGraphLayouts.tree(nodes, 80, c);
            assertEquals(nodes.size(), p.size());
            assertTrue(p.get("root").x < p.get("left").x && p.get("left").x < p.get("a").x);
            assertTrue(p.get("b").y + 80 < p.get("c").y, "Different parents must own disjoint leaf intervals");
            assertEquals((p.get("a").y + p.get("b").y) / 2, p.get("left").y, .01);
            assertEquals((p.get("c").y + p.get("e").y) / 2, p.get("right").y, .01);
            var pages = new TreeGraphRenderer().layout(new ResolvedGraph(new GraphSpec.TreeGraph("tree", root), List.of()), c);
            assertEquals(1, pages.size()); assertFalse(pages.get(0).heading().contains("层级展开")); pages.get(0).validate(c);
        }
    }

    @Test void skipEdgesAndSharedTerminalRoutesAvoidUnrelatedNodes() throws Exception {
        var nodes = List.of(node("a"), node("b"), node("c"), node("d"), node("done"), node("exit"));
        var edges = List.of(new GraphSpec.Edge("a", "b", ""), new GraphSpec.Edge("b", "c", ""), new GraphSpec.Edge("c", "d", ""),
                new GraphSpec.Edge("d", "done", ""), new GraphSpec.Edge("a", "c", "skip"),
                new GraphSpec.Edge("b", "exit", "branch"), new GraphSpec.Edge("c", "exit", "branch"), new GraphSpec.Edge("d", "exit", "branch"));
        try (var c = new GraphRenderContext(new GraphSettings())) {
            var points = NodeGraphLayouts.layered(nodes, edges, 80, c);
            assertTrue(points.get("exit").x > points.get("b").x + NodeGraphLayouts.NODE_WIDTH);
            var boxes = nodes.stream().map(n -> new NodeGraphLayouts.Box(n, List.of(), points.get(n.id()).x, points.get(n.id()).y, 80, false)).toList();
            for (int i = 0; i < edges.size(); i++) {
                var edge = edges.get(i);
                var from = boxes.stream().filter(b -> b.node().id().equals(edge.from())).findFirst().orElseThrow();
                var to = boxes.stream().filter(b -> b.node().id().equals(edge.to())).findFirst().orElseThrow();
                var route = NodeGraphLayouts.routeEdge(c, from, to, new NodeGraphLayouts.Link(edge, List.of(), "R" + i), true, false, i, boxes);
                var stroke = new BasicStroke(2).createStrokedShape(route.path());
                for (var other : boxes) if (other != from && other != to) {
                    assertFalse(stroke.intersects(new Rectangle2D.Float(other.x() + 1, other.y() + 1, NodeGraphLayouts.NODE_WIDTH - 2, other.height() - 2)), edge + " crosses " + other.node().id());
                }
            }
        }
    }

    @Test void allExamplePlansFitNarrowAndWidePagesWithoutIcons() throws Exception {
        for (int width : new int[]{640, 1440}) {
            var settings = new GraphSettings(); settings.width = width; settings.pageHeight = 800;
            try (var c = new GraphRenderContext(settings)) {
                for (var renderer : List.of(new ListGraphRenderer(), new CompareGraphRenderer(), new TimelineGraphRenderer(), new TreeGraphRenderer(), new FlowGraphRenderer(), new RelationGraphRenderer())) {
                    String xml = Files.readString(Path.of("examples/graphs/" + renderer.type().name().toLowerCase(java.util.Locale.ROOT) + ".xml"));
                    var graph = new ResolvedGraph(new GraphParser().parse(xml), List.of());
                    var pages = renderer.layout(graph, c); assertFalse(pages.isEmpty());
                    for (var page : pages) { page.validate(c); assertTrue(page.canvasHeight(c) <= 800); }
                }
            }
        }
    }

    @Test void denseCaptionsStillHaveACompleteRelationshipIndex() throws Exception {
        var settings = new GraphSettings(); settings.width = 640; settings.pageHeight = 800;
        var nodes = List.of(node("a"), node("b"), node("c"));
        var edges = List.of(new GraphSpec.Edge("a", "b", "中文说明".repeat(40)), new GraphSpec.Edge("b", "c", "另一条关系".repeat(30)));
        try (var c = new GraphRenderContext(settings)) {
            var graph = new ResolvedGraph(new GraphSpec.FlowGraph("dense", nodes, edges), List.of());
            var pages = new FlowGraphRenderer().layout(graph, c);
            assertTrue(pages.stream().anyMatch(p -> p.heading().contains("关系索引")));
            for (var page : pages) page.validate(c);
        }
    }

    @Test void displayOnlyIconTreatmentKeepsAntialiasingAndDoesNotRecolorColoredAssets() {
        var source = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.WHITE.getRGB()); source.setRGB(1, 0, Color.BLACK.getRGB()); source.setRGB(2, 0, new Color(128, 128, 128).getRGB());
        var display = GraphRenderContext.transparentMonochrome(source);
        assertEquals(0, display.getRGB(0, 0) >>> 24);
        assertEquals(GraphRenderContext.INK.getRGB(), display.getRGB(1, 0));
        assertEquals(127, display.getRGB(2, 0) >>> 24);
        var colored = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB); colored.setRGB(0, 0, Color.RED.getRGB());
        assertSame(colored, GraphRenderContext.transparentMonochrome(colored));
        assertEquals(Color.RED.getRGB(), colored.getRGB(0, 0));
    }

    @Test void listHeadingExtractionKeepsTheRemainingTextVerbatim() {
        var content = ItemCardLayouts.content(new GraphSpec.Item("", "小标题\r\n正文一\n\n正文二", "", ""));
        assertEquals("小标题", content.heading()); assertEquals("正文一\n\n正文二", content.body());
        String longLine = "长段落".repeat(30);
        assertEquals(longLine, ItemCardLayouts.content(new GraphSpec.Item("", longLine, "", "")).body());
    }
}
