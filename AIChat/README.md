# AIChat 浮标协议

图表解析、图标匹配及 Java2D PNG 渲染直接集成于 AIChat，见 [Graph 使用说明](GRAPH.md)。
图标向量计算经注册的 LLMProvider + Embedding 调用，百炼原生协议由 [Java DashScopeAdapter](../DashScopeAdapter/README.md) 提供。
实际部署目录、配置项与回滚步骤见 [部署操作文档](../docs/deployment-operations-2026-10-14.md)。
内置 `graph` 成对浮标和同名按需技能，按 type 路由到六个渲染器，返回包含图片的 MFChain。

**图表字体采用随 AIChat 工件打包的 HarmonyOS Sans SC（Regular、Bold）。**
Copyright 2021 Huawei Device Co., Ltd.；原始字体及许可保留在
[字体资源目录](src/main/resources/fonts/harmonyos-sans/README.md)，无需安装系统中文字体。

普通回复文字保持原样，已注册的 XML 浮标转换为消息字段。
支持自闭合浮标以及显式注册的成对元素；每个处理器声明自己接受的形式。

| 用途 | 格式 |
|---|---|
| 提及目标 | `<at target="目标标识"/>` |
| 回复消息 | `<reply to="消息ID"/>` |
| 表情 | `<emoji name="表情名"/>` |
| 图片编辑结果 | `<image_edit id="工具返回的编号"/>` |
| 工具参数图片引用 | `<ref id="N"/>` |
| 图表图片 | `<graph type="compare">...</graph>` |

系统在图片后展示 `<ReferenceId=N>`，这是编号说明，不是 XML 元素。
模型在工具参数的字符串值中使用 `<ref id="N"/>`，执行前替换为当前上下文图片的 URL。
例如 JSON 参数：

```json
{"source_image":"<ref id=\"1\"/>","angle":90}
```

编号必须对应当前上下文中实际存在的图片。不存在、无效或超范围的引用会阻止工具执行。
仅替换 JSON 字符串值，键保持原样；引用浮标不用于最终回复中的图片发送。

插件通过 `registerMarkDecoupler(name, handler)` 注册，处理器接口为
`MFChain process(org.w3c.dom.Element element)`。可使用
`XmlMarks.requiredAttribute(element, name)` 验证属性，以及
`XmlMarks.marker(name, attribute, value)` 生成已经转义的浮标。
XML 属性支持单双引号、合法空白和 XML 转义，生成端统一使用双引号。

旧的两参数注册方式只接受自闭合浮标，已有处理器行为保持不变。
成对元素使用三参数注册方式：

```java
registerMarkDecoupler("custom_block", MarkDecoupler.Form.PAIRED, element -> {
    // element 保留完整的属性、文字及子节点树，交给自定义处理器。
    return new MFChain(element.getTextContent());
});
```

AIChat 内置图表支持如下格式，主 agent 明确指定 type；仅 icon 属性用于语义匹配图标。
JAR 内包含预建图标包，配置匹配的模型 endpoint 后可使用 icon；文字向量命中 data 缓存时无需密钥，
未缓存且模型不可用时省略图标继续绘制。graph 不依赖 Qdrant，详见 Graph 使用说明：

```xml
<graph type="compare">
  <line icon="laptop">笔记本：OverClocked for 400MHz frequency</line>
  <line icon="desktop">台式机：None OverClock</line>
</graph>
```

`Form.PAIRED` 只接受成对元素；`Form.BOTH` 同时接受成对元素和自闭合浮标。
也可通过 `MarkDecoupler.withForm(form, handler)` 包装已有处理器。
注册名称区分大小写，必须是不含命名空间冒号的 XML 名称，空名称或空处理器被拒绝。

分发只执行最外层元素。子元素由外层处理器解析，不会递归执行其他 decoupler；
未知外层元素及其内部浮标整体保留为文字。注释、CDATA、处理指令及声明也不执行。
处理器返回的 `MFChain` 可包含文字、图片等消息字段；空链会移除该浮标。
流式发送等待整个元素闭合后再解码，元素内部的空行不作为分段边界。
未闭合、未知、形式不符、格式错误、缺少必要属性、返回 null 或处理器异常的浮标保留原文。
DTD 和外部实体禁用。

## 迁移

旧的冒号格式不再识别，也不迁移历史存储。已有插件必须更新并重新编译
`MarkDecoupler` 接口；外部会话配置和提示词需改用上述新格式。
