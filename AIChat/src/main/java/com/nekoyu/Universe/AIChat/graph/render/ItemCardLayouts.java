package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.GraphSpec;
import com.nekoyu.Universe.AIChat.graph.ResolvedGraph;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ItemCardLayouts {
    enum Style { LIST, TIMELINE }
    private record Card(List<GraphRenderContext.Line> heading, List<GraphRenderContext.Line> body,
                        List<GraphRenderContext.Line> time, String iconSlot, ResolvedGraph.IconMatch fallback, float height, boolean continuation) {}
    private record Placement(Card card, float x, float y, float width) {}

    static List<GraphPage> layout(ResolvedGraph graph, List<GraphSpec.Item> items, GraphRenderContext context, Style style) throws IOException {
        float width = style == Style.TIMELINE ? context.bodyWidth() - 200 : context.bodyWidth();
        Map<String, ResolvedGraph.IconMatch> matches = context.matches(graph);
        List<List<Card>> cards = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            GraphSpec.Item item = items.get(i);
            String heading = item.label().isEmpty() ? String.format("%02d", i + 1) : item.label();
            var header = context.text(heading, width - 110, 25, true);
            var body = context.text(item.text(), width - 48, 24, false);
            var time = item.time().isEmpty() ? List.<GraphRenderContext.Line>of() : context.text(item.time(), 148, 21, true);
            float headerHeight = Math.max(60, GraphRenderContext.textHeight(header));
            float fixed = headerHeight + 56;
            float available = context.bodyHeight() - fixed - 4;
            if (available < 40 || GraphRenderContext.textHeight(time) > context.bodyHeight() - 20) throw new IOException("Item heading/time is too tall");
            List<Card> fragments = new ArrayList<>();
            int cursor = 0;
            while (cursor < body.size()) {
                int start = cursor; float used = 0;
                while (cursor < body.size() && used + body.get(cursor).height() <= available) used += body.get(cursor++).height();
                if (start == cursor) throw new IOException("Cannot fit graph text on a page");
                float height = Math.max(fixed + used, GraphRenderContext.textHeight(time) + 24);
                String slot = "line[" + (i + 1) + "]";
                fragments.add(new Card(header, List.copyOf(body.subList(start, cursor)), time, slot, matches.get(slot), height, start > 0));
            }
            cards.add(fragments);
        }
        List<GraphPage> pages = new ArrayList<>();
        List<Placement> current = new ArrayList<>();
        float y = GraphRenderContext.TOP;
        for (int i = 0; i < cards.size(); i++) {
            List<Card> left = cards.get(i);
            for (int f = 0; f < left.size(); f++) {
                Card a = left.get(f);
                float height = a.height();
                if (!current.isEmpty() && y + height > context.height() - GraphRenderContext.FOOTER) {
                    pages.add(page(graph, current, style)); current = new ArrayList<>(); y = GraphRenderContext.TOP;
                }
                float x = GraphRenderContext.MARGIN + (style == Style.TIMELINE ? 200 : 0);
                current.add(new Placement(a, x, y, width));
                y += height + 20;
            }
        }
        if (!current.isEmpty()) pages.add(page(graph, current, style));
        if (pages.size() > context.maxPages()) throw new IOException("Graph exceeds maximum page count");
        return List.copyOf(pages);
    }
    private static GraphPage page(ResolvedGraph graph, List<Placement> placements, Style style) {
        List<Placement> snapshot = List.copyOf(placements);
        String title = graph.graph().title().isEmpty() ? switch (style) { case LIST -> "信息列表"; case TIMELINE -> "时间线"; } : graph.graph().title();
        int bottom = (int) Math.ceil(snapshot.stream().mapToDouble(p -> p.y() + p.card().height()).max().orElse(GraphRenderContext.TOP));
        return new GraphPage(title, bottom, (graphics, context) -> {
            if (style == Style.TIMELINE) {
                graphics.setColor(GraphRenderContext.BORDER); graphics.setStroke(new BasicStroke(3));
                graphics.drawLine(204, GraphRenderContext.TOP, 204, (int) (snapshot.get(snapshot.size() - 1).y() + snapshot.get(snapshot.size() - 1).card().height()));
            }
            for (Placement p : snapshot) paint(graphics, context, p, style);
        });
    }
    private static void paint(Graphics2D graphics, GraphRenderContext context, Placement p, Style style) throws IOException {
        Card card = p.card();
        GraphRenderContext.box(graphics, p.x(), p.y(), p.width(), card.height());
        if (style == Style.TIMELINE) {
            GraphRenderContext.drawText(graphics, card.time(), GraphRenderContext.MARGIN, p.y() + 24, GraphRenderContext.ACCENT);
            graphics.setColor(GraphRenderContext.ACCENT); graphics.fillOval(197, (int) p.y() + 27, 14, 14);
        }
        context.slotIcon(graphics, card.iconSlot(), card.fallback(), p.x() + 20, p.y() + 18, 60);
        GraphRenderContext.drawText(graphics, card.heading(), p.x() + 92, p.y() + 24, GraphRenderContext.INK);
        float headerHeight = Math.max(60, GraphRenderContext.textHeight(card.heading()));
        GraphRenderContext.drawText(graphics, card.body(), p.x() + 24, p.y() + headerHeight + 32, GraphRenderContext.INK);
        if (card.continuation()) {
            graphics.setColor(GraphRenderContext.MUTED); graphics.setFont(context.font(15, false));
            graphics.drawString("续", p.x() + p.width() - 34, p.y() + 22);
        }
    }
}
