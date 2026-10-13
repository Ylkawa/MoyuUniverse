# 幻月末屿 : 末屿宇宙 #
这个项目内部名词写得比较中二，所以看不懂是正常的

最近功能的工件打包、完整配置、发布验收与回滚步骤见 [部署操作文档](docs/deployment-operations-2026-10-14.md)。

## 名词对照： ##
宇宙端 Universe：主程序

宇宙法则 Law：插件

消息通道 MessageChannel：用于收发消息的实例

消息会话 MessageSession：依附于channel的会话实例

## 新建宇宙法则的方法： ##
法则主类继承 Law 类

打包 jar 时在 jar 的根目录加一个 law.yml 的清单，写明 name main version
