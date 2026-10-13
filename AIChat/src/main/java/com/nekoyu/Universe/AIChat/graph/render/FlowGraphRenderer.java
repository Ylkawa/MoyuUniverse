package com.nekoyu.Universe.AIChat.graph.render;
import com.nekoyu.Universe.AIChat.graph.*;
import java.io.IOException;
import java.util.List;
public final class FlowGraphRenderer implements GraphRenderer {
    public GraphSpec.Type type() { return GraphSpec.Type.FLOW; }
    public List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext context) throws IOException {
        var flow = (GraphSpec.FlowGraph) graph.graph();
        return NodeGraphLayouts.layout(graph, flow.nodes(), flow.edges(), true, false, context);
    }
}
