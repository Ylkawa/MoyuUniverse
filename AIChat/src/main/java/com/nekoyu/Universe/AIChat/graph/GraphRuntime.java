package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.AIChat.Skill.Skill;
import io.qdrant.client.QdrantClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** AIChat-owned lifecycle; importing/indexing catalogs remains an explicit maintenance operation. */
public final class GraphRuntime implements AutoCloseable {
    @FunctionalInterface public interface ClientFactory { QdrantClient open() throws IOException; }
    private final GraphDecoupler decoupler;
    private final QdrantClient client;
    private final Skill skill;
    private GraphRuntime(GraphDecoupler decoupler, QdrantClient client, GraphSettings settings, boolean iconsEnabled) {
        this.decoupler = decoupler; this.client = client; this.skill = graphSkill(settings, iconsEnabled);
    }
    public GraphDecoupler decoupler() { return decoupler; }
    public Skill skill() { return skill; }

    public static GraphRuntime open(GraphSettings settings, ClientFactory factory) throws IOException {
        settings.validate();
        GraphDecoupler.Resolver resolver = graph -> {
            if (!graph.iconSlots().isEmpty()) throw new IOException("Graph icon matching requires catalogManifest, Qdrant and the embedding API key");
            return new ResolvedGraph(graph, List.of());
        };
        QdrantClient client = null;
        boolean iconsEnabled = settings.catalogManifest != null && !settings.catalogManifest.isBlank();
        try {
            if (iconsEnabled) {
                IconCatalog catalog = new LucideCatalogImporter().loadManifest(Path.of(settings.catalogManifest));
                String key = settings.embeddingApiKeyEnv == null ? null : System.getenv(settings.embeddingApiKeyEnv);
                if (key == null || key.isBlank()) throw new IOException("Graph embedding API key environment variable is missing");
                var provider = new Qwen3VlEmbeddingProvider(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                        URI.create(settings.embeddingEndpoint), key, settings.embeddingDimension, Duration.ofSeconds(60));
                client = factory.open();
                var descriptor = IndexDescriptor.of(provider.space(), catalog);
                var index = new QdrantIconIndex(client, settings.qdrantCollection == null || settings.qdrantCollection.isBlank()
                        ? descriptor.defaultCollection() : settings.qdrantCollection, descriptor, Duration.ofSeconds(10));
                index.verify();
                var matcher = new GraphMatchService(provider, index, catalog);
                resolver = matcher::resolve;
            }
            return new GraphRuntime(new GraphDecoupler(settings, resolver), client, settings, iconsEnabled);
        } catch (IOException | RuntimeException e) {
            if (client != null) client.close();
            throw e;
        }
    }
    @Override public void close() {
        decoupler.close();
        if (client != null) client.close();
    }
    private static Skill graphSkill(GraphSettings settings, boolean iconsEnabled) {
        Skill skill = new Skill(); skill.id = "graph"; skill.name = "图表绘制";
        skill.description = "通过 graph XML 输出列表、对比图、时间线、树图、流程图和关系图，程序渲染为图片。";
        skill.systemPrompt = """
                用完整的 <graph type="..." title="可选标题">...</graph> 生成图表图片。
                主助手明确选择 type：list、compare、timeline、tree、flow、relation。不要推断或省略 type。
                list：<line>文字</line>；compare：至少两条 <line label="对象">文字</line>；
                timeline：<line time="时间">事件文字</line>，按输入顺序呈现，不自动排序。
                tree：一个根 <node id="唯一编号" label="文字"><node id="子编号" label="文字"/></node>，可以嵌套。
                flow/relation：平铺 <node id="编号" label="文字"/> 和 <edge from="编号" to="编号" label="可选关系"/>；
                edge 的端点必须存在。relation 可用 directed="true" 指定有向连接，默认无向；flow 始终有向。
                XML 属性里的引号与 &、< 要转义，正文里的 &、< 也要转义。不要在 graph 内嵌入其他功能标签。
                长图会分页返回多张图片。最多 %d 项/节点、%d 条边、%d 个字符、%d 页；
                单个节点 label 不超过 400 字符、id 不超过 80 字符，edge label 不超过 200 字符。
                """.formatted(settings.maxNodes, settings.maxEdges, settings.maxTextCharacters, settings.maxPages) + (iconsEnabled ? """
                line/node 可使用 icon="图标语义描述"。程序仅将 icon 内容交给 qwen3-vl-embedding 匹配图标库，
                取相似度最高的图标填充占位；正文、节点关系和图表类型由你明确填写。
                """ : "当前未配置图标匹配，省略所有 icon 属性。\n");
        return skill;
    }
}
