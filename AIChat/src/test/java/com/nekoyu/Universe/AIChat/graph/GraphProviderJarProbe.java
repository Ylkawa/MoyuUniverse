package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.API.Providers.ProviderPlugin;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.AIChat.XmlMarks;
import java.nio.file.Path;
import java.util.List;

/** 正式 JAR 的完整调用路径：插件 prepare 注册 → GraphRuntime → 公共 Provider → 持久缓存 → 绘图。 */
public final class GraphProviderJarProbe {
    public static void main(String[] args) throws Exception {
        var resource = GraphRuntime.class.getClassLoader().getResource(IconBundle.PREFIX + "current.json");
        if (resource == null || !resource.getProtocol().equals("jar")) throw new IllegalStateException("Probe must load a JAR");
        try (var plugin = ProviderPlugin.open(Path.of(args[1]))) {
            UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:2577");
            var settings = new GraphSettings(); settings.embeddingProvider = args[0]; settings.outputDirectory = "target/graph-provider-jar";
            try (var runtime = GraphRuntime.open(settings)) {
                int pages = 0;
                var examples = List.of(
                    "<graph type='list'><line icon='laptop'>完整正文</line><line icon='provider-jar-uncached-query'>未缓存描述仍保留</line></graph>",
                    "<graph type='compare'><object id='a' label='笔记本' icon='laptop'/><object id='b' label='服务器' icon='服务器'/><row label='场景'><cell object='a'>移动办公</cell><cell object='b'>服务端</cell></row></graph>",
                    "<graph type='timeline'><line time='今天' icon='search'>开始检索</line><line time='明天' icon='云端'>完成部署</line></graph>",
                    "<graph type='tree'><node id='r' label='系统' icon='服务器'><node id='db' label='数据库' icon='数据库'/></node></graph>",
                    "<graph type='flow'><node id='a' label='请求' icon='laptop'/><node id='b' label='服务' icon='服务器'/><edge from='a' to='b' label='提交'/></graph>",
                    "<graph type='relation'><node id='a' label='客户端' icon='laptop'/><node id='b' label='云端' icon='云端'/><edge from='a' to='b' label='通信'/></graph>");
                for (String xml : examples) {
                    var result = runtime.decoupler().process(XmlMarks.parse(xml));
                    if (result.isEmpty() || result.stream().anyMatch(f -> !(f instanceof ImageField))) throw new IllegalStateException("Graph rendering failed");
                    pages += result.size();
                }
                System.out.println("REGISTERED_PROVIDER_GRAPH_PAGES=" + pages);
            }
        }
    }
}
