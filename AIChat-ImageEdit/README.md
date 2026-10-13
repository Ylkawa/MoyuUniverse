# AIChat 图片编辑 Skill

插件入口为 `ImageEdit`，注册 `image_edit`（`ON_DEMAND`）Skill，包含
`ImageEdit_Crop` 和 `ImageEdit_Rotate` 两个同步工具，沿用 Amap 的
`tools(service)` + `definition()` 注册形式。

## 使用

将 `target/AIChat-ImageEdit-2.0-Phototaxis.jar` 放入 AIChat 的插件目录
`data/AIChat/Plugins/`。Universe 的对外 HTTP 地址必须能被消息客户端访问，
结果图片复用 Universe 文件挂载，通过 `/Universe/<随机编号>.png` 提供。

未配置技能白名单时会自动发现。配置了 `availableSkills` 白名单时，添加
`image_edit`；需要始终加载时加入会话的 `skills`。
聊天附件引用需要会话启用 `nativeImage`，通过 `<ref id="N"/>` 传入工具，
引用替换由已有 ChatContext 处理。也支持 HTTP/HTTPS 图片直链。

裁切示例（保留原图正中间一半，所有位置和尺寸均为原图百分比）：

```json
{"source_image":"<ref id=\"1\"/>","top_left_position":{"x":25,"y":25},"width":50,"height":50}
```

旋转示例（顺时针90度，负数为逆时针）：

```json
{"source_image":"<ref id=\"1\"/>","angle":90}
```

结果为 PNG，保持透明度；任意角度旋转扩展画布避免截断。
工具结果为文字，包含 URL、实际像素尺寸和 `<image_edit id="编号"/>` 发送标记。
助手在最终回复中原样使用该标记，插件的 MarkDecoupler 转换为 ImageField，
由原有消息通道发送图片。连续编辑可使用上一步返回的 URL。

输入/输出文件最大20MiB、1600万像素、单边8192像素。动画只处理首帧，
不支持 SVG 等 ImageIO 不可读取的格式。结果保存到 `cache/image-edit/`，
通过 `ImageField(File)` 交给已有 `CachedFile` 引用计数和 `FileField` Cleaner 管理。
工具结果及会话消息持有图片对象；发送标记索引仅持有弱引用。
最后一个持有该文件的字段对象被回收后，Cleaner 释放引用并删除文件及 HTTP 挂载。
没有固定有效期或数量淘汰，也不在插件停用时提前删除仍被会话持有的图片。
移除聊天记录只是解除持有，实际清理时机由 GC/Cleaner 决定。
整个 cache 目录仍由 Universe 原有的启动及关闭清理处理。

## 离线验证

`ImageEditTest` 是直接运行的 main 测试，与现有 MapSkillIntegrationTest 一致。
测试使用生成的像素图和本地 HTTP fixture，覆盖裁切/旋转、参数边界、
图片下载/解码、Skill 激活、图片引用工具循环、图片标记与缓存生命周期，
不会调用真实模型或 QQ。
发送测试经过真实 `AIChat.replyTurn()`、流式片段拼接、`decoupleMark()`、
`MCMessage.reply()` 和 `MessageChannelManager`，终点为本地捕获通道。
覆盖图片标记跨片段及逐字符输出、只发送一次图片、发送 URL 可下载 PNG、
会话与发送历史保留图片对象；真实 OneBot/QQ 网络发送仍需联调。

在仓库根目录离线编译，然后生成本地依赖 classpath：

```powershell
mvn.cmd --% -o -Dmaven.repo.local=C:\Users\imylk\.m2\repository -pl AIChat-ImageEdit -am -DskipTests package
mvn.cmd --% -o -Dmaven.repo.local=C:\Users\imylk\.m2\repository -pl AIChat-ImageEdit dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
$testClasspath = 'AIChat-ImageEdit/target/classes;AIChat-ImageEdit/target/test-classes;Universe/target/classes;AIChat/target/classes;' + (Get-Content AIChat-ImageEdit/target/test-classpath.txt -Raw).Trim()
java '-Djava.awt.headless=true' -cp $testClasspath com.nekoyu.universe.aichat.image_edit.ImageEditTest
```

`-DskipTests` 只控制 Maven 自动执行测试；上述 main 测试须用最后一行实际运行。
