package com.nekoyu.Universe.AIChat.graph;

import com.google.gson.GsonBuilder;
import com.nekoyu.Universe.API.Providers.ProviderPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 人工语义抽样入口：真实文字向量查询正式 JAR，结果留给人评估，不作为自动准确率测试。 */
public final class GraphSemanticProbe {
    public static void main(String[] args) throws Exception {
        int pluginIndex = java.util.Arrays.asList(args).indexOf("--provider-plugin");
        try (var plugin = pluginIndex >= 0 ? ProviderPlugin.open(Path.of(args[pluginIndex + 1])) : null) { run(args); }
    }
    private static void run(String[] args) throws Exception {
        var resource = IconBundle.class.getClassLoader().getResource(IconBundle.PREFIX + "current.json");
        if (resource == null || !resource.getProtocol().equals("jar")) throw new IllegalStateException("Probe must load a JAR");
        var bundle = IconBundle.load(IconAssetSource.classpath(IconBundle.class.getClassLoader(), IconBundle.PREFIX));
        var space = bundle.index().descriptor().space();
        var remote = new ProviderIconEmbeddingProvider(args[0], space.model(), space.dimension(), "v1");
        if (!remote.space().equals(space)) throw new IllegalStateException("Provider space differs from packaged index");
        var provider = new CachedEmbeddingProvider(space, Path.of("data/AIChat/graph-embeddings/text"), () -> remote);
        var texts = List.of("便携电脑", "计算设备", "数据存储", "联网服务", "放大镜", "通知提醒", "删除垃圾", "设置选项",
                "保护安全", "登录账号", "发送邮件", "下载文件", "播放音乐", "上传云端", "开始运行", "停止运行", "太阳", "月亮");
        var vectors = provider.embedTexts(texts);
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < texts.size(); i++) {
            var hits = bundle.index().search(vectors.get(i), 5);
            rows.add(Map.of("text", texts.get(i), "top5", hits.stream()
                    .map(hit -> Map.of("id", hit.resource().id(), "score", hit.score())).toList()));
            System.out.println(texts.get(i) + " => " + hits.stream().limit(3).map(hit -> hit.resource().id()).toList());
        }
        Files.writeString(Path.of("AIChat/target/graph-semantic-samples.json"), new GsonBuilder().setPrettyPrinting().create().toJson(rows));
        System.out.println("INPUT_TOKENS=" + remote.inputTokens());
    }
}
