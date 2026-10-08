# AIChat-Web

## map 地图与出行技能

Web 插件内置原生 AIChat `map` Skill，默认 `ON_DEMAND`。天气、行政区、地理编码、逆地理编码、地点搜索和路线查询分别注册为 `MapWeather`、`MapDistrict`、`MapGeocode`、`MapRegeocode`、`MapSearch`、`MapRoute`，由同一个 Skill 的 `toolNames` 关联。插件先注册技能及其回调，模型使用现有 `ManageSkills` 激活 `map` 后才加载操作说明和六个函数。无需向会话 `Tools` 列表添加各项地图功能。

在 `config/AIChat/Plugins/Web/config.json` **原有配置中合并**：

```json
{
  "EnableAmap": true,
  "AmapApiKey": "你的高德Web服务API密钥",
  "AmapRequestsPerMinute": 60
}
```

也可省略 `AmapApiKey`，由启动 Java 的进程读取环境变量 `AMAP_API_KEY`。配置字段优先；禁用、缺少密钥或限流非正数时，工具和 skill 均不注册。密钥需为高德 **Web 服务 API** 类型，相应接口权限、账户额度与访问限制以高德控制台为准。客户端沿用 Web 的 `EnableProxy` / `HttpProxyURI`，关闭 Web 代理时直连。

会话未指定 `availableSkills` 且全局未限制可用技能时，自动发现已注册的 `map`。如果已有可用技能白名单，在目标会话 `availableSkills` 原有数组中追加 `"map"`：

```json
{
  "availableSkills": ["map"]
}
```

这是最小示例，保留其他可用技能和会话字段。希望始终加载时，可在会话小写 `skills` 数组中追加 `"map"`。重启应用后配置生效；技能与 MaiBot 一样在 Java 中创建 `Skill` 并通过 `registerSkill` 注册，无需额外的技能资源文件。已有 `config/AIChat/Skills/*.json` 若使用相同 `map` ID，可能覆盖内置定义，应避免重名。

操作与参数：

| function | 必填参数 | 可选参数 | 能力 |
|---|---|---|---|
| `MapWeather` | `location` 地区名或 adcode | `province`、`type=live/forecast/both`，默认 live | 实况与返回日期内的预报 |
| `MapDistrict` | `location` 地区名或 adcode | `province`、`limit` | 行政区候选及编码 |
| `MapGeocode` | `location` 完整地址或地标 | `city`、`limit` | 地址转 GCJ-02 坐标 |
| `MapRegeocode` | `location` GCJ-02 坐标 | 无 | 坐标转地址与行政区 |
| `MapSearch` | `keywords`，以及 `city` 或中心坐标 `location` | `limit`、周边搜索的 `radius` | 城市内地点搜索或周边设施 |
| `MapRoute` | `origin`、`destination` 完整地址或 GCJ-02 坐标 | `city`、`travelMode=driving/walking`，默认 driving | 驾车或步行路线 |

`limit` 默认5，范围1～10；`radius` 默认3000米，范围1～50000。坐标为 `经度,纬度`，小数最多6位。模型调用对应函数并传入该函数的参数，无需传入 action，回调会提取声明的查询字段，忽略 AIChat 追加的会话占位符和调用 ID。地点缺失/同名地区/地址多候选时明确要求确认，不自动选择第一个候选；省份天气需进一步指定城市（直辖市除外）。路线地址仅匹配到省、市或区县时不能作为具体起终点。用户提供 WGS-84、百度或未知坐标系时应先确认/转换，本技能没有实现坐标转换。

模型可组合调用，例如：先 `MapSearch` 找到准确 POI，再以其坐标 `MapRoute` 规划路线；先 `MapRegeocode` 获取区县 adcode，再 `MapWeather` 查天气。查询失败、无结果或超出预报日期时直接说明，不编造答案。公交、骑行、历史/逐小时天气、空气质量、预警、实时导航暂不支持。

调用示例：

```text
MapWeather({"location":"杭州市","type":"both"})
MapWeather({"location":"朝阳区","province":"北京市","type":"forecast"})
MapSearch({"city":"杭州市","keywords":"杭州东站"})
MapSearch({"location":"120.15507,30.274084","keywords":"咖啡店","radius":3000})
MapRoute({"origin":"120.15507,30.274084","destination":"120.16007,30.270084","travelMode":"walking"})
```

所有结果以类型明确的 Gson 对象解析后格式化为文本，附高德来源及查询时间。天气保留 `reporttime` 和真实预报日期；地点保留地址、POI ID 和坐标；路线保留距离、预计耗时、收费/限行提示及最多12段指示。字段缺失显示“未提供”，高德缺失字符串的 `[]` 被单独处理，真实列表不被替换。接口错误只展示受控提示和 infocode，不将密钥、完整请求 URL、原始响应或网络异常详情传给模型。

插件进程内共用256项 LRU 缓存，并合并并发相同请求。实况5分钟、预报30分钟、行政区24小时、地理/逆地理编码1小时、地点5分钟、路线1分钟；成功的空结果也缓存，失败不缓存。缓存命中和合并请求不消耗本地限流，每个新 HTTP 请求（失败也算）计入滚动60秒窗口，默认60次；天气 `both` 和地点解析可能产生多个请求。此限额是本地保护，并非账户额度。不自动重试；连接超时10秒、单次请求总超时20秒。

验证入口均在 `src/test/java`，可直接在 IDE 运行 main：

- `MapServiceTest`：离线验证参数/URL编码、各类响应、地点歧义、缓存失效、限流、并发合并、部分天气失败与错误脱敏。
- `MapSkillIntegrationTest`：离线验证 Web 注册、按需激活/去激活、六个函数加载及真实 ChatContext 工具回合的结果回传。使用模拟模型和高德响应，未验证真实模型选择技能。
- `MapDemo`：设置 `AMAP_API_KEY`，输入内部 MapService 查询 JSON（含 action，仅用于直接调试服务） 或以一个程序参数传入；默认直连，打印真实 API 的格式化结果，无需启动机器人。

编译验证类：`mvn.cmd -o -Dmaven.repo.local=C:/Users/imylk/.m2/repository -pl AIChat-Web -am test-compile -DskipTests`；打包时将 `test-compile` 改为 `package`，生成 `AIChat-Web/target/AIChat-Web-2.0-Phototaxis.jar`。验证类是 main 程序，需要另行运行，Maven `test-compile` 不会执行它们。部署 Web JAR 到 `data/AIChat/Plugins/` 并重启应用；最终真实验收需在目标聊天分别查询“杭州明天天气”“杭州东站在哪里”“从已确认的起点到终点步行怎么走”，确认 `ManageSkills(map)`、对应地图函数的调用及回复。

接口依据：[天气](https://lbs.amap.com/api/webservice/guide/api/weatherinfo)、[行政区](https://lbs.amap.com/api/webservice/guide/api/district/)、[地理/逆地理编码](https://lbs.amap.com/api/webservice/guide/api/georegeo/)、[地点搜索](https://lbs.amap.com/api/webservice/guide/api-advanced/search)、[路径规划 v3](https://lbs.amap.com/api/webservice/guide/api/direction)。

## SauceNAO 图片来源查询

客户端：`src/main/java/com/nekoyu/Universe/AIChat/Web/SauceNAO/Client.java`。
调用 `new Client(apiKey).search(imageUrl)` 得到 `SearchResponse` 响应对象。按模块现有风格使用 Gson 反序列化，`header`、`results`、每项的 `header` 和 `data` 均为字段明确的 Java 类。支持传入 `Proxy`，不新增依赖。

Demo：在 IDE 中运行 `src/test/java/SauceNaoDemo.java` 的 main，通过程序参数或控制台输入图片直链。密钥优先读取环境变量 `SAUCENAO_API_KEY`，未设置时在控制台输入。打印整理后的相似度、作品、作者和来源链接，不需要启动机器人。

验证类代理优先使用第二个程序参数，其次读取 `HTTPS_PROXY`、`HTTP_PROXY`，均未配置则使用 JVM 默认代理选择器。运行时会打印实际代理设置。IDE 的程序参数示例：

```text
https://d.srzki.com/f/ZgcV/124130706_p0.png http://127.0.0.1:10808
```

也可在 IDE 运行配置中设置 `HTTPS_PROXY=http://127.0.0.1:10808`，然后在控制台输入图片 URL。支持 HTTP、SOCKS5 代理，以及第二参数 `direct` 显式直连。Windows 系统代理开关并不保证默认 JVM 会使用系统代理，因此需要以上显式配置。Web 插件仍沿用自身代理配置，验证类的环境变量不覆盖插件设置。

插件：在 `config/AIChat/Plugins/Web/config.json` 中添加 `"EnableSauceNAO": true` 和 `"SauceNAOApiKey": "你的密钥"`，或使用环境变量 `SAUCENAO_API_KEY`。配置字段优先，未配置密钥时不注册工具；重启插件后注册 `SauceNAOSearch`，参数为 `URL`。沿用 Web 的 `EnableProxy` / `HttpProxyURI`。

`response.header.status`：0 正常、正数为部分索引失败；负数抛出 IOException。剩余配额在 `short_remaining` / `long_remaining`。`response.results.get(i).header.similarity` 为相似度，`.data` 提供标题、作者、来源链接等字段。各索引不适用的字段为 null；`creator`、`material`、`characters` 将字符串或数组统一解析成 `List<String>`。空列表为无匹配。每次一次查询，不自动重试，来源候选不能单独证明角色身份。工具通过响应对象的 `toString()` 输出摘要。

## 工具注册与聊天附件

Web 插件在 `onEnable()` 中先注册工具，随后 AIChat 创建 assistant 时才从全局注册表加载会话选择的工具。注册名和函数名均为 `SauceNAOSearch`。没有密钥、禁用工具或限流配置非正数时不注册，并对缺少密钥/无效限流配置记录提示。变更配置后重启应用，不支持热重载。

在目标会话配置原有 `Tools` 列表中追加以下工具；保留其他工具及配置：

```json
{
  "Tools": ["SauceNAOSearch"],
  "nativeImage": true
}
```

聊天图片被 ChatContext 标为 `<quoteId:N>`。模型应调用 `{"URL":"<quote:N>"}`（两者语法不同），ChatContext 在 callback 执行前递归替换 JSON 字符串中的引用，工具接收真实 URL。不存在/已移出上下文/超范围的引用在 callback 前失败。也支持 `{"URL":"https://example.com/image.png"}`，不得编造链接。注册不会自动启用所有会话，`nativeImage=false` 时附件不会以此方式进入模型上下文。

## 缓存和配额保护

注册的工具共用一个 SearchService，跨会话共享进程内状态；重启后清空。以完整 URL 为键（同图不同 URL 分别查询），成功响应含空结果缓存 10 分钟，最多 256 条，按最近使用顺序淘汰。部分索引失败和异常不缓存。同 URL 正在执行的请求合并，其他 URL 限流时立即返回，不排队。缓存提示中的剩余配额是原查询时快照。

Web 配置可添加 `"SauceNAOShortLimit": 4`、`"SauceNAOLongLimit": 100`，分别限制滚动 30 秒/24 小时窗口中的新请求，必须为正数。这是本地保护默认值，实际账户额度以服务端为准。请求发出前计数，失败也计数，缓存命中和合并请求不计数。服务端剩余配额为零时，自响应时刻暂停对应窗口；HTTP 429 至少暂停 30 秒，负数 API 状态同时提供长期额度耗尽信息时暂停 24 小时。不自动重试。

错误结果区分参数错误、限流与查询失败，不把异常详情或密钥传给模型。保持连接 15 秒、总请求 45 秒超时，不增加裁剪或角色核验。

离线验证另运行 `SauceNaoServiceTest` 和 `SauceNaoIntegrationTest` 的 main，覆盖缓存、限流、并发合并、注册及图片引用。以上测试不会请求 SauceNAO。真实验收需要 Web 插件已加载、有效密钥、目标会话配置、模型及可访问的图片 URL，发送图片后要求搜图，确认 `SauceNAOSearch` 被调用且结果回传。

离线检查：运行 `src/test/java/SauceNaoParseTest.java` 的 main，验证多索引字段解析、空结果与部分失败的展示，不调用 API。

运行 `src/test/java/SauceNaoProxyTest.java` 的 main 验证代理地址解析。若日志在 `connectTls/startHandshake` 报 `Connection reset`，说明连接在收到 HTTP 响应前失败，应先检查打印的代理设置。HTTP 403 且服务端带 `cf-mitigated: challenge` 时会明确报告 Cloudflare 验证拦截，不能当成 API Key 无效。
