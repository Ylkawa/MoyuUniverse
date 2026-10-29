# leading 与 topic 生命周期

会话统一执行“新消息批次 → leading → 按决策调用主助手”。独立的 `constructMemory`、memory 输出 Schema 和解析入口已删除。

## 配置与调度

- leading 始终使用全局 `Annotator.provider`、`Annotator.model`，思考选项使用 `Annotator.enable_thinking`。记忆检索沿用 `Annotator.embeddingProvider`，知识检索沿用 KnowledgeStore 配置。
- leading 检索命中的知识、记忆以去重后的参考条目追加到会话队列。`%LEADING%` 引用这些条目，本批状态与编号放在固定尾部；不配置占位符也照常注入和执行 leading。
- 首次仍按会话 `Trigger=every`、非空 `Keyword`、直接 @/回复主助手或 `driveMessage` 激活 topic。激活后所有真人消息参加五秒静默批次；明确呼叫跳过等待，但成功的 leading 仍可判断无需回复。
- topic 批次固定等待五秒；旧 `ToolLoop.AsyncDebounceMillis` 字段保留配置兼容，不再用于 topic 排期。
- 连续二十条真人消息经过 leading 后均无需回复，就处理剩余消息并退出。`should_reply=true` 重置整个批次的退出计数（包括仅回应工具的批次）；`addressed_message_ids` 不重置计数，无需回复的致谢也计入。三十分钟空闲退出仍从激活或最后一次与主助手对话的真人消息起计时。未决工具任务在完成或超时续答前保护 topic。

## 输出与证据

leading 输出包含 `add`、`update`、`delete`、`memory_queries`、`knowledge_queries`、`should_reply`、`addressed_message_ids`。后者只能引用本批真人消息的原始消息 ID，与正文和回复目标一致，用于消息归属与空闲时间更新。`Leading complete` 日志包含会话 ID 及这些消息发送者去重后的 `addressedLocationIds`，无需回复时也会记录。仅回应本批工具输入时允许 `should_reply=true` 且 `addressed_message_ids=[]`。

相关类型位于 `com.nekoyu.Universe.AIChat.leading` 子包：`LeadingPlan` 负责解析校验，`LeadingResult` 表达推理结果，`MemoryStore` 定义持久化边界，`PendingMemoryWrite` 保存失败重试意图。解析测试在对应的 `leading` 测试目录，会话集成测试留在 AIChat 根测试包。

`LeadingResult` 返回回复决策、消息归属及已完成的双库检索注解。普通旁聊也维护记忆，但不进行 RAG；驱动输入保留强制处理语义。工具进度、最终结果及超时均作为新工具输入交给 leading 决定是否发言，不在入队时强制续答。后台启动、检索或整理状态、重复进度和待核查猜测通常保持静默；能解决尚未完成请求的完整结果、关键失败或需要用户介入的反馈才考虑回复，无关、过时或已取消任务的结果可以忽略。静默处理的已完成调用也释放未决保护。助手、driver、tool 内容不能作为真人记忆证据，也不增加真人消息计数；工具或驱动导致回复时按回复决策重置计数。

待处理证据有独立引用，不受实时上下文 token 裁剪影响。旧上下文只供解释与去重；持久化失败保留已校验操作，新增重试复用 UUID，成功操作不重复执行。后续新证据更正同一条记忆时替换未确认的旧意图。

leading 失败或 JSON 无效时，明确呼叫兜底回复，普通消息静默并保留证据；单纯工具反馈不兜底播报。此前已批准且正在等待工具的回复意图继续保留。退出最多补处理一次，仍失败则记录未完成状态并关闭，不无限重试。运行中关闭 topic 后返回的 leading 结果不再执行写入或回复；已经提交给存储的请求不保证能撤销。

动态帖文保留原触发、Memory 启用条件和点赞行为，复用同一 leading 核心；不建立活跃聊天、不执行检索、不自动回复。

## 只追加上下文与截断游标

`ChatContext` 的事件队列按发生顺序追加：固定规则、对话、skills、候选及检索记忆、检索知识都保留原位置与原内容。请求由事件队列的可见视图再接固定尾部组成；尾部不写回历史。leading 的固定规则与 Schema 仍是其独立前缀，主助手专属 PromptFirst 不传给 leading。

- 记忆 UUID 在 topic 内使用稳定编号，重复读取同一版本不重复追加；修改追加新版本，删除追加撤回记录。leading 末尾仅列本批允许操作的编号、模式、消息和证据目录，不再每轮重建记忆正文。
- 知识按文档、事实及来源分别去重追加；相似度或排名变化不产生新版本。来源、置信度和有效期随条目保留。旧知识属于历史参考，不代表当前仍然有效；来源冲突仍须核查。
- 主助手 PromptFirst 和 skills 中的占位符变成稳定的变量引用，变量当前值放入尾部；`%MEMORY%` 和 `%LEADING%` 指向队列中的参考条目。PromptLast 继续在固定尾部逐次渲染。技能停用追加状态通知，不删除旧提示词。
- 同步 Function Calling 的 assistant/tool 结果形成完整块。工具执行中激活的 skills 或查询到的记忆，等该组 tool 结果收齐再追加。异步进度、最终结果和超时作为带原调用 ID、明确标记非真人的 user 输入追加，不插回已经结束的调用块；leading 仍按 tool 来源处理。

`getHistoryStart()` 返回原队列的零基截断位置，初始为 0。生成请求时按输入估算预算（沿用 `maxTokens / 2`）自动前移，也可通过 `setHistoryStart(index)` 指定。起点前只保留规则、skills、知识、记忆及本批输入/未决调用等受保护消息，起点后全部保留，顺序不变；普通对话包括 user、assistant 和已完成 tool 块。工具块不能从中间截断。

例如游标为 100 时，第 100 条及以后的消息全部进入请求，前 100 条只保留上述常驻/受保护内容。队列本体不物理删除，游标不会自动回退。DEBUG 日志 `LLM context` 包含 `historyStart`、队列长度和请求消息数。

预算包含常驻条目与尾部，但不会为满足预算删除这些条目；若常驻内容本身已超预算，仍保留常驻内容及最新消息块，因此这是软预算。原队列随 topic 生命周期保留，topic 关闭后释放。工具定义增减、游标前移和服务端缓存淘汰仍可能打断缓存；本实现保证正常追加时已有消息前缀稳定，不承诺供应商实际命中率。
