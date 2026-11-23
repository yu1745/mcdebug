# 单仓多版本结构与公共层

本仓库同时维护三个 Minecraft 版本的 mcdebug，并抽出与 Minecraft 无关的公共层。
本文是目录结构、构建矩阵、共享约定与验证命令的唯一来源；历史计划见
[NeoForge 移植说明](plan-neoforge-port.md)与 [26.3 平台适配](port-26.3.md)。

## 目标与原则

- **第 1 层是纯 JVM**：`contract` / `sdk` / `testkit` / `cli` 不依赖 Minecraft 或任何加载器，
  产物为 Java 17 字节码。根构建不再加载 Loom / ModDevGradle / ForgeGradle。
- **每个 Minecraft 版本是独立构建**：各有自己的 `gradlew`、`gradle.properties` 与 JDK，
  互不共享源码集（26.3 与 1.21.1 的源码因 MC API 变化已实质分叉）。
- **一个产品版本**：`mod_version`、`maven_group`、`kotlin_version` 只在仓库根
  `gradle.properties` 定义一次，各版本目录按相对路径读取。
- **模组 JAR 必须自包含**：三个版本五个构建都保留 `verifyJarSelfContained`，契约源码直接编进模组。
- **1.20.1 是冻结线**：只保留 Forge 形态，源码为 1.20.1 分支的就地改写，不回合并到其他版本。

## 目录结构

```text
mcdebug/                              # 根 Gradle：仅纯 JVM 模块（Java 17 目标）
├── settings.gradle                   # include contract / sdk / testkit / cli
├── build.gradle
├── gradle.properties                 # mod_version、maven_version、maven_group、kotlin_version
├── contract/                         # RPC 方法清单（三个版本的并集 + 版本子集）与共享 DTO
├── sdk/                              # RPC 客户端、DebugApi、Session、截图比较；含旧包名别名
├── testkit/                          # JUnit 扩展与 Client/Server TestContext
├── cli/                              # Clikt CLI（jar --mc / --loader、会话管理）
├── frontend/                         # Maven 暂存与发布入口
├── mod-core/                         # 零 MC 依赖的共享源码（srcDir 引入，不发 JAR）
├── docs/                             # 唯一文档目录
├── skills/                           # 通用 SKILL.md + references/mc-<ver>.md
├── scripts/                          # 跨版本共享脚本（contract-parity.py / parity-diff.py / stop-server.py 等）
└── versions/
    ├── mc1.20.1/                     # Gradle 8.1.1 + JDK 17，仅 Forge；src/ 为冻结源码
    ├── mc1.21.1/                     # Gradle 9.4.1 + JDK 21，Fabric + neoforge/（NeoForge 21.1.265）
    └── mc26.3/                       # Gradle 9.6.0 + JDK 25，Fabric + neoforge/（NeoForge 26.3.0.103）
```

每个 `versions/mc<ver>/` 都是可直接 `cd` 进去运行的独立 Gradle 构建：
`build.gradle`（Fabric 或 Forge）、`settings.gradle`、`gradle.properties`（只放本版本专属属性）、
`gradle/wrapper`、`src/`，以及 Fabric 版本下的 `neoforge/` 子构建（通过 `:neoforge:*` 任务转发）和
`scripts/`（本版本的 `fabric-smoke.sh` / `neoforge-smoke.sh`）。

| 版本 | 来源提交 | 加载器 | JDK | Gradle | 产物 |
|---|---|---|---|---|---|
| `versions/mc26.3` | 26.3 线 `5233b63` + Phase 0 两个移植 | Fabric、NeoForge 26.3.0.103 | 25（工具链自动解析） | 9.6.0 | `mcdebug-<v>.jar`、`neoforge/build/libs/mcdebug-neoforge-<v>.jar` |
| `versions/mc1.21.1` | 1.21.1 线 `98eb5bf` | Fabric、NeoForge 21.1.265 | 21 | 9.4.1 | 同上 |
| `versions/mc1.20.1` | 1.20.1 线 `d376913` | Forge 47.4.0（FG 6.0.6） | 17 | 8.1.1 | `mcdebug-forge-<v>.jar`（`reobfJarJar`，内嵌 MixinExtras） |

## 构建与验证命令

```bash
# 1. 根纯 JVM：contract / sdk / testkit / cli 的编译与单测（JDK 25 工具链，守护进程用任意 JDK 17+）
./gradlew test

# 2. Minecraft 26.3（守护进程可用系统默认 JDK，编译走 25 工具链）
(cd versions/mc26.3 && ./gradlew build && ./gradlew :neoforge:build)

# 3. Minecraft 1.21.1
(cd versions/mc1.21.1 && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build && \
                        JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :neoforge:build)

# 4. Minecraft 1.20.1（仅 Forge；Gradle 8.1.1 只能在 JDK 17 上运行）
(cd versions/mc1.20.1 && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew build)

# 5. CLI fat jar：内嵌 versions/*/build/libs 中已存在的模组 JAR，不触发任何 Minecraft 构建
./gradlew :cli:copyCliJar
java -jar mcdebug-cli.jar --help
java -jar mcdebug-cli.jar jar --list
java -jar mcdebug-cli.jar jar --mc 1.21.1 --loader neoforge --output <gameDir>/mods
```

在 Fabric 版本目录里裸写 `./gradlew build` 会同时执行 `:neoforge:build`（任务名在所有项目中匹配），
与显式的 `:neoforge:build` 等价。各版本目录下的 `scripts/fabric-smoke.sh`、`scripts/neoforge-smoke.sh`
启动一次性专用服做冒烟，需要先在仓库根生成 `mcdebug-cli.jar`，并按版本把 `JAVA_HOME` 指向对应 JDK。
在 `versions/mc26.3` / `versions/mc1.21.1` 自身起托管会话（`session start --project versions/mc<ver>`）要写
`--task :runClient`：裸 `runClient` 同时匹配 `:neoforge:runClient` 转发任务，后者不接受 `--args`。
NeoForge 冒烟跑的是版本目录下的 dev 服；仓库所在路径很深时默认的 unix socket 路径会超过 AF_UNIX 上限，
脚本会自动改用 `/tmp` 下的短路径并经 `MCDEBUG_SOCKET` 传给 dev 服。

`build.sh` 默认只构建根模块；设置 `MCDEBUG_BUILD_VERSIONS=26.3,1.21.1,1.20.1`（或 `all`）时
再依次构建各版本（JDK 取 `JAVA25_HOME` / `JAVA21_HOME` / `JAVA17_HOME`）并重新打包 CLI。
`dist/` 里只收本次构建的版本的模组 JAR，并按 Release 命名改名为 `mcdebug-<loader>-<mc>-<version>.jar`
（26.3 与 1.21.1 的原始文件名相同，直接复制会互相覆盖）。

## 公共层（第 1 层）约定

- **契约版本子集**：`contract` 的 `Methods.kt` 是三个版本的**并集**；某版本尚未提供的方法登记在
  `Methods.MISSING_BY_MC_VERSION`（目前只有 1.20.1 缺 `gui.toasts`）。各版本的 `RpcContractTest`、
  客户端启动自检（`ClientPlane.contractMismatch`）、`gui.status.methods` 与
  `scripts/contract-parity.py --mc <ver>` 都用 `Methods.serverMethods/clientMethods/all(mc)` 取子集。
  版本线标识是各版本 `McDebug.MC_VERSION_LINE`。新增只落在部分版本的方法时：先在并集里声明，
  再在缺席版本登记，实现后从 `MISSING_BY_MC_VERSION` 移除。
- **Kotlin 插件版本**：`plugins {}` 块只能读本构建的 `gradle.properties`，所以每个版本构建的
  `settings.gradle` 用 `pluginManagement.resolutionStrategy.eachPlugin` 从仓库根读取 `kotlin_version`。
- **CLI 内嵌**：`cli/build.gradle` 只扫描 `versions/*/build/libs` 与 `versions/*/neoforge/build/libs`
  中已存在的产物，按 `META-INF/mcdebug/<mc>/<loader>.jar` 打包（全部构建完毕时共 5 个）；
  `-PembedVersions=26.3,1.21.1` 可限定版本，点名的版本没有产物时构建失败。
  `mcdebug jar` 写出的文件名统一为 `mcdebug-<loader>-<mc>-<version>.jar`
  （例如 `mcdebug-forge-1.20.1-0.8.0.jar`），并会清理 mods 目录里**任何**命名的旧 mcdebug JAR
  （`mcdebug-<v>.jar`、`mcdebug-neoforge-<v>.jar`、`mcdebug-forge-<v>.jar` 与新命名）。
  默认 `--mc 26.3`、`--loader fabric`（1.20.1 默认 `forge`）；`--list` 列出内嵌与缺失的组合。
- **旧消费者别名**：1.21.1 的 `com.mcdebug.cli.*` / `com.mcdebug.cli.session.*` 公开类型已迁到 `sdk`，
  `sdk` 保留 `@Deprecated` 的 typealias / 转发函数（仅 Kotlin 源码兼容，不保证二进制兼容）。
  `com.mcdebug.runner.*` 仍在 `testkit`，包名未变。
- **托管启动**：`SessionManager.detectFlavor` 识别 Loom、ModDevGradle 与 ForgeGradle；
  ForgeGradle 工程在创建任何记录之前被明确拒绝（提示改用 `session attach`），不再落到 Loom 的
  `--args` 启动路径上静默失败。

## 1.20.1 冻结线

- `versions/mc1.20.1/src/` 是 `d376913` 的共享源码去掉 Fabric 入口、Fabric 接缝、Fabric 资源与
  `FabricRuntimeNamesTest` 之后的快照，Forge 接缝、Mixin 与 `mods.toml` 合并进同一棵树。
  服务端平面在 `src/main/kotlin`，客户端平面在 `src/client/kotlin`（包仍是 1.20.1 的扁平布局），
  Forge 构建只有一个源集。
- `-Poracle` 的实机对照不再硬编码本机 `flatDir`：目录由 `-PoracleDownloads=<dir>` 指定，
  不传时不注册该仓库，普通构建不受影响。
- 原有单测（149 个）在 Forge 构建里随 `./gradlew build` 运行；Kotlin `apiVersion` 仍锁定 `1.9`
  （运行时由 KotlinForForge 4.x 提供 1.9 标准库）。

## mod-core（第 2 层，源码级共享）

`mod-core/src/main/kotlin` 只收录确认零 MC / 加载器传递依赖的纯 Kotlin 文件，由各版本构建以
`srcDir` / `Sync` 引入，不发布 JAR。共享范围以逐文件比对为准：

| 文件 | 1.20.1 | 1.21.1 | 26.3 | 说明 |
|---|---|---|---|---|
| `rpc/JsonRpc.kt`、`rpc/RpcErrors.kt`、`rpc/RpcEnv.kt` | 共享 | 共享 | 共享 | 三版本逐字节相同 |
| `wait/PredicateExpr.kt` | 自有副本 | 共享 | 共享 | 1.20.1 缺 `storage[...]` 谓词源与 `jsonPath` |
| `client/input/GuiInputState.kt` | 自有副本 | 共享 | 共享 | 1.20.1 在扁平包 `com.mcdebug.client` 且缺 `held` 修饰键 |
| `client/render/RenderSchedule.kt` | 自有副本 | 共享 | 共享 | 内容相同，但 1.20.1 位于 `com.mcdebug.client` |
| `client/render/CaptureGeometry.kt`（自 `Capture.kt` 拆出） | 自有副本 | 共享 | 共享 | 同上；1.20.1 的几何代码仍在其 `Capture.kt` 内 |

根构建有两道门禁：`verifyModCoreHasNoGameImports`（挂在各 JVM 模块的 `check` 上）扫描 `mod-core/` 下对
`net.minecraft.*`、`net.fabricmc.*`、`net.neoforged.*`、`net.minecraftforge.*`、`com.mojang.*`、`org.lwjgl.*`
的引用；不发布的 `:mod-core` 子项目只带 Kotlin 标准库与 Gson 独立编译（`apiVersion = 1.9`），
同包引用了版本目录里的类也会失败。任一失败都会让根 `./gradlew build` 失败，规则约定见 `mod-core/README.md`。

客户端三个文件不与 1.20.1 共享的原因是**包名**：1.20.1 冻结源码把它们放在扁平的
`com.mcdebug.client` 包里并由同包其他文件直接引用，迁移到 `input` / `render` 子包需要改写多份
冻结文件。向 `mod-core` 新增文件前先确认三个目标版本的内容一致且 `apiVersion = '1.9'` 可编译。

## 发布与 CI

- GitHub Release（`release.yml`）在 tag 上构建全部五个模组 JAR 与 CLI，附件按
  `mcdebug-<loader>-<mc>-<version>.jar` 命名并附 `.sha256`。
- `build.yml` 按改动路径触发：根模块、`contract/`、`mod-core/`、`gradle/`、工作流等共用部分变化时
  纯 JVM job 与三个版本 job 全部运行；只动 `versions/mc<ver>/` 时只跑对应版本的 job。
  有版本 job 成功时，`cli-with-mods` 下载它们的工件、按文件名放回 `versions/*/build/libs` 并重新打包 CLI。
- Maven 仓库（`maven.yml`）暂存全部构件：纯 JVM 的 `mcdebug-contract` / `mcdebug-sdk` / `mcdebug-testkit`，
  以及每个 `<mc, loader>` 的预编译模组 JAR `mcdebug-mod-<mc>-<loader>`（与 Release 文件名一一对应）；
  旧坐标 `mcdebug-neoforge` 作为 26.3 NeoForge 代理的兼容别名保留。构件清单只在 `frontend/build.gradle`
  维护，工作流用 `./gradlew -p frontend -q listPublicationArtifactIds` 读取它做不可变检查：已发布版本跳过，
  同版本缺件（包括新增构件）视为不完整并失败，所以新增构件也要提升 `maven_version`。
- **JitPack 发布链路已弃用**：仓库没有 `jitpack.yml`，各版本构建也不带 `maven-publish`；
  模组 JAR 只经 GitHub Release 与 `maven` 分支分发。各版本 `build.gradle` 里的
  `maven { url = "https://jitpack.io" }` 只是第三方依赖仓库，与发布无关。

## 后续观察（Phase 3：1.21.1 / 26.3 `shim/` spike）

- 两个版本的差异集中在 MC API 调用形态（`Identifier` / 访问器式 getter、`GuiGraphicsExtractor`、
  SDL 输入、事务型 `ResourceHandler`），RPC 处理器、谓词、会话与渲染调度的结构完全一致。
  spike 应从差异最薄的一组处理器开始（如 `trace.*`、`snapshot.*`、`redstone.*`），
  用薄适配层吸收 API 差异，而不是一次性合并两棵源码树。
- 共享前先给两侧补同一份“行为对照”：`scripts/parity-diff.py` 目前比较 Fabric 与 NeoForge，
  可扩展为跨 MC 版本比较，作为 shim 的回归护栏。
- `PredicateExpr`、`GuiInputState`、`RenderSchedule`、`CaptureGeometry` 的 1.20.1 副本与共享版本
  只差包名或少量增量；若将来需要 1.20.1 也共享，优先考虑把冻结线的这几个类迁入子包。
