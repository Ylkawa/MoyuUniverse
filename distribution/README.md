# 统一外部依赖部署

当前推荐：Maven 构建业务普通 JAR，所有运行依赖统一放在 `lib/`。不再将第三方依赖解压合并进 Universe 或法则/插件 JAR。

```text
Universe.jar
lib/                         # Maven 统一解析的依赖；包括辅助模块 AmapAPI
laws/                        # AIChat、各 Adapter 及其他法则，共 9 个
data/AIChat/Plugins/          # Web、ImageEdit、NetTools
start.cmd
start.sh
release.json                 # 工件、大小和 SHA-256
dependency-tree.txt
```

`config/`、持久 `data/`、Config.properties、UniverseChannel.properties 等沿用现有部署。构建目录没有生产凭据，不要用空构建目录覆盖现有数据。所有法则都会被加载器扫描，首次切换时只部署你原先启用的法则/插件；交付目录含全部可用模块，不代表必须把所有 Adapter 都启用。

## 构建

在仓库根目录执行（构建机需 JDK 17+、Maven、PowerShell）：

```powershell
./scripts/package-deployment.ps1 -ReleaseId lib-layout-001 `
  -LocalRepository C:/Users/imylk/.m2/repository
```

生成 `target/deployment-lib-layout-001/`。脚本构建并 install 全部模块（跳过执行测试，但编译测试），然后从 `distribution/pom.xml` 的单个依赖图复制运行库，避免将各模块各自解析出的不同版本混进 lib。测试请在发布前另行执行；依赖与类加载校验是每次打包的必经步骤。

`-Offline` 禁用依赖下载。`-SkipBuild` 仅用于已完成对应 install 的当前工件，不能用来发布未构建的源码。新模块需同时加入根 POM 和 distribution 依赖清单，脚本会检查遗漏。现有 AmapAPI 不是根 reactor 模块，仍从当前 Maven 缓存解析，放在 lib 里。

## 启动及首次切换

备份旧业务 JAR 和启动命令，停止旧进程。用薄 JAR 替换旧合并 JAR，完整部署这一版 lib，保留配置和持久数据，然后修改服务启动命令：

```text
Windows: start.cmd
Linux:   sh start.sh
```

脚本会把工作目录切到部署目录。可通过 `JAVA` 指定 Java 可执行文件，`JAVA_OPTS` 传 JVM 参数。手工命令等价为：

```sh
java -cp 'Universe.jar:lib/*' com.nekoyu.Universe.Universe
```

Windows classpath 分隔符用 `;`。不要继续使用只含 `java -jar Universe.jar` 的旧启动命令，它没有引入外部 lib。

共享依赖由主 ClassLoader 加载；法则仍由原 LawsManager 按 law.yml 建立 loader，AIChat 插件仍由 AIChat loader 的子 loader 加载。业务 JAR 不复制到 lib，避免重复加载 AIChat/Universe 类型。Minecraft-Connect 的服务器端 Spigot/Velocity 工件不在本部署方式的验收范围；该模块在这里作为 Universe 法则交付。

## 后续增量更新

```powershell
./scripts/package-deployment.ps1 -ReleaseId lib-layout-002 `
  -LocalRepository C:/Users/imylk/.m2/repository `
  -PreviousRelease target/deployment-lib-layout-001
```

`update-plan.json` 列出按 SHA-256 比较需要上传的文件及需要移除的旧文件，不以文件时间判定变更。只改某法则/插件且依赖不变时，通常只需上传对应业务 JAR；AIChat 自带的图标/前端等资源仍在业务 JAR 内，没有移到第三方 lib。

依赖升级时，同时更新 Upload 文件并清理 Remove 中旧版本，避免新旧库同时存在。只删除清单所列发行文件，不清理配置或持久数据。按原服务管理方式停机替换并重启；当前类加载器不提供依赖热替换。

## 校验及边界

脚本检查所有法则主类及 AIChat 插件主类在实际父子 loader 关系下的反射链接、插件类型一致性、业务 JAR 没有嵌入第三方 class、lib 不重复包含业务模块、库坐标不重复，并调用 Qdrant 生成类验证 protobuf 静态兼容性。随后校验既有图标资源。日志分别在 verification.log 和 icons-verification.log。

这些检查不会执行主程序、法则构造/prepare 或插件 onEnable，不连接数据库、模型、Broker 或设备。首次实际启动后仍需检查已启用的业务链路。构建机用 JDK 17+；本次默认依赖集合含 HikariCP 7，运行环境需满足各库的 JDK 要求。

IDEA 的旧 Artifacts 合并配置与这个布局不同。发布统一使用本脚本，不再用旧的 extracted-dir 合并依赖；本地 IDEA artifact 已调整为仅业务输出，单独 Build Artifacts 不负责生成 lib。

2026-10-21 构建验证：13 个业务 JAR、103 个共享依赖（lib 约 63.88 MiB）；9 个法则和 3 个插件入口链接通过，2143 个图标校验通过。同一工件再次打包，update-plan 的 Upload/Remove 均为空；移除 PostgreSQL 驱动的反例能被校验拒绝。Windows 启动脚本通过替身 Java 验证工作目录与参数；Linux 脚本尚未在 Linux 主机执行。未运行或切换生产实例。
