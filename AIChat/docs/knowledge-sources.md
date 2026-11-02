# 知识事实与来源数组

2026-11-02 主体与指代专项补强及后续 153 条存量修复，见 [专项记录](knowledge-subject-precision.md)。下方结构迁移记录中的逐条核验对应迁移提交时的快照；已发生本次修订后，不能再直接用旧迁移备份恢复来覆盖新数据。

`knowledge_document` 按 URL 保存来源文档；`knowledge_fact` 每行保存一条事实。一个文档可以支持多个事实，一个事实可以由多个文档支持。来源直接保存在事实行的 `sources` JSONB 数组中，不另建关联表。

## 事实表

| 列 | 类型 | 含义 |
| --- | --- | --- |
| `id` | `uuid` | 事实主键 |
| `question` | `text` | 标准问题，与检索向量对应 |
| `conclusion` | `text` | 代表来源支持的结论 |
| `embedding` | `vector(dimension)` | 标准问题的向量，维度沿用配置 |
| `confidence` | `real` | 代表来源对结论的支持程度，0 到 1 |
| `review_due_at` | `timestamptz` | 来源复查时间的最大值，用于初步筛选 |
| `created_at` | `timestamptz` | 首次创建时间 |
| `updated_at` | `timestamptz` | 最近一次来源更新的时间 |
| `sources` | `jsonb` | 非空来源对象数组 |

每个来源对象只包含：

```json
{
  "document_id": 123,
  "question": "来源资料对应的问题",
  "conclusion": "该来源实际支持的完整结论",
  "confidence": 0.95,
  "review_due_at": "2027-01-01T00:00:00Z"
}
```

URL、标题、正文只存于文档表，通过 `document_id` 读取。检索结果按需补齐 URL 和标题；同次检索中相同文档只加载一次来源信息。JSON 数组中的文档 ID 没有普通外键约束，应用先保存文档再关联事实，读取缺失文档时明确报错。

不再保存 `payload`、顶层 `document_id`、`document_ids`、`source_urls` 或毫秒时间戳 `ttl`。向量保留 HNSW 余弦索引；`review_due_at` 使用 B-tree；`sources` 使用 `jsonb_path_ops` GIN，按 `sources @> '[{"document_id":123}]'` 查询文档关联。

复查时间不是事实成立的终止时间。保持已有检索规则：至少有一个未到复查期限的来源，事实才参与检索；召回后逐来源过滤到期项，不能用一个来源的新期限延长其他来源的期限。历史条目保留在数据库中。新增来源不自动提高置信度，也不意味着独立交叉验证。

## 入库行为

入库先提取问答并计算向量，取最多 10 个相似候选，阈值 0.6。文本完全相同或模型确认完整事实等价才合并，否则单独保存。合并保留原 UUID、创建时间、标准问题与向量，各来源保留自己的原始问答。同一文档在一个事实中最多保留一份来源记录。

同一 `KnowledgeStore` 的同步入库与后台队列共用串行写入锁。新事实全部写成功后，才移除该文档已撤回的旧支持；其他来源继续保留。写入失败不清理旧支持，允许重试收敛。不同进程间没有分布式写锁，仍应保持单写入实例。

完整文档使用全量重建；研究摘要增量合并只移除明确冲突的本来源旧陈述，保留未讨论的事实。模型判断失败不能默认允许删除。成功后文档归档保存该 URL 仍保留的事实摘要，并明确标注不是网页全文；研究原始证据片段另存于研究归档。

文档归档成功不等于索引成功。SQL、结构化提取、向量失败或向量数量不符会向调用者抛错；后台队列记录失败，后续学习同一来源可以重试。内存队列不提供进程退出后的持久重放。模型显式返回 `{"items":[]}` 是保留旧事实的成功空操作。

## 一次性迁移

服务端保持停机，设置 `PG_URL`、`PG_USER`、`PG_PASSWORD` 环境变量，然后运行：

```powershell
./scripts/migrate-knowledge-facts.ps1 -Mode migrate -Backup tmp/knowledge-facts-20261102 -LocalRepository C:/Users/imylk/.m2/repository
./scripts/migrate-knowledge-facts.ps1 -Mode verify -Backup tmp/knowledge-facts-20261102 -LocalRepository C:/Users/imylk/.m2/repository
```

`migrate` 只接受旧的 `id / embedding / payload` 表；正式应用只读取新结构，没有双读、双写、旧字段回退或启动时转换逻辑。新建空库时由应用创建新表。现有表必须先迁移再启动新版本，不能用旧版程序写入新表。

工具在事务内锁定事实和文档，保存完整事实（含原向量、全部 payload）及文档 JSONL 备份并附 SHA-256，再转换业务字段与来源对象，删除旧列及其索引。UUID、所有向量分量、问答、float32 置信度、每个来源文档 ID、复查时间和创建/更新时间逐条核对；文档所有列核对未改变后才提交。任何转换、关联或校验失败都回滚。历史清理、审核、证据标记等额外字段完整保存在原始备份中，不进入新表；本次不重新提取或评判事实。

只有在迁移后事实和文档均未改变的情况下，才能用同一备份执行恢复：

```powershell
./scripts/migrate-knowledge-facts.ps1 -Mode restore -Backup tmp/knowledge-facts-20261102 -LocalRepository C:/Users/imylk/.m2/repository
```

恢复会先完整验证当前新表仍等于迁移结果，再在事务中还原原 payload 和旧表结构。已启动新服务并产生写入时，工具拒绝用旧备份覆盖。

`scripts/MigrateVectors.java` 是更早的 Qdrant 导入工具，其事实表目标结构已过时，不能再对当前事实表运行导入或恢复；本次恢复应使用以上工具。记忆表和旧知识归档不属于此次结构调整。

## 验证范围

事实合并、重建、部分失败、重试、独立复查时间和增量冲突规则由离线模型边界测试验证。真实 PostgreSQL 测试使用随机 schema，覆盖新表列、约束、索引查询、问答/时间/来源数组读写、向量保持和文档 URL/标题解析；不会调用真实模型。数据库迁移另以正式数据完整副本演练，包括原样恢复和 DDL 中途失败后的事务回滚。

新程序部署和真实模型、消息、机器人链路验收由使用者执行。

## 2026-11-02 执行记录

- 已迁移 `10.8.31.37:5432/moyu_universe` 的 `aichat.knowledge_fact`，并通过独立连接再次逐条核验。
- 767 条事实、830 个来源关联、1024 维原向量全部保留；379 份来源文档所有列保持不变。
- `payload` 列及旧 GIN 索引已删除；现有 HNSW 索引保留，新增复查时间 B-tree 和来源数组 GIN 索引。
- 无悬空来源、无聚合复查时间不一致、无遗留测试 schema。
- 完整原始备份：`tmp/knowledge-facts-20261102/facts.jsonl`、`documents.jsonl`，分别附 `.sha256`。备份位于 Git 忽略目录，需要随数据库备份另存。
- 已演练完整数据副本迁移、原样恢复、无效来源拒绝和 DDL 中途失败回滚后重试；隔离 schema 已回滚清理。
- 全发行构建 `mvn -o -Dmaven.repo.local=C:/Users/imylk/.m2/repository -pl distribution -am package` 通过，共 242 项测试，失败、错误、跳过均为 0，包括真实 PostgreSQL 测试。
- 新工件：`AIChat/target/AIChat-2.0-Phototaxis.jar`。本次没有新增运行依赖。服务器部署和启动尚未执行，由使用者完成。
