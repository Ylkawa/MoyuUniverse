# SauceNAO 图片来源查询

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
