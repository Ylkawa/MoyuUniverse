package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.AIChat.XmlMarks;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.UniverseChannel;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manual preview generator. Explicit demo icon bindings; no embedding or Qdrant calls. */
public final class GraphExampleGenerator {
    public static void main(String[] args) throws Exception {
        Path sources = Path.of(args.length > 0 ? args[0] : "examples/graphs").toAbsolutePath();
        Path output = Path.of(args.length > 1 ? args[1] : "target/graph-render-preview/examples").toAbsolutePath();
        Files.createDirectories(output);
        Map<String, IconResource> resources = demoIcons(output.resolve("icons"));
        GraphSettings settings = new GraphSettings(); settings.outputDirectory = output.resolve("working").toString();
        String address = UniverseChannel.getOutboundHttpAddress();
        UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:8080");
        StringBuilder index = new StringBuilder("# 六种图表效果示例\n\n数据为演示设定，图标使用固定本地素材，未调用 embedding 服务。\n\n");
        try (var renderer = new GraphDecoupler(settings, graph -> {
            List<ResolvedGraph.IconMatch> matches = new ArrayList<>();
            for (var slot : graph.iconSlots()) {
                IconResource resource = resources.get(slot.query());
                if (resource == null) throw new IOException("Unknown demo icon: " + slot.query());
                matches.add(new ResolvedGraph.IconMatch(slot.key(), slot.query(), List.of(new IconCandidate(resource, 1))));
            }
            return new ResolvedGraph(graph, matches);
        })) {
            for (String type : List.of("list", "compare", "timeline", "tree", "wide-tree", "flow", "relation")) {
                String xml = Files.readString(sources.resolve(type + ".xml"));
                Files.writeString(output.resolve(type + ".xml"), xml);
                var pages = renderer.process(XmlMarks.parse(xml));
                try {
                    index.append("## ").append(new GraphParser().parse(xml).title()).append("\n\n")
                            .append("[输入 XML](").append(type).append(".xml)\n\n");
                    for (int i = 0; i < pages.size(); i++) {
                        ImageField image = (ImageField) pages.get(i);
                        String filename = type + "-" + (i + 1) + ".png";
                        Files.copy(mountedFile(image).toPath(), output.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                        index.append("![").append(type).append(" 第 ").append(i + 1).append(" 页](").append(filename).append(")\n\n");
                    }
                    System.out.println(type + ": " + pages.size() + " pages → " + output);
                } finally {
                    for (var page : pages) UniverseChannel.releaseRepost(((ImageField) page).getUrl());
                }
            }
            Files.writeString(output.resolve("index.md"), index);
        } finally { UniverseChannel.setOutboundHttpAddress(address); }
    }
    private static File mountedFile(ImageField image) throws Exception {
        var field = UniverseChannel.class.getDeclaredField("fileMounting"); field.setAccessible(true);
        String path = image.getUrl().getPath();
        File file = (File) ((Map<?, ?>) field.get(null)).get(path.substring(path.lastIndexOf('/') + 1));
        if (file == null) throw new IOException("Generated image is not mounted");
        return file;
    }
    private static Map<String, IconResource> demoIcons(Path directory) throws IOException {
        Files.createDirectories(directory);
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("laptop", "<rect x='4' y='3' width='16' height='13' rx='1'/><path d='M2 20h20M4 16l-2 4m18-4 2 4'/>");
        shapes.put("desktop", "<rect x='2' y='3' width='20' height='14' rx='1'/><path d='M12 17v4m-5 0h10'/>");
        shapes.put("server", "<rect x='3' y='3' width='18' height='8' rx='2'/><rect x='3' y='13' width='18' height='8' rx='2'/><path d='M7 7h.01M7 17h.01m5-10h5m-5 10h5'/>");
        shapes.put("database", "<ellipse cx='12' cy='5' rx='9' ry='3'/><path d='M3 5v14c0 4 18 4 18 0V5M3 12c0 4 18 4 18 0'/>");
        shapes.put("shield", "<path d='m12 2 9 4v7c0 5-9 9-9 9s-9-4-9-9V6Zm-4 10 3 3 5-6'/>");
        shapes.put("backup", "<path d='M3 10a9 9 0 1 1 2 8M3 4v6h6m3-4v6l4 2'/>");
        shapes.put("battery", "<rect x='2' y='6' width='18' height='12' rx='2'/><path d='M22 10v4M12 8l-4 5h6l-3 3'/>");
        shapes.put("bell", "<path d='M18 8a6 6 0 0 0-12 0c0 7-3 8-3 9h18c0-1-3-2-3-9M10 21h4'/>");
        shapes.put("clipboard", "<rect x='5' y='4' width='14' height='18' rx='2'/><rect x='9' y='2' width='6' height='4' rx='1'/><path d='M9 11h6m-6 4h6'/>");
        shapes.put("code", "<path d='m8 6-6 6 6 6m8-12 6 6-6 6m-3-15-2 18'/>");
        shapes.put("image", "<rect x='2' y='3' width='20' height='18' rx='2'/><circle cx='7' cy='8' r='2'/><path d='m3 19 6-7 4 4 4-6 5 7'/>");
        shapes.put("check", "<circle cx='12' cy='12' r='10'/><path d='m7 12 3 3 7-7'/>");
        shapes.put("rocket", "<path d='M9 15 6 12C8 6 13 2 21 3c1 8-3 13-9 15l-3-3Zm-3-3H2l4-6 5 1m1 11v4l6-4-1-5M7 17l-3 3'/><circle cx='16' cy='8' r='2'/>");
        shapes.put("messages", "<path d='M3 3h15v12H8l-5 4V3Zm15 5h4v14l-5-3h-6v-4'/>");
        shapes.put("chart", "<path d='M3 2v19h19M7 17v-6m5 6V7m5 10V4'/>");
        shapes.put("search", "<circle cx='10' cy='10' r='7'/><path d='m15 15 7 7'/>");
        shapes.put("cloud", "<path d='M7 19a5 5 0 1 1 0-10 7 7 0 0 1 13 1 4.5 4.5 0 0 1-1 9Z'/>");
        shapes.put("alert", "<circle cx='12' cy='12' r='10'/><path d='M12 6v7m0 4h.01'/>");
        Map<String, IconResource> result = new LinkedHashMap<>();
        var preview = new SvgIconPreview();
        for (var shape : shapes.entrySet()) {
            String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='24' height='24' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'>" + shape.getValue() + "</svg>";
            byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
            Path svgFile = directory.resolve(shape.getKey() + ".svg"), pngFile = directory.resolve(shape.getKey() + ".png");
            Files.write(svgFile, bytes); Files.write(pngFile, preview.render(bytes));
            result.put(shape.getKey(), new IconResource("example/" + shape.getKey(), shape.getKey(), "example", "1", IndexDescriptor.hash(bytes), svgFile, pngFile));
        }
        return result;
    }
}
