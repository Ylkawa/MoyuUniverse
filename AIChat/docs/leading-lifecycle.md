# leading 与 topic 生命周期

会话统一执行“新消息批次 → leading → 按决策调用主助手”。独立的 `constructMemory`、memory 输出 Schema 和解析入口已删除。

## 配置与调度

- leading 始终使用全局 `Annotator.provider`、`Annotator.model`，思考选项使用 `Annotator.enable_thinking`。记忆检索沿用 `Annotator.embeddingProvider`，知识检索沿用 KnowledgeStore 配置。
- `%LEADING%` 仅决定检索注解在主助手提示词中的展示位置；未使用占位符也会注入本批注解，不会关闭 leading。
- 首次仍按会话 `Trigger=every`、非空 `Keyword`、直接 @/回复主助手或 `driveMessage` 激活 topic。激活后所有真人消息参加五秒静默批次；明确呼叫跳过等待，但成功的 leading 仍可判断无需回复。
- topic 批次固定等待五秒；旧 `ToolLoop.AsyncDebounceMillis` 字段保留配置兼容，不再用于 topic 排期。
- 与主助手对话时重置退出计数，包括无需回复的致谢。自激活或最后一次对话起十分钟、连续二十条未与主助手对话的真人消息，任一达到就处理剩余消息并退出。未决工具任务在完成或超时续答前保护 topic。

## 输出与证据

leading 输出包含 `add`、`update`、`delete`、`memory_queries`、`knowledge_queries`、`should_reply`、`addressed_message_ids`。后者只能引用本批真人消息的内部编号，与通道原始消息 ID 分开；同批消息顺序用于精确更新退出计数。

相关类型位于 `com.nekoyu.Universe.AIChat.leading` 子包：`LeadingPlan` 负责解析校验，`LeadingResult` 表达推理结果，`MemoryStore` 定义持久化边界，`PendingMemoryWrite` 保存失败重试意图。解析测试在对应的 `leading` 测试目录，会话集成测试留在 AIChat 根测试包。

`LeadingResult` 返回回复决策、消息归属及已完成的双库检索注解。普通旁聊也维护记忆，但不进行 RAG；驱动输入、工具完成及超时续答保留主助手任务处理入口。助手、driver、tool 内容不能作为真人记忆证据，也不重置真人对话的退出计数。

待处理证据有独立引用，不受实时上下文 token 裁剪影响。旧上下文只供解释与去重；持久化失败保留已校验操作，新增重试复用 UUID，成功操作不重复执行。后续新证据更正同一条记忆时替换未确认的旧意图。

leading 失败或 JSON 无效时，明确呼叫兜底回复，普通消息静默并保留证据。退出最多补处理一次，仍失败则记录未完成状态并关闭，不无限重试。运行中关闭 topic 后返回的 leading 结果不再执行写入或回复；已经提交给存储的请求不保证能撤销。

动态帖文保留原触发、Memory 启用条件和点赞行为，复用同一 leading 核心；不建立活跃聊天、不执行检索、不自动回复。

## 验证

`UnifiedLeadingTest` 使用确定性时钟、模型/存储替身及真实会话入口，覆盖调度、路由、生命周期、记忆重试、双库检索注入和旧入口回归。运行：

```text
mvn -o -pl AIChat,AIChat-Web,AIChat-NetTools,AIChat-ImageEdit -am -Dtest=UnifiedLeadingTest -Dsurefire.failIfNoSpecifiedTests=false test
```

这些测试不连接真实模型、Qdrant、MySQL 或生产消息通道；真实模型的对话对象判断及真实存储验收需要运行配置和服务。
