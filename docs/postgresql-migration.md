# MySQL 文档归档迁移至 PostgreSQL

2026-10-21：代码适配和首次数据导入已完成；生产部署、停写、最终同步和重启由使用者操作。

## 已完成的迁移

- 源：`10.8.31.37:3306/moyu_universe.ks_documents`，MySQL 9.0.1。
- 目标：`10.8.31.37:5432/moyu_universe` 的 `aichat.knowledge_document`，PostgreSQL 18.6。
- 277 条文档，id 范围 1–281，保留空号；迁移完成后 identity 下一个值为 282。
- 按 id 排序逐行核对 id、URL、url_hash、title、content、NULL 和毫秒时间，全部一致。正文未重算、未重新调用模型或 embedding。
- `created_at/updated_at` 从 Unix 毫秒转为 UTC instant 对应的 `timestamptz`；显示时区不改变 instant。
- 源库仅执行读取；历史 `memories` 表不是当前 SQL 业务路径，未迁移。Qdrant 向量和 payload 未修改；原 `document_id` 因保留 SQL id 而继续指向同一文档。
- 新字段 `content_type` 对历史及尚无类型信息的写入使用 `unspecified`；没有把历史摘要自动标注为全文。

本地完整快照：`tmp/postgresql-migration-20261021/ks_documents.snapshot`（128,619 字节）。

SHA-256：`3db179d4df674bec7212cc7cbfdb46ae39adf1c369c6e1ca1b45c36db77b4c59`。

快照是版本化长度前缀 UTF-8 格式，包含所有迁移字段，支持 NULL 和长正文；`restore` 命令校验旁边的 `.sha256` 文件，并可还原到空目标表。快照处于被 Git 忽略的 `tmp/`，请在清理工作区前另行保存。

## 部署配置

备份现有部署目录的工件和配置。在实际 `config/AIChat/config.json` 中只更新以下字段，保留其他配置：

```json
{
  "SQLConfig": {
    "url": "jdbc:postgresql://10.8.31.37:5432/moyu_universe",
    "user": "postgres",
    "password": "<填写已提供的 PostgreSQL 密码>"
  },
  "KnowledgeStore": {
    "documentsTable": "aichat.knowledge_document"
  }
}
```

上面是需要合并的字段片段，不是完整配置。尤其不要覆盖 `KnowledgeStore` 内的模型、维度、collection、vectorName 等现有字段。显式配置过旧 `documentsTable=ks_documents` 的部署必须更新该字段，仅修改 JDBC URL 不够。

运行时使用 PostgreSQL JDBC 42.7.14 和现有 Hikari。归档初始化只创建 schema/文档表，不安装 pgvector，也不创建新闻或偏好表。未来需要执行完整 `news-preferences-schema.sql` 时，必须先改成增量迁移，不能原样执行其中重复的 `CREATE TABLE knowledge_document`。

## 最终切换顺序

1. 停止旧实例或暂停全部文档归档写入，等待已开始的归档写入结束。
2. 从仓库根目录设置下方环境变量，执行 `verify`。这是切换前的实时对账，首次导入成功不代表此后 MySQL 没有新增或修改。
3. 若 `verify` 不一致且 PostgreSQL 仍未投入运行，用 `sync` 从首次快照作为 baseline 同步。成功后再执行 `verify`。
4. 构建并部署包含 PostgreSQL 驱动及本次代码适配的工件，保留现有配置和持久数据，只启用原有法则/插件。按现有部署方式确认运行时 classpath 包含 PostgreSQL JDBC；数据库最终同步与配置步骤见上下文。
5. 合并上面的配置，然后启动新实例。确认数据库和 KnowledgeStore 初始化成功，再用一次现有知识检索核对标题、URL 和正文，并通过实际归档入口新增/更新一篇测试文档。
6. 确认新文档自增 id 正常、同 URL 更新仍保留 id。现有 Qdrant collection 和模型配置保持原值。

部署完成后不再用 MySQL 与 PostgreSQL 的 `verify` 比较新增业务数据；此时新写入只进入 PostgreSQL，两库产生差异是预期行为。

### 迁移工具

需要 JDK 17+；脚本会编译独立 JDBC 工具，不启动机器人。凭据只从进程环境读取，不写入仓库配置：

```powershell
$env:MYSQL_URL = 'jdbc:mysql://10.8.31.37:3306/moyu_universe?connectTimeout=10000&socketTimeout=30000&serverTimezone=UTC'
$env:MYSQL_USER = 'moyu_universe'
$env:MYSQL_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'MySQL password' -AsSecureString)).Password
$env:PG_URL = 'jdbc:postgresql://10.8.31.37:5432/moyu_universe?connectTimeout=10&socketTimeout=30'
$env:PG_USER = 'postgres'
$env:PG_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'PostgreSQL password' -AsSecureString)).Password

# 源库只读；逐行比较两端。
./scripts/migrate-knowledge-documents.ps1 -Mode verify

# 只在旧归档已停写、新库尚未用于业务时执行。
# 文件名必须未被使用，成功后新快照可作为再次同步的 baseline。
./scripts/migrate-knowledge-documents.ps1 -Mode sync `
  -Baseline tmp/postgresql-migration-20261021/ks_documents.snapshot `
  -Backup tmp/postgresql-migration-20261021/ks_documents-final.snapshot
./scripts/migrate-knowledge-documents.ps1 -Mode verify
```

`sync` 先保存最新源快照，在目标事务内锁表并确认目标所有迁移字段仍等于 baseline，然后才按原 id 同步新增及变化的源文档。目标发生业务分叉、源删除了 baseline 文档、唯一键冲突或对账失败都会拒绝/回滚。它不会自动删除目标数据，也不会解决已经双端写入的冲突。每次成功同步后，如需再次同步，使用上次成功同步生成的新快照作为 baseline；失败生成的快照不能当作已导入的 baseline。

首次导入与空库恢复（本次已完成首次导入，无需重复）：

```powershell
# 可用环境变量切换隔离 schema/table；默认 aichat.knowledge_document。
$env:PG_DOCUMENTS_TABLE = 'restore_check.knowledge_document'
./scripts/migrate-knowledge-documents.ps1 -Mode restore `
  -Backup tmp/postgresql-migration-20261021/ks_documents.snapshot
Remove-Item Env:PG_DOCUMENTS_TABLE

# 对全新目标库首次复制：-Mode copy -Backup <新文件名>
```

`copy` 和 `restore` 都拒绝覆盖非空目标；所有导入在一个 PostgreSQL 事务内执行。源读取使用只读 repeatable-read 快照。导入后逐行对账再校正 identity 序列并提交。工具在内存中持有完整快照，适用于当前 277 条文档规模。

驱动从本地 Maven 缓存加载；迁移工具单独使用 MySQL 8.4.0 驱动，生产运行依赖已不再需要 MySQL。新环境先准备：

```powershell
mvn.cmd -N '-Dmaven.repo.local=C:\Users\imylk\.m2\repository' org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get '-Dartifact=com.mysql:mysql-connector-j:8.4.0' '-Dtransitive=false'
mvn.cmd -N '-Dmaven.repo.local=C:\Users\imylk\.m2\repository' org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get '-Dartifact=org.postgresql:postgresql:42.7.14' '-Dtransitive=false'
```

可通过脚本 `-LocalRepository` 指定其他缓存位置。Linux 可用 `javac --release 17 -encoding UTF-8 -d <classes> AIChat/src/main/java/com/nekoyu/Universe/AIChat/knowledge/DocumentArchive.java scripts/MigrateKnowledgeDocuments.java` 编译，再以 `java -cp '<classes>:<mysql-jar>:<postgresql-jar>' MigrateKnowledgeDocuments verify` 运行同一工具。

## 回退

- 新实例尚未写入时：停止新实例，恢复旧工件、MySQL 连接配置及 `KnowledgeStore.documentsTable=ks_documents`，再启动旧实例。源库未被本次迁移修改。
- 新实例已经写入时：先停写并备份 PostgreSQL，再处理新旧文档及 Qdrant 引用的差量，不能直接切回旧库并假定数据完整。工具不自动执行反向迁移。

## 验证证据与边界

- Maven 定向测试 48 项通过，失败/错误/跳过均为 0：DocumentArchivePostgresTest 3、KnowledgeIngestionTest 3、RetrievalSelectionTest 8、UnifiedLeadingTest 34。
- 真实 PostgreSQL 隔离测试验证重复 URL 的 id/created_at 不变、updated_at 更新、中文/emoji 长正文、NULL、非 UTC 会话下的 instant、非法 hash 的数据库约束，以及 SQL 错误传播。
- 独立 JDBC 工具在随机隔离 schema 中通过快照恢复、只读对账、非空拒绝覆盖、最终同步、序列 next=282 和目标分叉拒绝覆盖测试；测试 schema 已清理。
- `mvn -pl AIChat -am -DskipTests package` 成功，既有 2143 个图标资源打包校验通过。运行工件已经构建，生产部署未执行。
- 首次真实导入已逐字段核验 277 条记录；实际 Qdrant/模型检索和生产消息链路未运行，需要在使用者部署后验收。保留全部原 id 是关联完整性的 SQL 证据，不等于实际检索验收。
- Maven 日志和原始快照在 `tmp/postgresql-migration-20261021/`。已有无关工作区改动保持原状；全库 `git diff --check` 可受到已有 Librarian.java 尾空白影响，迁移所涉文件单独检查。

参考：[PostgreSQL ON CONFLICT/RETURNING](https://www.postgresql.org/docs/current/sql-insert.html)、[pgJDBC 连接配置](https://jdbc.postgresql.org/documentation/use/)、[驱动下载版本](https://jdbc.postgresql.org/download/)。
