package com.nekoyu.Universe.AIChat.graph.render;
import com.nekoyu.Universe.AIChat.graph.*;
import java.io.IOException;
import java.util.List;
public final class TimelineGraphRenderer implements GraphRenderer {
    public GraphSpec.Type type() { return GraphSpec.Type.TIMELINE; }
    public List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext context) throws IOException {
        return ItemCardLayouts.layout(graph, ((GraphSpec.TimelineGraph) graph.graph()).items(), context, ItemCardLayouts.Style.TIMELINE);
    }
}
