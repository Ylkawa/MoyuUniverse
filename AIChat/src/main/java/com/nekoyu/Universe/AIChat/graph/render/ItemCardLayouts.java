package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.GraphSpec;
import com.nekoyu.Universe.AIChat.graph.ResolvedGraph;
import java.awt.BasicStroke;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Compact reading order: row-major list tiles and an open, ordinal timeline. */
final class ItemCardLayouts {
    enum Style { LIST, TIMELINE }
    record Content(String heading, String body) {}
    private record Item(int number, List<GraphRenderContext.Line> heading, List<GraphRenderContext.Line> body,
                        List<GraphRenderContext.Line> time, String slot, ResolvedGraph.IconMatch match,
                        boolean inline, float height, boolean continuation) {}
    private record Placement(Item item, float x, float y, float width) {}

    // A short first paragraph is a typographic heading; all text remains in input order.
    static Content content(GraphSpec.Item item) {
        if (!item.label().isEmpty()) return new Content(item.label(), item.text());
        String text = item.text(); int newline = text.indexOf('\n');
        if (newline > 0 && newline <= 48) return new Content(text.substring(0, newline).replace("\r", ""), text.substring(newline + 1));
        return new Content("", text);
    }
    static List<GraphPage> layout(ResolvedGraph graph, List<GraphSpec.Item> items, GraphRenderContext c, Style style) throws IOException {
        boolean tiles = style == Style.LIST && c.width() >= 1100 && items.size() >= 4 && items.stream().allMatch(i -> i.text().length() <= 600);
        int columns = tiles ? 2 : 1;
        float gap = 24, gutter = c.width() < 900 ? 160 : 200;
        float width = style == Style.TIMELINE ? c.bodyWidth() - gutter : (c.bodyWidth() - gap * (columns - 1)) / columns;
        var matches = c.matches(graph); List<Item> fragments = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            var source = items.get(i); var content = content(source);
            var heading = content.heading().isEmpty() ? List.<GraphRenderContext.Line>of() : c.text(content.heading(), width - 144, 27, true);
            var body = content.body().isEmpty() ? List.<GraphRenderContext.Line>of() : c.text(content.body(), width - 48, 24, false);
            var time = source.time().isEmpty() ? List.<GraphRenderContext.Line>of() : c.text(source.time(), gutter - 52, 22, true);
            boolean inline = heading.isEmpty() && body.size() == 1 && body.get(0).layout().getAdvance() <= width - 144;
            float fixed = inline ? 40 : Math.max(36, GraphRenderContext.textHeight(heading)) + 48;
            float available = c.bodyHeight() - fixed;
            if (available < 36 || GraphRenderContext.textHeight(time) + 32 > c.bodyHeight()) throw new IOException("Item heading/time is too tall");
            int cursor = 0;
            do {
                int start = cursor; float used = 0;
                while (cursor < body.size() && used + body.get(cursor).height() <= available) used += body.get(cursor++).height();
                if (start == cursor && cursor < body.size()) throw new IOException("Cannot fit graph text on a page");
                String slot = "line[" + (i + 1) + "]";
                fragments.add(new Item(i + 1, heading, List.copyOf(body.subList(start, cursor)), time, slot, matches.get(slot), inline,
                        Math.max(fixed + used, GraphRenderContext.textHeight(time) + 32), start > 0));
            } while (cursor < body.size());
        }
        List<GraphPage> pages = new ArrayList<>(); List<Placement> current = new ArrayList<>(); float y = GraphRenderContext.TOP;
        for (int i = 0; i < fragments.size(); i += columns) {
            int count = Math.min(columns, fragments.size() - i); float height = 0;
            for (int j = 0; j < count; j++) height = Math.max(height, fragments.get(i + j).height());
            if (!current.isEmpty() && y + height > c.height() - GraphRenderContext.FOOTER) {
                pages.add(page(graph, current, style, tiles, gutter)); current.clear(); y = GraphRenderContext.TOP;
            }
            for (int j = 0; j < count; j++) {
                var item = fragments.get(i + j);
                var aligned = new Item(item.number(), item.heading(), item.body(), item.time(), item.slot(), item.match(), item.inline(), height, item.continuation());
                current.add(new Placement(aligned, GraphRenderContext.MARGIN + (style == Style.TIMELINE ? gutter : j * (width + gap)), y, width));
            }
            y += height + (tiles ? 24 : 20);
        }
        if (!current.isEmpty()) pages.add(page(graph, current, style, tiles, gutter));
        if (pages.size() > c.maxPages()) throw new IOException("Graph exceeds maximum page count");
        return List.copyOf(pages);
    }
    private static GraphPage page(ResolvedGraph graph, List<Placement> placements, Style style, boolean tiles, float gutter) {
        var rows = List.copyOf(placements);
        String title = graph.graph().title().isEmpty() ? (style == Style.LIST ? "信息清单" : "时间线") : graph.graph().title();
        int bottom = (int) Math.ceil(rows.stream().mapToDouble(p -> p.y() + p.item().height()).max().orElse(GraphRenderContext.TOP));
        return new GraphPage(title, bottom, (g, c) -> {
            float axis = GraphRenderContext.MARGIN + gutter - 28;
            if (style == Style.TIMELINE && rows.size() > 1) {
                g.setColor(GraphRenderContext.BORDER); g.setStroke(new BasicStroke(2));
                g.draw(new java.awt.geom.Line2D.Float(axis, rows.get(0).y() + 30, axis, rows.get(rows.size() - 1).y() + 30));
            }
            for (int i = 0; i < rows.size(); i++) {
                var p = rows.get(i); var item = p.item();
                if (tiles) GraphRenderContext.box(g, p.x(), p.y(), p.width(), item.height());
                else if (i > 0 && style == Style.LIST) {
                    g.setColor(GraphRenderContext.BORDER); g.setStroke(new BasicStroke(1));
                    g.draw(new java.awt.geom.Line2D.Float(p.x(), p.y() - 10, p.x() + p.width(), p.y() - 10));
                }
                float x = p.x() + 24, top = p.y() + 20;
                if (style == Style.TIMELINE) {
                    GraphRenderContext.drawText(g, item.time(), GraphRenderContext.MARGIN, top, GraphRenderContext.ACCENT);
                    if (c.visibleIcon(item.slot(), item.match())) {
                        g.setColor(GraphRenderContext.BACKGROUND); g.fillRect((int) axis - 22, (int) p.y() + 8, 44, 44);
                        c.slotIcon(g, item.slot(), item.match(), axis - 18, p.y() + 12, 36);
                    } else {
                        g.setColor(GraphRenderContext.BACKGROUND); g.fillOval((int) axis - 10, (int) p.y() + 20, 20, 20);
                        g.setColor(GraphRenderContext.ACCENT); g.setStroke(new BasicStroke(3)); g.drawOval((int) axis - 6, (int) p.y() + 24, 12, 12);
                    }
                } else {
                    g.setFont(c.font(20, true)); g.setColor(GraphRenderContext.ACCENT);
                    g.drawString(String.format(java.util.Locale.ROOT, "%02d", item.number()), x, top + 24); x += 48;
                }
                if (!item.heading().isEmpty()) GraphRenderContext.drawText(g, item.heading(), x, top, GraphRenderContext.INK);
                if (style == Style.LIST) c.slotIcon(g, item.slot(), item.match(), p.x() + p.width() - 56, top, 32);
                float bodyTop = top + Math.max(36, GraphRenderContext.textHeight(item.heading())) + 8;
                GraphRenderContext.drawText(g, item.body(), item.inline() ? x : p.x() + 24, item.inline() ? top : bodyTop, GraphRenderContext.INK);
                if (item.continuation()) { g.setColor(GraphRenderContext.MUTED); g.setFont(c.font(15, false)); g.drawString("续", p.x() + p.width() - 24, p.y() + item.height() - 8); }
            }
        });
    }
}
