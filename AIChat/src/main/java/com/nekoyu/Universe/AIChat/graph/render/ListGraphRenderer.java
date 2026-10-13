package com.nekoyu.Universe.AIChat.graph.render;
import com.nekoyu.Universe.AIChat.graph.*;
import java.io.IOException;
import java.util.List;
public final class ListGraphRenderer implements GraphRenderer {
    public GraphSpec.Type type() { return GraphSpec.Type.LIST; }
    public List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext context) throws IOException {
        return ItemCardLayouts.layout(graph, ((GraphSpec.ListGraph) graph.graph()).items(), context, ItemCardLayouts.Style.LIST);
    }
}
