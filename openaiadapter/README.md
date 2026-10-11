# OpenAI 兼容适配层

Provider JSON 配置示例：

```json
{
  "ProviderId": "bailian",
  "APIKey": "YOUR_API_KEY",
  "BaseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "DefaultModel": "qwen3-max",
  "Search": true
}
```

`Search` 缺省为 `false`。仅在百炼适配且本次请求的实际模型命中搜索白名单时，发送顶层 `enable_search: true`；其他情况完全省略该字段。
百炼识别支持现有 `*.aliyuncs.com` 地址及 `maas.qianwenaiapi.com`。

白名单集中维护在 `OpenAIChannel`：带数字版本的 Qwen `max/plus/flash/turbo`、相同档位的无版本别名，以及 DeepSeek `v/r` 数字版本系列；支持规则中列出的型号及日期快照后缀。
Qwen 的版本号直接接在 `qwen` 后，例如 `qwen3.5-plus`。Coder、Omni、VL、图像、向量、按参数规模命名的开源 Qwen 模型不在首批白名单中。
未来数字版本可自动匹配，但命名规则无法保证新型号实际支持搜索；服务端报错沿用原有处理，不自动重试。

仅开启基础联网搜索，不发送 `search_options`，由平台默认策略和模型判断决定是否搜索。
Responses API、网页抓取、代码解释器及搜索来源引用留待后续接入。

离线请求测试为可直接运行的 `com.nekoyu.universe.openaiadapter.BailianSearchTest`；测试通过配置加载入口和 OkHttp 拦截器检查实际 JSON，不调用真实模型 API。
