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
        GraphDecoupler.Resolver resolver = skipped(settings.iconsEnabled ? "BUNDLE_UNAVAILABLE" : "ICONS_DISABLED");
        boolean iconsEnabled = false;
        if (settings.iconsEnabled) {
            try {
                var bundle = IconBundle.load(assets);
                if (!settings.embeddingModel.equals(bundle.index().descriptor().space().model())
                        || settings.embeddingDimension != bundle.index().descriptor().space().dimension()) throw new IOException("Embedding model differs from bundled vectors");
                resolver = new ProviderResolver(settings, bundle);
                iconsEnabled = true;
                org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).debug("Graph 图标包就绪：icons={}，space={}，旧包身份迁移={}",
                        bundle.catalog().icons().size(), bundle.index().descriptor().space(), !bundle.storedSpace().equals(bundle.index().descriptor().space()));
            } catch (IOException | RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).warn("Graph 图标状态：BUNDLE_UNAVAILABLE，继续无图标绘图，原因={}", e.toString());
            }
        }
        if (!settings.iconsEnabled) org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).info("Graph 图标状态：ICONS_DISABLED，配置已关闭图标匹配");
        return new GraphRuntime(new GraphDecoupler(settings, resolver), settings, iconsEnabled);
    }
    private static GraphDecoupler.Resolver skipped(String reason) {
        return graph -> {
            org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).info("Graph 图标匹配跳过：reason={}，iconSlots={}，Provider 调用=0",
                    reason, graph.iconSlots().size());
            return new ResolvedGraph(graph, graph.iconSlots().stream()
                    .map(slot -> new ResolvedGraph.IconMatch(slot.key(), slot.query(), List.of(), true)).toList());
        };
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
                    if (!delegate.space().equals(bundle.index().descriptor().space()))
                        throw new IOException("Registered Provider space differs from bundled vectors: expected=" + bundle.index().descriptor().space() + ", actual=" + delegate.space());
                    var cached = new CachedEmbeddingProvider(delegate.space(), Path.of("data/AIChat/graph-embeddings/text"), () -> delegate, bundle.storedSpace());
                    matcher = new GraphMatchService(cached, bundle.index(), bundle.catalog());
                    org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).debug("Graph 图标 Provider 就绪：provider={}，space={}", settings.embeddingProvider, delegate.space());
                } catch (IOException | RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).warn("Graph 图标状态：PROVIDER_UNAVAILABLE，provider={}，Provider 调用=0，原因={}", settings.embeddingProvider, e.toString());
                    return null;
                }
            }
            return matcher;
        }
        @Override public ResolvedGraph resolve(GraphSpec graph) throws IOException {
            if (graph.iconSlots().isEmpty()) {
                org.slf4j.LoggerFactory.getLogger(GraphRuntime.class).info("Graph 图标匹配跳过：reason=NO_ICON_ATTRIBUTES，iconSlots=0，Provider 调用=0");
                return new ResolvedGraph(graph, List.of());
            }
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
                list：<line>文字</line>。
                list/timeline 的多行内容可将简短标题放在第一行，后面换行填写正文；不要为了排版重复内容或添加空白。
                compare：对象为列、对比维度为行，禁止使用旧 line 格式或把两个对象的值合并到同一单元格。
                先声明至少两个 <object id="唯一编号" label="对象名称"/>，再声明至少一条
                <row label="对比维度"><cell object="对象编号">该对象在此维度的内容</cell>...</row>。
                每行必须为每个对象填写一个 cell，引用 object 的 id，不可重复、遗漏或引用未知对象。
                空 cell 显示为 —；不确定的内容填“未提供”。列按 object 顺序，行按 row 顺序显示。
                例如：<graph type="compare" title="设备对比"><object id="laptop" label="笔记本"/>
                <object id="desktop" label="台式机"/><row label="使用场景"><cell object="laptop">移动办公</cell>
                <cell object="desktop">固定工位</cell></row></graph>。
                timeline：<line time="时间">事件文字</line>，按输入顺序呈现，不自动排序。
                tree：一个根 <node id="唯一编号" label="文字"><node id="子编号" label="文字"/></node>，可以嵌套。
                flow/relation：平铺 <node id="编号" label="文字"/> 和 <edge from="编号" to="编号" label="可选关系"/>；
                edge 的端点必须存在。relation 可用 directed="true" 指定有向连接，默认无向；flow 始终有向。
                node 的 label 写供读者阅读的名称，id 仅用于连接引用，不需要在 label 中重复。
                XML 属性里的引号与 &、< 要转义，正文里的 &、< 也要转义。不要在 graph 内嵌入其他功能标签。
                长图会分页返回多张图片。最多 %d 项/节点、%d 条边、%d 个字符、%d 页；
                compare 的 object、row、cell 均计入项数预算；对象和维度 label 不超过 400 字符。
                单个节点 label 不超过 400 字符、id 不超过 80 字符，edge label 不超过 200 字符。
                """.formatted(settings.maxNodes, settings.maxEdges, settings.maxTextCharacters, settings.maxPages) + (iconsEnabled ? """
                list/timeline 的 line、tree/flow/relation 的 node、compare 的 object 可使用 icon="图标语义描述"。
                需要图标时必须显式填写 icon，标题、正文和 id 不会自动触发匹配；compare 的 row/cell 不接受 icon。
                程序仅将 icon 内容向量化并在本地图标库匹配，
                取相似度最高的图标填充占位；正文、节点关系和图表类型由你明确填写。
                """ : "当前未配置图标匹配，省略所有 icon 属性。\n");
        return skill;
    }
}
