# mcdebug — Minecraft Debug Server Mod

Fabric 1.20.1 + Kotlin Mod，提供 JSON-RPC 服务，让 Kotlin CLI / JUnit runner 远程读写世界/方块实体/物品栏/资源存储/快照/GUI，**用于 Mod 开发者自动化测试自己的机器方块**（Kotlin JUnit 5，见 ic2-fabric 的 `core/src/mcdebugTest/`）。

```
┌─────────────────┐  JSON-RPC 2.0   ┌──────────────────┐
│  mcdebug (Kotlin│  NDJSON over    │  DebugServerMod  │
│  CLI / runner)  │  unix socket    │  (Kotlin/Fabric) │
│                 │  <gameDir>/     │      ▲           │
│                 │   mcdebug/socket│      │ TCP       │
│                 ├─────────────── ►│  25580 (跨机访问)│
│                 │  NDJSON over    │                  │
│                 │  TCP host:25580 │                  │
└─────────────────┘                 └──────────────────┘

双传输、同一 dispatcher（0.5.1+）：unix socket 主通道（本机），TCP 辅助通道（跨机）。
两边各自独立绑定：单边失败（典型：TCP 端口被占）只 WARN 不影响启动；两边都失败才报错。
```

## 1. 硬性约束

- **绝对不要**把 `build/libs/*.jar` 复制到 `run/mods/`。Loom dev-launch-injector 会自动从 `build/classes/kotlin/main` 注入 classpath，副本会和注入版本冲突，源码改了也不生效。
- **绝对不要**暴露 `tick.run` / `tick.runUntil` 之类主动推进 server tick 的 RPC。tick 由游戏引擎驱动，mod 只观察不控制。断言统一走 `wait.until`（注册 `ServerTickEvents.END_SERVER_TICK` 回调检查条件）。
- 服务端默认监听 `0.0.0.0`，容器部署时通过端口发布规则或防火墙限制访问范围。
- 不要在 `src/main/resources/assets/mcdebug/` 之外加资源（避免污染上游资源命名空间）。

## 2. 目录结构

单 Gradle 项目（`settings.gradle` 只有 `rootProject`，无 `include`）。Gradle 插件、Kotlin test DSL、反编译源码参考均已不在仓库中（见 §12）。

```
mcdebug/
├── build.gradle              # Fabric 1.20.1 + Kotlin 2.3.10 + fabric-language-kotlin 1.13.9
├── gradle.properties         # 锁版本（参考 ic2-fabric 工作配置）
├── settings.gradle
├── CLAUDE.md
├── contract/                 # 共享契约（方法名清单 + DTO 样板，零依赖）
├── cli/                       # Kotlin CLI（clikt）+ SDK + JUnit runner
│   └── src/main/kotlin/com/mcdebug/{cli,runner}/
├── mcdebug-cli.jar            # CLI fat jar（固定名，进 git，随 tag 分发）
├── package.json               # npm 壳包：bin/mcdebug.js 一行透传 java -jar
└── bin/mcdebug.js             # 壳脚本（pnpm dlx 拉包后 jar 在包内）
└── src/main/
    ├── kotlin/com/mcdebug/
    │   ├── McDebugMod.kt         # @Mod 入口，注册 server lifecycle 钩子
    │   ├── rpc/                  # 传输 + 协议层（JsonRpc/RpcServer/RpcDispatcher/RpcErrors/RpcHandler）
    │   ├── api/                  # WorldOps / BlockEntityOps / InventoryOps / CraftingOps / FluidOps
    │   │                         #   ScanOps / ServerOps / StorageOps / SnapshotOps / TraceOps / ScreenOps / FakePlayer
    │   ├── wait/WaitOps.kt       # wait.until — 被动观察
    │   └── util/{NbtJson,ServerContext}.kt
    └── resources/
        ├── fabric.mod.json       # 注意 entrypoint 用 "adapter": "kotlin"（Kotlin object 需要）
        ├── mcdebug.mixins.json   # 当前 mod 未写 mixin 代码（占位）
        └── modid.mixins.json     # ← Fabric 模板遗留，待清理
```

## 3. 命名约定

- Kotlin 类：PascalCase，与 RPC method 名空间对齐
  - `WorldOps` ↔ `world.*`
  - `BlockEntityOps` ↔ `be.*`
  - `InventoryOps` ↔ `inv.*`
  - `CraftingOps` ↔ `craft.*`
  - `FluidOps` ↔ `fluid.*`
  - `WaitOps` ↔ `wait.*`
  - `ScanOps` ↔ `scan.*`
  - `ServerOps` ↔ `server.*`
  - `StorageOps` ↔ `storage.*`
  - `SnapshotOps` ↔ `snapshot.*`
  - `TraceOps` ↔ `trace.*`
  - `ScreenOps` ↔ `screen.*`
- 错误码：JSON-RPC 标准 `-32700..-32603` + 自定义 `-32001..-32018`（在 `RpcErrors` 中）
- 传输：**双通道并行**（0.5.1+）。unix domain socket（AF_UNIX SOCK_STREAM，默认 `<gameDir>/mcdebug/socket`）是主通道，本机访问、多客户端并发、网络不可达；TCP（默认 25580，wildcard `0.0.0.0`）是辅助通道，用于跨机访问，同样多客户端并发。无鉴权，TCP 侧靠端口发布规则/防火墙限制。**容错语义**：两个监听各自独立 try/catch，单边绑定失败只记 WARN、不影响启动；仅当两边都失败才报错。

## 4. 传输配置解析顺序（两端统一）

### 4a. unix socket 路径

1. JVM 系统属性 `-Dmcdebug.socket=<path>` / CLI `--socket <path>`（最优先）
2. 环境变量 `MCDEBUG_SOCKET`
3. `<gameDir>/config/mcdebug.json` 中 `socket` 字段（相对路径按 gameDir 解析）
4. 默认 `<gameDir>/mcdebug/socket`

### 4b. TCP（跨机辅助通道，端口与 enabled 标志独立解析）

1. JVM 系统属性 `-Dmcdebug.tcpPort=<port>` / `-Dmcdebug.tcpEnabled=<bool>`
2. 环境变量 `MCDEBUG_TCP_PORT` / `MCDEBUG_TCP_ENABLED`
3. `<gameDir>/config/mcdebug.json` 中 `tcpPort`（数字）/ `tcpEnabled`（布尔）字段
4. 默认：enabled，端口 25580（与 0.4.x 旧版一致，生产 compose 映射无需改动）；`tcpPort=0` 表示临时端口（OS 分配，实际端口写进发现文件与日志）

### 4c. 发现文件（best effort）

- `<gameDir>/mcdebug/port`：内容为 **unix socket 路径**（沿用旧文件名，0.4.x 时期内容是端口号，0.5.0 起变为 socket 路径）
- `<gameDir>/mcdebug/tcpPort`：内容为 **实际绑定的 TCP 端口号**（新文件，0.5.1+；仅 TCP 成功绑定时才写）

客户端（CLI）默认走 socket 发现（`--socket` / `MCDEBUG_SOCKET` / `mcdebug/port` 文件）；跨机访问必须显式 `--tcp host:port`（或 `--host` + `--port`），CLI 不会在两种传输间自动回退。

修改 socket 位置的推荐方式：往 `run/config/mcdebug.json` 写 `{"socket": "mcdebug/alt.sock"}`，不需要重启服务配置即可生效。

注意：启动时会删除目标路径的残留 stale socket 文件（防 EADDRINUSE），停止时删除 socket 文件；发现文件 `mcdebug/port`、`mcdebug/tcpPort` 保留。

## 5. 常用命令

```bash
# 完整 build（Kotlin 重编 + remap jar）
./gradlew build

# 启动开发服务器（dev launcher 注入 classpath）
./gradlew runServer

# CLI 端（Kotlin fat jar）
./gradlew :cli:copyCliJar     # 产出根目录 mcdebug-cli.jar
node bin/mcdebug.js --help    # 查看所有 CLI 命令
node bin/mcdebug.js status    # 调用 server.status RPC（unix socket，本机）
node bin/mcdebug.js --tcp 192.168.5.102:25582 status   # 跨机访问（TCP）

# pnpm dlx（发布包或 GitHub 根目录直装）
pnpm dlx @yu1745/mcdebug status
pnpm dlx 'github:yu1745/mcdebug#v0.5.0' raw storage.list '{"target":{"kind":"block","pos":[0,64,0]}}'
```

> 反编译 MC / Fabric API 源码（参考用，不参与编译、不进仓库）：`./gradlew genSources`，
> 产物在 `.gradle/loom-cache/...` 下，自行解压到本地未跟踪目录查看即可（`mc_source/`
> 和 `fabric-api_source/` 已被 `.gitignore` 排除，不要再加回来）。

## 6. 添加新 RPC 方法的流程

1. 在对应的 `*Ops.kt` 里加一个 `RpcHandler`（签名 `(MinecraftServer, JsonObject?) -> CompletableFuture<JsonElement>`），handler body 通过 `RpcContext.onServer(server) { ... }` 切到 server 线程
2. 错误抛出 `RpcException(code, message, data?)`（从 `RpcErrors` 选错误码）
3. 类型在 `RpcHandlerGroup.methods(): Map<String, RpcHandler>` 注册
4. **同步 `contract/src/main/kotlin/com/mcdebug/contract/Methods.kt`**：在对应组 setOf 中加方法名（`RpcContractTest` 会双向校验，漏加或写错直接构建失败）；有需要时同时补充 DTO 到 `contract/.../<Group>Dtos.kt`（字段名 = JSON 字段名，服务端 Gson 与未来 Kotlin 客户端共用）
5. 对应在 `cli/src/main/kotlin/com/mcdebug/cli/DebugApi.kt` 加方法，参数与返回对齐
6. CLI 命令在 `cli/src/main/kotlin/com/mcdebug/cli/commands/<Group>Commands.kt` 注册；没有专用 CLI 子命令时，至少更新 `raw`/REPL help 和 README 的 `pnpm dlx` 示例
7. 重新 `./gradlew :cli:copyCliJar`（CLI fat jar）和 `./gradlew build`（契约测试 + 服务端）

## 7. Tick 设计原则

- 任何"等 N 拍"或"等条件成立"的需求统一走 `wait.until`，禁止在 RPC 层提供 `tick.run` / `tick.runUntil`
- `wait.until` 实现：`ServerTickEvents.END_SERVER_TICK` 注册回调，每个自然 tick 检查一次条件；满足或超时才返回
- 谓词是手写 DSL（`PredicateExpr`，词法器 + 递归下降解析器，**无 eval、无脚本、无反射**，避免 RCE）。语法：
  - 比较：`be[x,y,z].<path> <op> <value>` / `inv[x,y,z].<slot>.<field> <op> <value>` / `block[x,y,z].<id|prop.k> <op> <value>` / `tick <op> <value>`
  - 布尔组合：`AND` / `OR` / `NOT` / 括号（关键字大写）
  - 算术：`+` `-` `*`（比较两侧或聚合结果上）
  - 聚合（仅单 BE 库存）：`sum(inv[x,y,z].*.count) >= 64` / `count(inv[x,y,z].*.item) > 0`，`field ∈ {*, count, item, nbt.<path>}`
  - 字面量：数字（默认窄化为 NbtInt；NBT 写入要精确类型用 `be.setNbt` 的 `#nbt` 标注，见 §11b）/ 字符串 / `true` / `false` / `null`
  - 操作符：`==/!=/</<=/>/>=`
  - v1 的单比较谓词是 v2 DSL 的子集，完全向后兼容
- 断连时服务端会取消该连接发起的 `wait.until`（`WaitOps.cancelConnection`），不依赖超时兜底

## 8. 提交前验证

- 改 Kotlin：`./gradlew build` 通过（0 warning, 0 error）
- 改 Kotlin 逻辑（解析器/NBT/谓词）：`./gradlew test` 通过（JUnit5，`src/test/kotlin/`）。现有测试：`PredicateExprTest`（谓词 DSL 解析+求值，~48 例）、`NbtJsonTest`（`#nbt` 标注+往返，~29 例）
- 改 CLI/SDK：`./gradlew :cli:copyCliJar` 通过
- 改 entrypoint/资源：手动 `./gradlew runServer` 启动一次确认 mod 加载 + RPC 监听
- 改了 API/CLI 协议：两端一起改，Kotlin 端 `DebugApi` 强类型签名是契约（`RpcContractTest` 双向校验方法名）
- 改了用户可见 RPC：更新 `README.md`，尤其要给 `pnpm dlx ... raw namespace.method` 示例
- 测试编排统一在消费者项目（Kotlin JUnit，`@McDebugTest` runner）；mod 端不做任何 test DSL / 注解扫描
- 改 mcdebug 默认端口 / 协议层：先在 plan 文件里讨论再动

## 9. 调试技巧

- 看 RPC socket：`cat run/mcdebug/port`（内容为 socket 路径）；TCP 端口：`cat run/mcdebug/tcpPort`
- 实时看 server 日志：`run/logs/latest.log`（loom 写文件 + stdout）
- Loom 缓存了旧 remap jar：清 `run/.fabric/processedMods/`，再 `./gradlew build`
- CLI 快速调试：`node bin/mcdebug.js raw world.getBlock '{"pos":[0,64,0]}'`（本机 socket）；跨机：`node bin/mcdebug.js --tcp host:port ...`
- pnpm 快速调试：`pnpm dlx 'github:yu1745/mcdebug#v0.5.0' raw storage.list '{"target":{"kind":"block","pos":[0,64,0]}}'`

## 10. 版本更新规则

版本源是 `gradle.properties` 的 `mod_version`。不要手工逐个改版本号；使用脚本一次性同步 Gradle、npm 包声明、CLI version source 和 dist 输出。

```bash
node scripts/set-version.mjs X.Y.Z
```

脚本会更新：

| # | 文件 | 字段 | 用途 |
|---|------|------|------|
| 1 | `gradle.properties` | `mod_version=X.Y.Z` | Fabric mod 版本（Gradle → fabric.mod.json `${version}`） |
| 2 | `package.json` | `"version": "X.Y.Z"` | npm 壳包版本（与 tag 一致） |

### 发版检查清单

```
□ node scripts/set-version.mjs X.Y.Z
□ ./gradlew :cli:copyCliJar            ← fat jar 重新打包（含 Implementation-Version）
□ ./gradlew build                      ← 契约测试 + 单测
□ node bin/mcdebug.js --version        ← 必须输出 X.Y.Z
□ git add -A && git commit -m "vX.Y.Z: ..."
□ git tag -a vX.Y.Z -m "..."
□ git push origin main && git push origin vX.Y.Z
□ pnpm dlx 'github:yu1745/mcdebug#vX.Y.Z' --version ← 确认 X.Y.Z（jar 在包内）
```

### 踩过的坑

- **artifactId 用了项目名**：`cli` 子项目发布到 mavenLocal 时 artifactId 默认是 `cli`；已显式设为 `mcdebug-cli`。
- **旧 TS 壳（0.4.x）**：mcdebug-client 子包已退役（0.5.0 起根目录 npm 壳 + Kotlin jar）。不要在根目录之外重新创建 TS 包。

## 11. 已知小问题

- `be.setNbt` 用 `readNbt` 重置整个 NBT；某些 BlockEntity 子类可能不会完全重新初始化（红石信号、缓存等）
- `wait.until` 在断连时不会主动取消服务端回调，依赖 tick listener 的 `future.isDone` 自检
- `ScanOps.countByBlock` 在 box 很大时是 O(N)，加 chunk 进度回调再说
- `world.getRegion` / `world.selectBlocks` 的 `ensureChunkLoaded` 只检查不加载，遇到未加载 chunk 直接抛 `CHUNK_NOT_LOADED`；而 `setBlock(s)` / `getBlock` 不做该检查，chunk 状态行为在 `world.*` 各方法间不完全一致
- `FakePlayerPool` 是 per-(server,world) 单例且每次调用 mutate 其 position/yaw/手部状态：同一 ServerWorld 上并发 RPC（例如两个 CLI 连接同时 useOnBlock）会互相踩；test-runner 用网格隔离缓解了，但单连接内混用需注意
- `NbtJson.fromJson` 有损：JSON 数字统一成 `NbtInt`，无法区分 byte/short/int/long；某些 BE 字段（byte 标志位、long 坐标）需要精确类型时可能出错
- MCP adapter 没做（对话中"Layer 4"）
- 专用 CLI 子命令暂未覆盖 `storage.*` / `snapshot.*` / `trace.*` / `screen.*`，目前通过 `raw`、REPL 和 TS `DebugApi` 调用
- 自定义 storage adapter SPI 没做；特殊资源类型后续扩展
- `modid.mixins.json` 是 Fabric 模板遗留，且 mod 当前未写任何 mixin 代码，发版前可清理
- `inv.insert` / `inv.extract` 对目标方块已消失（如过压爆炸成 air）时报 `-32004 no block entity`，错误不够友好；改进：目标无 BE 时返回 `INVALID_TARGET`/`STORAGE_NOT_FOUND` 类错误并带 `reason`（ic2-fabric 过压测试曾踩中：机器放下即炸后 insertItem 必然撞上）

## 11b. 已完成变更记录（v2 → 0.5.x）

- **0.5.1 双通道（unix socket + TCP）+ 容错启动**（`rpc/RpcServer.kt` + `McDebugMod.kt` + `cli/RpcClient.kt` + `cli/Main.kt`）
  恢复 TCP 监听与 unix socket 并行服务同一 dispatcher：unix socket 主通道（本机，默认 `<gameDir>/mcdebug/socket`），TCP 辅助通道（跨机，默认 25580 = 0.4.x 旧端口，wildcard 绑定）。配置沿用现有模式：`-Dmcdebug.socket`/`MCDEBUG_SOCKET`/json `socket` 之外新增 `-Dmcdebug.tcpPort`/`-Dmcdebug.tcpEnabled`（env `MCDEBUG_TCP_PORT`/`MCDEBUG_TCP_ENABLED`、json `tcpPort`/`tcpEnabled`）。**容错语义**：两个监听各自独立 try/catch，单边绑定失败（典型：TCP 端口被占）只 WARN、不影响启动；仅两边都失败才 error。发现文件：`mcdebug/port` 继续写 socket 路径；新增 `mcdebug/tcpPort` 写实际端口（仅 TCP 绑定时）。CLI 新增 `--tcp host:port` / `--host`+`--port`（跨机），`--socket` 不变，`--timeout` 现在真正作用于 TCP connect。单元测试 `RpcServerBindTest`（TCP 被占→unix 继续服务 / 双失败→抛错 / 双成功→并行服务+发现文件）覆盖容错逻辑。

下列原 v2 待办已实现。保留描述以便回溯"为什么这么设计"。

- **v2-nbtJson：NbtJson 精确类型**（`util/NbtJson.kt`）
  `fromJson` 支持 `{"#nbt":"<type>","value":...}` 标注，`type ∈ {byte, short, int, long, float, double, string, byteArray, intArray, longArray}`。不带标注的 JSON 数字仍窄化为 NbtInt（向后兼容）。`#nbt` 键保留，常规 NBT compound 不以 `#` 开头故无冲突。NBT→JSON 方向（`toJson`）保留原类型。

- **v2-setNbt：`be.setNbt` / `be.setField` 重初始化**（`api/BlockEntityOps.kt`）
  统一抽 `applyBeNbt(world, pos, be, nbt)`：`readNbt` + `markDirty` + `ServerWorld.updateListeners`（客户端 block/BE NBT 增量）+ `World.updateComparators`（比较器立即重算红石信号）。旧的 `readNbt` + 只调 `updateListeners` 缺了比较器更新，导致"设了 NBT 但比较器信号没变"。

- **v2-waitCancel：`wait.until` 断连取消**（`rpc/RpcServer.kt` + `rpc/RpcHandler.kt` + `wait/WaitOps.kt`）
  `RpcContext.currentConnectionId` ThreadLocal 在 `handleConnection` 入口设置；`until` 把它存进 `WaitJob.connId`；`handleConnection` 的 finally 调 `WaitOps.cancelConnection(connId)` 把该连接所有未完成的 wait future 异常完成。注意：ThreadLocal 只在连接线程读（`until` 函数体在 dispatch 调用栈、未进 `server.execute`），所以不跨线程传递，安全。

- **v2-complexPredicate：复杂断言**（`wait/PredicateExpr.kt` 新文件 + `wait/WaitOps.kt`）
  `wait.until` 谓词从 v1 单比较正则升级为手写 DSL（语法见 §7）。**无 eval/脚本/反射**，纯词法器 + 递归下降解析器 + 求值器，绝对避免 RCE。v1 单比较是 v2 的子集，完全向后兼容。`PredicateExpr` 刻意不依赖任何 Minecraft 类型（pos 用 `Triple<Int,Int,Int>`，server 通过闭包传入），便于将来单测。已通过 14 例自测（v1 兼容 5 + v2 新 6 + 负面 3）。

不在计划内（已决定不做，不要再加回来）：
- 鉴权：当前协议没有鉴权；服务端监听所有接口，部署时必须通过端口发布规则或防火墙限制访问范围。
- `scan.countByBlock` chunk 进度回调：O(N) 可接受。
- MCP adapter。

## 12. 已移除的子系统（避免被旧文档/旧 commit 误导）

下列内容在历史 commit / 旧版 CLAUDE.md 中出现过，但**当前已不在仓库**，不要再加回来：

- **Gradle 插件**（`gradle-plugin/` 子项目，曾含 `McDebugPlugin` / `McDebugExtension` / `McDebugTestTask` / `mcdebugTest` task）——已移除。测试编排改由消费者项目（Kotlin JUnit，`com.mcdebug.runner`）承担。
- **TS 客户端 / TS 测试 runner**（`mcdebug-client/`）——0.5.0 起退役，由 Kotlin CLI（`cli/`）+ JUnit runner 接替；TS 测试已全部移植为 Kotlin JUnit（见 ic2-fabric）。
- **Gradle 插件的 JitPack 构件**——已随 `gradle-plugin/` 子项目移除，不要恢复该插件或其坐标。
- **Server mod jar 的 JitPack 构建仍在使用**。根项目是当前唯一的 Gradle 项目，`jitpack.yml` 通过 `publishToMavenLocal` 发布根项目的 `mavenJava` 构件。不要因为 Gradle 插件已移除而删除此配置。
  - `build.gradle` 里的 `maven { url "https://jitpack.io" }` 用于拉取第三方依赖，与本项目发布配置是两个不同用途。
  - GitHub Release（`.github/workflows/release.yml`）同时提供 remapped jar，CLI 的 `mcdebug jar` 命令从 Release 下载并校验 sha256；它不取代 JitPack server jar 构建。
- **Kotlin test DSL / 注解扫描测试入口 / `scanPackages` 配置**——随 Gradle 插件一并移除。
- **`mc_source/` / `fabric-api_source/`**（反编译源码参考）——`.gitignore` 明确排除，永远不进仓库；需要时本地 `./gradlew genSources` 解压查看。

CLAUDE.md 维护规则：改了仓库结构（新增/移除子系统、改发布通道、改默认端口/协议）必须同步本文件，避免下次又被旧描述带偏。
