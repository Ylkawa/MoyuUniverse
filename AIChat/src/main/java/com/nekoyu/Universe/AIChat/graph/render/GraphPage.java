package com.nekoyu.Universe.AIChat.graph.render;

import java.awt.Graphics2D;
import java.io.IOException;
import java.util.Objects;

public record GraphPage(String heading, int contentBottom, Painter painter) {
    @FunctionalInterface public interface Painter { void paint(Graphics2D graphics, GraphRenderContext context) throws IOException; }
    public GraphPage(String heading, Painter painter) { this(heading, 0, painter); }
    public GraphPage { Objects.requireNonNull(heading); Objects.requireNonNull(painter); if (contentBottom < 0) throw new IllegalArgumentException("Negative page extent"); }
    public void validate(GraphRenderContext context) throws IOException {
        context.validateHeader(heading);
        if (contentBottom > context.height() - GraphRenderContext.FOOTER) throw new IOException("Graph content exceeds page height");
    }
    public int canvasHeight(GraphRenderContext context) {
        return contentBottom == 0 ? context.height() : Math.min(context.height(), Math.max(320, contentBottom + GraphRenderContext.FOOTER + 20));
    }
}
