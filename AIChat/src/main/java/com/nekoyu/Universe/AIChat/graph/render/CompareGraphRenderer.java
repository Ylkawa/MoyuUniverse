package com.nekoyu.Universe.AIChat.graph.render;
import com.nekoyu.Universe.AIChat.graph.*;
import java.io.IOException;
import java.util.List;
public final class CompareGraphRenderer implements GraphRenderer {
    public GraphSpec.Type type() { return GraphSpec.Type.COMPARE; }
    public List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext context) throws IOException {
        return ItemCardLayouts.layout(graph, ((GraphSpec.CompareGraph) graph.graph()).items(), context, ItemCardLayouts.Style.COMPARE);
    }
}
