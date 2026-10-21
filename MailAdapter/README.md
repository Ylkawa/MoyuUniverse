# MailAdapter 消息 ID

IMAP 邮件在转换为 `MCMessage`、交给消息通道管理器之前，按
`(channelId, folder.getFullName(), UIDVALIDITY, UID)` 查询或持久化分配 `long` ID。
同一身份重试或重启后复用原 ID；不同文件夹、UIDVALIDITY 或 UID 分配新 ID。
邮件头 `Message-ID` 仍保留在 `metainfo.messageId`，原始定位信息分别保存在
`imapFolder`、`uidValidity`、`imapUid`。

每个通道使用自己的编号空间，完整定位仍需会话/通道标识。通道 ID 必须稳定，
不要将原通道配置改指另一个账号或 IMAP 服务器。SMTP 发信也从此编号空间预先分配，
发送失败可能留下空号。现有发送接口返回 `int`，超出其范围时发信明确失败，
收信仍可继续使用 `long`。

映射保存在 UID 检查点旁的 `<checkpoint>.message-ids` 文件，
`<checkpoint>.message-ids.lock` 用于互斥。请将映射与原检查点一并保留、备份；
删除映射会失去身份对应关系，可能重新使用旧编号。升级前已经存在的历史消息不会重编号，
旧版直接使用 UID 的历史记录应在切换时清理或另行迁移。

映射分配在广播前落盘，确保重试身份稳定；它本身不提供下游处理的恰好一次保证。
实现使用文件锁和临时文件替换，每次分配读取并写入完整映射，适合当前小规模邮箱；
大量历史邮件时应迁移到带唯一约束和索引的持久化数据库。
