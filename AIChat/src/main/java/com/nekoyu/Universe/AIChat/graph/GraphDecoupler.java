package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.AIChat.MarkDecoupler;
import com.nekoyu.Universe.AIChat.graph.render.*;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.UniverseChannel;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Routes explicit graph types, resolves only icon slots, and publishes complete PNG pages. */
public final class GraphDecoupler implements MarkDecoupler, AutoCloseable {
    @FunctionalInterface
    public interface Resolver { ResolvedGraph resolve(GraphSpec graph) throws IOException; }
    // Shared across instances: concurrent chats cannot multiply raster/encoder memory.
    private static final Semaphore RENDER_SLOT = new Semaphore(1, true);
    // Page plans can wait on remote matching; cap retained jobs as well as raster concurrency.
    private static final Semaphore JOB_SLOTS = new Semaphore(8);
    private final GraphParser parser = new GraphParser();
    private final Resolver resolver;
    private final GraphRenderContext context;
    private final Map<GraphSpec.Type, GraphRenderer> renderers = new EnumMap<>(GraphSpec.Type.class);
    private final Path directory;
    private final int maxNodes, maxEdges, maxTextCharacters;
    private BufferedImage canvas;
    private volatile boolean closed;

    public GraphDecoupler(GraphSettings settings, Resolver resolver) throws IOException {
        this(settings, resolver, List.of(new ListGraphRenderer(), new CompareGraphRenderer(), new TimelineGraphRenderer(),
                new TreeGraphRenderer(), new FlowGraphRenderer(), new RelationGraphRenderer()));
    }
    public GraphDecoupler(GraphSettings settings, Resolver resolver, List<GraphRenderer> renderers) throws IOException {
        this.resolver = Objects.requireNonNull(resolver);
        context = new GraphRenderContext(settings);
        directory = Path.of(settings.outputDirectory).toAbsolutePath().normalize();
        maxNodes = settings.maxNodes; maxEdges = settings.maxEdges; maxTextCharacters = settings.maxTextCharacters;
        for (GraphRenderer renderer : renderers) {
            if (this.renderers.put(renderer.type(), renderer) != null) throw new IllegalArgumentException("Duplicate graph renderer");
        }
        if (this.renderers.size() != GraphSpec.Type.values().length) throw new IllegalArgumentException("All graph types require a renderer");
    }
    @Override public Form form() { return Form.PAIRED; }

    @Override public MFChain process(Element element) {
        if (!JOB_SLOTS.tryAcquire()) throw new UncheckedIOException(new IOException("Graph request capacity exceeded"));
        try {
            validateBudget(element);
            GraphSpec spec = parser.parse(element);
            var logger = org.slf4j.LoggerFactory.getLogger(GraphDecoupler.class);
            logger.info("Graph 绘图输入：type={}，iconSlots={}，明确指定 icon 才会触发匹配", spec.type(), spec.iconSlots().size());
            if (logger.isDebugEnabled()) {
                try {
                    var factory = javax.xml.transform.TransformerFactory.newInstance();
                    factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    var transformer = factory.newTransformer();
                    transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
                    var xml = new java.io.StringWriter();
                    transformer.transform(new javax.xml.transform.dom.DOMSource(element), new javax.xml.transform.stream.StreamResult(xml));
                    logger.debug("Graph 原始 XML：{}", xml);
                } catch (javax.xml.transform.TransformerException e) { logger.debug("Graph XML 日志序列化失败", e); }
            }
            // Build the full page plan before any potentially paid icon request.
            acquireRenderSlot();
            List<GraphPage> pages;
            try {
                if (closed) throw new IOException("Graph renderer is closed");
                validateOutboundAddress();
                pages = renderers.get(spec.type()).layout(new ResolvedGraph(spec, List.of()), context);
                if (pages.isEmpty() || pages.size() > context.maxPages()) throw new IOException("Invalid graph page count");
                for (GraphPage page : pages) page.validate(context);
            } finally { RENDER_SLOT.release(); }
            ResolvedGraph resolved = resolver.resolve(spec);
            if (resolved == null || !spec.equals(resolved.graph())) throw new IOException("Icon resolver changed the graph specification");
            logger.info("Graph 图标匹配结果：iconSlots={}，matched={}，unmatched={}，unavailable={}", spec.iconSlots().size(),
                    resolved.matches().stream().filter(m -> m.status() == ResolvedGraph.Status.CANDIDATES).count(),
                    resolved.matches().stream().filter(m -> m.status() == ResolvedGraph.Status.UNMATCHED).count(),
                    resolved.matches().stream().filter(m -> m.status() == ResolvedGraph.Status.UNAVAILABLE).count());
            acquireRenderSlot();
            try {
                if (closed) throw new IOException("Graph renderer is closed");
                context.bindIcons(resolved);
                return publish(spec, pages);
            } finally { context.clearIcons(); RENDER_SLOT.release(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Graph rendering interrupted", e);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        finally { JOB_SLOTS.release(); }
    }

    private static void acquireRenderSlot() throws IOException, InterruptedException {
        if (!RENDER_SLOT.tryAcquire(30, TimeUnit.SECONDS)) throw new IOException("Graph rendering queue timed out");
    }

    private MFChain publish(GraphSpec spec, List<GraphPage> pages) throws IOException {
        // Validate before rasterizing or retaining a file in Universe's HTTP cache.
        validateOutboundAddress();
        Files.createDirectories(directory);
        List<Path> files = new ArrayList<>();
        List<ImageField> images = new ArrayList<>();
        boolean success = false;
        try {
            // Reuse one bounded backing buffer across pages AND requests. A large application heap
            // must not postpone GC while successive page-sized allocations accumulate.
            if (canvas == null) canvas = new BufferedImage(context.width(), context.height(), BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < pages.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Graph rendering interrupted");
                Path file = Files.createTempFile(directory, "graph-", ".png"); files.add(file);
                int canvasHeight = pages.get(i).canvasHeight(context);
                BufferedImage raster = canvas.getSubimage(0, 0, context.width(), canvasHeight);
                try {
                    var graphics = raster.createGraphics();
                    try {
                        GraphRenderContext.quality(graphics);
                        context.header(graphics, pages.get(i).heading(), spec.type().name().toLowerCase(java.util.Locale.ROOT), i + 1, pages.size(), canvasHeight, pages.get(i).headerStyle());
                        pages.get(i).painter().paint(graphics, context);
                    } finally { graphics.dispose(); }
                    if (!ImageIO.write(raster, "png", file.toFile())) throw new IOException("PNG encoder is unavailable");
                } finally { raster.flush(); }
            }
            MFChain result = new MFChain();
            for (Path file : files) {
                ImageField image = new ImageField(file.toFile()); images.add(image); result.add(image);
            }
            success = true; return result;
        } finally {
            if (!success) {
                images.forEach(image -> UniverseChannel.releaseRepost(image.getUrl()));
                for (Path file : files) {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) { /* Original failure remains actionable. */ }
                }
            }
        }
    }

    private static void validateOutboundAddress() throws IOException {
        String address = UniverseChannel.getOutboundHttpAddress();
        java.net.URI uri;
        try { uri = java.net.URI.create(address == null ? "" : address); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid Universe outbound HTTP address", e); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
            throw new IOException("Universe outbound HTTP address is required for graph images");
    }

    private void validateBudget(Element root) throws IOException {
        ArrayDeque<Node> pending = new ArrayDeque<>(); pending.add(root);
        int nodes = 0, edges = 0, elements = 0; long characters = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (node instanceof Element element) {
                if (++elements > maxNodes + maxEdges + 1) throw new IOException("Graph contains too many elements");
                if (java.util.Set.of("line", "node", "object", "row", "cell").contains(element.getTagName()) && ++nodes > maxNodes)
                    throw new IOException("Graph contains too many items/nodes");
                if (element.getTagName().equals("edge") && ++edges > maxEdges) throw new IOException("Graph contains too many edges");
                for (int i = 0; i < element.getAttributes().getLength(); i++) characters += element.getAttributes().item(i).getNodeValue().length();
            } else if (node.getNodeValue() != null) characters += node.getNodeValue().length();
            if (characters > maxTextCharacters) throw new IOException("Graph exceeds text budget");
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) pending.add(child);
        }
        if ("tree".equals(root.getAttribute("type")) && nodes - 1 > maxEdges) throw new IOException("Tree contains too many edges");
    }

    @Override public void close() {
        RENDER_SLOT.acquireUninterruptibly();
        try {
            closed = true; context.close();
            if (canvas != null) { canvas.flush(); canvas = null; }
        } finally { RENDER_SLOT.release(); }
    }
}
