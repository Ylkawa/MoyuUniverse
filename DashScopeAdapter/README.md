# DashScopeAdapter

Java 17、Maven、项目已有 OkHttp/Gson。源码与构建均不再使用 Kotlin。
插件按 OpenAIAdapter 的方式从 `config/DashScopeAdapter/*.json` 读取配置，将 `DashScopeChannel` 注册到 `Universe.Providers`。
通道继承 `LLMProvider` 并实现公共 `Embedding` 接口；AIChat 和独立预建工具通过 ProviderId 调用。

## 配置与部署

将 `examples/graph.json` 复制到 `config/DashScopeAdapter/graph.json`，其地址与当前正式图标包一致。
配置格式如下；使用其他业务空间或模型时必须重新确认向量空间及预建包：

```json
{
  "ProviderId": "DashScope",
  "APIKey": "填写你的 DashScope 密钥",
  "BaseUrl": "https://你的业务空间.cn-beijing.maas.aliyuncs.com/api/v1",
  "DefaultEmbeddingModel": "tongyi-embedding-vision-plus-2026-03-06",
  "EmbeddingSpaceVersion": "v1",
  "EmbeddingResolutionLevel": 1
}
```

BaseUrl 是百炼**原生 `/api/v1`** 地址；多模态向量不通过 OpenAI 兼容路径调用。
APIKey 直接读取上述 UTF-8 JSON 配置文件中的字符串；不读取环境变量，也不读取独立密钥文件。
修改配置后重启进程生效；旧 APIKeyEnv 字段不再生效，应替换为 APIKey。
注册和空间查询不要求密钥，计算未缓存输入时才检查密钥。
provider 未注册、模型空间不一致或模型失败时，graph 保留正文并省略对应图标。

在 `laws/` 部署 DashScopeAdapter JAR，和本轮更新后的 Universe、AIChat 一起发布。
JAR 包含 law.yml，IDEA 的 `DashScopeAdapter:jar` artifact 已配置；Maven 使用正常 resources 复制。
Adapter 依赖 Universe 提供的 OkHttp/Gson 等运行库，不把 Universe 类复制进插件。

完整发布步骤、配置表和回滚操作见 [部署操作文档](../docs/deployment-operations-2026-10-14.md)。

## 公共接口

```java
LLMProvider provider = (LLMProvider) Universe.Providers.get("DashScope");
Embedding embedding = (Embedding) provider;
EmbeddingRequest request = new EmbeddingRequest();
request.model = "tongyi-embedding-vision-plus-2026-03-06";
request.dimension = 1152;
request.resolutionLevel = 1;
request.spaceVersion = "v1";
request.inputs.add(List.of(new TextPiece("笔记本电脑")));
request.inputs.add(List.of(new ImageBytesPiece("image/png", pngBytes)));
EmbeddingResponse response = embedding.embedding(request);
```

每个 inputs 项返回一个向量，支持现有 TextPiece/ImageUrlPiece，以及新增的内存图片 ImageBytesPiece。
图片字节由 Adapter 编码为 data URI，不创建消息文件或调用图片托管；ImageUrlPiece 支持 HTTP URL 或合法图片 data URI。
原有 `message: List<MFChain>` 也支持文字和 ImageField，通过真实图片 URL 发送，不能与 inputs 同时使用。
本次实现输入范围为文字、单张图片、图文融合；不支持的输入会明确报错。

独立模式是默认值。图文融合时设置 `enableFusion=true`，把同一输入项写成
`List.of(new TextPiece("说明"), new ImageBytesPiece("image/png", pngBytes))`。
快照模型将该项转换为同一个 content 对象；Qwen 则按项请求并发送 enable_fusion=true，避免整批被融合成一个向量。
支持 `tongyi-embedding-vision-plus-2026-03-06`、`tongyi-embedding-vision-flash-2026-03-06` 和 `qwen3-vl-embedding`。
[官方协议](https://help.aliyun.com/zh/model-studio/multimodal-embedding-api-reference)

结果统一为 EmbeddingResponse：索引使用跨批次全局顺序，usage.input_tokens 映射到 prompt_tokens/total_tokens。
缺项、重复/非法索引、类型错误、非数值、非有限值、零向量及维度错误均报错。
整次 HTTP 调用超时 60 秒，响应上限 2 MiB；不会把厂商错误正文或密钥写入异常。
embeddingSpace(request) 返回模型、实际原生 endpoint、维度、融合模式、分辨率和显式空间版本组成的身份，不发起网络请求。
显式请求的 spaceVersion 必须与 Provider 配置一致；云端语义空间变化时同步更新 Adapter、Graph 和预建参数，避免用旧版本标签读取新空间。
独立模式的身份与既有正式包和 data 缓存保持一致，迁移不需要重新付费计算。

原 Kotlin 模块的聊天和文生图功能均未完成；Java 版保留类型与配置，调用这些入口会明确报错。
当前插件适用于 Embedding 能力；聊天仍使用原有 OpenAIAdapter。

## 构建与验证

```powershell
mvn -B -pl DashScopeAdapter,AIChat -am package
```

协议测试使用本机 HTTP fixture，验证文字、图片、两种融合格式、批次/顺序/usage、非法结果、大小/总超时和注册/停止生命周期。
AIChat 测试验证公共 Provider 调用、注册延迟、缓存与空间变化；真实图标包另通过最终 JAR 验收。
