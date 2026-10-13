# Graph 图表渲染与图标匹配

代码直接集成于 AIChat 的 `com.nekoyu.Universe.AIChat.graph` 包。
提供结构解析、Lucide 导入、Qwen3-VL 向量化、Qdrant 匹配和六种 Java2D 图片渲染器。
AIChat 初始化时注册 `graph` 成对 decoupler 和同名按需技能，停止时释放自己持有的资源。
处理流程为 XML → GraphSpec → 按 type 生成并校验绘图计划 → 仅匹配 icon → PNG → ImageField → MFChain。
不启动浏览器，不增加独立插件或 LLM 绘图工具。

## 启用和配置

默认启用无图标图表。普通配置不需要模型密钥；使用 icon 时必须先导入图标库并建立向量索引。
下面是 AIChat `config/AIChat/config.json` 的相关配置片段，其他配置照常保留：

```json
{
  "Graph": {
    "enabled": true,
    "width": 1440,
    "pageHeight": 2000,
    "maxPixels": 8000000,
    "maxNodes": 200,
    "maxEdges": 400,
    "maxTextCharacters": 20000,
    "maxPages": 32,
    "outputDirectory": "cache/graph",
    "catalogManifest": "cache/graph/icons/lucide/实际内容摘要/catalog.json",
    "embeddingApiKeyEnv": "DASHSCOPE_API_KEY",
    "embeddingEndpoint": "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding",
    "embeddingDimension": 2560
  },
  "Qdrant": { "address": "127.0.0.1", "port": 6334, "encryptedConnection": false }
}
```

`catalogManifest` 指向 `LucideCatalogImporter.importDirectory()` 保存的清单。
启动只加载并核验清单、核验已有 Qdrant 索引，不自动下载图标或付费重建索引。
API Key 从指定环境变量读取，Qdrant 复用 AIChat 的地址、TLS 和 secretKey 配置。
可选 `qdrantCollection` 显式指定集合，但索引身份仍须完全匹配。
仅需无图标绘图时省略 catalogManifest；未配置匹配而使用 icon 会保留原始 XML 并记录错误。
配置错误时不注册 graph 能力。`enabled: false` 关闭内置图表。
若会话或全局 Skills.availableSkills 配置了白名单，需将 `graph` 加入列表，供主 agent 按需激活格式说明。
图表图片使用 Universe 的文件托管与引用回收机制，需配置有效的对外 HTTP 地址。

默认使用随 AIChat JAR 打包的 **HarmonyOS Sans SC Regular、Bold** 原始字体，
通过 `Font.createFont()` 直接加载，无需安装系统中文字体。两种字重在进程内复用；
资源缺失或损坏会明确报错，不静默退回系统字体。字体及原始许可证、来源和摘要见
[字体资源说明](src/main/resources/fonts/harmonyos-sans/README.md)。
`fontFile` 仍可显式覆盖默认字体，配置错误时不会静默使用其他字体。

| 渲染器 | 排版 |
|---|---|
| ListGraphRenderer | 依次排列的信息卡片 |
| CompareGraphRenderer | 两列成组对照，多项按输入顺序分组 |
| TimelineGraphRenderer | 时间轴与事件卡片，保持输入顺序 |
| TreeGraphRenderer | 按子树叶子跨度排版；宽树、深树改为保留父节点信息的层级展开 |
| FlowGraphRenderer | 有向分层，循环先归并强连通分量 |
| RelationGraphRenderer | 无向环形，空间不足改为网格；有向时分层 |

文字按实际字体度量换行；列表、对照及时间线的长正文跨页继续。
流程图和关系图的节点过多时按输入顺序分图，跨页连接以节点 ID 和页码在关系索引中保留。
树形图仅在完整父子布局能放入画布时使用自上而下排版，否则按前序展开、缩进和分页。
展开布局逐节点显示父节点 ID、父节点所在页和实际层级，跨页父子边另附关系索引。
超过可用缩进宽度时用明确的父节点文字表达层级，不绘制可能合并的引导线。
所有带 label 的边另附完整关系索引，密集连线可能重叠，但不丢失关系文字。
连线先全部计算并绘制，再沿实际线段两侧放置文字，避让整页节点、所有线路、箭头和其他文字。
文字与线路留出间距，不居中压在线上，也不使用背景色擦除线路。
无法容纳完整文字时尝试显示 `R01` 等关系编号，完整内容在关系索引中保留。
短内容自动收紧画布高度。每页生成一张 PNG，按页码顺序加入返回的 MFChain。
任一页失败时整图保留原 XML，并清理本次未发布的图片，不返回半张图表。

在图标请求之前校验节点/边文字、标题、正文分页、页数和页面边界，以及对外 HTTP 地址。
完整绘图计划只生成一次；网络匹配期间释放绘图锁，其他图表可继续绘制，关闭渲染器也不等待网络匹配。
匹配返回后再次检查关闭状态，关闭期间返回的结果不发布。绘图锁等待最多30秒；
全局最多保留8个处理中请求，超出容量明确失败并由 XML 分发保留原文。
内存通过全局串行绘图、请求数量上限、复用画布、逐页编码、文件输出和最多32 MiB 的图标栅格缓存控制。
渲染用图标在解码阶段采样到96px以内，不重复解码完整预览；向量模型仍使用原始256px预览。
画布宽640–2048、高上限800–4096，单页不超过800万像素；配置不能提高这些硬上限。
每图最多200项/节点、400条边、20000个字符（属性和空白也计入）、64页；默认32页。
节点 label 最多400字符、id 最多80字符，edge label 最多200字符。
这些约束控制绘图新增内存，300MB 目标不包含已有 AIChat、Qdrant 服务或远程模型。
Java 堆上限并非整个进程内存上限，部署时应按实际字体和 JVM 监测进程增量。

## XML 格式

根元素为 `<graph type="..." title="...">`。`type` 必填且区分大小写，`title` 可选。
所有 `icon` 均为可选的自然语言图标描述；缺省时不匹配，显式空白值会报错。

| type | 内容 | 规则 |
|---|---|---|
| list | line | 至少一项，保持输入顺序 |
| compare | line | 至少两项，label 可选 |
| timeline | line | 至少一项，time 必填；不按日期重排 |
| tree | 嵌套 node | 一个根节点，嵌套表达父子关系 |
| flow | 平铺 node 和 edge | 固定有向，支持分支、汇合、循环 |
| relation | 平铺 node 和 edge | directed 可选，默认 false，支持 true |

`line` 的内容只接受文字、CDATA 和注释，保留 XML 解码后的文字及空白。
`node` 的 id、label 必填，id 在整个图内唯一；显示文字取 label，不包含子节点文字。
`edge` 的 from、to 必填，必须引用已有 node，label 可选。连线可以出现在节点之前。
flow/relation 至少有一个节点，可以包含暂未连接的节点。
未知类型、属性、子元素、非法引用或缺少必要值返回 `GraphParseException`，包含 XML 元素路径。
解析失败不会调用模型推断、补全或修改结构；DTD 与外部实体禁用。

```xml
<graph type="list" title="功能">
  <line icon="search">检索资料</line>
  <line icon="cloud">云端同步</line>
</graph>

<graph type="compare" title="超频情况">
  <line label="笔记本" icon="laptop">OverClocked for 400MHz frequency</line>
  <line label="台式机" icon="desktop">None OverClock</line>
</graph>

<graph type="timeline">
  <line time="第一天" icon="clipboard">收集需求</line>
  <line time="第二天" icon="code">完成实现</line>
</graph>

<graph type="tree">
  <node id="system" label="系统" icon="network">
    <node id="client" label="客户端" icon="laptop"/>
    <node id="server" label="服务端" icon="server"/>
  </node>
</graph>

<graph type="flow">
  <node id="receive" label="收到请求" icon="inbox"/>
  <node id="process" label="处理请求" icon="processor"/>
  <edge from="receive" to="process" label="提交"/>
</graph>

<graph type="relation" directed="false">
  <node id="client" label="客户端" icon="laptop"/>
  <node id="server" label="服务端" icon="server"/>
  <edge from="client" to="server" label="通信"/>
</graph>
```

## 开发调用入口

`GraphParser.parse(String)` 接收一个完整 XML 文档；`parse(Element)` 接收已经解析的 graph 根元素。
返回不可变 `GraphSpec`，六种类型分别具有对应的 record；`iconSlots()` 提供占位键与原始查询。
行占位键为 `line[1]` 等，节点占位键为 `node:节点ID`。

以下示意应由开发代码显式调用；没有启用时自动下载资源或付费建立索引的行为：

```java
import com.nekoyu.Universe.AIChat.graph.*;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.nio.file.Path;

IconCatalog catalog = new LucideCatalogImporter().importDirectory(
    Path.of("resources", "lucide-static"), "实际版本号", Path.of("cache", "graph", "icons"));
IconEmbeddingProvider provider = new Qwen3VlEmbeddingProvider(System.getenv("DASHSCOPE_API_KEY"));
IndexDescriptor descriptor = IndexDescriptor.of(provider.space(), catalog);

try (QdrantClient client = new QdrantClient(
        QdrantGrpcClient.newBuilder("127.0.0.1", 6334, false).build())) {
    IconIndex index = new QdrantIconIndex(client, descriptor);
    // 图标库首次导入或版本变化时显式建立索引；正常查询时不调用 index()。
    new IconIndexer(provider, index).index(catalog);
    GraphSpec graph = new GraphParser().parse(xml);
    ResolvedGraph result = new GraphMatchService(provider, index, catalog).resolve(graph);
}
```

调用方管理 QdrantClient 生命周期，可通过 builder 配置 API Key、TLS 和地址。
默认 collection 为 `moyu_universe_aic_graph_icons_<身份哈希>`；也可显式指定 collection 和超时时间。
现有 collection 必须具有匹配身份的唯一命名向量、正确维度及 Cosine 距离。
不兼容时拒绝使用，不删除或重建现有 collection。查询不会自动创建 collection。

## 图标资源与模型

导入目录应包含 `icons/*.svg` 与完整 `LICENSE`；也接受 SVG 直接位于目录根部。
若存在 package.json，其 name 必须为 lucide-static，version 必须与显式参数一致。
获取和解压指定版本的图标包由调用方负责。来源：
[Lucide](https://lucide.dev/)、[许可说明](https://lucide.dev/license)。

导入后保留原 SVG、许可证和 catalog.json；每个资源记录名称、版本、SHA-256 和持久文件路径。
为图片向量化生成黑色图标、白色背景的 256×256 PNG，图标区域为居中的 192×192。
使用 Apache Batik；预览禁止脚本及外部资源。这些 PNG 作为向量化预览和渲染时的图标素材。
重复导入同一内容得到相同清单和路径，更新内容得到新的版本目录。

`Qwen3VlEmbeddingProvider` 固定使用 `qwen3-vl-embedding`，调用百炼原生多模态 embedding API，
使用独立向量模式 `enable_fusion=false`。图片以 PNG Base64 Data URI 发送，每次一张；
文字每批最多20条，并按响应 index 还原顺序。默认2560维，也可选择模型支持的其他维度。
自定义 endpoint/HttpClient/timeout 的构造器便于测试和兼容服务接入。
[官方 API 说明](https://www.alibabacloud.com/help/zh/model-studio/multimodal-embedding-api-reference)。

匹配查询仅使用 icon 属性，不混入标题、正文、时间或连线文字。
同一 graph 的重复描述只向量化和检索一次；默认返回按相似度降序排列的前五个候选。
`ResolvedGraph` 保留原 GraphSpec，并逐占位返回查询、资源及余弦相似度。
`preferred()` 为首选候选；`CANDIDATES` 表示找到候选，`UNMATCHED` 表示没有结果。
相似度不是置信度概率，目前不设统一阈值，也不使用替代图标掩盖匹配失败。

索引身份包含模型、endpoint/适配版本、维度、图标库版本、内容摘要和预处理版本。
模型与图片向量必须处于同一空间。维度不符、缺失向量、零向量、非有限数值、
资源身份不符及服务错误均返回明确异常。重复索引以稳定 UUID upsert，不增加重复资源，
但仍会重新调用向量服务，因此正常查询应复用已有索引。
云端模型别名的内部升级无法由本地自动识别，确认服务模型变化时需重新建立索引。

## 验证

```powershell
mvn -pl AIChat -am test
```

graph 测试使用合成 SVG、确定性测试向量、本机 HTTP 和 gRPC 服务，默认不需要 API Key 或 Qdrant 实例。
包含六种解析、错误定位、预览内容、清单重载校验、匹配去重与排序、Qwen API 请求/响应、
Qdrant 索引身份、幂等写入及故障传播验证。Qdrant gRPC 测试验证真实 Java 客户端的传输行为，
不替代实际数据库或真实模型的匹配质量评估。

PNG 测试走真实 GraphDecoupler、AIChat XML 分发及 ImageField 文件托管路径，验证六种渲染、
中文混排、分页、跨页索引、宽树/深树回退、绘图串行化、匹配不阻塞其他绘图、
输入及渲染预检、失败清理与启动/停止注册。字体测试验证官方原始文件摘要、中文覆盖和独立粗体字重。
测试预览保存在 `AIChat/target/graph-render-preview/`。
七组完整场景的 XML 和重新生成方式见 [图表效果示例](examples/graphs/README.md)，
包括 NAS 清单、设备对比、发布计划、AIChat 模块树、宽树层级展开、图片返回流程与服务关系。
对应图片保存在 `AIChat/target/graph-render-preview/examples/`，可通过该目录中的 `index.md` 查看全部页面。
`GraphRenderMemoryProbe` 是手动进程内存探针：输出 READY 后等待一行输入，再以最大画布、
200个图标槽和400条边连续渲染六种类型；可在独立 JVM 中测量 READY 至 DONE 的进程 RSS 增量。
Windows 可在上述 Maven 测试通过后执行 `AIChat/scripts/measure-graph-memory.ps1`，默认独立探针堆上限192 MiB，
可用 `-HeapMiB 1024` 检查大堆条件；报告保存在 `AIChat/target/graph-memory-probe/report.json`。

2026-10-13 修复后 Windows / JDK 24 实测：探针堆上限1 GiB，画布2048×3906，200个不同图标素材（512px源图）、
六种类型连续输出90页，flow/relation 各400条边；线路旁文字修复后的实际 JVM Working Set 从94.77 MiB 增至峰值203.96 MiB，
新增109.20 MiB。测量包括工件内 HarmonyOS Sans Regular/Bold 加载、XML解析、排版、图标解码和PNG编码，
不包括远程向量服务与Qdrant服务，也不包含完整 AIChat 并发运行的压力验证。
这是该压力场景的进程实测值，不是不同 JVM、字体和业务环境下的统一内存保证。

`mvn -B -pl AIChat -am package` 生成 `AIChat/target/AIChat-2.0-Phototaxis.jar`，约10.9 MiB。
工件包含原始 Regular、Bold 字体、原始许可及 NOTICE；已经以 JAR 优先类路径验证字体资源的
`jar:` 加载来源、实际字重及中文字符覆盖，不依赖 `src/main/resources` 或已安装的系统中文字体。

真实 Qdrant 测试需要显式设置 `QDRANT_TEST_HOST`；可选变量为 QDRANT_TEST_PORT（6334）、
QDRANT_TEST_TLS（false）、QDRANT_TEST_API_KEY。测试仅创建并清理自己随机生成的 graph_test_* collection。
没有配置时该测试跳过。真实 Qwen 模型与 Lucide 全量库的语义质量评估需要单独提供服务配置。
