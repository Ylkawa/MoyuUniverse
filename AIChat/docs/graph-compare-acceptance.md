# Compare 表格与图标空间身份修正验收

## 改动

- compare 改为 object 与 row/cell，对象固定为列、维度固定为行。旧 compare/line 明确拒绝并给出迁移提示。
- 维度列 140–220px，对象列等宽且至少 180px，每组最多四个对象。共享行高、重复表头、超长行同步分片，无图标时对象标题居中。
- Graph Skill 明确要求完整的对象引用；标题、正文、ID 不会自动变成 icon。测试、样例和手动验收入口同步迁移。
- DashScope 向量空间身份移除工作空间 endpoint，保留模型、维度、独立/融合模式、空间版本和分辨率。
- 旧预建包先按原始身份校验二进制头、摘要与资源，随后转换内存身份；包文件和 2143 行向量不重写、不重算。
- 旧文字缓存按原身份校验并复制到新身份目录，保留原文件和 float32 值。
- INFO 增加图标槽数量、匹配状态、跳过原因、缓存/Provider 调用记录；DEBUG 可记录 graph XML。Adapter 在真正开始 HTTP 请求时另行记录模型、输入项数和 host，不记录密钥。

## 无请求的原因

已确认旧实现把完整工作空间 endpoint 写入 revision，GraphRuntime 将其与包身份严格比较。
远端 ws-qq5d08tnnrr1l69a 工作空间与包内历史 llm-a1bz0eifucqm4izg 工作空间不同，会在 HTTP 请求前误拒绝匹配。
这属于代码的身份定义错误，远端 BaseUrl 无需改成包内历史域名。
当前缺少该次原始 XML 和匹配日志，不能唯一还原历史请求是否还同时缺少 icon 或命中了其他降级分支。

无 icon、关闭图标、包加载失败、Provider 缺失或真实模型参数不一致仍可能产生零请求；正常缓存命中也无需云端请求。
正常索引没有相似度过滤阈值，查询返回 Top 5，所以不能把无图标归因于阈值过滤。

## 验证结果

在独立目录 target/graph-compare-validation-20261014 捕获工作区源码快照，用 JDK 17 和本地 Maven 依赖完成 package：

- AIChat 117 项 JUnit、DashScopeAdapter 12 项 JUnit，共 129 项，0 失败、0 错误、0 跳过。
- 覆盖 compare 引用、旧格式拒绝、空单元格、转义、预算预检、窄画布、多对象分页、超长行内容保留和无图标居中。
- 公开 GraphRuntime 入口覆盖无 icon、配置关闭、包缺失、Provider 缺失、真实参数不一致、跨工作空间请求及缓存命中。
- 迁移测试验证旧包文件不变、旧缓存仍存在，读取无需模型请求；模型、维度、模式、版本、分辨率变化仍隔离。
- 正式 JAR 从 jar: 读取 2143 个图标及字体，完成 44 页布局验收和 8 页公开 Provider/GraphRuntime 验收，共 52 页。
- JAR 验收使用用户提供的 ws 工作空间 URL、空 APIKey 和旧文字缓存副本；成功迁移八条描述并匹配图标，HTTP 开始记录为 0。
- 未缓存描述明确记录缺少密钥，省略对应图标并保留正文；不再发生工作空间域名导致的空间误拒绝。
- 截图中的十个维度生成一页表格并完成视觉检查；内容仅用于排版复现，不作为模型评测或定价事实。
- 首次共享 target 的全量测试有五项记忆测试因缺少内部编译类失败；独立源码快照重新完整编译后全部通过。

截图复现预览：
[compare-models-1.png](../../target/graph-compare-validation-20261014/jar-acceptance/previews/compare-models-1.png)。
固定演示图标的布局预览不作为生产语义匹配证据；生产检索证据来自上述 JAR 和缓存验收。

## 产物与线上边界

- [AIChat JAR](../../target/graph-compare-validation-20261014/AIChat/target/AIChat-2.0-Phototaxis.jar)，SHA-256：
  AD70F777917F834E09D93F64FBF2C65ED3E4B5903CB74614667BB7E3651C741B。
- [DashScopeAdapter JAR](../../target/graph-compare-validation-20261014/DashScopeAdapter/target/DashScopeAdapter-2.0-Phototaxis.jar)，SHA-256：
  9814EA4315258336EA07E098A835E0A88FC40F2927320B782AC84C3BD685FC34。
- [构建日志](../../target/graph-compare-validation-20261014/build.log)、
  [JAR 验收日志](../../target/graph-compare-validation-20261014/jar-acceptance/jar-acceptance.log)、
  [公开 Provider 验收日志](../../target/graph-compare-validation-20261014/jar-acceptance/provider-jar.log)。

两份 JAR 需要同步更新；现有工作空间 BaseUrl 保持不变，旧图标包和缓存无需删除。
产物包含源码快照时已有的未提交改动，尚未部署远端。
没有有效远端凭据及聊天模型配置，本次没有完成真实 LLM 生成 XML 或阿里云新增向量调用；HTTP 协议测试使用本机服务。
已有会话若已加载旧 Graph Skill 提示词，应卸载后重新加载该 Skill，让新协议提示词进入上下文。
