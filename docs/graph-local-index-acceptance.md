# Graph 本地图标索引验收（2026-10-13）

## 实现与正式资源

graph 的 Qdrant 索引已替换为归一化 float32 精确检索。图标资源通过 classpath 流读取，不依赖本地绝对路径。
厂商协议迁移到独立的 Java DashScopeAdapter。Graph 用 ProviderIconEmbeddingProvider 适配公共 EmbeddingRequest/EmbeddingResponse，按 ProviderId 从 Universe.Providers 调用已注册 LLMProvider。
长期文本缓存位于 `data/AIChat/graph-embeddings/text/`，图片预建缓存位于 `data/AIChat/graph-embeddings/images/`。
内存缓存 1024 项，磁盘不自动淘汰；模型失败只省略相应图标，缓存命中可在没有 API Key 时工作。
知识库和记忆模块的 Qdrant 保持原有实现。

正式素材来自 npm 的 `lucide-static@1.56.0`，共 **2143** 个图标，不使用测试合成向量。
来源：[固定版本 npm 包](https://registry.npmjs.org/lucide-static/-/lucide-static-1.56.0.tgz)。
模型固定为 `tongyi-embedding-vision-plus-2026-03-06`，**1152 维**，res_level=1，每个图片单独一个 content。
模型和版本能力：[百炼说明](https://help.aliyun.com/zh/model-studio/embedding)。

资源包目录为 `AIChat/src/main/resources/graph/icons/`，4290 个文件，包括 SVG、PNG、LICENSE、清单、向量和有效包指针。
完整包经过资源摘要、PNG 解码、向量顺序/维度/范数/摘要验证；Maven prepare-package 再次验证，不调用模型。

- 图标内容摘要：`225e81a0c034246efc458d55a7067e7db0fccf55586f4fb1b3b84acf4580bac6`。
- 索引身份：`95deb10286b7a516fff52ef17cb85b7e3c5776f0f6580deedfd83be19c577101`。
- 向量文件 SHA-256：`f706ba82bf85f9561f88c825fe9aba65ca5d3fca0e0ed3af56f48444c053b962`。

## 真实计算用量

一次全库预建记录 **754956 个输入 Token**。按官方 0.0005 元/千输入 Token，标价折算 **0.377478 元**，不含先前接口探针和少量文字验收请求。
这是接口 usage 与标价计算值，实际扣费及免费额度以账户账单为准。[官方单价](https://help.aliyun.com/zh/model-studio/embedding)。
2143 张 PNG 按内容摘要去重后有 **1878** 份实际图片，模型输入为每份 402 Token；1878 × 402 = 754956。
预建采用最多 16 张一批，成功结果即时缓存；重复图片共享计算结果，索引仍保留全部 2143 个稳定 ID。
测试验证中断后的成功项无需重算。
生产资源、配置示例和文档均不包含调用密钥；两份临时调用密钥文件已删除，发行 JAR 和源码的临时密钥标记扫描结果均为 0。

## 验证记录

- 八模块 Maven package 成功：父工程、Universe、AIChat、AIChat-Web、AIChat-ImageEdit、OnebotAdapter、openaiadapter、DashScopeAdapter。
- **97 项 JUnit 通过，0 失败/错误/跳过**：AIChat 86 项、DashScopeAdapter 11 项，覆盖公共 Provider 调用、延迟注册、协议参数/结果校验、两种融合格式、大小/总超时，以及既有绘图与缓存。
- 缓存测试覆盖跨实例读取、并发合并、模型身份隔离、损坏重算、写盘失败、失败不缓存、已缓存/未缓存描述混合降级。
- 包测试覆盖 jar: 资源读取、向量损坏、PNG 篡改、前缀名称排序、断点续建和失败不覆盖旧包。
- 最终发行 JAR 验收：资源与字体来源均为 `jar:`，运行 classpath 不包含 AIChat/target/classes 或源资源目录。
- 六类图表以及长文分页、120 子节点宽树、80 层深树共生成 **44 页 PNG**；未缓存描述在明确禁止调用模型的适配器下保留正文并省略图标。
- 正式文字向量先通过真实服务计算，随后在另一 JVM 不提供密钥复用同一 data 缓存。
- 最终 JAR 的独立预建工具通过 law.yml 和 prepare 注册真实 Java DashScopeChannel，按公共接口查询身份后用图片缓存重建 2143 行向量：新增模型 token=0，向量 SHA-256 与原包完全一致。
- GraphProviderJarProbe 从最终 JAR 调用公开 GraphRuntime.open()，正常经过注册表和公共 Embedding，六类图表生成额外 **8 页**；缺密钥未缓存描述保留正文。与 44 页布局回归合计 52 页。
- DashScopeAdapter JAR 包含 law.yml，没有 Kotlin metadata 或 Companion 类；仓库已无 .kt/.kts 源码，模块移除了 Kotlin 编译插件和直接依赖。

额外 **8 个 main 回归通过**：ImageEditTest、ImageSendTest、MapSkillIntegrationTest、SauceNaoIntegrationTest、DriveMessageTest、SessionReplyQueueTest、StructuredTurnTest、StructuredOutputsTest。这些入口已单独执行，不由 Maven 自动测试执行。
ImageEditTest 的旧夹具改用真实会话创建和发送服务启动入口，适配既有会话排队实现；产品图像编辑代码没有改动。

2026-10-14 密钥改为 JSON 的 APIKey 字符串后，JDK 17 重建 `AIChat/target/AIChat-2.0-Phototaxis.jar` 为 **28,865,933 字节（27.53 MiB）**，SHA-256：
`4761180f3fdba140061c8e56d9c40a73e0a23fcf36e89efd42cc135a6fa05d2f`。
Universe JAR 的 SHA-256：`e740833c1c56549d710704a8f0c0625c4f4fe268d6d43ce9c456c7ff1da90bf2`。
DashScopeAdapter JAR 为 **22,658 字节**，SHA-256：
`431359c6e9b16ebb6d70071eed19329afc6930ab41dcdc069bb25a484fbcd97c`。

2026-10-14 使用 JDK 17 的实际构建命令（先安装 AmapAPI）：

```powershell
mvn -o -B -Dmaven.repo.local=C:/Users/imylk/.m2/repository -pl DashScopeAdapter,AIChat,AIChat-Web,AIChat-ImageEdit,OnebotAdapter,openaiadapter -am install
```

八模块 install 与 97 项 JUnit 通过。旧 OneBot 的 Lombok 1.18.30 不兼容 JDK 25，单加 proc=full 不能解决重新编译，发布构建改用 JDK 17。
Jar 注册/缓存续建旧日志在 `AIChat/target/provider-jar-acceptance/`，不会写入发行资源。
部署脚本生成的 `target/deployment-config-string-20261014/` 包含 105 个运行 JAR，release.json 记录大小与摘要；工作区 Dirty=true，正式发行仍需固定提交重建。
JDK 17 从该目录的 Universe/AIChat/Adapter JAR 与 lib 运行，在隔离工作目录、APIKey 空字符串下通过 44 页布局与 8 页公共 GraphRuntime 回归，日志位于 `AIChat/target/config-string-release-acceptance/`。
同一发行 JAR/lib 下上述8个 main 再次通过，新增复核 BailianSearchTest（69次拦截请求）和 SeleniumFetchTest，共10个 main。均使用本地夹具，没有实际模型、浏览器服务或 QQ 网络调用。

## 全库性能实测

环境：Windows，JDK 25，编译目标 Java 17；独立探针 `-Xms32m -Xmx1024m`。
图标向量矩阵占用约 **9.42 MiB**；图标包原始文件合计 **20,208,419 字节**。
测量使用最终 JAR 和真实 2143 行向量矩阵，100 次预热后对 8 个描述轮换执行 300 次 Top 5 查询。

迁移 Adapter 前的两轮 44 页性能基线如下，第二轮不提供 API Key；以下 Working Set 是初版基线，不充当 Java Adapter 迁移后的重新测量：

| 轮次 | 全库加载 | 平均 Top 5 查询 | 初始 Working Set | 峰值 Working Set | 增量 |
| --- | --- | --- | --- | --- | --- |
| 第一轮 | 3164 ms | 1.107 ms | 42.47 MiB | 300.59 MiB | 258.12 MiB |
| 无密钥重启 | 5385 ms | 4.334 ms | 42.32 MiB | 271.69 MiB | 229.37 MiB |

Java Adapter 迁移后的最终 JAR 抽测：全库加载 **3364 ms**，同一查询方法平均 **1.040 ms**；资源及字体仍来自 jar:。

2026-10-14 改用 JDK 17、完整发行 lib、`-Xmx1024m`、空 APIKey 的一次复核：全库加载 **7962 ms**，平均 Top 5 查询 **1.739 ms**，52 页回归通过。此轮没有重新测量 Working Set。

该增量包含加载/校验全库、字体、文字缓存读取及连续输出 44 页；不包含完整 AIChat、多会话压力、外部 embedding 或 Qdrant 进程。
它不是不同 JDK/平台下的统一内存上限，线上仍需独立验收。

| 描述 | 首选结果 |
| --- | --- |
| 笔记本电脑 / laptop | lucide/laptop |
| 服务器 | lucide/server |
| search | lucide/file-search |
| 数据库 | lucide/database-plus |
| 云端 | lucide/cloud |
| 电池 | lucide/car-battery |
| 地图 | lucide/map-plus |

以上为八个抽样描述的结果，不是全库语义准确率。相似度不作为概率解释。

另用 `GraphSemanticProbe` 实际计算十八条中文描述，增加 395 输入 Token（标价约 0.000198 元），保留全部 Top 5 到 `AIChat/target/graph-semantic-samples.json`。

| 描述 | 首选结果 |
| --- | --- |
| 便携电脑 | lucide/laptop |
| 数据存储 | lucide/hard-drive |
| 联网服务 | lucide/wifi-cog |
| 通知提醒 | lucide/bell-check |
| 删除垃圾 | lucide/trash |
| 保护安全 | lucide/shield-lock |
| 上传云端 | lucide/cloud-upload |
| 播放音乐 | lucide/music-4 |
| 月亮 | lucide/moon |

抽象描述仍会歧义：“计算设备”首选 calculator，“登录账号”首选 lock-open，“开始运行”首选 fast-forward。
这些样例验证了中文文字与图片的语义检索路径，也说明只有视觉向量时无法保证应用意图的精确图标选择。
当前以最高相似度候选绘制；没有引入关键词映射、阈值或二次模型裁决。
融合向量将图标图片和说明共同编码，和当前纯图片建库不同；若将来采用，需更新预处理版本、重建并对照评测。

## 上线配置与验证边界

本次预建包使用用户提供的业务空间原生 endpoint。上线安装 DashScopeAdapter，复制 `DashScopeAdapter/examples/graph.json` 到 `config/DashScopeAdapter/graph.json`：

```json
{
  "ProviderId": "DashScope",
  "BaseUrl": "https://llm-a1bz0eifucqm4izg.cn-beijing.maas.aliyuncs.com/api/v1",
  "APIKey": "填写你的 DashScope 密钥",
  "DefaultEmbeddingModel": "tongyi-embedding-vision-plus-2026-03-06",
  "EmbeddingSpaceVersion": "v1",
  "EmbeddingResolutionLevel": 1
}
```

AIChat 的 Graph 使用 ProviderId，不再包含厂商 endpoint 或密钥设置：

```json
{
  "iconsEnabled": true,
  "embeddingModel": "tongyi-embedding-vision-plus-2026-03-06",
  "embeddingDimension": 1152,
  "embeddingSpaceVersion": "v1",
  "embeddingProvider": "DashScope"
}
```

这是 Graph 内部字段片段，需合并到现有 `Graph` 对象，不覆盖其他配置。
需同时更新 Universe、AIChat、DashScopeAdapter JAR，公共 Embedding API 新增输入和空间查询能力。
旧 Graph.embeddingEndpoint/embeddingApiKeyEnv 启动时提示已失效；原有聊天继续使用 OpenAIAdapter。
运行进程直接读取上述 JSON 配置中的 APIKey 字符串；删除临时密钥不会影响已缓存描述，新增描述则需可用模型服务。
保留 data 缓存，生成的图表仍使用 cache/graph，并要求消息通道可访问的图片 HTTP 地址。
旧图标目录、Qdrant 图标 collection 不再读取且未自动删除，知识库/记忆的集合不变。
本次未部署线上、未进行 Linux/真实 NapCat/QQ 网络验收；OneBot 回归终点是本地序列化或捕获通道。
Java Adapter 的新 HTTP 路径使用本机服务验证，正式素材和向量来自此前真实模型调用；迁移后的复核没有发起额外付费请求。
