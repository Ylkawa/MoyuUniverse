# 最近功能部署操作文档（2026-10-14）

适用：从线上 `c2807b4e81dfdb37025c4ba46e7d768f21f47df0` 升级到当前 Phototaxis 功能。包含六类 graph、本地图标与向量缓存、Java DashScopeAdapter、图片编辑、MIME 识别、百炼搜索、知识/记忆维护、会话驱动队列和 Selenium 修复。变更提交列表见 [此前交接清单](deployment-handoff-2026-10-13.md)。

本轮代码和资源尚需固定为发行提交。本文提供可执行的打包和发布流程；尚未操作线上，线上 OS、服务名、工作目录和原启动参数未确认。下文用 `APP_ROOT` 表示**现有程序工作目录**，按实际路径替换。服务切换沿用现有管理方式，不假设 systemd、Docker 或 Windows 服务名称。

## 1. 这次需要配置什么

已有实例升级时合并字段，保留原有会话、提示词、数据库和模型配置。字段大小写严格按示例，JSON 不写注释。配置修改后重启整个进程生效。

| 文件或目录（相对 APP_ROOT） | 本次操作 | 必要性 |
| --- | --- | --- |
| `config/DashScopeAdapter/graph.json` | 新建；填写 `APIKey` 字符串，设置 ProviderId、原生 BaseUrl、固定模型和空间版本 | 使用未缓存描述匹配图标时需要 |
| `config/AIChat/config.json` 的 `Graph` | 合并下文完整 Graph 对象；删除失效的四个字段 | 启用图表 |
| `config/AIChat/SessionCFG/**/*.json` | 有技能白名单时追加 `graph`、`image_edit`；读聊天附件的会话设 `nativeImage: true` | 目标会话需要对应功能时 |
| `config/OpenAIAdapter/<现有 provider 文件>` | 按需增加 `Search: true`；保留聊天 ProviderId、BaseUrl、APIKey、Model | 百炼基础联网搜索 |
| `UniverseChannel.properties` | 确认 `Enable=true` 和消息端可访问的 `OutboundHttpAddress` | graph、图片编辑及图片发送需要 |
| `config/AIChat/Plugins/Web/config.json` | 保留并检查 `Selenium.URL`、代理和搜索配置 | 普通网页抓取需要 Selenium |
| `config/Onebot/*.json` | 沿用 URI、Token、ID；确认 NapCat/OneBot 实际连接及图片下载通路 | QQ 发送 |
| `config/AIChat/config.json` 的 SQL/Qdrant/知识/记忆部分 | 沿用原服务、provider、模型、维度和 collection | 本次图标迁移不改变这部分 |
| 自定义提示词、Skills、SubAgents、外部插件 | 迁移旧冒号浮标和图片引用；外部 MarkDecoupler 插件重新编译 | 协议不兼容，必须检查 |

DashScope 密钥**直接从 UTF-8 JSON 文件的 `APIKey` 字符串读取**。不读取环境变量，不使用独立密钥文件，也不接受 CLI 明文密钥参数。发行包和仓库示例不含有效密钥；在部署目录填写。

## 2. 构建并生成完整发行目录

### 2.1 固定源代码与工具链

先完成当前修改审查，将代码和 `AIChat/src/main/resources/graph/icons/` 的真实资源一起固定到候选提交。在干净 checkout 构建该提交，记录提交号。版本名 `2.0-Phototaxis` 不随每次提交变化，不能用文件名判断发布版本。

构建使用 **JDK 17 + Maven**。本机 Java 25 与 OnebotAdapter 的 Lombok 1.18.30 编译处理器不兼容；单加 `maven.compiler.proc=full` 不能解决。无需为了发布修改 OneBot 源码，选择 JDK 17 即可。

在仓库根目录的 PowerShell 执行，工具路径按构建机实际安装位置调整：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
java -version
mvn.cmd -version

# AmapAPI 不在根 modules 中，但 Universe 依赖它；干净机器先安装该工件。
mvn.cmd -B -f AmapAPI/pom.xml clean install
if ($LASTEXITCODE -ne 0) { throw 'AmapAPI 构建失败' }

# install 让后续依赖收集读取同一次构建的项目工件。
mvn.cmd -B -pl DashScopeAdapter,AIChat,AIChat-Web,AIChat-ImageEdit,OnebotAdapter,openaiadapter -am clean install
if ($LASTEXITCODE -ne 0) { throw '发行构建失败' }

$releaseId = (git rev-parse --short HEAD).Trim() + '-20261014'
./scripts/package-deployment.ps1 -ReleaseId $releaseId -Java "$env:JAVA_HOME/bin/java.exe"
```

`java -version` 使用 PATH，`mvn -version` 使用 JAVA_HOME；两者应核实，运行测试/打包脚本时可显式使用上面的 Java 路径。Maven 首次需要下载依赖，有完整本机缓存时可加 `-o`。脚本支持 `-Maven <mvn.cmd绝对路径>`、`-LocalRepository <本机Maven仓库>`、`-Offline`，不要把本文机器的用户目录当作线上固定路径。

打包脚本要求先完成 install；只收集运行依赖、复制工件并验证 JAR 图标包，不读取生产配置或调用付费模型。输出到新的 `target/deployment-<ReleaseId>/`，拒绝覆盖已有发行目录。失败产生的目录不能发布，查看其中日志后使用新的 ReleaseId 重试。

### 2.2 工件布局

```text
target/deployment-<ReleaseId>/
  Universe.jar
  lib/                              # Maven 收齐的运行依赖，包括 AmapAPI、Batik、Qdrant 客户端等
  laws/
    AIChat.jar
    OnebotAdapter.jar
    openaiadapter.jar
    DashScopeAdapter.jar
  data/AIChat/Plugins/
    AIChat-Web.jar
    AIChat-ImageEdit.jar
  release.json                      # 源提交、工作区是否有修改、所有 JAR 大小和 SHA-256
  dependencies.log
  icons-verification.log
```

普通 `target/*.jar` 是薄 JAR，第三方依赖要放到 lib 并加入启动 classpath。不要仅替换 AIChat，也不要直接对这些薄 JAR 执行 `java -jar` 作为生产启动方式。上述布局让第三方库由主程序 classloader 共享，Universe/AIChat API 不重复复制到各插件。

正式包已有 `lucide-static@1.56.0` 的 2143 个图标、1152 维真实图片向量、PNG/SVG、Lucide 许可证和 HarmonyOS Sans SC 字体。正常 Maven 构建只验证、复制它们，**上线不需要预建向量数据库、下载图标或重新付费计算全库**。`icons-verification.log` 应显示 `Verified icons=2143, dimension=1152`；资源不完整时构建/打包应失败。

若继续使用 IDEA artifacts，成套构建 Universe、AIChat、OpenAIAdapter、OnebotAdapter、DashScopeAdapter、AIChat-Web、AIChat-ImageEdit，并按相同目录部署。AIChat artifact 已加入 Batik，DashScopeAdapter artifact 已加入 law.yml；IDEA 的依赖列表还需与当前 Maven 解析版本核对。本文经验证的打包入口是上面的 Maven + lib 脚本。

其他已安装 laws 和 AIChat 插件也必须进入候选发行清单；不要因上表未列出而删除。自定义插件若依赖旧 MarkDecoupler API，重新编译后加入。同一个 law/插件 ID 只保留一份 JAR，备份放在加载目录之外；共享 lib 也采用新发行目录的完整集合，避免混入旧版同名依赖。

## 3. 配置 DashScope 图标向量 Provider

新建 `APP_ROOT/config/DashScopeAdapter/graph.json`：

```json
{
  "ProviderId": "DashScope",
  "APIKey": "填写你的 DashScope 密钥",
  "BaseUrl": "https://llm-a1bz0eifucqm4izg.cn-beijing.maas.aliyuncs.com/api/v1",
  "DefaultEmbeddingModel": "tongyi-embedding-vision-plus-2026-03-06",
  "EmbeddingSpaceVersion": "v1",
  "EmbeddingResolutionLevel": 1
}
```

| 字段 | 作用与填写要求 |
| --- | --- |
| `ProviderId` | 注册到 Universe.Providers 的名称；与 `Graph.embeddingProvider` 相同。不能与聊天/其他 Provider 重名 |
| `APIKey` | 配置文件中的密钥字符串。缺省/null/空白时仍可注册并读取已有缓存，未命中的描述不能计算向量 |
| `BaseUrl` | 当前正式包使用上面这个业务空间的**原生 `/api/v1`** 地址。不能填 `/compatible-mode/v1` |
| `DefaultEmbeddingModel` | 当前正式包固定为 `tongyi-embedding-vision-plus-2026-03-06` |
| `EmbeddingSpaceVersion` | 当前 `v1`，与 Graph 和包相同；云端语义空间变化时提高版本并重新预建 |
| `EmbeddingResolutionLevel` | 当前 `1`，对应包的 `res_level=1`；不要随意调整 |

其他兼容保留字段 `DefaultModel`、`enable_thinking` 不用于此次向量路径，不需要填写。DashScopeAdapter 当前实现 Embedding；聊天和文生图入口仍未实现，因此不要将会话 `Provider`、Librarian 等聊天 Provider 改为 DashScope。

该目录只放需要注册的 `.json` 文件，每个文件一个配置。插件按文件排序读取 UTF-8 JSON；多个文件里的 ProviderId 不能重复。旧 `APIKeyEnv` 字段不会被使用，迁移为 `APIKey` 后删除。密钥修改需重启，没有热加载。

即使模型名和维度相同，endpoint、分辨率、独立/融合模式或空间版本不同也会拒绝混用。当前包以图片独立向量建库，以文本独立向量查询，已经可以实现文字语义命中图标；不用切换为融合向量。更换服务地址或模型前，按 [Graph 预建说明](../AIChat/GRAPH.md) 显式重建包。

## 4. 配置 AIChat Graph 与会话

### 4.1 Graph 的完整可配置字段

将以下 `Graph` 对象合并进现有 `config/AIChat/config.json`，保留原有其他对象：

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
    "embeddingProvider": "DashScope",
    "embeddingModel": "tongyi-embedding-vision-plus-2026-03-06",
    "embeddingDimension": 1152,
    "embeddingSpaceVersion": "v1"
  }
}
```

| 字段 | 默认值 | 可用范围/说明 |
| --- | --- | --- |
| `enabled` | true | 注册 graph 渲染和 Skill；false 关闭该功能 |
| `iconsEnabled` | true | false 时直接无图标绘图，不查询向量 |
| `width` | 1440 | 640～2048 像素 |
| `pageHeight` | 2000 | 800～4096 像素 |
| `maxPixels` | 8000000 | 1～8000000；width × pageHeight 不能超过该值 |
| `maxNodes` | 200 | 1～200 |
| `maxEdges` | 400 | 0～400 |
| `maxTextCharacters` | 20000 | 1～20000 |
| `maxPages` | 32 | 1～64 |
| `outputDirectory` | cache/graph | 非空、可写；相对路径以 APP_ROOT 为基准 |
| `fontFile` | 未配置 | 可选的外部字体路径；默认读取 JAR 字体，通常无需设置 |
| `embeddingProvider` | DashScope | 注册的 ProviderId |
| `embeddingModel` | 指定 plus 快照 | 必须与正式包模型空间相同 |
| `embeddingDimension` | 1152 | 当前包固定为 1152 |
| `embeddingSpaceVersion` | v1 | 必须与 Adapter 和正式包相同 |

删除旧 Graph 字段 `catalogManifest`、`qdrantCollection`、`embeddingEndpoint`、`embeddingApiKeyEnv`。启动会提示它们失效，不自动转换或删除旧资源/远端 collection。

缺包、损坏包、Provider 不可用、没有密钥或请求失败时，图表仍保留正文和关系，相应图标省略。损坏缓存视为未命中。错误 XML 和非法结构仍按失败处理，不通过省略图标来掩盖图表错误。

### 4.2 会话与技能

在需要新功能的 `config/AIChat/SessionCFG/**/*.json` 中合并：

```json
{
  "Provider": "bailian-chat",
  "Model": "qwen3.5-plus",
  "nativeImage": true,
  "availableSkills": ["graph", "image_edit", "map"],
  "skills": [],
  "Tools": ["WebSearch", "VisitURL", "SauceNAOSearch"]
}
```

这是字段示例，**不能整文件覆盖**：保留原 `SessionId`、`Trigger`、`Keyword`、提示词、Tools、skills、availableSkills 等内容，在原数组追加需要的项。Provider/Model 使用现有有效聊天配置；示例不要求切换聊天模型。没有高德/SauceNAO 配置时不必追加相关项。

| 字段 | 用途 |
| --- | --- |
| `SessionId` | 原有全局会话 ID/正则，形如 `<Onebot配置ID>:group/<群号>`；会话驱动也匹配此值 |
| `Provider` / `Model` | 聊天 ProviderId/实际模型；必须支持计划使用的图片、工具和结构化输出能力 |
| `Trigger` / `Keyword` | 保持原触发策略；`every` 代表每条用户消息触发 |
| `nativeImage` | 默认 false，启用后聊天附件进入模型上下文并获得图片引用；图片编辑/搜聊天附件需要 true |
| `availableSkills` | 会话技能白名单。不存在时回退到全局 `Skills.availableSkills`，两者都不存在时自动发现所有注册技能。空数组是无可用技能 |
| `skills` | 始终加载技能；不存在时回退到全局 `Skills.defaultSkills`。graph/image_edit 默认按需加载，不必强制放入这里 |
| `Tools` | 注册名数组。网页访问填 `VisitURL`（内部函数名为 VisitWebPage），搜图填 SauceNAOSearch；graph/image_edit/map 使用 Skill 激活 |
| `maxTokens` | 默认 20000，沿用既有上下文预算 |
| `enable_thinking`、`subAgents` | 沿用已有配置，并确认目标模型/子代理支持 |

全局技能白名单示例为 `"Skills": {"availableSkills": ["graph", "image_edit", "map"], "defaultSkills": []}`，同样只追加，不删除原条目。有会话级白名单时只改全局不会对该会话生效。检查自定义 `Skills/*.json` 不要以同名 ID 覆盖内置技能。

会话驱动 API 无新开关：调用方在 AIChat 已启动、通道已登录、有匹配会话配置和有效聊天 Provider 后使用 `driveMessage(sessionId, MFChain)`。输入保持 user 角色，同会话串行；连续驱动输入静默五秒后推理，普通触发并入已有驱动批次，不主动周期唤醒。

`ToolLoop` 保留既有字段及默认值：`MaxToolCallsPerTurn=20`、`MaxToolRounds=5`、`EnableAsyncTools=false`、`AsyncMaxWaitMillis=30000`、`AsyncDebounceMillis=5000`。只有需要跨请求异步工具时开启 EnableAsyncTools；驱动消息的五秒合并无需开启它。

## 5. 图片托管、聊天模型与 Web

### 5.1 UniverseChannel.properties

```properties
Enable=true
WsPort=2576
HttpPort=2577
Token=替换为现有有效通道口令
OutboundHttpAddress=http://消息端可访问的主机:2577
```

保留现有端口和通道口令。`OutboundHttpAddress` 是最终图片 URL 的前缀，包含端口或对应反向代理入口，通常不带末尾 `/`；它不是绑定监听地址，也不决定 HttpPort。已有 HTTPS 代理时使用该对外地址。

开启图表或图片编辑时必须 Enable=true。从 NapCat 所在机器/容器访问实际生成的 `/Universe/<随机编号>.png`，还要确保模型能访问需要引用的图片。部署机访问成功不能证明远端能下载，容器中的 `127.0.0.1` 指向容器自身。已有代理要保留 `/Universe/`、`/AIChat/` 的路由。

`Config.properties` 保留 `Debug=false`；需要调试时值可为 `debug` 或 `trace`，并非 `Debug=true`。日志默认位于 `log/app.log`，按日滚动。

### 5.2 OpenAIAdapter 聊天与百炼搜索

在现有 `config/OpenAIAdapter/<provider文件>` 中保留字段并按需启用 Search：

```json
{
  "ProviderId": "bailian-chat",
  "APIKey": "填写聊天服务密钥",
  "BaseUrl": "https://llm-a1bz0eifucqm4izg.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
  "DefaultModel": "qwen3.5-plus",
  "Search": true
}
```

聊天 BaseUrl 使用 OpenAI 兼容地址，Graph Adapter 使用原生地址，两个 ProviderId 分开。`DefaultEmbeddingModel` 是该通道原有文字向量的可选配置，保持知识库/记忆所使用的原值，不能因 Graph 模型变化而改成多模态模型。

Search 默认 false，只有实际请求模型命中百炼搜索白名单才发送 enable_search。会话 Model 可以覆盖 DefaultModel，必须以真实会话测试；服务器决定是否搜索，开关开启并不等于每次执行搜索。OpenAIAdapter 读取配置目录中的文件，备份文件放在目录之外。具体规则见 [OpenAIAdapter](../openaiadapter/README.md)。

### 5.3 Web 的配置项

文件为 `config/AIChat/Plugins/Web/config.json`。普通网页抓取所需片段：

```json
{
  "Selenium": {"URL": "http://现有Selenium主机:4444"},
  "EnableProxy": false,
  "HttpProxyURI": ""
}
```

保留已有 Selenium endpoint，包括实际需要的 `/wd/hub` 等路径；必须 HTTP/HTTPS 且非空。Selenium 运行浏览器的环境也需要能访问目标网页。代理字段只作用于 Web 使用它的客户端，不能当作 DashScope/OpenAIAdapter 的统一代理设置。

| Web 字段 | 使用场景/默认值 |
| --- | --- |
| `Selenium.URL` | 通用网页 fallback；没有 Selenium 时只访问已有专用路由支持的页面 |
| `EnableProxy` / `HttpProxyURI` | false / 空串；Web 的代理开关/URI |
| `SearchProvider` | `SerpApiGoogle`、`Google` 或 `BrightDataGoogle`；WebSearch 所选客户端 |
| `SerpApiKey` | SerpApiGoogle 密钥 |
| `GoogleAPIKey` / `SearchEngineID` | Google 搜索配置；GoogleAPIKey 也用于 YouTube API |
| `BrightDataApiKey` / `BrightDataZone` | BrightDataGoogle 配置 |
| `SearchParam.language` / `country` / `location` | 所选搜索客户端使用的语言、地区与位置 |
| `EnableYouTubeAPI` | 默认 true；不使用时关闭，否则保留有效 GoogleAPIKey |
| `EnableGoogleSearch` | 旧配置字段；当前搜索客户端由 SearchProvider 选择，不能仅设此字段开启搜索 |
| `EnableSauceNAO` / `SauceNAOApiKey` | 默认 true；搜图需密钥，有效配置才注册工具 |
| `SauceNAOShortLimit` / `SauceNAOLongLimit` | 默认 4 / 100，必须正数；本地 30 秒/24 小时新请求上限 |
| `EnableAmap` / `AmapApiKey` | 默认 true；地图 Skill 需高德 Web 服务密钥 |
| `AmapRequestsPerMinute` | 默认 60，必须正数 |

这次部署将所需密钥填到对应配置字符串中即可。高德/搜图是已有能力的回归项，不要求本次购买或启用。插件注册和会话工具/技能选择都要成立才能使用，详见 [Web 配置说明](../AIChat-Web/README.md)。Selenium 修复的超时和导航策略在代码中，无新增调节字段。

### 5.4 OneBot 与数据库配置

OneBot 文件路径是 `config/Onebot/*.json`，**不是 config/OnebotAdapter/**。保留 `ID`、`URI`、`Token`、`EnableQZone`、`BlockedUsers`、`DegradedUsers` 和 `RemoteWebDriver.url`。ID 用于会话前缀，改名需同步 SessionId。Web 的 Selenium.URL 与 OneBot 的 RemoteWebDriver.url 是不同用途，勿互相覆盖。

现有 `config/AIChat/config.json` 中以下配置与图标迁移独立：

| 对象 | 需要检查/保留的字段 |
| --- | --- |
| `ProviderId` | 全局辅助聊天 ProviderId |
| `PromptFirst` / `PromptLast` | 全局提示词，完成 XML 格式迁移 |
| `SQLConfig` | url、user、password；已有 MySQL 和持久数据保持原值 |
| `Qdrant` | address、port、encryptedConnection、secretKey、provider；port 默认6334是 gRPC，不能误填6333 HTTP端口 |
| `Annotator` | provider、embeddingProvider、model、dimension、enable_thinking；沿用辅助模型/文字向量配置 |
| `Memory` | collection、vectorName、dimension；不重建或清空现有集合 |
| `KnowledgeStore` | collection、vectorName、documentsTable、embeddingProvider、embeddingModel、dimension、llmProvider、llmModel、queryCount、candidateLimit、chunkTokenThreshold、embeddingBatchSize、defaultTtlDays |
| `Librarian` | provider、model、tools、thinking、enableAsyncUpdate |

搜索研究改进（2026-10-15）：`Librarian` 使用异步分段回调。研究模型使用自然语言，在每轮工具调用前返回阶段发现、待查证点和下一步目标；最后单独返回一条完整报告，即使前面已提过也重新说明结论、依据、来源、适用条件和未确认事项。进度按完整段落回调，最终报告不拆段。回调沿用会话队列和工具续答，不依赖一次性补投的 `EnableAsyncTools` 开关。非会话调用者仍可通过同步 `search(question)` 获取最终报告。

本地知识检索与联网研究并行，完成后在研究下一轮请求前作为材料加入，不再额外调用合并模型。`mergeThinking` 已移除，旧配置残留字段可删除。研究和后台入库分别沿用 `Librarian.provider/model` 与 `KnowledgeStore.llmProvider/llmModel`，无需新增模型配置。只有研究正常结束后才异步启动结构化入库编排；研究与入库失败分别处理，入库失败不改变已返回的报告。研究总期限为十分钟，超时或模型输出截断会明确报错，不自动入库。

耗时排查查看 `Librarian search complete`、每轮 `LLM request` 与 `Tool execution`。异步工具的分发耗时不代表后台研究总耗时。日志的 `thinking` 是请求意图，是否真正生效取决于 Provider 适配。比较模型时使用相同任务、工具、部署网络与思考模式，同时记录首次有效进度、首次用户回复、研究结束时间、模型请求次数和来源质量。

Graph 不再连接 Qdrant，**知识库和记忆继续连接**，不能因此停掉 Qdrant。已有库的 embedding 模型/维度必须保持一致。没有启用数据库的实例无需为了 Graph 新建 SQLConfig/Memory/KnowledgeStore；已启用的实例不要套用只包含 Graph 的最小片段覆盖全局配置。

## 6. XML 与图片引用迁移

本次包含不兼容的浮标协议变化。逐项检查全局/会话提示词、Skills、SubAgents、外部会话生成器及插件：

| 旧写法 | 现在写法 |
| --- | --- |
| `<at:目标标识>` | `<at target="目标标识"/>` |
| `<reply:消息ID>` | `<reply to="消息ID"/>` |
| `<emoji:名称>` | `<emoji name="名称"/>` |
| 工具图片参数 `<quote:N>` | `<ref id="N"/>` |
| 图片编号说明 `<quoteId:N>` | `<ReferenceId=N>`（说明标签） |

工具字符串中的图片引用例如 `{"source_image":"<ref id=\"1\"/>","angle":90}`。最终发送图片编辑结果使用工具返回的 `<image_edit id="编号"/>`。Graph 使用 `<graph type="list|compare|timeline|tree|flow|relation">...</graph>`，具体结构以 [图表示例](../AIChat/examples/graphs/README.md) 为准。

MarkDecoupler 接口现在接收 DOM `Element`。自定义插件重新编译，不能只更新提示词而继续运行旧二进制。旧历史内容不会自动迁移；切换时结束旧内存会话，检查再次载入的历史是否继续诱导旧格式；按既有会话管理方式处理，不批量改写数据库历史。

## 7. 保存哪些目录、怎样更新上线

### 7.1 持久目录与临时目录

| 路径 | 管理方式 |
| --- | --- |
| `config/`、Config.properties、UniverseChannel.properties | 配置和提示词，变更前备份；限制为运行账号和管理员可读 |
| `data/AIChat/graph-embeddings/text/` | 长期文本向量缓存，保留并备份；无 TTL/自动磁盘淘汰；内存 LRU 1024项 |
| `data/AIChat/graph-embeddings/images/` | 预建工具的图片向量缓存，续建时复用；纯运行实例无需从开发机复制全库图片缓存 |
| `data/AIChat/graph-icons/` | 预建中间资源；运行只读 JAR 内资源，不依赖此目录 |
| `data/AIChat/Plugins/` | Web/ImageEdit 等二级插件；成套更新选定 JAR，保留其他插件 |
| 其他 `data/`、MySQL、Qdrant | 持久业务数据，按原备份与恢复方式保留 |
| `cache/graph`、`cache/image-edit`、其他 cache | 临时输出；Universe 启动/退出会清空整个 cache；不放密钥、插件或持久向量 |
| `log/` | 故障诊断，按现有保留策略管理 |

图标资源留在 AIChat JAR 中，运行时不解压；向量计算缓存已放到 data，不受 cache 清理影响。若向新 APP_ROOT 迁移，至少复制有效的 text 缓存，保留所有原持久业务目录。APIKey 换新不改变缓存身份；endpoint/模型空间变化会隔离缓存。

### 7.2 变更窗口操作

1. 记录线上提交 c2807b4e、主程序/插件/依赖 SHA-256、JDK、启动命令、JVM 参数、实际工作目录、服务账号、代理和外部服务地址。保存整个旧发行包，备份配置、持久 data 与数据库快照。备份不能放到 laws 或 Plugins 的扫描目录内。
2. 在隔离测试目录放入新发行包和配置副本，使用独立测试会话；测试若写知识库/记忆，用独立库/集合或既有测试环境。执行下一节验收。不要让两个实例同时消费同一线上账号/会话。
3. 到变更窗口，用**现有服务/容器管理方式**停止旧进程，确认已退出、端口释放。先停进程再备份最终的持久缓存和更新文件。
4. 成套更新 Universe.jar、lib、laws 的选定插件；从发行目录 `data/AIChat/Plugins/` 更新 Web、ImageEdit。将旧同 ID JAR 移出加载目录。只迁移选定插件文件，不能把发行目录这个精简 data 覆盖线上完整 data。
5. 合并本文件的配置和 XML 迁移；在 graph.json 填写 APIKey，保持当前包的模型空间设置，检查运行账号有 config 读取和 data/cache/log 写权限。
6. 使用现有服务管理方式启动，工作目录必须 APP_ROOT，运行 classpath 包含 Universe.jar 和 lib/*。确认实例只有一个，核对启动日志及实际消息通道登录。
7. 先在测试会话跑 graph/编辑/搜索/网页/记忆，再开放正常会话，观察错误、延迟、内存和磁盘增长。完整重启一次，验证相同 icon 描述读 data 缓存而不重新计费。

Windows 在 APP_ROOT 前台启动示例（验收通过后把同一命令交给现有管理器）：

```powershell
Set-Location -LiteralPath 'C:/实际路径/MoyuUniverse'
& 'C:/实际路径/jdk-17/bin/java.exe' '-Djava.awt.headless=true' -cp 'Universe.jar;lib/*' com.nekoyu.Universe.Universe
```

Linux 前台启动示例：

```sh
cd /实际路径/MoyuUniverse
/实际路径/jdk-17/bin/java -Djava.awt.headless=true -cp 'Universe.jar:lib/*' com.nekoyu.Universe.Universe
```

保留线上已验证的 JVM 内存/GC 参数，按目标实例压测调整。服务的 WorkingDirectory/容器 WORKDIR 与挂载路径必须一致。不要从源码目录的 target/classes 或开发机 Maven 仓库启动线上。Linux/容器还需实际验证 Java2D 与所需系统库；本机 Windows 验收不能代替这一项。

## 8. 验收、故障定位与回滚

### 8.1 发布包的离线验证

在已收齐依赖的 APP_ROOT 执行：

```powershell
& 'C:/实际路径/jdk-17/bin/java.exe' '-Djava.awt.headless=true' -cp 'Universe.jar;laws/AIChat.jar;lib/*' com.nekoyu.Universe.AIChat.graph.GraphIconCli verify-classpath --required true
```

Linux 将 classpath 分隔符替换为 `:`。此命令只读发行 JAR、验证全库，不启动数据库、机器人或调用模型。必须显示2143图标、1152维。比对 `release.json` 中 SHA-256，并确认 Dirty=false 的固定提交候选；Dirty=true 是工作区验证工件，不是可复现的正式版本。

Maven 不自动执行 main 形式的回归。候选提交应单独运行 ImageEditTest、ImageSendTest、MapSkillIntegrationTest、SauceNaoIntegrationTest、DriveMessageTest、SessionReplyQueueTest、StructuredTurnTest、StructuredOutputsTest、BailianSearchTest 和 SeleniumFetchTest。这些测试使用本地夹具，不证明外部服务可用。例如从仓库根目录检查最终发行 Web/OpenAIAdapter JAR：

```powershell
$release = (Resolve-Path "target/deployment-$releaseId").Path
$repo = (Get-Location).Path
$fixture = New-Item -ItemType Directory -Path "target/main-tests-$releaseId" -ErrorAction Stop
$testCp = @("$release/Universe.jar", "$release/laws/AIChat.jar", "$release/laws/openaiadapter.jar", "$release/data/AIChat/Plugins/AIChat-Web.jar", "$release/lib/*", "$repo/AIChat-Web/target/test-classes", "$repo/openaiadapter/target/test-classes") -join ';'
Push-Location -LiteralPath $fixture.FullName
try {
    & "$env:JAVA_HOME/bin/java.exe" '-Djava.awt.headless=true' -cp $testCp SeleniumFetchTest
    if ($LASTEXITCODE -ne 0) { throw 'SeleniumFetchTest 失败' }
    & "$env:JAVA_HOME/bin/java.exe" '-Djava.awt.headless=true' -cp $testCp com.nekoyu.universe.openaiadapter.BailianSearchTest
    if ($LASTEXITCODE -ne 0) { throw 'BailianSearchTest 失败' }
} finally { Pop-Location }
```

夹具会生成临时配置，因此使用新的隔离工作目录，不能在生产 APP_ROOT 运行；测试类仅存在于构建机的 test-classes，无需部署线上。

### 8.2 真实上线验收

| 验收 | 通过标准 |
| --- | --- |
| 启动 | 无 ClassNotFound/NoSuchMethod；Graph ready，Web/ImageEdit 载入；OneBot 登录；已启用的 MySQL/Qdrant 正常 |
| 六类 graph | list/compare/timeline/tree/flow/relation 均收到 PNG；中文、宽/深树、分页、连线文字正常；页序正确，无重复发送 |
| 首次描述 | 用未缓存 icon 描述匹配图标；出现 Embedding-Usage，text 目录新增记录，图标与语义合理 |
| 跨图/重启缓存 | 完全相同描述再次绘图和重启后绘图复用持久缓存；不增加对应 embedding 调用 |
| 缺密钥降级 | 测试实例 APIKey 设空并重启；已有缓存仍能匹配，未缓存描述无图标，正文/关系完整 |
| 断网降级 | 测试实例阻断模型访问；缓存命中绘图正常，未命中按超时后无图标绘图 |
| 图片编辑 | 用户发图→引用→裁切/旋转→标记→QQ收到；连续编辑、回复历史持有期间 URL 正常 |
| XML | at/reply/emoji 与 `<ref>` 工作，模型不再输出旧冒号格式 |
| 搜索/网页 | 实际会话模型 Search 行为可核对；真实失败网页经已配置 Selenium 恢复读取，成功/失败后浏览器会话都释放 |
| 知识与记忆 | 查询既有记录、相关/无关网页过滤、时效与维护正常；没有误切换 embedding 空间 |
| 会话驱动 | user 角色输入、五秒合并、自动建会话、同会话串行、多会话并行、异步工具保护、停止后不回复 |
| 容量 | 记录目标机启动/峰值内存、查询与首请求延迟、并发 PNG 数量、data/cache 增长；重复重启正常 |

本机 Java 17 八模块 install、97项 JUnit、上述10个 main 回归，以及从完整发行目录 JAR/lib 运行的52页图表回归已通过；HTTP fixture 验证配置 APIKey 进入注册 Provider 的 Authorization，APIKey 空字符串时真实文本缓存仍可复用。真实图标资源来自此前模型计算。验收素材、费用和既往 Windows 测量见 [图标验收记录](graph-local-index-acceptance.md)。这些检查没有部署线上，也不证明真实 Linux、模型新请求或 NapCat/QQ 网络通路已验收。

### 8.3 常见故障

| 现象 | 检查与处理 |
| --- | --- |
| 程序找不到类/Batik/Qdrant库 | 启动是否包含 lib/*，是否用完整发行目录；同一提交成套更新，避免新插件配旧 Universe |
| Graph 配置初始化失败 | 图尺寸/上限是否越界、输出目录是否可写、最终字体资源是否完整 |
| 有图表但无图标 | iconsEnabled、Adapter law.yml/配置加载、ProviderId、完整模型空间、APIKey 和网络；允许降级不代表配置正常 |
| 空间不匹配 | 对照正式包的业务 endpoint、模型、1152维、v1、res1；需要变更时显式重新预建，不能强行绕过校验 |
| 新描述请求失败 | APIKey 是否有效、填在正确工作目录的 JSON、是否已重启；看受控 HTTP 状态和用量日志 |
| 重启重复计算 | text 缓存是否持久挂载、运行账号是否能写、实际文字是否完全相同、空间身份是否变更 |
| QQ 收不到图 | Enable/HttpPort/OutboundHttpAddress、代理转发、NapCat网络下载、OneBot连接；先用实际图片 URL 验证 |
| 找不到 Skill | 会话白名单覆盖全局、同名自定义 Skill、插件未注册；修改后重启并结束旧内存会话 |
| 普通网页不支持/超时 | Web的 Selenium.URL，而非OneBot的 RemoteWebDriver；检查真实服务和浏览器访问目标的网络 |
| 搜索开关无效果 | 会话实际Model、百炼识别/白名单、服务器是否决定搜索；不能只看回复说“已搜索” |
| data 磁盘持续增长 | text缓存无TTL；按运维策略备份/监控。不要把清空cache作为清理data的命令 |

### 8.4 回滚

1. 出现不可接受的消息/协议、数据库、启动或资源问题时，先停止新进程，保存错误日志和新配置差异。
2. 成套恢复旧 Universe、lib、laws、二级插件以及旧配置/提示词和启动命令，移出新增 DashScopeAdapter/ImageEdit。保留其他原插件，不让同 ID 两个版本并存。
3. 保留新增 graph 文本/图片缓存；旧版本不读取它们，无需删除。持久业务数据按实际变化评估恢复，不能仅为代码回滚盲目覆盖数据库。
4. 从旧 APP_ROOT 启动，验证 OneBot、原聊天/搜索/知识功能。cache 是临时目录，升级/回滚重启后旧图片 URL 可能失效。
5. 若仅图标服务有问题，可先将 `Graph.iconsEnabled=false` 并重启保留基础绘图；整个 graph 有问题时设 enabled=false。这是功能降级，不替代其他模块故障的完整回滚。

最终交付保留：候选提交号、release.json、构建/包验证日志、配置差异（不在公开记录中写密钥）、线上验收结果、旧发行包与恢复路径。
