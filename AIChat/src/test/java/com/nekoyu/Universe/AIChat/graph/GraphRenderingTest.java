package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.AIChat.AIChat;
import com.nekoyu.Universe.AIChat.MarkDecoupler;
import com.nekoyu.Universe.AIChat.Skill.SkillRegistry;
import com.nekoyu.Universe.AIChat.XmlMarks;
import com.nekoyu.Universe.AIChat.graph.render.*;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.UniverseChannel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GraphRenderingTest {
    @TempDir Path temp;
    private String previousAddress;
    private final List<ImageField> published = new ArrayList<>();
    @BeforeEach void setup() { previousAddress = UniverseChannel.getOutboundHttpAddress(); UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:8080"); }
    @AfterEach void cleanup() { published.forEach(image -> UniverseChannel.releaseRepost(image.getUrl())); UniverseChannel.setOutboundHttpAddress(previousAddress); }
    private GraphSettings settings() { var s = new GraphSettings(); s.outputDirectory = temp.resolve("output").toString(); return s; }
    @SuppressWarnings("unchecked")
    static File mountedFile(ImageField image) throws Exception {
        var field = UniverseChannel.class.getDeclaredField("fileMounting"); field.setAccessible(true);
        String path = image.getUrl().getPath();
        return (File) ((Map<String, ?>) field.get(null)).get(path.substring(path.lastIndexOf('/') + 1));
    }
    static String example(String type) {
        return switch (type) {
            case "list" -> "<graph type='list' title='设备信息'><line icon='laptop'>笔记本：OverClocked for 400MHz frequency</line><line icon='desktop'>台式机：None OverClock</line></graph>";
            case "compare" -> "<graph type='compare' title='超频情况对比'><object id='a' label='笔记本' icon='laptop'/><object id='b' label='台式机' icon='desktop'/><row label='超频'><cell object='a'>OverClocked for 400MHz frequency</cell><cell object='b'>None OverClock</cell></row><row label='场景'><cell object='a'>适合移动办公</cell><cell object='b'>适合固定工作站</cell></row></graph>";
            case "timeline" -> "<graph type='timeline' title='开发时间线'><line time='第一天' icon='laptop'>收集需求与设计</line><line time='第二天' icon='desktop'>实现解析、图标匹配与图片输出</line></graph>";
            case "tree" -> "<graph type='tree' title='系统层级'><node id='system' label='系统' icon='desktop'><node id='client' label='客户端' icon='laptop'/><node id='server' label='服务端' icon='desktop'><node id='database' label='数据存储'/></node></node></graph>";
            case "flow" -> "<graph type='flow' title='请求处理流程'><node id='receive' label='收到请求' icon='laptop'/><node id='process' label='处理请求' icon='desktop'/><node id='done' label='返回结果'/><edge from='receive' to='process' label='提交'/><edge from='process' to='done' label='成功'/><edge from='process' to='receive' label='重试'/></graph>";
            case "relation" -> "<graph type='relation' title='服务关系'><node id='client' label='客户端' icon='laptop'/><node id='api' label='接口服务' icon='desktop'/><node id='db' label='数据存储'/><node id='cache' label='缓存服务'/><edge from='client' to='api' label='请求'/><edge from='api' to='db' label='读写'/><edge from='api' to='cache' label='缓存'/></graph>";
            default -> throw new IllegalArgumentException(type);
        };
    }
    @ParameterizedTest @ValueSource(strings = {"list", "compare", "timeline", "tree", "flow", "relation"})
    void sixTypesRouteToRealPngAndKeepSurroundingText(String type) throws Exception {
        var catalog = GraphTestFixtures.catalog(temp); var provider = new GraphTestFixtures.Provider();
        var index = new GraphTestFixtures.MemoryIndex(IndexDescriptor.of(provider.space(), catalog));
        GraphTestFixtures.fill(provider, index, catalog);
        var matcher = new GraphMatchService(provider, index, catalog);
        try (var renderer = new GraphDecoupler(settings(), matcher::resolve)) {
            Map<String, MarkDecoupler> registry = registry(); var previous = registry.get("graph");
            AIChat agent = new AIChat();
            try {
                AIChat.registerMarkDecoupler("graph", renderer);
                MFChain output = agent.decoupleMark("前文" + example(type) + "后文");
                assertInstanceOf(TextField.class, output.getFirst()); assertEquals("前文", output.getFirst().toString());
                assertEquals("后文", output.getLast().toString());
                assertTrue(output.size() >= 3);
                assertEquals(new GraphParser().parse(example(type)).iconSlots().stream().map(GraphSpec.IconSlot::query).distinct().toList(), provider.texts);
                int page = 0;
                for (var item : output) if (item instanceof ImageField image) {
                    published.add(image); File file = mountedFile(image); assertNotNull(file);
                    var png = ImageIO.read(file); assertEquals(1440, png.getWidth()); assertTrue(png.getHeight() >= 320 && png.getHeight() <= 2000);
                    assertEquals(GraphRenderContext.BACKGROUND.getRGB(), png.getRGB(0, 0));
                    long colored = 0; for (int y = GraphRenderContext.TOP; y < png.getHeight() - 56; y += 4) for (int x = 40; x < 1400; x += 4)
                        if (png.getRGB(x, y) != GraphRenderContext.BACKGROUND.getRGB()) colored++;
                    assertTrue(colored > 500, "Graph body is blank"); png.flush();
                    Path preview = Path.of("target", "graph-render-preview", type + "-" + (++page) + ".png");
                    Files.createDirectories(preview.getParent()); Files.copy(file.toPath(), preview, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                assertTrue(page > 0);
            } finally { if (previous == null) registry.remove("graph", renderer); else registry.put("graph", previous); agent.stop(); }
        }
    }
    @SuppressWarnings("unchecked") private static Map<String, MarkDecoupler> registry() throws Exception {
        var f = AIChat.class.getDeclaredField("markDecouplers"); f.setAccessible(true); return (Map<String, MarkDecoupler>) f.get(null);
    }
    @Test void longTextAndCrossPageEdgesProduceMultipleImages() throws Exception {
        var settings = settings(); settings.width = 640; settings.pageHeight = 800;
        var parser = new GraphParser();
        try (var renderer = new GraphDecoupler(settings, graph -> new ResolvedGraph(graph, List.of()))) {
            String xml = "<graph type='list'><line>" + "中文长文本 English " .repeat(600) + "</line></graph>";
            var output = renderer.process(XmlMarks.parse(xml)); assertTrue(output.size() > 1);
            output.forEach(field -> published.add((ImageField) field));
            StringBuilder nodes = new StringBuilder("<graph type='flow'>");
            for (int i = 0; i < 12; i++) nodes.append("<node id='n").append(i).append("' label='节点").append(i).append("'/>");
            for (int i = 1; i < 12; i++) nodes.append("<edge from='n").append(i - 1).append("' to='n").append(i).append("'/>");
            nodes.append("</graph>");
            try (var context = new GraphRenderContext(settings)) {
                var graph = new ResolvedGraph(parser.parse(nodes.toString()), List.of());
                var pages = new FlowGraphRenderer().layout(graph, context);
                assertTrue(pages.size() > 4); assertTrue(pages.get(pages.size() - 1).heading().contains("关系索引"));
            }
            var flow = renderer.process(XmlMarks.parse(nodes.toString())); assertTrue(flow.size() > 4);
            flow.forEach(field -> published.add((ImageField) field));
        }
    }
    @Test void exampleTreeKeepsSeparateParentBranches() throws Exception {
        try (var renderer = new GraphDecoupler(settings(), graph -> new ResolvedGraph(graph, List.of()))) {
            var result = renderer.process(XmlMarks.parse(Files.readString(Path.of("examples", "graphs", "tree.xml"))));
            result.forEach(field -> published.add((ImageField) field));
            var png = ImageIO.read(mountedFile((ImageField) result.getFirst()));
            try {
                // The session branch owns its context leaf; the graph branch owns the other three.
                // A shared horizontal bus in this gap would falsely connect those two parents.
                for (int y = 540; y < 585; y++) assertEquals(GraphRenderContext.BACKGROUND.getRGB(), png.getRGB(350, y), "Unrelated parent buses were merged");
            } finally { png.flush(); }
        }
    }
    @Test void exampleFlowSkipEdgesRemainVisibleOutsideIntermediateCards() throws Exception {
        try (var renderer = new GraphDecoupler(settings(), graph -> new ResolvedGraph(graph, List.of()))) {
            var result = renderer.process(XmlMarks.parse(Files.readString(Path.of("examples", "graphs", "flow.xml"))));
            result.forEach(field -> published.add((ImageField) field));
            var png = ImageIO.read(mountedFile((ImageField) result.getFirst()));
            try {
                int visible = 0;
                for (int x = 1060; x < 1110; x++) for (int y = 600; y < 800; y++)
                    if (png.getRGB(x, y) == GraphRenderContext.ACCENT.getRGB()) visible++;
                assertTrue(visible > 80, "Skip-level error connections disappeared under intermediate cards");
            } finally { png.flush(); }
        }
    }
    @Test void flowCaptionsPreserveEveryRouteAndArrowPixel() throws Exception {
        String labelled = Files.readString(Path.of("examples", "graphs", "flow.xml"));
        // Only edge captions are removed; node labels and graph geometry stay identical.
        String bare = labelled.replaceAll("(<edge[^>]*?) label=\"[^\"]*\"", "$1");
        try (var renderer = new GraphDecoupler(settings(), graph -> new ResolvedGraph(graph, List.of()))) {
            var baseline = renderer.process(XmlMarks.parse(bare));
            var output = renderer.process(XmlMarks.parse(labelled));
            baseline.forEach(field -> published.add((ImageField) field));
            output.forEach(field -> published.add((ImageField) field));
            var plain = ImageIO.read(mountedFile((ImageField) baseline.getFirst()));
            var captions = ImageIO.read(mountedFile((ImageField) output.getFirst()));
            try {
                assertEquals(plain.getWidth(), captions.getWidth()); assertEquals(plain.getHeight(), captions.getHeight());
                int routePixels = 0;
                for (int y = GraphRenderContext.TOP; y < plain.getHeight() - GraphRenderContext.FOOTER; y++) {
                    for (int x = 0; x < plain.getWidth(); x++) {
                        if (plain.getRGB(x, y) == GraphRenderContext.ACCENT.getRGB()) {
                            routePixels++;
                            assertEquals(plain.getRGB(x, y), captions.getRGB(x, y), "Caption covered a route or arrow at " + x + "," + y);
                        }
                    }
                }
                assertTrue(routePixels > 1000, "Baseline must contain the real flow routes and arrowheads");
            } finally { plain.flush(); captions.flush(); }
        }
    }
    @Test void failureAfterFirstPageDeletesAllUnpublishedFiles() throws Exception {
        GraphRenderer faulty = new GraphRenderer() {
            @Override public GraphSpec.Type type() { return GraphSpec.Type.LIST; }
            @Override public List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext c) {
                return List.of(new GraphPage("第一页", (g, ctx) -> {}), new GraphPage("第二页", (g, ctx) -> { throw new IOException("Deliberate painting failure"); }));
            }
        };
        try (var renderer = new GraphDecoupler(settings(), graph -> new ResolvedGraph(graph, List.of()),
                List.of(faulty, new CompareGraphRenderer(), new TimelineGraphRenderer(), new TreeGraphRenderer(), new FlowGraphRenderer(), new RelationGraphRenderer()))) {
            assertThrows(java.io.UncheckedIOException.class, () -> renderer.process(XmlMarks.parse("<graph type='list'><line>x</line></graph>")));
            try (var files = Files.list(temp.resolve("output"))) { assertEquals(0, files.count()); }
        }
    }
    @Test void requestsAreSerializedAcrossDecouplerInstances() throws Exception {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        GraphRenderer tracked = new GraphRenderer() {
            public GraphSpec.Type type() { return GraphSpec.Type.LIST; }
            public List<GraphPage> layout(ResolvedGraph graph, GraphRenderContext context) throws IOException {
                return new ListGraphRenderer().layout(graph, context).stream().map(page -> new GraphPage(page.heading(), page.contentBottom(), (g, c) -> {
                    int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
                    try {
                        Thread.sleep(20); page.painter().paint(g, c);
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    finally { active.decrementAndGet(); }
                })).toList();
            }
        };
        var renderers = List.of(tracked, new CompareGraphRenderer(), new TimelineGraphRenderer(), new TreeGraphRenderer(), new FlowGraphRenderer(), new RelationGraphRenderer());
        GraphDecoupler.Resolver resolve = graph -> new ResolvedGraph(graph, List.of());
        try (var first = new GraphDecoupler(settings(), resolve, renderers); var second = new GraphDecoupler(settings(), resolve, renderers)) {
            var workers = Executors.newFixedThreadPool(4);
            try {
                var futures = new ArrayList<java.util.concurrent.Future<MFChain>>();
                for (int i = 0; i < 4; i++) { var renderer = i % 2 == 0 ? first : second;
                    futures.add(workers.submit(() -> renderer.process(XmlMarks.parse("<graph type='list'><line>并发</line></graph>")))); }
                for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS).forEach(f -> published.add((ImageField) f));
                assertEquals(1, peak.get());
            } finally { workers.shutdownNow(); }
        }
    }
    @Test void slowMatchingDoesNotBlockOtherDrawingOrClose() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(3);
        try (var slow = new GraphDecoupler(settings(), graph -> {
            entered.countDown();
            try { if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Test resolver timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return new ResolvedGraph(graph, List.of());
        }); var plain = new GraphDecoupler(settings(), graph -> new ResolvedGraph(graph, List.of()))) {
            var waiting = workers.submit(() -> slow.process(XmlMarks.parse("<graph type='list'><line icon='slow'>慢匹配</line></graph>")));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var quick = workers.submit(() -> plain.process(XmlMarks.parse("<graph type='list'><line>无需图标</line></graph>")));
            quick.get(5, java.util.concurrent.TimeUnit.SECONDS).forEach(field -> published.add((ImageField) field));
            workers.submit(slow::close).get(5, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> waiting.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.io.UncheckedIOException.class, failure.getCause());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void renderingLimitsAndPageHeadersAreCheckedBeforeMatching() throws Exception {
        var settings = settings(); settings.width = 640; settings.pageHeight = 800; settings.maxPages = 1;
        AtomicInteger calls = new AtomicInteger();
        List<String> invalid = List.of(
                "<graph type='flow'><node id='n' label='" + "字".repeat(401) + "' icon='device'/></graph>",
                "<graph type='flow'><node id='" + "n".repeat(81) + "' label='节点' icon='device'/></graph>",
                "<graph type='flow'><node id='n' label='节点' icon='device'/><edge from='n' to='n' label='" + "字".repeat(201) + "'/></graph>",
                "<graph type='list' title='" + "标题".repeat(100) + "'><line icon='device'>正文</line></graph>",
                "<graph type='list'><line icon='device'>" + "中文 English paragraph ".repeat(500) + "</line></graph>",
                "<graph type='timeline'><line time='" + "时间".repeat(300) + "' icon='device'>正文</line></graph>");
        try (var renderer = new GraphDecoupler(settings, graph -> { calls.incrementAndGet(); return new ResolvedGraph(graph, List.of()); })) {
            for (String xml : invalid) assertThrows(java.io.UncheckedIOException.class, () -> renderer.process(XmlMarks.parse(xml)));
            assertEquals(0, calls.get(), "Invalid render plans must not incur icon requests");
        }
        assertFalse(Files.exists(temp.resolve("output")), "Preflight must not publish files");
    }
    @Test void iconBindingsWorkForPlansAndDoNotLeakIntoTheNextRequest() throws Exception {
        var resource = GraphTestFixtures.catalog(temp).icons().get(0);
        GraphDecoupler.Resolver resolve = graph -> new ResolvedGraph(graph, graph.iconSlots().stream()
                .map(slot -> new ResolvedGraph.IconMatch(slot.key(), slot.query(), List.of(new IconCandidate(resource, 1)))).toList());
        String xml = "<graph type='list'><line icon='laptop'>图标测试</line></graph>";
        try (var renderer = new GraphDecoupler(settings(), resolve); var context = new GraphRenderContext(settings())) {
            for (boolean hasIcon : new boolean[]{true, false}) {
                var output = renderer.process(XmlMarks.parse(hasIcon ? xml : xml.replace(" icon='laptop'", "")));
                output.forEach(field -> published.add((ImageField) field));
                var png = ImageIO.read(mountedFile((ImageField) output.getFirst()));
                try { assertEquals(hasIcon, blackIconPixels(png) > 10); } finally { png.flush(); }
            }
            var graph = resolve.resolve(new GraphParser().parse(xml));
            var page = new ListGraphRenderer().layout(graph, context).get(0);
            var png = new java.awt.image.BufferedImage(context.width(), page.canvasHeight(context), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = png.createGraphics();
            try {
                GraphRenderContext.quality(graphics); context.header(graphics, page.heading(), "list", 1, 1, png.getHeight());
                page.painter().paint(graphics, context);
                assertTrue(blackIconPixels(png) > 10, "Direct renderer callers must retain supplied icon matches");
            } finally { graphics.dispose(); png.flush(); }
        }
    }
    private static int blackIconPixels(java.awt.image.BufferedImage png) {
        int pixels = 0;
        for (int y = 174; y < 234; y++) for (int x = 60; x < 120; x++) if (png.getRGB(x, y) == java.awt.Color.BLACK.getRGB()) pixels++;
        return pixels;
    }
    @Test void inFlightPlansAreBoundedAndCapacityIsReleasedAfterCompletion() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(8);
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(8);
        try (var renderer = new GraphDecoupler(settings(), graph -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Test resolver timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return new ResolvedGraph(graph, List.of());
        })) {
            var tasks = new ArrayList<java.util.concurrent.Future<MFChain>>();
            String xml = "<graph type='list'><line>容量验证</line></graph>";
            for (int i = 0; i < 8; i++) tasks.add(workers.submit(() -> renderer.process(XmlMarks.parse(xml))));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var failure = assertThrows(java.io.UncheckedIOException.class, () -> renderer.process(XmlMarks.parse(xml)));
            assertTrue(failure.getCause().getMessage().contains("capacity")); assertEquals(8, calls.get());
            release.countDown();
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS).forEach(field -> published.add((ImageField) field));
            renderer.process(XmlMarks.parse(xml)).forEach(field -> published.add((ImageField) field));
            assertEquals(9, calls.get());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void wideAndDeepTreesRetainExplicitHierarchyAndStayWithinPageBounds() throws Exception {
        String wide = "<graph type='tree'><node id='root' label='系统'><node id='session' label='会话'><node id='context' label='上下文'/></node><node id='graph' label='绘图'><node id='parser' label='解析'/><node id='icons' label='图标'/><node id='renderer' label='渲染'/><node id='export' label='导出'/></node></node></graph>";
        StringBuilder deep = new StringBuilder("<graph type='tree'>");
        for (int i = 0; i < 20; i++) deep.append("<node id='n").append(i).append("' label='第").append(i).append("层'>");
        deep.append("</node>".repeat(20)).append("</graph>");
        try (var context = new GraphRenderContext(settings()); var renderer = new GraphDecoupler(settings(), graph -> new ResolvedGraph(graph, List.of()))) {
            for (String xml : List.of(wide, deep.toString())) {
                var graph = new ResolvedGraph(new GraphParser().parse(xml), List.of());
                var plan = new TreeGraphRenderer().layout(graph, context);
                assertTrue(plan.get(0).heading().contains("层级展开"));
                for (var page : plan) {
                    page.validate(context);
                    assertTrue(page.contentBottom() <= context.height() - GraphRenderContext.FOOTER);
                }
                var images = renderer.process(XmlMarks.parse(xml));
                images.forEach(field -> published.add((ImageField) field));
                if (xml.equals(wide)) {
                    Path preview = Path.of("target", "graph-render-preview", "wide-tree-fixed.png");
                    Files.createDirectories(preview.getParent());
                    Files.copy(mountedFile((ImageField) images.getFirst()).toPath(), preview, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else assertTrue(plan.get(plan.size() - 1).heading().contains("关系索引"));
            }
        }
    }
    @Test void inputBudgetsApplyBeforeCallingMatcherAndFailuresKeepXml() throws Exception {
        var settings = settings(); settings.maxNodes = 1;
        AtomicInteger calls = new AtomicInteger();
        try (var renderer = new GraphDecoupler(settings, graph -> { calls.incrementAndGet(); return new ResolvedGraph(graph, List.of()); })) {
            var registry = registry(); var previous = registry.get("graph"); AIChat agent = new AIChat();
            try {
                AIChat.registerMarkDecoupler("graph", renderer);
                String xml = "<graph type='list'><line>a</line><line>b</line></graph>";
                assertEquals(xml, agent.decoupleMark(xml).toString()); assertEquals(0, calls.get());
                assertEquals("<graph type='unknown'></graph>", agent.decoupleMark("<graph type='unknown'></graph>").toString());
                assertEquals("<graph type='list'/>", agent.decoupleMark("<graph type='list'/>").toString());
            } finally { if (previous == null) registry.remove("graph", renderer); else registry.put("graph", previous); agent.stop(); }
        }
    }
    @Test void runtimeRegistersPairedGraphAndSkillAndRemovesOwnedEntriesOnStop() throws Exception {
        var registry = registry(); var previous = registry.get("graph"); var priorSkill = SkillRegistry.get("graph"); AIChat agent = new AIChat();
        try {
            var config = new com.google.gson.Gson().fromJson("{\"Graph\":{\"iconsEnabled\":false}}", com.nekoyu.Universe.AIChat.Config.class);
            var configField = AIChat.class.getDeclaredField("globalCfg"); configField.setAccessible(true); configField.set(agent, config);
            var load = AIChat.class.getDeclaredMethod("loadInternalMarkDecoupler"); load.setAccessible(true); load.invoke(agent);
            assertEquals(MarkDecoupler.Form.PAIRED, registry.get("graph").form()); assertNotNull(SkillRegistry.get("graph"));
            assertTrue(SkillRegistry.get("graph").systemPrompt.contains("省略所有 icon"));
            MFChain output = agent.decoupleMark("<graph type='list'><line>内置图片输出</line></graph>");
            assertEquals(1, output.size()); published.add(assertInstanceOf(ImageField.class, output.getFirst()));
            assertTrue(mountedFile(published.get(published.size() - 1)).isFile());
            agent.stop(); assertNull(registry.get("graph")); assertNull(SkillRegistry.get("graph"));
        } finally { agent.stop(); if (previous != null) registry.put("graph", previous); if (priorSkill != null) SkillRegistry.register(priorSkill); }
    }
}
