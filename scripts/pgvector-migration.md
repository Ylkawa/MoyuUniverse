# PostgreSQL 向量存储迁移

记忆、知识事实、研究主题向量统一使用 PostgreSQL + pgvector，复用 `SQLConfig` 的 Hikari 连接池。运行依赖不再包含 Qdrant 客户端及其 gRPC/Protobuf 依赖链。服务端停机和部署由使用者操作。

## 2026-10-26 执行结果

- 目标：`10.8.31.37:5432/moyu_universe`，PostgreSQL 18.6，已启用 pgvector 0.8.7。
- 记忆 1457 条、知识事实 691 条、旧 EKB 329 条、研究向量 6 条，共 2483 条，已全部提交至下表目标。
- 原 UUID、1024 维向量每个 float32 分量及全部 payload 已逐条核对；知识文档引用有效，原 SQL 文档与研究报告内容未改变。
- 删除前再次完整读取源数据，确认与备份一致，再删除四个明确列出的集合；随后通过集合清单确认它们均已不存在。
- 备份：`tmp/pgvector-migration-20261026/`，五份完整快照均附 SHA-256；执行日志位于 `tmp/pgvector-migration-copy.log`、`tmp/pgvector-migration-verify.log`、`tmp/pgvector-migration-delete.log`。
- 全项目 `mvn -o -pl distribution -am package` 通过，共 230 项测试，失败、错误、跳过均为 0，包含 8 项真实 PostgreSQL 隔离测试。真实模型、消息与生产进程尚未运行。
- 发行目录：`target/deployment-pgvector-20261026/`，13 个业务 JAR、89 个运行库；运行库中已无 Qdrant、gRPC 和 Protobuf。法则/插件类加载及 2143 个图标资源校验通过。部署由使用者执行。

以下迁移命令作为复现及恢复说明；本次数据迁移和集合删除已完成，不需要再次运行 `copy` 或 `delete`。

## 部署配置

沿用 `SQLConfig`，合并以下字段，保留现有知识模型、维度、会话等配置：

```json
{
  "Memory": {
    "table": "aichat.memory",
    "embeddingProvider": "<原 Qdrant.provider 的值>",
    "dimension": 1024
  },
  "KnowledgeStore": {
    "factsTable": "aichat.knowledge_fact",
    "documentsTable": "aichat.knowledge_document"
  }
}
```

删除顶层 `Qdrant` 对象以及 `Memory`、`KnowledgeStore` 中的 `collection`、`vectorName` 字段。记忆的 `embeddingProvider` 应与旧 `Qdrant.provider` 一致，保持既有向量空间；未显式填写时使用 `Annotator.embeddingProvider`。`KnowledgeStore` 的 `embeddingProvider`、`embeddingModel`、`dimension` 保持原值。不能仅凭维度相同更换 embedding 模型。

启动时创建缺少的表和索引；已有向量表的维度与配置不符会拒绝启动。迁移已在目标数据库启用 `vector` 扩展，部署不需要另起向量服务。

| 原集合 | PostgreSQL 目标 |
| --- | --- |
| `moyu_universe_aic_memory` | `aichat.memory`，原 UUID、1024 维向量、完整 JSONB payload |
| `moyu_universe_aic_ks` | `aichat.knowledge_fact`，保留多来源、TTL、置信度和文档 ID |
| `moyu_universe_aic_ks_research` | `aichat.research_task.embedding`，与报告同一行 |
| `moyu_universe_aic_ekb` | `aichat.legacy_knowledge`，完整保留旧数据，当前业务代码不读取此归档 |

新的运行目录用 `scripts/package-deployment.ps1` 构建，按其完整 `lib` 清单替换旧依赖目录，避免继续保留旧 Qdrant 库。这里只构建工件，不操作服务器部署。

## 迁移、核验和清理

凭据只通过进程环境提供，不写入脚本、配置样例或快照：

```powershell
$env:PG_URL = 'jdbc:postgresql://10.8.31.37:5432/moyu_universe'
$env:PG_USER = 'postgres'
$env:PG_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'PostgreSQL password' -AsSecureString)).Password
$env:QDRANT_URL = 'http://10.8.31.37:6333'
$env:QDRANT_API_KEY = [Net.NetworkCredential]::new('', (Read-Host 'Qdrant API key' -AsSecureString)).Password

# 源服务停写后首次导入：新目录必须尚不存在。
./scripts/migrate-vectors.ps1 -Mode copy -Backup tmp/pgvector-migration-20261026
# 核验目标及源快照，任何源变更均拒绝。
./scripts/migrate-vectors.ps1 -Mode verify -Backup tmp/pgvector-migration-20261026
# 重新核验全部目标、原 SQL 正文、全部源数据后，才删除四个明确列出的集合。
./scripts/migrate-vectors.ps1 -Mode delete -Backup tmp/pgvector-migration-20261026
```

导入前保存四份完整 Qdrant 集合快照（含向量配置、全部向量与 payload）及原 PostgreSQL 文档/报告快照，每个文件附 SHA-256。快照目录在 Git 忽略的 `tmp/` 下，应随数据库备份另存。导入在一个 PostgreSQL 事务中完成：逐条比较 UUID、每个 float32 向量分量的位值和 JSONB payload，核验原 SQL 文档/报告未改变以及知识来源文档关联有效后提交。非空目标中的不同数据不会被覆盖。

`restore` 从经过 SHA-256 核验的备份补入 PostgreSQL，并进行同样的完整校验；它不是生产数据冲突合并工具。对研究报告，先恢复快照中的原 SQL 记录，再恢复向量；工具不会凭空生成不存在的报告行。删除后的 Qdrant 不再是运行依赖，正常恢复应还原 PostgreSQL 备份。

测试通过 `PG_URL`、`PG_USER`、`PG_PASSWORD` 启用真实 pgvector 测试，所有写入使用随机隔离 schema 并在结束时清理；不调用真实模型服务。部署后的消息、模型与机器人链路由使用者验收。
