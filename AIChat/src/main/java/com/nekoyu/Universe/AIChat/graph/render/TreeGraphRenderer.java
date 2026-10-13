package com.nekoyu.Universe.AIChat.graph.render;
import com.nekoyu.Universe.AIChat.graph.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
public final class TreeGraphRenderer implements GraphRenderer {
    public GraphSpec.Type type() { return GraphSpec.Type.TREE; }
    public List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext context) throws IOException {
        List<GraphSpec.Node> nodes = new ArrayList<>(); List<GraphSpec.Edge> edges = new ArrayList<>();
        flatten(((GraphSpec.TreeGraph) graph.graph()).root(), nodes, edges);
        return NodeGraphLayouts.layout(graph, nodes, edges, true, false, context);
    }
    private static void flatten(GraphSpec.Node node, List<GraphSpec.Node> nodes, List<GraphSpec.Edge> edges) {
        nodes.add(node);
        for (GraphSpec.Node child : node.children()) { edges.add(new GraphSpec.Edge(node.id(), child.id(), "")); flatten(child, nodes, edges); }
    }
}
