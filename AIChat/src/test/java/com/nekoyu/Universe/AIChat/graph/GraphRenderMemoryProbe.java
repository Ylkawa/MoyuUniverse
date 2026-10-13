package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.AIChat.XmlMarks;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.UniverseChannel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Manual RSS probe: wait at READY, then render at maximum raster/node/edge bounds. */
public final class GraphRenderMemoryProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of("target", "graph-memory-probe"); Files.createDirectories(directory);
        UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:8080");
        System.out.println("READY " + ProcessHandle.current().pid()); System.out.flush();
        new BufferedReader(new InputStreamReader(System.in)).readLine();
        GraphSettings settings = new GraphSettings(); settings.width = 2048; settings.pageHeight = 3906;
        settings.maxPages = 64; settings.outputDirectory = directory.resolve("images").toString();
        Path preview = directory.resolve("preview.png");
        var thumbnail = new java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = thumbnail.createGraphics(); graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 512, 512);
        graphics.setColor(java.awt.Color.BLACK); graphics.drawRect(128, 128, 256, 192); graphics.dispose();
        javax.imageio.ImageIO.write(thumbnail, "png", preview.toFile()); thumbnail.flush();
        List<IconResource> resources = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Path copy = directory.resolve("icon-" + i + ".png"); Files.copy(preview, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            resources.add(new IconResource("probe/" + i, "icon-" + i, "probe", "1", "test", directory.resolve("unused.svg"), copy));
        }
        GraphDecoupler.Resolver resolver = graph -> {
            List<ResolvedGraph.IconMatch> matches = new ArrayList<>(); int i = 0;
            for (GraphSpec.IconSlot slot : graph.iconSlots()) matches.add(new ResolvedGraph.IconMatch(slot.key(), slot.query(),
                    List.of(new IconCandidate(resources.get(i++ % resources.size()), 1))));
            return new ResolvedGraph(graph, matches);
        };
        try (var renderer = new GraphDecoupler(settings, resolver)) {
            for (String type : List.of("list", "compare", "timeline", "tree", "flow", "relation")) {
                String xml;
                if (type.equals("list") || type.equals("compare") || type.equals("timeline")) {
                    StringBuilder items = new StringBuilder("<graph type='").append(type).append("' title='内存压力验证'>");
                    for (int i = 0; i < 200; i++) items.append("<line icon='device'").append(type.equals("timeline") ? " time='第" + i + "天'" : "")
                            .append(">中文与 English 混排，用于验证分页绘图的内存占用。</line>");
                    xml = items.append("</graph>").toString();
                } else if (type.equals("tree")) {
                    StringBuilder tree = new StringBuilder("<graph type='tree'><node id='root' label='根节点' icon='device'>");
                    for (int i = 1; i < 200; i++) tree.append("<node id='n").append(i).append("' label='节点").append(i).append("' icon='device'/>");
                    xml = tree.append("</node></graph>").toString();
                } else {
                    StringBuilder graph = new StringBuilder("<graph type='").append(type).append("'>");
                    for (int i = 0; i < 200; i++) graph.append("<node id='n").append(i).append("' label='节点").append(i).append("' icon='device'/>");
                    for (int i = 0; i < 400; i++) graph.append("<edge from='n").append(i % 200).append("' to='n").append((i * 17 + 1) % 200).append("'/>");
                    xml = graph.append("</graph>").toString();
                }
                var images = renderer.process(XmlMarks.parse(xml));
                System.out.println(type + " pages=" + images.size()); System.out.flush();
                for (var field : images) UniverseChannel.releaseRepost(((ImageField) field).getUrl());
            }
        }
        System.out.println("DONE");
    }
}
