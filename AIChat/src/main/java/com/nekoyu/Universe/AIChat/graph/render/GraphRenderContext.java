package com.nekoyu.Universe.AIChat.graph.render;

import com.nekoyu.Universe.AIChat.graph.GraphSettings;
import com.nekoyu.Universe.AIChat.graph.IconResource;
import com.nekoyu.Universe.AIChat.graph.ResolvedGraph;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GraphRenderContext implements AutoCloseable {
    public static final Color BACKGROUND = new Color(0xfafbf9);
    public static final Color INK = new Color(0x233e43);
    public static final Color MUTED = new Color(0x647879);
    public static final Color ACCENT = new Color(0x237b72);
    public static final Color BORDER = new Color(0xdbe5e1);
    public static final Color SOFT = new Color(0xeaf2ee);
    public static final int MARGIN = 40, TOP = 156, FOOTER = 56;
    private static final long ICON_CACHE_BYTES = 32L * 1024 * 1024;
    private final LinkedHashMap<IconResource, BufferedImage> icons = new LinkedHashMap<>(16, .75f, true);
    private long iconBytes;
    private final Font regular, bold;
    private Map<String, ResolvedGraph.IconMatch> bindings = Map.of();
    private final FontRenderContext metrics = new FontRenderContext(null, true, true);
    private final int width, height, maxPages;

    public GraphRenderContext(GraphSettings settings) throws IOException {
        settings.validate(); width = settings.width; height = settings.pageHeight; maxPages = settings.maxPages;
        if (settings.fontFile != null && !settings.fontFile.isBlank()) {
            try {
                regular = Font.createFont(Font.TRUETYPE_FONT, Path.of(settings.fontFile).toFile());
                bold = regular.deriveFont(Font.BOLD);
            }
            catch (Exception e) { throw new IOException("Cannot load graph font", e); }
        } else {
            var family = GraphFonts.bundled();
            regular = family.regular(); bold = family.bold();
        }
    }
    public int width() { return width; }
    public int height() { return height; }
    public int bodyWidth() { return width - MARGIN * 2; }
    public int bodyHeight() { return height - TOP - FOOTER; }
    public int maxPages() { return maxPages; }
    public Font font(float size, boolean useBold) { return (useBold ? bold : regular).deriveFont(size); }
    public record Line(TextLayout layout, float height) {}

    public List<Line> text(String text, float availableWidth, float size, boolean bold) throws IOException {
        if (availableWidth < 24) throw new IOException("Text column is too narrow");
        Font font = font(size, bold);
        List<Line> lines = new ArrayList<>();
        for (String paragraph : text.split("\\r\\n|\\r|\\n", -1)) {
            AttributedString attributed = new AttributedString(paragraph.isEmpty() ? " " : paragraph);
            attributed.addAttribute(TextAttribute.FONT, font);
            var iterator = attributed.getIterator();
            var measurer = new LineBreakMeasurer(iterator, metrics);
            while (measurer.getPosition() < iterator.getEndIndex()) {
                TextLayout line = measurer.nextLayout(availableWidth);
                if (line == null) throw new IOException("Cannot wrap graph text");
                lines.add(new Line(line, line.getAscent() + line.getDescent() + line.getLeading() + 7));
            }
        }
        return List.copyOf(lines);
    }
    public static float textHeight(List<Line> lines) { float height = 0; for (Line line : lines) height += line.height(); return height; }
    public static void drawText(Graphics2D graphics, List<Line> lines, float x, float y, Color color) {
        graphics.setColor(color);
        for (Line line : lines) { line.layout().draw(graphics, x, y + line.layout().getAscent()); y += line.height(); }
    }
    public static void box(Graphics2D graphics, float x, float y, float width, float height) {
        box(graphics, x, y, width, height, Color.WHITE);
    }
    public static void box(Graphics2D graphics, float x, float y, float width, float height, Color fill) {
        var shape = new RoundRectangle2D.Float(x, y, width, height, 16, 16);
        graphics.setColor(fill); graphics.fill(shape);
        texture(graphics, shape, .48f);
        graphics.setColor(BORDER); graphics.setStroke(new BasicStroke(1.5f)); graphics.draw(shape);
    }
    /** One bundled RGBA raster, center-cropped with a uniform scale; never stretch or tile. */
    public static void texture(Graphics2D graphics, java.awt.Shape shape, float opacity) {
        BufferedImage image = TextureHolder.IMAGE;
        var bounds = shape.getBounds2D();
        double scale = Math.max(bounds.getWidth() / image.getWidth(), bounds.getHeight() / image.getHeight());
        var copy = (Graphics2D) graphics.create();
        try {
            copy.clip(shape);
            copy.setComposite(java.awt.AlphaComposite.SrcOver.derive(opacity));
            var transform = java.awt.geom.AffineTransform.getTranslateInstance(
                    bounds.getCenterX() - image.getWidth() * scale / 2,
                    bounds.getCenterY() - image.getHeight() * scale / 2);
            transform.scale(scale, scale);
            copy.drawImage(image, transform, null);
        } finally { copy.dispose(); }
    }
    private static final class TextureHolder {
        static final BufferedImage IMAGE = load();
        private static BufferedImage load() {
            try (var input = GraphRenderContext.class.getResourceAsStream("/graph/textures/circle-lines-alpha.png")) {
                if (input == null) throw new IOException("Missing graph background texture");
                var image = ImageIO.read(input);
                if (image == null || !image.getColorModel().hasAlpha()) throw new IOException("Graph texture must be RGBA");
                return image;
            } catch (IOException e) { throw new IllegalStateException("Cannot load graph background texture", e); }
        }
    }
    public static void quality(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }
    public Map<String, ResolvedGraph.IconMatch> matches(ResolvedGraph graph) {
        Map<String, ResolvedGraph.IconMatch> result = new LinkedHashMap<>();
        for (var match : graph.matches()) result.put(match.slot(), match);
        return result;
    }
    /** Bind only while painting under the global raster lock. Layout never depends on icon results. */
    public void bindIcons(ResolvedGraph graph) { bindings = matches(graph); }
    public void clearIcons() { bindings = Map.of(); }
    public boolean visibleIcon(String slot) {
        return visibleIcon(slot, null);
    }
    public boolean visibleIcon(String slot, ResolvedGraph.IconMatch fallback) {
        var match = bindings.getOrDefault(slot, fallback);
        return match != null && match.status() != ResolvedGraph.Status.UNAVAILABLE;
    }
    public void slotIcon(Graphics2D graphics, String slot, ResolvedGraph.IconMatch fallback, float x, float y, int size) throws IOException {
        icon(graphics, bindings.getOrDefault(slot, fallback), x, y, size);
    }
    public void icon(Graphics2D graphics, ResolvedGraph.IconMatch match, float x, float y, int size) throws IOException {
        if (match == null || match.status() == ResolvedGraph.Status.UNAVAILABLE) return;
        if (match.preferred().isEmpty()) {
            graphics.setColor(MUTED); graphics.setStroke(new BasicStroke(2));
            graphics.drawRoundRect((int) x + 6, (int) y + 6, size - 12, size - 12, 10, 10);
            graphics.setFont(font(24, true)); graphics.drawString("?", x + size / 2f - 7, y + size / 2f + 8);
            return;
        }
        IconResource resource = match.preferred().orElseThrow().resource();
        BufferedImage image = icons.get(resource);
        if (image == null) {
            // Read the dimensions before decoding; a corrupt/malicious thumbnail cannot allocate a huge raster.
            try (var bytes = resource.openPreview(); var input = new javax.imageio.stream.MemoryCacheImageInputStream(bytes)) {
                if (input == null) throw new IOException("Icon preview does not exist: " + resource.id());
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Invalid icon preview: " + resource.id());
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if (reader.getWidth(0) > 512 || reader.getHeight(0) > 512) throw new IOException("Icon preview exceeds 512px");
                    // Rendered slots are at most 60px. Subsample while decoding instead of
                    // allocating full thumbnails repeatedly in a large, slow-to-GC application heap.
                    var parameters = reader.getDefaultReadParam();
                    int sample = Math.max(1, (Math.max(reader.getWidth(0), reader.getHeight(0)) + 95) / 96);
                    parameters.setSourceSubsampling(sample, sample, 0, 0);
                    image = transparentMonochrome(reader.read(0, parameters));
                } finally { reader.dispose(); }
            }
            long bytes = (long) image.getWidth() * image.getHeight() * 4;
            while (iconBytes + bytes > ICON_CACHE_BYTES && !icons.isEmpty()) {
                var entry = icons.entrySet().iterator(); var old = entry.next();
                iconBytes -= (long) old.getValue().getWidth() * old.getValue().getHeight() * 4;
                old.getValue().flush(); entry.remove();
            }
            icons.put(resource, image); iconBytes += bytes;
        }
        graphics.drawImage(image, (int) x, (int) y, size, size, null);
    }
    public void validateHeader(String title) throws IOException {
        var lines = text(title, bodyWidth(), 32, true);
        if (lines.size() > 2 || textHeight(lines) > TOP - 52) throw new IOException("Graph title is too long for the page header");
    }
    /** Display-only treatment of black-on-white index thumbnails; source assets and vectors stay intact. */
    static BufferedImage transparentMonochrome(BufferedImage source) {
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int rgb = source.getRGB(x, y), r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 2) return source;
        }
        var display = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int rgb = source.getRGB(x, y), alpha = (rgb >>> 24) * (255 - (rgb & 255)) / 255;
            display.setRGB(x, y, (alpha << 24) | (INK.getRGB() & 0xffffff));
        }
        source.flush(); return display;
    }
    public void header(Graphics2D graphics, String title, String type, int page, int count, int canvasHeight) throws IOException {
        header(graphics, title, type, page, count, canvasHeight, GraphPage.HeaderStyle.STANDARD);
    }
    public float compactBodyTop(String title) throws IOException {
        validateHeader(title);
        return 84 + textHeight(text(title, bodyWidth(), 32, true));
    }
    public void header(Graphics2D graphics, String title, String type, int page, int count, int canvasHeight, GraphPage.HeaderStyle style) throws IOException {
        graphics.setColor(BACKGROUND); graphics.fillRect(0, 0, width, canvasHeight);
        texture(graphics, new java.awt.geom.Rectangle2D.Float(0, 0, width, canvasHeight), .35f);
        var heading = text(title, bodyWidth(), 32, true);
        if (heading.size() > 2) throw new IOException("Graph title is too long for the page header");
        String category = switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "list" -> "清单"; case "compare" -> "对比"; case "timeline" -> "时间线";
            case "tree" -> "层级结构"; case "flow" -> "流程"; default -> "关系网络";
        };
        graphics.setColor(ACCENT); graphics.setFont(font(16, true)); graphics.drawString(category, MARGIN, 31);
        drawText(graphics, heading, MARGIN, 48, INK);
        int lineY = style == GraphPage.HeaderStyle.COMPACT ? (int) compactBodyTop(title) - 20 : Math.max(TOP - 24, (int) textHeight(heading) + 60);
        graphics.setColor(BORDER); graphics.setStroke(new BasicStroke(1)); graphics.drawLine(MARGIN, lineY, width - MARGIN, lineY);
        if (count > 1) {
            String pagination = String.format(java.util.Locale.ROOT, "%02d / %02d", page, count);
            graphics.setColor(MUTED); graphics.setFont(font(16, false));
            graphics.drawString(pagination, width - MARGIN - graphics.getFontMetrics().stringWidth(pagination), canvasHeight - 24);
        }
    }
    @Override public void close() { clearIcons(); icons.values().forEach(BufferedImage::flush); icons.clear(); iconBytes = 0; }
}
