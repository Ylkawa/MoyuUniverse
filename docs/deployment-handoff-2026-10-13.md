# 最近功能上线交接（2026-10-13）

完整执行步骤与最新配置（DashScope 密钥改为配置文件 APIKey 字符串）见 [部署操作文档](deployment-operations-2026-10-14.md)。

## 发布结论与基线

用户确认线上为 `c2807b4e81dfdb37025c4ba46e7d768f21f47df0`。整理期间分支已前进到 `35490c1`，另有本轮尚未提交的 graph 本地索引与持久缓存改造。
发布前需将本轮改造固定为候选提交；已有工作区工件和验证结果见 [本地索引验收](graph-local-index-acceptance.md)。本轮没有部署线上。

graph 图标匹配现已改为 JAR 内真实预建向量与图标，文本向量长期缓存到 data，不再需要远端 Qdrant 图标建库。
Selenium 修复也已提交。本次功能范围包含六类 graph、本地图标、图片编辑、文件类型识别、百炼搜索、检索/记忆修正、会话调度及 Selenium 修复。

## 相对线上新增的九个已提交变更及本轮改造

| 提交 | 行为变化 | 交接重点 |
| --- | --- | --- |
| `52f43c0` | 转发文件的 MIME 类型和后缀识别 | 更新 Universe，检查带参数 MIME、JSON/XML、未知类型 |
| `5d22f34` | 图片裁切/旋转 Skill、文件引用生命周期 | 新增 ImageEdit 插件，同时更新 Universe 和 AIChat；实测生成图的 URL 与 QQ 发送 |
| `e06bdbc` | 百炼基础联网搜索开关和模型白名单 | 更新 OpenAIAdapter，在目标 provider 配置添加 `Search: true`；用实际会话模型验收 |
| `4c9e4a3` | 知识库抓取过滤无关内容 | 更新 AIChat/Web，以相关与无关网页分别验证入库行为 |
| `cc6a210` | 检索、记忆维护及 Librarian 提示词修正 | 更新 AIChat/Web，验证事实来源、时效和维护行为 |
| `5a6e952` | 六类 graph、图标匹配接口、PNG 渲染、标准 XML 浮标 | 有不兼容协议变更；同步插件、提示词和图片引用格式 |
| `aaa7109` | driveMessage API、会话串行推理、静默五秒合并、自动创建会话与异步调用保护 | 回归普通触发、驱动输入、并发、关闭及异步工具结果；调用方输入角色保持 user |
| `f907b9b` | 忽略运行日志并添加 ImageEdit 打包配置 | 干净构建可以取得 ImageEdit artifact 定义 |
| `35490c1` | Selenium 超时、导航策略与会话释放 | 回归已配置 endpoint 和真实失败网页 |
| 本轮未提交 | graph 本地索引、JAR 图标包、data 向量缓存、Java DashScopeAdapter | 2143 个真实图标、1152 维；注册 LLMProvider + Embedding，校验模型空间，保留线上通道验收 |

结构化输出与 JSON Schema 的 `c2807b4e` 已在线上，本次不重复算作新增功能。
高德与 SauceNAO 也早于该基线；本次需回归其图片引用和插件兼容性。

## 当前工作区边界

此前未提交的会话驱动、Selenium 和 ImageEdit artifact 已由其他工作提交。本轮修改 graph 的源代码、测试、图标资源、Maven/IDEA 打包及文档，另修正旧图片发送测试的会话创建前置条件。
当前生成工件对应未提交工作区；完成审查、固定提交后，应在干净目录重建同一候选版本。预建资源已经在 source resources 中，正常打包不需要密钥或再次付费。

## 第一阶段：基础功能上线门槛

### 1. 确认部署现场

- [ ] 记录线上 OS、架构、JDK 版本、实际启动命令、服务/容器管理方式、工作目录及运行账号。
- [ ] 记录当前主程序、laws、AIChat Plugins 的 JAR 清单与 SHA-256；相同 `2.0-Phototaxis` 文件名不能区分提交版本。
- [ ] 记录现有 JVM 内存参数、CPU/内存/磁盘基线和反向代理配置。
- [ ] 核实 MySQL、Qdrant、模型、Selenium 与 OneBot/NapCat 的配置路径及连通性。

仅线上提交已确认；上述部署信息尚未提供。根 POM 编译目标为 Java 17，实际发行依赖与线上 JDK 的兼容性仍须用最终工件启动验证。

### 2. 构建与工件清单

在固定提交的干净目录执行 Maven 构建检查：

```powershell
mvn.cmd -B -pl AIChat,AIChat-Web,AIChat-ImageEdit,openaiadapter,OnebotAdapter -am clean package
```

这是编译与 Maven 自动测试入口，不是现成的生产发行包命令。仓库没有配置 Maven shade/assembly；常规 `target/*.jar` 不自动带齐第三方依赖。
`AmapAPI` 不在根 POM 的 modules 列表中，但被 Universe 依赖；干净构建环境需先提供同版本工件，必要时先单独 `mvn.cmd -B -f AmapAPI/pom.xml install`。

| 工件 | 线上位置（按现有加载逻辑） | 操作 |
| --- | --- | --- |
| Universe 主程序及运行依赖 | 实际启动命令指定的位置 | 更新；包含 MIME 识别、本地文件托管与引用生命周期变化 |
| AIChat | `laws/` | 更新；包含 graph、XML 协议、检索/提示词及驱动/队列变化 |
| OpenAIAdapter | `laws/` | 更新；包含 Search 开关 |
| AIChat-Web | `data/AIChat/Plugins/` | 更新；兼容新 MarkDecoupler 与 `<ref>`，包含抓取过滤变化 |
| AIChat-ImageEdit | `data/AIChat/Plugins/` | 新增；根目录含 `plug.properties` |
| OnebotAdapter | `laws/` | 本次区间生产代码无变化，仅新增发送测试；保持兼容版本并实际回归图片发送 |

- [ ] 确定现有发行方式：带依赖 JAR，或显式 runtime classpath；验证两级插件类加载器能看到依赖。
- [x] 补齐 IDEA AIChat artifact 的 Batik 1.19 及传递依赖；Maven 加入图标资源包完整性验证。
- [x] ImageEdit 的 IDEA artifact 定义已提交。
- [ ] 检查 Regular/Bold 字体、原始许可和 NOTICE、各插件清单均进入实际发行工件。
- [ ] 按同一提交构建所有待替换模块；避免重复打入 Universe/AIChat API 类造成类加载冲突。
- [ ] 在无源码目录、无开发机依赖 classpath 的目标同类环境启动，并运行实际 graph/图片编辑路径。
- [ ] 记录构建提交、JDK、依赖、产物 SHA-256、构建日志和验收结果。

### 3. 迁移 XML 协议与插件

`5a6e952` 是不兼容变更：旧冒号浮标不再解码，历史内容不自动迁移。

| 旧格式 | 新格式 |
| --- | --- |
| `<at:目标标识>` | `<at target="目标标识"/>` |
| `<reply:消息ID>` | `<reply to="消息ID"/>` |
| `<emoji:名称>` | `<emoji name="名称"/>` |
| 工具图片参数 `<quote:N>` | `<ref id="N"/>` |
| 图片后的编号说明 `<quoteId:N>` | `<ReferenceId=N>`（说明标签，不是待执行 XML） |

图片编辑的发送标记为 `<image_edit id="编号"/>`；工具引用与最终发送标记用途不同。

- [ ] 检查 `config/AIChat/config.json`、`SessionCFG/`、`SubAgents/`、`Skills/`、自定义插件提示词中的旧格式并逐项更新。
- [ ] 所有实现或注册 MarkDecoupler 的外部插件改为接收 DOM `Element` 并重新编译；内置 Web/ImageEdit 同步更新。
- [ ] 核对外部会话生成器、自动化调用方及自定义工具的图片引用格式。
- [ ] 对含旧标记的现有会话上下文确定切换方式，避免升级后继续引导模型输出旧语法；不批量改写历史存储。

### 4. 配置与网络

基础 graph 只需在现有 `config/AIChat/config.json` 中合并以下片段，保留其他配置：

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
    "outputDirectory": "cache/graph"
  }
}
```

无图标阶段设置 `iconsEnabled: false`。启用本地图标时合并 [验收报告](graph-local-index-acceptance.md) 中的 ProviderId、模型、1152 维与空间版本；endpoint 和密钥改在 DashScopeAdapter 配置。
旧 catalogManifest/qdrantCollection 失效；缺包或模型故障时仍绘图，省略相应图标。

- [ ] 会话 `availableSkills` 优先于全局 `Skills.availableSkills`；存在白名单时在有效列表中追加 `graph` 和 `image_edit`，保留原有项。
- [ ] 对需要读聊天图片的会话开启 `nativeImage: true`，并选择支持图片输入的实际模型。
- [ ] 在目标 `config/OpenAIAdapter/` provider 配置中按需要启用 `Search: true`；默认关闭。用线上实际模型验证白名单命中及服务响应，不能仅以开关存在判定生效。
- [ ] 核实 `UniverseChannel.properties` 的 `Enable`、`HttpPort` 和 `OutboundHttpAddress`。对外地址须包含正确端口或经代理映射，且能被 NapCat/模型服务实际访问。
- [ ] 从消息发送端和模型服务所在网络验证生成图的 `/Universe/<随机编号>.png`；仅在部署机上访问成功不够。
- [ ] 核实文件目录写权限；Linux/容器上实际验证 headless Java2D、中文字体加载和 ImageIO/Batik，不将 Windows 结果当作 Linux 验收。

## 本地图标改造后的上线检查

此前 Qdrant 图标维护方案已由本地预建包替代，以下为当前状态。

| 工作 | 当前状态与需要完成的内容 |
| --- | --- |
| 图标清单 | 已统一排序，校验 SVG/PNG 摘要与 PNG 有效性，完整 Lucide 库已导入。 |
| 路径迁移 | 已移除绝对路径身份，资源从 JAR 流读取；Linux 运行验收仍待执行。 |
| 索引完整性 | 已验证全部 2143 行向量与资源，包损坏/缺失有测试；有效包指针在完整验证后原子发布。 |
| 构建与费用 | 已有 CLI、持久图片向量缓存及断点续建；本次记录 754956 输入 Token，标价折算约 0.377 元。未实现自动按人民币计费的预算熔断。 |
| 输入与响应上限 | 保留现有绘图硬上限；新增 embedding 响应 2 MiB 上限及完整请求超时。DOM 前长度/深度限制仍属后续工作。 |
| 故障降级与运维 | 缺包/模型失败可无图标绘图，缓存长期保存；现有临时文件托管机制不变，独立的运行期磁盘配额/期限策略仍待规划。 |

运行时图标随 JAR 分发，不再解压到 data。预建中间资源仍使用 `data/AIChat/graph-icons/`，向量缓存使用 `data/AIChat/graph-embeddings/`，结果 PNG 仍使用 `cache/graph`。
Universe 清空 cache 不会影响 JAR 和 data 缓存。旧目录/远端图标集合不再读取，但没有自动删除；全局知识库/记忆 Qdrant 保留。

代码补齐后执行以下准备：

- [ ] 固定 Lucide 版本、许可证、内容摘要及预处理版本，保存导入产物和可复现构建信息。
- [ ] 部署 Java DashScopeAdapter JAR 并配置 ProviderId；同步更新 Universe 与 AIChat 公共 API。
- [ ] 在 `config/DashScopeAdapter/graph.json` 的 `APIKey` 字符串中填写密钥；修改后重启进程。密钥不再读取环境变量。
- [ ] Adapter 的 BaseUrl/空间版本和 Graph 的 ProviderId/模型/1152 维与包一致；本次使用 tongyi-embedding-vision-plus-2026-03-06。旧 Graph endpoint/密钥字段不再生效。
- [x] 图标不再创建或查询 Qdrant collection，不覆盖知识库和记忆集合。
- [x] 已完成真实模型全库向量生成和八个中英文描述抽样匹配、本地延迟及 Windows 进程内存测量。
- [x] 已测试超时、断网、缓存重启复用、损坏 PNG/向量包与中断续建；更广语义质量评测和线上并发验收仍待执行。

启动只加载 JAR 里的预建包和 data 缓存，不自动下载图标或付费构建；模型空间变化需要显式运行 CLI 重新预建。

## 验证与发布步骤

### 验证边界

本轮工作区已完成八模块 Maven package：97 项 JUnit 通过，0 失败/错误/跳过；额外八个 main 回归通过。
最终 AIChat JAR 全库校验、无密钥缓存复用、44 页布局回归和注册 Provider 的 8 页公开 GraphRuntime 回归均通过。全库缓存续建新增 token=0，向量摘要未变；工件 SHA-256 和语义抽样结果见 [验收报告](graph-local-index-acceptance.md)。
此前 JDK 25 的增量构建曾完成；2026-10-14 重新编译发现旧 OneBot 的 Lombok 1.18.30 不兼容 JDK 25，单加 proc=full 不能解决。现已用 JDK 17 完成八模块 install，部署构建按最新文档使用 JDK 17；本轮没有修改 OneBot 源码。
这些证据对应尚未提交的工作区，不替代固定候选提交后的重跑；Windows 探针也不证明线上完整进程的压力表现。

- [ ] 在固定候选提交重跑 Maven 测试与包构建。
- [ ] 单独执行 main 测试：ImageEditTest、ImageSendTest、BailianSearchTest，以及 Web 的图片引用/地图/SauceNAO 和已有结构化输出回归；Maven package 成功不保证这些 main 已执行。
- [ ] 对 `aaa7109` 运行 DriveMessageTest、SessionReplyQueueTest 及现有 AIChat 回归；已有本地 drive-recovery 日志不能证明最终发行包的线上调度行为。
- [ ] 真实验收驱动输入保持 user 角色、仅有待处理输入时触发、配置存在但未激活的会话自动创建、普通用户触发并入五秒静默批次、同会话串行、多会话并发、异步工具未完成保护及停止清理。
- [ ] 六类 graph 各跑中文、长文、分页、宽树/深树和带标签连线；只发送一次且页序正确。
- [ ] 超限与错误 XML 不触发付费匹配、不返回半图；图标服务故障按选定降级策略处理。
- [ ] 真实聊天验收：模型按需加载 Skill → 输出 graph → NapCat/QQ 收到完整 PNG。
- [ ] 真实图片编辑：用户发图 → `<ref>` → 裁切/旋转 → `<image_edit>` → QQ 收到；连续编辑和保留历史时 URL 正常。
- [ ] 实测 at/reply/emoji、工具图片引用、百炼搜索，以及知识入库/检索/记忆维护。
- [ ] 目标环境测多会话并发、进程内存、PNG 磁盘增长、失败恢复及两次完整重启。
- [x] 正式图标包使用真实 Lucide 与真实模型向量，已校验全库完整性并抽样中文语义查询。
- [ ] 在目标环境继续验证语义质量、首次未缓存请求的延迟与断网恢复；知识库/记忆的 Qdrant 另按原有流程验收。

### 变更窗口与回滚

1. 保存旧的发行工件、依赖、配置、启动参数和 SHA-256；备份 `config/` 与持久 `data/`，按现有运维方式备份 MySQL/Qdrant。运行插件和持久资源分清，不能只备份主 JAR。
2. 在隔离测试会话/测试实例完成上述验收，记录通过的候选提交、配置差异和未启用功能。
3. 在变更窗口停止旧进程，成套替换 Universe、AIChat、OpenAIAdapter、Web，并新增 ImageEdit、DashScopeAdapter；移走重复/旧版插件，合并配置及新提示词。
4. 启动并确认 provider、Web/ImageEdit、graph 注册成功，MySQL/Qdrant 初始化与消息通道正常；再通过真实聊天检查文字、图表、编辑及搜索。
5. 先启用一个测试会话，观察异常、延迟、费用、磁盘与内存，再扩大启用范围。
6. 如核心聊天、插件加载、图片发送或数据写入异常，停止新版本并恢复旧工件、依赖、配置和旧提示词。只退 JAR 会留下新 XML 提示词与旧解析器不匹配。

`Graph.enabled: false` 可关闭 graph；停用 ImageEdit 插件/技能、设置 provider `Search: false` 可分别关闭新能力，但这些开关不能撤销 XML 协议和 Universe API 变化。
恢复数据库/索引备份前评估升级后的新增写入，避免覆盖新数据。图标资源随版本化 JAR 回滚，保留旧发行包与 data 缓存；旧图标集合没有自动删除，知识库/记忆集合不要随图标迁移删除。图片 URL 属于临时文件挂载，重启后旧 URL 失效需纳入用户验收预期。

## 交接完成标准

交接接手人需要拿到：固定提交、最终发行包及校验值、脱敏配置差异、部署与回滚操作、离线及真实验收记录、基础/完整图标模式启用范围，以及未完成项与责任人。
当前已完成发布范围、源码改造、工作区发行包及本地验证；待固定候选提交、取得部署环境并完成真实服务/消息通道验收后，才能宣布可上线。

源码依据：`AIChat/GRAPH.md`、`AIChat/README.md`、`AIChat-ImageEdit/README.md`、`openaiadapter/README.md`；
`Universe.java` 的清缓存及配置加载、`LawsManager.java` 的插件加载；公共 `Embedding`、`EmbeddingRequest`、`ProviderPlugin`；Java `DashScopeChannel`/`DashScopeEmbedding`；Graph 的 `ProviderIconEmbeddingProvider`、`LucideCatalogImporter`、`IconBundleBuilder`、`IconBundle`、`LocalIconIndex`、`EmbeddingCache`、`GraphMatchService`、`GraphDecoupler`、`XmlMarks`；根/模块 POM 与 IDEA artifacts。
