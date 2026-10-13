# Graph 图表、本地图标索引与持久向量缓存

graph 集成在 AIChat，支持 list、compare、timeline、tree、flow、relation 六类图表。
流程为 XML → GraphSpec → 绘图预检 → icon 文字向量 → 本地余弦 Top 5 → Java2D PNG → ImageField。
图标 PNG、原始 SVG、许可证和预计算图片向量随 JAR 分发，graph 不连接 Qdrant；知识库和记忆仍沿用原有 Qdrant。
云端向量计算通过 `Universe.Providers` 中注册的 `LLMProvider + Embedding` 调用，百炼协议由独立 Java DashScopeAdapter 实现。
AIChat 仅保留图标业务适配、持久缓存、本地检索与渲染，不处理厂商 HTTP、密钥或 Base64 编码。

## 配置

合并到 `config/AIChat/config.json`，保留其他配置：

```json
{
  "Graph": {
    "enabled": true,
    "iconsEnabled": true,
    "width": 1440,
    "pageHeight": 2000,
    "maxPixels": 8000000,
    "maxNodes": 200,
    "maxEdges": 400,
    "maxTextCharacters": 20000,
    "maxPages": 32,
    "outputDirectory": "cache/graph",
    "embeddingModel": "tongyi-embedding-vision-plus-2026-03-06",
    "embeddingDimension": 1152,
    "embeddingSpaceVersion": "v1",
    "embeddingProvider": "DashScope"
  }
}
```

`embeddingProvider` 对应注册表中的 ProviderId。安装 DashScopeAdapter JAR，并将
`DashScopeAdapter/examples/graph.json` 复制到 `config/DashScopeAdapter/graph.json`；该示例与当前正式包空间一致。
Adapter 的 BaseUrl 指定 endpoint，APIKey 直接读取 JSON 配置文件中的字符串，配置格式见 [DashScopeAdapter](../DashScopeAdapter/README.md)。
旧 Graph.embeddingEndpoint/embeddingApiKeyEnv 已失效，启动时给出迁移提示，不自动转换或删除旧配置。
本次预建包使用北京业务空间、固定快照模型、1152 维和 res_level=1。
运行时由公共 embeddingSpace() 查询实际 Provider 空间，必须与包身份一致；查询身份不发网络请求或要求密钥。
插件注册顺序未固定，Graph 延迟到首次实际绘图获取 Provider。

DashScopeAdapter 支持本次指定 plus 快照、同日期 flash 快照及 `qwen3-vl-embedding`；切换模型或维度必须重新预建。
模型、endpoint、维度、独立向量模式、res_level 或显式空间版本不符时拒绝混用向量。
服务模型实际更新时应提高 `embeddingSpaceVersion` 并重新预建；维度相同不能证明语义空间相同。

## 语义检索与融合向量

正式包对每张 PNG 发送独立的 `{"image":"..."}` content；查询对 icon 描述发送 `{"text":"..."}`。
两者使用同一多模态模型的语义空间，归一化后按余弦相似度检索，实现文字语义匹配图片。
Lucide 名称只作为稳定资源标识，不用于关键词查找，也不作为图片向量的文字输入。

融合向量是把图片与其描述一起编码成一个向量。例如该快照模型的同一个 content 中包含
`{"image":"...", "text":"邮件信封图标"}` 时会产生融合向量；它不需要 `enable_fusion` 参数。
`qwen3-vl-embedding` 则通过该参数控制融合。两套请求格式不能照搬。
独立向量也可以跨模态检索，当前方案不需要融合。将来若采用图文融合建库，应提高预处理版本并重新预建，
再用实际描述评估检索质量，不能仅凭“融合”一词认定结果更好。[官方模式说明](https://help.aliyun.com/zh/model-studio/embedding)

`iconsEnabled: false` 只关闭图标；`enabled: false` 关闭 graph。
缺少或损坏图标包时记录告警，无图标绘图继续注册，不自动下载资源或调用模型重建。
旧 `catalogManifest`、`qdrantCollection` 字段已失效，加载配置时给出提示；不会自动删除旧目录或远端集合。
存在技能白名单时，在有效的会话 availableSkills 或全局 Skills.availableSkills 中添加 `graph`。
生成图表仍通过 Universe 文件托管发送，因此必须配置消息端可访问的对外 HTTP 地址。

## 文本与图片向量缓存

文本缓存位于 `data/AIChat/graph-embeddings/text/`，图片预建缓存位于 `data/AIChat/graph-embeddings/images/`。
磁盘不设 TTL 或自动淘汰；文本内存缓存最多 1024 条，按 LRU 淘汰。
每条记录使用模型空间及输入内容的 SHA-256 分目录保存，包含格式版本、身份、归一化 float32 向量和内容校验。
缓存写入使用同目录临时文件和原子替换，不会把半条记录当作成功结果。
损坏缓存视为未命中，失败响应不缓存；写盘失败时当前结果仍可使用，并记录告警。

文本按实际发送字符串区分，不折叠大小写、空格或同义词。图标库版本不进入文本缓存键，
同一模型空间更新图标包后可以复用文字向量。相同目录和键的并发请求在同一 JVM 内合并为一次计算。
缓存命中无需密钥；未命中才调用已注册 Provider 的 embedding()。没有密钥、断网或 embedding 失败时，
保留已匹配图标，省略失败描述对应的图标，完整保留文字及图表关系，不用问号替代服务失败。

运行时直接从 JAR 流读取 PNG，不把图标包解压到 data。绘图图标仍按尺寸预检、采样到 96px 以内解码，栅格缓存上限 32 MiB。
Universe 启动及退出会清空 cache，因此长期向量缓存必须留在 data；临时图表图片继续放 cache/graph。

## 显式预建 CLI

CLI 主类：`com.nekoyu.Universe.AIChat.graph.GraphIconCli`。
先构建 AIChat、DashScopeAdapter 与依赖，使用包含 AIChat/Universe 输出及 Maven runtime 依赖的 classpath 执行。
正式构建与普通 Maven package 相互独立；CLI 不读取密钥或 endpoint，配置由注册的 Adapter 管理。
独立运行 build 时用 --provider-plugin 指定 Provider JAR，公共 ProviderPlugin 读取 law.yml 并调用正常 prepare 注册入口；不启动聊天、数据库或其他插件。

```text
GraphIconCli prepare --source <lucide-static目录> --version <明确版本> --output data/AIChat/graph-icons
GraphIconCli build --manifest <prepare输出的catalog.json> --provider-plugin DashScopeAdapter/target/DashScopeAdapter-2.0-Phototaxis.jar --provider DashScope --model tongyi-embedding-vision-plus-2026-03-06 --dimension 1152 --space-version v1 --batch-size 16 --output AIChat/src/main/resources/graph/icons
GraphIconCli verify --bundle AIChat/src/main/resources/graph/icons
GraphIconCli verify-classpath --required true
```

可用仓库提供的 `scripts/graph-icons.ps1` 启动 CLI，它从 Maven 测试报告提取 classpath。
该脚本的 build 默认加载构建好的 DashScopeAdapter JAR；先准备相应 config/DashScopeAdapter 配置。
`prepare` 要求 package.json 的 name/version 与显式版本匹配，保留完整 LICENSE，按去后缀名称排序，校验 SVG 与 PNG 内容摘要和 PNG 有效性。
`build` 复用图片向量缓存；本次模型按最多 16 张图片请求，每个 content 仅包含一张图片，不进行图文融合。
每批成功结果立即写入各自缓存，进程中断后重新执行同一命令即可续建。
只在资源与向量完整校验后原子更新有效包指针，失败不覆盖上一份完整包。

JAR 的 `graph/icons/current.json` 指向 `bundles/<索引身份>-<向量摘要>/`，其中保存 catalog.json、LICENSE、SVG、PNG 和 vectors.bin。
vectors.bin 使用固定大端格式：magic、版本、图标数、维度、身份，随后按清单顺序保存图标 ID 与归一化 float32 向量。
加载时校验头部、资源顺序、向量摘要、维度、有限值和单位范数，矩阵以连续 float[] 一次加载。
精确搜索只保存 Top K，分数相同按图标 ID 排序；余弦相似度不是置信度概率，不设新的匹配阈值。

Maven 在 prepare-package 验证 classpath 中的包，不请求模型。默认正式打包要求图标包存在。
确实需要无图标发行时显式设置 `-Dgraph.icons.required=false`，不会自动用测试向量填充生产资源。
IDEA artifact 同步包含 Batik 及传递依赖；资源来自模块资源输出。

## XML 与渲染

根元素为 `<graph type="..." title="可选标题">`，type 必填，icon 是可选自然语言描述。
仅 icon 内容参与检索，标题、正文、时间和连线文字不参与。

| type | 内容 | 规则 |
| --- | --- | --- |
| list | line | 至少一项，保持输入顺序 |
| compare | line | 至少两项，label 可选 |
| timeline | line | time 必填，保持输入顺序 |
| tree | 嵌套 node | 一个根，id/label 必填且 ID 唯一 |
| flow | 平铺 node、edge | 有向，支持分支、汇合与循环 |
| relation | 平铺 node、edge | directed 默认 false，可设置 true |

```xml
<graph type="compare" title="设备对比">
  <line label="笔记本" icon="笔记本电脑">移动办公</line>
  <line label="服务端" icon="服务器">固定运行</line>
</graph>
```

edge 的 from/to 必须引用现有 node，label 可选。非法类型、属性、结构及引用保留原 XML，禁用 DTD 与外部实体。
XML 协议与其他浮标的迁移说明见 [AIChat README](README.md)。

HarmonyOS Sans SC Regular/Bold 原始字体随 JAR 分发，无需安装系统字体；许可与摘要见字体目录。
六类绘图保持现有排版：长文字分页；宽树/深树层级展开，保留父节点、层级及跨页关系；流程/关系图附完整关系索引。
连线文字放在线路旁，避让节点、线路和箭头；任一页失败清理未发布图片并保留整段原 XML。

全局串行绘图、最多 8 个处理中请求、绘图锁等待最多 30 秒。
画布宽 640–2048、高 800–4096、单页最多 800 万像素；最多 200 节点、400 边、20000 字符、64 页，默认 32 页。
这些绘图限制不包含新增常驻图标向量矩阵，也不是整个 JVM 的内存保证。

## 验证

```powershell
mvn -B -pl AIChat -am test
mvn -B -pl DashScopeAdapter,AIChat,AIChat-Web,AIChat-ImageEdit,OnebotAdapter,openaiadapter -am package
```

JUnit 使用临时合成素材和本机 HTTP fixture，覆盖精确余弦排序、稳定 Top 5、缓存复用/并发/损坏/写盘失败、
续建、包完整性与 jar: 资源加载、离线降级以及现有六类绘图。测试素材不进入正式图标资源包。
额外 main 回归（图片编辑、OneBot 序列化、Web 技能和结构化输出）仍需显式执行。

正式包使用 Lucide 1.56.0 的 2143 个图标与指定真实模型生成的 1152 维向量。
固定模型和图标资源来源分别见 [百炼说明](https://help.aliyun.com/zh/model-studio/embedding) 与 [Lucide](https://lucide.dev/)。
`GraphJarAcceptance` 是手动 JAR 验收/性能入口：测量全库加载、本地检索，测试真实中英文描述、持久缓存离线复用和六类 PNG。
`GraphSemanticProbe` 用十八条真实中文描述输出 Top 5，人工评估具体物件、同义描述和抽象概念；不是准确率自动测试。
在仓库根目录执行 `./AIChat/scripts/measure-graph-index.ps1 -ProviderId DashScope -HeapMiB 1024` 可采集进程 Working Set；先配置 Adapter。
以最终 AIChat JAR 优先的 classpath 运行，不把 AIChat/target/classes 或源资源目录放到 classpath。
运行参数首项为 ProviderId，独立进程使用 `--provider-plugin <Jar>` 正常注册通道；`--pause` 在加载前后等待一行输入，供宿主采样进程内存。
实测结果与验证边界记录在 `docs/graph-local-index-acceptance.md`。
