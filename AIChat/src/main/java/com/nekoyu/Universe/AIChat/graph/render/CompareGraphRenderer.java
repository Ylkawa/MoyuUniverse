package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 对象固定为列、维度固定为行；分页和行高在查询图标之前完整确定。 */
public final class CompareGraphRenderer implements GraphRenderer {
    private static final int PAD = 16;
    private static final Color HEADER = new Color(0xe8eff6);
    record Column(GraphSpec.CompareObject object, List<GraphRenderContext.Line> label, float width) {}
    record Fragment(List<List<GraphRenderContext.Line>> cells, float height, boolean continuation) {}
    record Table(List<Column> columns, List<Fragment> rows, float metricWidth, float top, float headerHeight) {}
    public GraphSpec.Type type() { return GraphSpec.Type.COMPARE; }

    public List<GraphPage> layout(ResolvedGraph resolved, GraphRenderContext context) throws IOException {
        var graph = (GraphSpec.CompareGraph) resolved.graph();
        String title = graph.title().isEmpty() ? "对比图" : graph.title();
        List<GraphPage> pages = new ArrayList<>();
        for (Table table : tables(graph, context)) {
            int bottom = (int) Math.ceil(table.top() + table.headerHeight() + table.rows().stream().mapToDouble(Fragment::height).sum());
            pages.add(new GraphPage(title, bottom, GraphPage.HeaderStyle.COMPACT, (g, c) -> paint(g, c, table)));
        }
        return List.copyOf(pages);
    }
    List<Table> tables(GraphSpec.CompareGraph graph, GraphRenderContext context) throws IOException {
        String title = graph.title().isEmpty() ? "对比图" : graph.title();
        float top = context.compactBodyTop(title);
        float metricWidth = Math.max(140, Math.min(220, context.bodyWidth() * .2f));
        int capacity = Math.min(4, (int) ((context.bodyWidth() - metricWidth) / 180));
        if (capacity < 2) throw new IOException("Compare columns are too narrow");
        List<List<Column>> groups = new ArrayList<>();
        List<Column> all = new ArrayList<>();
        float headerHeight = 72;
        for (int start = 0; start < graph.objects().size(); start += capacity) {
            int count = Math.min(capacity, graph.objects().size() - start);
            float width = (context.bodyWidth() - metricWidth) / count;
            List<Column> group = new ArrayList<>();
            for (int i = start; i < start + count; i++) {
                var object = graph.objects().get(i);
                // 测量时预留声明图标的宽度；绘制时无图标的标题仍居中，避免远端结果改变分页。
                var label = context.text(object.label(), width - PAD * 2 - (object.icon().isEmpty() ? 0 : 52), 25, true);
                headerHeight = Math.max(headerHeight, GraphRenderContext.textHeight(label) + PAD * 2);
                var column = new Column(object, label, width);
                group.add(column); all.add(column);
            }
            groups.add(List.copyOf(group));
        }
        float available = context.height() - GraphRenderContext.FOOTER - top - headerHeight;
        if (available < 100) throw new IOException("Compare heading cannot fit with a row");
        List<List<Fragment>> vertical = fragments(graph, all, metricWidth, available, context);
        if ((long) groups.size() * vertical.size() > context.maxPages()) throw new IOException("Graph exceeds maximum page count");
        List<Table> tables = new ArrayList<>();
        int offset = 0;
        for (List<Column> group : groups) {
            for (List<Fragment> rows : vertical) {
                List<Fragment> visible = new ArrayList<>();
                for (Fragment row : rows) {
                    List<List<GraphRenderContext.Line>> cells = new ArrayList<>();
                    cells.add(row.cells().get(0));
                    cells.addAll(row.cells().subList(offset + 1, offset + group.size() + 1));
                    visible.add(new Fragment(List.copyOf(cells), row.height(), row.continuation()));
                }
                Table table = new Table(group, List.copyOf(visible), metricWidth, top, headerHeight);
                tables.add(table);
            }
            offset += group.size();
        }
        return List.copyOf(tables);
    }

    private static List<List<Fragment>> fragments(GraphSpec.CompareGraph graph, List<Column> columns,
                                                  float metricWidth, float available, GraphRenderContext context) throws IOException {
        List<List<Fragment>> pages = new ArrayList<>();
        List<Fragment> page = new ArrayList<>();
        float used = 0;
        for (var row : graph.rows()) {
            List<List<GraphRenderContext.Line>> wrapped = new ArrayList<>();
            wrapped.add(context.text(row.label(), metricWidth - PAD * 2, 25, true));
            for (int i = 0; i < columns.size(); i++) {
                String value = row.cells().get(i).text();
                wrapped.add(context.text(value.isBlank() ? "—" : value, columns.get(i).width() - PAD * 2, 24, false));
            }
            float full = maxHeight(wrapped) + PAD * 2;
            if (!page.isEmpty() && full > available - used) {
                pages.add(List.copyOf(page)); page.clear(); used = 0;
            }
            int[] cursors = new int[wrapped.size()];
            boolean continuation = false;
            do {
                float fixed = PAD * 2 + (continuation ? 18 : 0);
                float space = available - used - fixed;
                List<List<GraphRenderContext.Line>> cells = new ArrayList<>();
                boolean more = false;
                for (int i = 0; i < wrapped.size(); i++) {
                    var lines = wrapped.get(i);
                    int begin = cursors[i]; float height = 0;
                    while (cursors[i] < lines.size() && height + lines.get(cursors[i]).height() <= space)
                        height += lines.get(cursors[i]++).height();
                    if (begin == cursors[i] && begin < lines.size()) throw new IOException("Cannot fit compare text on a page");
                    cells.add(List.copyOf(lines.subList(begin, cursors[i])));
                    more |= cursors[i] < lines.size();
                }
                float height = Math.max(60, maxHeight(cells) + fixed);
                page.add(new Fragment(List.copyOf(cells), height, continuation)); used += height;
                if (!more) break;
                pages.add(List.copyOf(page)); page.clear(); used = 0; continuation = true;
                if (pages.size() >= context.maxPages()) throw new IOException("Graph exceeds maximum page count");
            } while (true);
        }
        if (!page.isEmpty()) pages.add(List.copyOf(page));
        return List.copyOf(pages);
    }
    private static float maxHeight(List<List<GraphRenderContext.Line>> cells) {
        return (float) cells.stream().mapToDouble(GraphRenderContext::textHeight).max().orElse(0);
    }
    private static void centered(Graphics2D g, List<GraphRenderContext.Line> text, float x, float y, float width) {
        g.setColor(GraphRenderContext.INK);
        for (var line : text) {
            line.layout().draw(g, x + (width - line.layout().getAdvance()) / 2, y + line.layout().getAscent());
            y += line.height();
        }
    }
    static void paint(Graphics2D g, GraphRenderContext c, Table table) throws IOException {
        float left = GraphRenderContext.MARGIN;
        float bottom = table.top() + table.headerHeight() + (float) table.rows().stream().mapToDouble(Fragment::height).sum();
        g.setColor(Color.WHITE); g.fill(new java.awt.geom.Rectangle2D.Float(left, table.top(), c.bodyWidth(), bottom - table.top()));
        g.setColor(HEADER); g.fill(new java.awt.geom.Rectangle2D.Float(left, table.top(), c.bodyWidth(), table.headerHeight()));
        var metric = c.text("对比维度", table.metricWidth() - PAD * 2, 25, true);
        centered(g, metric, left, table.top() + (table.headerHeight() - GraphRenderContext.textHeight(metric)) / 2, table.metricWidth());
        float x = left + table.metricWidth();
        for (var column : table.columns()) {
            String slot = "object:" + column.object().id();
            boolean icon = c.visibleIcon(slot);
            float textWidth = (float) column.label().stream().mapToDouble(l -> l.layout().getAdvance()).max().orElse(0);
            float start = x + (column.width() - textWidth - (icon ? 52 : 0)) / 2;
            if (icon) c.slotIcon(g, slot, null, start, table.top() + (table.headerHeight() - 40) / 2, 40);
            centered(g, column.label(), start + (icon ? 52 : 0),
                    table.top() + (table.headerHeight() - GraphRenderContext.textHeight(column.label())) / 2, textWidth);
            x += column.width();
        }
        float y = table.top() + table.headerHeight();
        horizontal(g, left, y, c.bodyWidth());
        for (var row : table.rows()) {
            x = left;
            if (row.continuation()) {
                g.setColor(GraphRenderContext.MUTED); g.setFont(c.font(15, false)); g.drawString("续", x + PAD, y + 21);
            }
            for (int i = 0; i < row.cells().size(); i++) {
                float width = i == 0 ? table.metricWidth() : table.columns().get(i - 1).width();
                GraphRenderContext.drawText(g, row.cells().get(i), x + PAD, y + PAD + (row.continuation() ? 18 : 0), GraphRenderContext.INK);
                x += width;
            }
            y += row.height(); horizontal(g, left, y, c.bodyWidth());
        }
        g.setColor(GraphRenderContext.BORDER); g.setStroke(new java.awt.BasicStroke(1.5f));
        x = left; g.draw(new java.awt.geom.Line2D.Float(x, table.top(), x, bottom)); x += table.metricWidth();
        g.draw(new java.awt.geom.Line2D.Float(x, table.top(), x, bottom));
        for (var column : table.columns()) { x += column.width(); g.draw(new java.awt.geom.Line2D.Float(x, table.top(), x, bottom)); }
        horizontal(g, left, table.top(), c.bodyWidth());
    }
    private static void horizontal(Graphics2D g, float x, float y, float width) {
        g.setColor(GraphRenderContext.BORDER); g.setStroke(new java.awt.BasicStroke(1.5f));
        g.draw(new java.awt.geom.Line2D.Float(x, y, x + width, y));
    }
}
