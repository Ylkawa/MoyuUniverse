package com.nekoyu.Universe.AIChat.graph.render;
import com.nekoyu.Universe.AIChat.graph.*;
import java.io.IOException;
import java.util.List;
public final class RelationGraphRenderer implements GraphRenderer {
    public GraphSpec.Type type() { return GraphSpec.Type.RELATION; }
    public List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext context) throws IOException {
        var relation = (GraphSpec.RelationGraph) graph.graph();
        return NodeGraphLayouts.layout(graph, relation.nodes(), relation.edges(), relation.directed(), !relation.directed(), context);
    }
}
