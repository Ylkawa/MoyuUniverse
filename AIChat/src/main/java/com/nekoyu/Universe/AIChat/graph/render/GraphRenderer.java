package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.GraphSpec;
import com.nekoyu.Universe.AIChat.graph.ResolvedGraph;
import java.io.IOException;
import java.util.List;

/** Produces lightweight page plans; geometry never depends on icon results.
 * Icon slots can be bound by the decoupler after preflight or supplied by a direct caller.
 * Pixel buffers are created one at a time, under the global rendering lock. */
public interface GraphRenderer {
    GraphSpec.Type type();
    List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext context) throws IOException;
}
