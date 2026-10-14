package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.*;
import com.nekoyu.Universe.AIChat.XmlMarks;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompareGraphRendererTest {
    @TempDir Path temp;
    private GraphSettings settings(int width, int height) {
        var settings = new GraphSettings(); settings.width = width; settings.pageHeight = height;
        settings.outputDirectory = temp.resolve("png").toString(); return settings;
    }
    private GraphSpec.CompareGraph graph(int objects, String value) {
        StringBuilder xml = new StringBuilder("<graph type='compare' title='对比测试'>");
        for (int i = 0; i < objects; i++) xml.append("<object id='o").append(i).append("' label='对象").append(i).append("'/>");
        xml.append("<row label='共同维度'>");
        for (int i = objects - 1; i >= 0; i--) xml.append("<cell object='o").append(i).append("'>").append(value).append("</cell>");
        return (GraphSpec.CompareGraph) new GraphParser().parse(xml.append("</row></graph>").toString());
    }
    @Test void narrowAndManyObjectTablesKeepEveryColumnAndCell() throws Exception {
        for (int width : new int[]{640, 1440}) for (int count : new int[]{2, 3, 5}) {
            var spec = graph(count, "首尾完整保留");
            try (var context = new GraphRenderContext(settings(width, 800))) {
                var tables = new CompareGraphRenderer().tables(spec, context);
                assertEquals(count, tables.stream().mapToInt(t -> t.columns().size()).sum());
                var objects = tables.stream().flatMap(t -> t.columns().stream()).map(c -> c.object().id()).toList();
                assertEquals(spec.objects().stream().map(GraphSpec.CompareObject::id).toList(), objects);
                for (var table : tables) {
                    assertTrue(table.columns().size() <= 4);
                    assertTrue(table.columns().stream().allMatch(c -> c.width() >= 180));
                    assertEquals(width == 640 ? 140 : 220, table.metricWidth(), .01);
                    for (int i = 1; i < table.rows().get(0).cells().size(); i++)
                        assertEquals("首尾完整保留".length(), table.rows().get(0).cells().get(i).stream().mapToInt(l -> l.layout().getCharacterCount()).sum());
                }
            }
        }
    }
    @Test void oversizedRowSplitsInSyncAndKeepsAllCharacters() throws Exception {
        String value = "中文长文保留".repeat(220);
        var spec = graph(3, value);
        try (var context = new GraphRenderContext(settings(1440, 800))) {
            var renderer = new CompareGraphRenderer();
            var tables = renderer.tables(spec, context);
            assertTrue(tables.size() > 1);
            assertFalse(tables.get(0).rows().get(0).continuation());
            assertTrue(tables.get(1).rows().get(0).continuation());
            for (int col = 1; col <= 3; col++) {
                final int index = col;
                assertEquals(value.length(), tables.stream().flatMap(t -> t.rows().stream())
                        .flatMap(r -> r.cells().get(index).stream()).mapToInt(l -> l.layout().getCharacterCount()).sum());
            }
            for (var page : renderer.layout(new ResolvedGraph(spec, List.of()), context)) {
                page.validate(context);
                assertEquals(GraphPage.HeaderStyle.COMPACT, page.headerStyle());
                assertTrue(page.contentBottom() <= 744);
            }
        }
    }
    @Test void absentOrUnavailableIconsKeepHeadingCenteredAndRowBordersAligned() throws Exception {
        var spec = graph(2, "一段比另一段长的文字");
        try (var context = new GraphRenderContext(settings(1440, 800))) {
            var table = new CompareGraphRenderer().tables(spec, context).get(0);
            var image = new BufferedImage(1440, 800, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            try {
                GraphRenderContext.quality(g);
                CompareGraphRenderer.paint(g, context, table);
                float x = GraphRenderContext.MARGIN + table.metricWidth();
                for (var column : table.columns()) {
                    int min = 1440, max = -1;
                    for (int px = (int) x + 2; px < x + column.width() - 2; px++)
                        for (int py = (int) table.top() + 2; py < table.top() + table.headerHeight() - 2; py++)
                            if (image.getRGB(px, py) == GraphRenderContext.INK.getRGB()) { min = Math.min(min, px); max = Math.max(max, px); }
                    assertTrue(max >= min);
                    assertEquals(x + column.width() / 2, (min + max) / 2f, 8);
                    x += column.width();
                }
                int rowBottom = (int) (table.top() + table.headerHeight() + table.rows().get(0).height());
                for (int px : new int[]{80, 500, 1100}) assertEquals(image.getRGB(80, rowBottom), image.getRGB(px, rowBottom));
            } finally { g.dispose(); image.flush(); }
        }
    }
    @Test void compareBudgetsAndPageLimitsRunBeforeMatching() throws Exception {
        String xml = "<graph type='compare'><object id='a' label='A'/><object id='b' label='B'/><row label='维度'><cell object='a'>"
                + "长文字".repeat(400) + "</cell><cell object='b'/></row></graph>";
        var calls = new AtomicInteger();
        String address = UniverseChannel.getOutboundHttpAddress(); UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:2577");
        try {
            for (boolean budget : new boolean[]{true, false}) {
                var settings = settings(640, 800);
                if (budget) settings.maxNodes = 4; else settings.maxPages = 1;
                try (var renderer = new GraphDecoupler(settings, s -> { calls.incrementAndGet(); return new ResolvedGraph(s, List.of()); })) {
                    assertThrows(java.io.UncheckedIOException.class, () -> renderer.process(XmlMarks.parse(xml)));
                }
            }
            assertEquals(0, calls.get());
        } finally { UniverseChannel.setOutboundHttpAddress(address); }
    }
    @Test void screenshotContentRendersTenAlignedDimensionsToPreview() throws Exception {
        String xml = Files.readString(Path.of("examples/graphs/compare-models.xml"));
        var spec = (GraphSpec.CompareGraph) new GraphParser().parse(xml);
        assertEquals(2, spec.objects().size()); assertEquals(10, spec.rows().size());
        String address = UniverseChannel.getOutboundHttpAddress(); UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:2577");
        try (var renderer = new GraphDecoupler(settings(1440, 2000), s -> new ResolvedGraph(s, List.of()))) {
            var images = renderer.process(XmlMarks.parse(xml)); assertEquals(1, images.size());
            for (var field : images) {
                var image = (ImageField) field;
                try {
                    var mount = UniverseChannel.class.getDeclaredField("fileMounting"); mount.setAccessible(true);
                    var mounts = (Map<?, ?>) mount.get(null);
                    String path = image.getUrl().getPath();
                    var file = (java.io.File) mounts.get(path.substring(path.lastIndexOf('/') + 1));
                    Path preview = Path.of("target/graph-render-preview/compare-models.png");
                    Files.createDirectories(preview.getParent()); Files.copy(file.toPath(), preview, StandardCopyOption.REPLACE_EXISTING);
                } finally { UniverseChannel.releaseRepost(image.getUrl()); }
            }
        } finally { UniverseChannel.setOutboundHttpAddress(address); }
    }
}
