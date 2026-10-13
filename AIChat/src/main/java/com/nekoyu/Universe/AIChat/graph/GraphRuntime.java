package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.AIChat.Skill.Skill;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** AIChat-owned lifecycle; importing/indexing catalogs remains an explicit maintenance operation. */
public final class GraphRuntime implements AutoCloseable {
    private final GraphDecoupler decoupler;
    private final Skill skill;
    private GraphRuntime(GraphDecoupler decoupler, GraphSettings settings, boolean iconsEnabled) {
        this.decoupler = decoupler; this.skill = graphSkill(settings, iconsEnabled);
    }
    public GraphDecoupler decoupler() { return decoupler; }
    public Skill skill() { return skill; }

    public static GraphRuntime open(GraphSettings settings) throws IOException {
        return open(settings, IconAssetSource.classpath(GraphRuntime.class.getClassLoader(), IconBundle.PREFIX));
    }
    static GraphRuntime open(GraphSettings settings, IconAssetSource assets) throws IOException {
        settings.validate();
        GraphDecoupler.Resolver resolver = graph -> {
            return new ResolvedGraph(graph, graph.iconSlots().stream()
                    .map(slot -> new ResolvedGraph.IconMatch(slot.key(), slot.query(), List.of(), true)).toList());
        };
        boolean iconsEnabled = false;
        if (settings.iconsEnabled) {
            try {
                var bundle = IconBundle.load(assets);
                if (!settings.embeddingModel.equals(bundle.index().descriptor().space().model())
                        || settings.embeddingDimension != bundle.index().descriptor().space().dimension()) throw new IOException("Embedding model differs from bundled vectors");
                resolver = new ProviderResolver(settings, bundle);
                iconsEnabled = true;
            } catch (IOException | RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).warn("图标预建包不可用，继续提供无图标绘图：{}", e.toString());
            }
        }
        return new GraphRuntime(new GraphDecoupler(settings, resolver), settings, iconsEnabled);
    }
    @Override public void close() {
        decoupler.close();
    }
    /** 插件 prepare 顺序未固定，延迟到第一次真实绘图获取注册表；空间查询不需要 API Key。 */
    private static final class ProviderResolver implements GraphDecoupler.Resolver {
        private final GraphSettings settings;
        private final IconBundle bundle;
        private GraphMatchService matcher;
        ProviderResolver(GraphSettings settings, IconBundle bundle) { this.settings = settings; this.bundle = bundle; }
        private synchronized GraphMatchService matcher() {
            if (matcher == null) {
                try {
                    var delegate = new ProviderIconEmbeddingProvider(settings.embeddingProvider, settings.embeddingModel, settings.embeddingDimension, settings.embeddingSpaceVersion);
                    if (!delegate.space().equals(bundle.index().descriptor().space())) throw new IOException("Registered Provider space differs from bundled vectors");
                    var cached = new CachedEmbeddingProvider(delegate.space(), Path.of("data/AIChat/graph-embeddings/text"), () -> delegate);
                    matcher = new GraphMatchService(cached, bundle.index(), bundle.catalog());
                } catch (IOException | RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).warn("图标 Provider 不可用，保留正文继续绘制：{}", e.toString());
                    return null;
                }
            }
            return matcher;
        }
        @Override public ResolvedGraph resolve(GraphSpec graph) throws IOException {
            if (graph.iconSlots().isEmpty()) return new ResolvedGraph(graph, List.of());
            var ready = matcher();
            return ready == null ? new ResolvedGraph(graph, graph.iconSlots().stream()
                    .map(s -> new ResolvedGraph.IconMatch(s.key(), s.query(), List.of(), true)).toList()) : ready.resolve(graph);
        }
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
                line/node 可使用 icon="图标语义描述"。程序仅将 icon 内容向量化并在本地图标库匹配，
                取相似度最高的图标填充占位；正文、节点关系和图表类型由你明确填写。
                """ : "当前未配置图标匹配，省略所有 icon 属性。\n");
        return skill;
    }
}
