package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import javax.imageio.ImageIO;
import com.nekoyu.Universe.API.Providers.ProviderPlugin;
import java.nio.file.*;
import java.util.*;

/** 手动验收入口：只从发行 JAR 读资源，测量全库并验证真实查询及六类绘图。 */
public final class GraphJarAcceptance {
    public static void main(String[] args) throws Exception {
        int pluginIndex = Arrays.asList(args).indexOf("--provider-plugin");
        try (var plugin = pluginIndex >= 0 ? ProviderPlugin.open(Path.of(args[pluginIndex + 1])) : null) { run(args); }
    }
    private static void run(String[] args) throws Exception {
        var resource = IconBundle.class.getClassLoader().getResource(IconBundle.PREFIX + "current.json");
        if (resource == null || !resource.getProtocol().equals("jar")) throw new IllegalStateException("Acceptance must load icons from a JAR");
        System.out.println("READY");
        if (Arrays.asList(args).contains("--pause")) new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        long start = System.nanoTime();
        var bundle = IconBundle.load(IconAssetSource.classpath(IconBundle.class.getClassLoader(), IconBundle.PREFIX));
        System.out.println("LOAD_MS=" + (System.nanoTime() - start) / 1e6);
        System.out.println("ICONS=" + bundle.catalog().icons().size());
        var space = bundle.index().descriptor().space();
        Path cache = Path.of("data/AIChat/graph-embeddings/text");
        var registered = new ProviderIconEmbeddingProvider(args[0], space.model(), space.dimension(), "v1");
        if (!registered.space().equals(space)) throw new IllegalStateException("Provider space differs from packaged index");
        var provider = new CachedEmbeddingProvider(space, cache, () -> registered, bundle.storedSpace());
        var texts = List.of("笔记本电脑", "laptop", "服务器", "search", "数据库", "云端", "电池", "地图");
        var vectors = provider.embedTexts(texts);
        for (int i = 0; i < texts.size(); i++) {
            var hits = bundle.index().search(vectors.get(i), 5);
            System.out.println("MATCH " + texts.get(i) + " => " + hits.get(0).resource().id() + " " + hits.get(0).score());
        }
        for (int i = 0; i < 100; i++) bundle.index().search(vectors.get(0), 5);
        start = System.nanoTime();
        for (int i = 0; i < 300; i++) bundle.index().search(vectors.get(i % vectors.size()), 5);
        System.out.println("SEARCH_MEAN_MS=" + (System.nanoTime() - start) / 1e6 / 300);
        var offline = new CachedEmbeddingProvider(space, cache, () -> { throw new java.io.IOException("offline acceptance"); }, bundle.storedSpace());
        var service = new GraphMatchService(offline, bundle.index(), bundle.catalog());
        UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:2577");
        var settings = new GraphSettings(); settings.outputDirectory = "target/graph-jar-acceptance";
        var examples = new ArrayList<>(List.of(
                "<graph type='list'><line icon='laptop'>笔记本电脑与中文说明</line><line icon='never-cached-new-query'>未缓存描述保留正文</line></graph>",
                "<graph type='compare'><object id='a' label='笔记本' icon='laptop'/><object id='b' label='服务端' icon='服务器'/><row label='场景'><cell object='a'>移动办公</cell><cell object='b'>固定运行</cell></row></graph>",
                "<graph type='timeline'><line time='今天' icon='search'>测试检索</line><line time='明天' icon='云端'>完成部署</line></graph>",
                "<graph type='tree'><node id='root' label='系统' icon='服务器'><node id='db' label='数据库' icon='数据库'/></node></graph>",
                "<graph type='flow'><node id='a' label='接收请求' icon='laptop'/><node id='b' label='处理请求' icon='服务器'/><edge from='a' to='b' label='提交处理'/></graph>",
                "<graph type='relation'><node id='a' label='客户端' icon='laptop'/><node id='b' label='云服务' icon='云端'/><edge from='a' to='b' label='通信连接'/></graph>"));
        var longList = new StringBuilder("<graph type='list' title='长文分页'>");
        for (int i = 0; i < 60; i++) longList.append("<line icon='laptop'>").append("中文正文保留与自然换行。".repeat(15)).append("</line>");
        examples.add(longList.append("</graph>").toString());
        var wide = new StringBuilder("<graph type='tree'><node id='root' label='宽树根节点' icon='服务器'>");
        for (int i = 0; i < 120; i++) wide.append("<node id='n").append(i).append("' label='第").append(i).append("个子节点'/>");
        examples.add(wide.append("</node></graph>").toString());
        var deep = new StringBuilder("<graph type='tree'>");
        for (int i = 0; i < 80; i++) deep.append("<node id='d").append(i).append("' label='第").append(i).append("层节点'>");
        examples.add(deep.append("</node>".repeat(80)).append("</graph>").toString());
        var retained = new ArrayList<ImageField>();
        try (var renderer = new GraphDecoupler(settings, service::resolve)) {
            for (String xml : examples) {
                var result = renderer.process(com.nekoyu.Universe.AIChat.XmlMarks.parse(xml));
                for (var field : result) if (field instanceof ImageField image) retained.add(image);
            }
        }
        if (retained.size() < 6) throw new IllegalStateException("Missing graph images");
        try (var files = Files.list(Path.of(settings.outputDirectory))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".png")).toList()) {
                var image = ImageIO.read(file.toFile()); if (image == null) throw new IllegalStateException("Unreadable graph"); image.flush();
            }
        }
        System.out.println("RENDERED_PAGES=" + retained.size());
        System.out.println("FONT_RESOURCE=" + IconBundle.class.getResource("/fonts/harmonyos-sans/HarmonyOS_Sans_SC_Regular.ttf").getProtocol());
        System.out.println("DONE");
        if (Arrays.asList(args).contains("--pause")) new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
    }
}
