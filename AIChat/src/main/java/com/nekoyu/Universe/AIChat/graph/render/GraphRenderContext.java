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
    public static final Color BACKGROUND = new Color(0xf3f6fa);
    public static final Color INK = new Color(0x203148);
    public static final Color MUTED = new Color(0x63768c);
    public static final Color ACCENT = new Color(0x167b91);
    public static final Color BORDER = new Color(0xdce5ee);
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
        var shape = new RoundRectangle2D.Float(x, y, width, height, 20, 20);
        graphics.setColor(Color.WHITE); graphics.fill(shape);
        graphics.setColor(BORDER); graphics.setStroke(new BasicStroke(1.5f)); graphics.draw(shape);
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
                    image = reader.read(0, parameters);
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
    public void header(Graphics2D graphics, String title, String type, int page, int count, int canvasHeight) throws IOException {
        graphics.setColor(BACKGROUND); graphics.fillRect(0, 0, width, canvasHeight);
        var heading = text(title, bodyWidth(), 32, true);
        if (heading.size() > 2) throw new IOException("Graph title is too long for the page header");
        drawText(graphics, heading, MARGIN, 24, INK);
        graphics.setColor(ACCENT); graphics.fillRoundRect(MARGIN, 128, 64, 4, 4, 4);
        graphics.setColor(MUTED); graphics.setFont(font(17, false));
        graphics.drawString(type + "  ·  " + page + " / " + count, MARGIN, canvasHeight - 24);
    }
    @Override public void close() { clearIcons(); icons.values().forEach(BufferedImage::flush); icons.clear(); iconBytes = 0; }
}
