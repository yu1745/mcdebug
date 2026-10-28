# mcdebug — AGENTS.md（贡献者指南 + 容器手册）

本文件是 mcdebug 的唯一开发指南：§1–§14 是仓库/协议/验证规则，§15 是本地隔离测试容器的重建手册。

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

- **绝对不要**把 `build/libs/*.jar` 复制到 **mcdebug 自己的** `run/mods/`。Loom dev-launch-injector 会自动从 `build/classes/kotlin/main`（客户端平面是 `build/classes/kotlin/{main,client}`）注入 classpath，副本会和注入版本冲突，源码改了也不生效。
- 反过来，**别的项目（消费者项目 A）的 `run/mods/` 就是发布 jar 的正常部署位置**：把 `build/libs/mcdebug-<version>.jar` 拷进去当普通 mod 用，一个 jar 同时提供服务端平面和客户端平面。放新 jar 前先删掉旧版本（`mods/` 里两份同 modid 会加载失败/加载到旧的）。
- **绝对不要**暴露 `tick.run` / `tick.runUntil` 之类主动推进 server tick 的 RPC。tick 由游戏引擎驱动，mod 只观察不控制。断言统一走 `wait.until`（注册 `ServerTickEvents.END_SERVER_TICK` 回调检查条件）。
- 服务端默认监听 `0.0.0.0`，容器部署时通过端口发布规则或防火墙限制访问范围。
- 不要在 `src/main/resources/assets/mcdebug/` 之外加资源（避免污染上游资源命名空间）。

## 2. 目录结构

Gradle 根项目 + `contract`/`cli` 子项目 + `forge`（委托项目，见 §13）。Gradle 插件、Kotlin test DSL、反编译源码参考均已不在仓库中（见 §12）。

```
mcdebug/
├── build.gradle              # Fabric 1.20.1 + Kotlin 2.3.10 + fabric-language-kotlin 1.13.9
├── gradle.properties         # 锁版本（参考 ic2-fabric 工作配置）
├── settings.gradle
├── AGENTS.md
├── contract/                 # 共享契约（方法名清单 + DTO 样板，零依赖）
├── forge/                     # Forge 1.20.1 原生移植（独立 Gradle 8.1.1 + ForgeGradle 6 构建，见 §13）
├── cli/                       # Kotlin CLI（clikt）+ SDK + JUnit runner
│   └── src/main/kotlin/com/mcdebug/{cli,runner}/
├── mcdebug-cli.jar            # CLI fat jar（固定名，进 git，随 tag 分发）
├── package.json               # npm 壳包：bin/mcdebug.js 一行透传 java -jar
└── bin/mcdebug.js             # 壳脚本（pnpm dlx 拉包后 jar 在包内）
├── src/main/
│   ├── kotlin/com/mcdebug/
│   │   ├── McDebugMod.kt         # server entrypoint，注册 server lifecycle 钩子（+ ServerPlane）
│   │   ├── rpc/                  # 传输 + 协议层（JsonRpc/RpcServer/RpcDispatcher/RpcErrors/RpcHandler/TransportConfig）
│   │   ├── api/                  # WorldOps / BlockEntityOps / InventoryOps / CraftingOps / FluidOps
│   │   │                         #   ScanOps / ServerOps / StorageOps / SnapshotOps / TraceOps / ScreenOps / FakePlayer
│   │   ├── wait/WaitOps.kt       # wait.until — 被动观察
│   │   └── util/{NbtJson,ServerContext}.kt
│   └── resources/
│       ├── fabric.mod.json       # environment "*" + server/client 两个 entrypoint（Kotlin 需 "adapter": "kotlin"）
│       ├── mcdebug.mixins.json   # 服务端 mixin 配置（当前为空占位）
│       └── modid.mixins.json     # ← Fabric 模板遗留，别删也别用
└── src/client/                   # 客户端平面（gui.*），由 client entrypoint 加载，见 §14
    ├── kotlin/com/mcdebug/client/{McDebugClientMod,GuiOps,GuiScreenOps,GuiClickOps,GuiPointerOps,GuiInputOps,GuiDevOps,Capture,ClientTasks,ClientRpc,ClientInfo}.kt
    │                             # + GuiSettle/ProblemLog/ScreenGuard/DrawRecorder/Annotator/Hotswap/ScreenReload（agent 迭代回路，见 §14）
    ├── java/com/mcdebug/client/mixin/{AbstractContainerScreenAccessor,…,GuiGraphicsMixin,ClientPacketListenerMixin,RenderSystemMixin}.java
    └── resources/mcdebug.client.mixins.json
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
  - `ScreenOps` ↔ `screen.*`（服务端假玩家菜单，只有槽位 JSON，**没有像素**）
  - `GuiOps` ↔ `gui.*`（`src/client`，真实客户端渲染/截图/Screen 事件，**像素只能由这里产生**）
- 错误码：JSON-RPC 标准 `-32700..-32603` + 自定义 `-32001..-32021`（在 `RpcErrors` 中）。客户端平面新增 `-32020 CLIENT_NOT_READY`（客户端没起来/没进世界）、`-32021 GUI_ELEMENT_NOT_FOUND`（`gui.click --text` 找不到控件，data 里带可选控件列表）
- **平面（plane）**：`world.*`…=服务端平面（`src/main`，`server` entrypoint）；`gui.*`=客户端平面（`src/client`，`client` entrypoint）。两边在同一份 jar 里，方法名清单由 `contract/Methods.kt` 的 `SERVER_METHODS` / `CLIENT_METHODS` 声明（`RpcContractTest` 双向校验服务端侧 + 两平面不相交；客户端侧的注册表在客户端启动时自检，见 §14）
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

# 启动开发客户端（真实渲染，配合 gui.* 用；需要 X/Wayland display）
DISPLAY=:0 ./gradlew runClient --args="--width 1280 --height 720"
# 客户端平面自检 + 首次截图（run/ 下有 mcdebug/clientPort 时 CLI 会自动发现）
java -jar mcdebug-cli.jar gui status --brief
java -jar mcdebug-cli.jar gui screenshot --out /tmp/shot.png

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
- 改冒烟脚本：`scripts/fabric-smoke.sh`（专用服）与 `scripts/forge-smoke.sh`（Forge GameTest 服）**共用** `scripts/contract-parity.py` 做契约+平面隔离校验——两个脚本各自内联解析 `Methods.kt` 会漂移（曾漏掉"排除客户端平面 `gui.*`"和"按 id 匹配响应"，报出假的 `inv.extract` 缺失）。改一处即可，别复制粘贴回去
- 改 `src/client/**`（客户端平面）：`./gradlew compileClientKotlin` + `./gradlew build`（后者含 `verifyJarSelfContained`，见 §14）；真机验证要起 `runClient` 并用 CLI 打 `gui.*`（Java/Kotlin 改动必须重启客户端，只有贴图/语言/模型改动可以 `gui reload-resources`）
- **发布 jar 必须自包含**：`verifyJarSelfContained` 已挂在 `check` 上，缺失入口点运行时用到的类/资源会直接构建失败。历史上 `implementation project(':contract')` 就是这么炸的（dev classpath 有、jar 里没有 → 别的项目里 `NoClassDefFoundError`）
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
- `modid.mixins.json` 是 Fabric 模板遗留；`mcdebug.mixins.json` 仍是空占位，真正在用的 mixin 位于客户端侧的 `mcdebug.client.mixins.json`（GUI 矩形访问器、渲染调度/新帧屏障、失焦策略/虚拟光标，见 §14）。三个配置文件都不要当成"没用的模板"删掉
- `inv.insert` / `inv.extract` 对目标方块已消失（如过压爆炸成 air）时报 `-32004 no block entity`，错误不够友好；改进：目标无 BE 时返回 `INVALID_TARGET`/`STORAGE_NOT_FOUND` 类错误并带 `reason`（ic2-fabric 过压测试曾踩中：机器放下即炸后 insertItem 必然撞上）

## 11b. 已完成变更记录（v2 → 0.5.x）

- **GUI agent 迭代回路（1.21.1 线，未发版；设计与真机证据见 `docs/plan-gui-agent-loop.md`）**
  - 点击 settle（服务端 barrier + 2 tick 静默）取代固定 20 tick；`gui.act` 一次往返；`gui.settle`/`gui.reinit`/`gui.problems`/`gui.hotswap` 新方法；`gui.click {widget}`；`gui.screen` 递归控件、hover/focus/tooltip；截图 `annotate`/`drawLog`/`format:none`、只读回裁剪区、`pixelHash`；screen guard；轻量模式 RPC 唤醒限帧器；`gui compare --ignore/--report/--accept`；SDK `ScreenshotOptions`/`act`/`ScreenshotRegion`。
  - 同一机器 A/B（llvmpipe、demand、Unix socket 直连）：节流时单跳 37ms→0.8ms，`gui.click --slot` 988ms→102ms，GUI 截图 p50 37→21.5ms；Screen 方法体改动 `classes` 9s + hotswap 1.2s 生效（原需重启 ≈25–30s）。
  - 第二轮：`gui.hover`（本帧真实 tooltip）、`gui.scroll`、`gui.openScreen`（Screen 夹具）、`gui.reloadScreen`（Screen 类任意结构改动免重启，容器同一 menu）、截图 `cursor:"hide"`、`--text` 回退到本帧画出的文字、hotswap 改为全有或全无 + 逐类列出结构变化。

- **0.6.0 功能批（trace 自动停止 / wait 默认超时 / diff 忽略元数据 / 编码修复 / 批处理 / watch / health / replace-box / repl 增强 / 退出码）**
  - `trace.start` 新增 `maxTicks`：到期自动停止（抓最后一帧，`autoStopped:true`），移入 finished 表（上限 64）供 `trace.get` 继续读；0/缺省 = 不自动停止。
  - `wait.until` 默认 `timeoutTicks=1200`（1min @20tps）；0 = 无限（需显式传）。runner 侧总是显式传超时，不受影响。
  - `snapshot.diff` 新增 `ignoreMeta` 参数：过滤顶层 `/tick`、`/time` 后再 diff（`SnapshotOps.stripMeta`）。
  - **编码修复**：CLI `RpcClient.readLoop` 原逐字节 cast Char（UTF-8 多字节拆成 mojibake），改为逐字节找行分隔后整行按 UTF-8 解码；`be/entity set-nbt` 非 ASCII（含 \u 转义、emoji）往返正常。单测 `RpcClientUtf8Test`（TCP loopback 端到端）+ `NbtJsonTest` 非 ASCII 用例。
  - CLI 新命令：`-c/--command` 可重复 + `--batch`（连一次跑多条、逐条打印状态、失败即停并返回对应退出码；每条命令用独立命令树 parse，避免 clikt 选项状态残留；根命令需 `invokeWithoutSubcommand=true`）；`watch block|field|entity`（纯 CLI 轮询，`--interval-ticks` 默认 20、`--timeout-ticks` 默认 1200/0=无限）；`world replace-box`（select-blocks + 一次 setBlocks）；`status --health`（新方法 `server.health`：TPS/MSPT=server.tickTime、各维度 iterateEntities 计数 + loadedChunkCount；旧服务端报「需服务端 ≥0.6.0」退出 3）；`snapshot diff --ignore-meta`；`trace start --max-ticks`。
  - repl：历史持久化 ~/.mdb_history（200 条）、`$1`（上次结果 JSON 原样）/`$?`（上次退出码）替换、多行输入（行尾 `\` 或 JSON 括号不配平续行）。
  - 退出码统一：0=成功（含业务性 ok:false）；1=CLI 用法/解析错误；2=服务端拒绝（RPC error 含 -320xx）；3=method not found（-32601）。根 `--help` 有「退出码」一节。
  - 连接后比较 `server.status.modVersion` 与 CLI 版本，不一致 stderr 打一行警告（`warnVersionMismatch`，不阻塞；status 不可得时静默跳过）。
  - **注意**：clikt/mordant 会把帮助文本里的 `$...$` 当 markdown 数学解析（两个 `$` 配对即抛异常），所以 repl 相关 help/echo 文案里不得出现 `$` 字面量（README/AGENTS.md 等纯 markdown 不受影响）。

- **0.5.2 help 文案修正**（commit 0ed6339）

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

下列内容在历史 commit / 旧版文档中出现过，但**当前已不在仓库**，不要再加回来：

- **Gradle 插件**（`gradle-plugin/` 子项目，曾含 `McDebugPlugin` / `McDebugExtension` / `McDebugTestTask` / `mcdebugTest` task）——已移除。测试编排改由消费者项目（Kotlin JUnit，`com.mcdebug.runner`）承担。
- **TS 客户端 / TS 测试 runner**（`mcdebug-client/`）——0.5.0 起退役，由 Kotlin CLI（`cli/`）+ JUnit runner 接替；TS 测试已全部移植为 Kotlin JUnit（见 ic2-fabric）。
- **Gradle 插件的 JitPack 构件**——已随 `gradle-plugin/` 子项目移除，不要恢复该插件或其坐标。
- **Server mod jar 的 JitPack 构建仍在使用**。根项目是当前唯一的 Gradle 项目，`jitpack.yml` 通过 `publishToMavenLocal` 发布根项目的 `mavenJava` 构件。不要因为 Gradle 插件已移除而删除此配置。
  - `build.gradle` 里的 `maven { url "https://jitpack.io" }` 用于拉取第三方依赖，与本项目发布配置是两个不同用途。
  - GitHub Release（`.github/workflows/release.yml`）同时提供 remapped jar，CLI 的 `mcdebug jar` 命令从 Release 下载并校验 sha256；它不取代 JitPack server jar 构建。
- **Kotlin test DSL / 注解扫描测试入口 / `scanPackages` 配置**——随 Gradle 插件一并移除。
- **`mc_source/` / `fabric-api_source/`**（反编译源码参考）——`.gitignore` 明确排除，永远不进仓库；需要时本地 `./gradlew genSources` 解压查看。

本文件维护规则：改了仓库结构（新增/移除子系统、改发布通道、改默认端口/协议）必须同步本文件，避免下次又被旧描述带偏。

## 13. Forge 1.20.1 原生移植（`forge/`，0.6.1 起）

- **构建隔离**：ForgeGradle 6 跑不了根构建的 Gradle 9，所以 `forge/` 是**独立构建**（自带 wrapper = Gradle 8.1.1、`gradle.properties` 钉 JDK17、官方映射、KotlinForForge 4.11、Kotlin 2.3 编译器 + `apiVersion=1.9`，因为运行时 KFF 只带 1.9 标准库）。根 `settings.gradle` 把它作为委托项目 `:forge` 引入（`forge/delegate.gradle` 只是 Exec `forge/gradlew <task>`），所以 `./gradlew :forge:build` 和根 `./gradlew build` 都会构建它；产物 `forge/build/libs/mcdebug-forge-<mod_version>.jar`（版本取自根 `gradle.properties`，不单独 bump）。`./build.sh` 显式再跑一次 `:forge:build`，dist/ 里多一个 `mcdebug-forge-*.jar`。
- **共享源码（不复制）**：`forge/build.gradle` 的 `syncShared` 把根 `src/main/kotlin/com/mcdebug/` 下与加载器无关的 `rpc/{JsonRpc,RpcErrors,RpcHandler,RpcServer,RpcEnv}.kt`、`wait/PredicateExpr.kt` 同步到 `forge/build/generated/` 编译。`RpcServer` 取游戏目录走 `RpcEnv.gameDir`（Fabric 在 `onInitializeServer` 里设 FabricLoader 的值，Forge 设 `FMLPaths.GAMEDIR`）。**改这几个文件时两端都要编译通过**；它们引用 `McDebugMod.LOGGER` / `MOD_VERSION` 与 `WaitOps.cancelConnection`，Forge 侧同名提供。
- **移植的部分**（`forge/src/main/kotlin/com/mcdebug/`，Mojang 官方命名）：`McDebugMod`（`@Mod` + `ServerStartedEvent`/`ServerStoppingEvent`，只在服务端启动 RPC）、`api/*Ops`（16 组、80 个方法全部注册，`scripts/forge-smoke.sh` 会对照 `contract/Methods.kt` 逐个验证）、`util/{NbtJson,ServerContext,Mappings}`、`wait/WaitOps`。新增/改 RPC 方法时 Fabric 与 Forge 两端都要实现，并更新 `contract/Methods.kt`。
- **与 Fabric 版的语义差异**（需在 README/排障时知道）：
  - 存储走 Forge Capability：`vanilla:inventory`=`Container`，`fabric:item`=`ITEM_HANDLER`，`fabric:fluid`=`FLUID_HANDLER`(/`FLUID_HANDLER_ITEM`)，`teamreborn:energy`=`ENERGY`(FE)。句柄 id 沿用 Fabric 名（CLI 写死了它们），另接受 `forge:item|fluid|energy` 别名。
  - **流体单位是 mB（1000/桶）**，Fabric 是 droplet（81000/桶）；流体结果带 `"unit":"mB"`。`fluid.insert --index` 无法指定罐（`IFluidHandler.fill` 不分罐），结果多一个 `changedTank`。
  - 假玩家用 Forge `FakePlayer`（不进 `world.players`、不 tick），不是 Fabric 版手工 spawn 的 ServerPlayer；`UseEntityCallback` 等价物是手动调 `ForgeHooks.onInteractEntityAt`。
  - `reflect.*`：只认 Mojang 官方类名；成员名在 dev 是官方名、生产是 SRG（`m_123_`），不带映射表（Fabric 版带 yarn/intermediary 表）。`reflect.mappings` 恒返回 available。
  - `server.mantleInteract` 去掉了 Kilt 桥接的多步诊断 trace，只保留直接调用 Mantle `FluidTransferHelper.interactWithContainer` 的结果。
- **运行/验证**：不要为 dedicated `runServer` 自行同意 EULA。用 `scripts/forge-smoke.sh`（起 `runGameTestServer`：开发用 GameTest 服务器不检查 EULA，`forge/src/devtest` 的 `RpcHold` 测试让它保持存活直到 flag 文件出现，该测试集只在 dev 运行时 classpath，不进 jar），或手动：`cd forge && ./gradlew runGameTestServer -PdevFlag=/tmp/x.done`，socket 在 `forge/run-gametest/mcdebug/socket`，结束时 `touch /tmp/x.done`。GameTest 服务器不限速 tick（1 秒可达数百上千 tick），`wait.until` 的 tick 数与实时时间无关。
- 已知未验证：生产 Forge 服（SRG 运行时 + Connector 环境）下的行为；真正的 dedicated `runServer`/生产 `forge/` jar 放进 mods 的加载（只在 dev 的 userdev 环境验证了 mod 加载与全部 RPC）。

## 14. 客户端平面（`gui.*`，0.6.1 起）

> 设计与取舍的完整记录（含未实现的 P1/P2、验证证据）：`docs/plan-gui-screenshot.md`。

**为什么要有它**：服务端平面只能返回数据，永远拿不到像素——渲染是客户端的事。`screen.*`（服务端假玩家菜单）给的是槽位 JSON；`gui.*` 在**真实客户端**里截图与点击，agent 才能闭环调试 GUI（"摆方块/写 NBT → 打开 GUI → 截图 → 点击 → 再截图"）。

### 14.0 怎么用（agent 工作流，先读这段）

1. 摆场景走服务端平面（`world.setBlock` / `inv.setSlot` / `be.setNbt`），写完 `gui settle` 再看 GUI。
   纯客户端 Screen（配置页/手册/自写 Screen）不用方块：`gui open-screen pkg.Fixtures#myScreen`（dev 源码集里的静态工厂，可带 Screen/Player/Minecraft/Options 参数）或 `gui open-screen inventory`。
2. 看：`gui screenshot --mode gui --annotate`（`*.annotated.png` 里青色数字=槽位、品红数字=控件序号）+ `gui screen`；要核对 `renderBg` 里画的进度条/能量条/贴图偏移，用 `--draw-log`（每个 blit/fill/文字/物品/tooltip 及其 GUI 单位矩形、贴图与 texel UV）。tooltip 用 `gui hover --slot N|--gui X,Y`（返回那一帧真正画出的 tooltip，含 renderBg 里手画的能量条提示）。
3. 动：`gui click --slot N|--widget N|--text|--png`，`gui scroll --dy N`，加 `--shot gui [--screen]` 一次往返拿"结果+settle+截图+期间日志"（底层 `gui.act`）；点击默认 settle 返回，不再固定睡 20 tick。`--text` 先匹配控件文字，找不到再匹配本帧画出的字符串（手画按钮/列表项，`mode:"drawnText"`）。
4. 出问题先看 `gui problems --brief`（全 JVM WARN/ERROR + Screen 渲染异常）；Screen render 抛异常不再崩客户端（screen guard），`gui.status.screenGuard.fault` 有堆栈。
5. 改代码：`./gradlew classes` → 方法体改动 `mcdebug gui hotswap --classes build/classes/kotlin/client`（全有或全无，自动 reinit）；当前 Screen 类本身加字段/方法/lambda/按钮 → `mcdebug gui reload-screen --classes ...`（新类加载器重建实例，容器沿用同一 menu）；只改资源用 `gui reload-resources`；其它类的结构改动要重启（或 JBR `-XX:+AllowEnhancedClassRedefinition`）。
6. 回归：`gui screenshot --hide-cursor`（该帧光标移出屏幕：无悬停高亮/tooltip/手持物）+ `gui compare --ignore x,y,w,h --report r.html`；只有显式 `--accept` 写基线。

下面 14.1 起是**实现不变式**（改 `src/client/**` 时要守的规则）。

### 14.1 实现不变式

- **entrypoint 语义**：`fabric.mod.json` 的 `environment` 是 `"*"`，`server` entrypoint = `com.mcdebug.McDebugMod`（`DedicatedServerModInitializer`），`client` entrypoint = `com.mcdebug.client.McDebugClientMod`（`ClientModInitializer`）。loader 0.19.x **没有** `net.fabricmc.api.ServerModInitializer`，所以专用服务器不会触发 client entrypoint，而 client 上 server entrypoint **根本不触发**——单机客户端要两个平面都有，必须由 client entrypoint 自己调 `ServerPlane.installLifecycleHooks()`（它挂的是 `ServerLifecycleEvents`，集成服务器同样触发）。
- **两个平面的端点必须区分**（单机客户端同进程同 gameDir）：服务端 `<gameDir>/mcdebug/socket` + 发现文件 `mcdebug/port`，TCP 25580 默认开；客户端 `<gameDir>/mcdebug/clientSocket` + `mcdebug/clientPort`，TCP **25581 默认关**（真实玩家客户端绝不能开）。配置键 `mcdebug.clientSocket`/`MCDEBUG_CLIENT_SOCKET`/json `clientSocket`（TCP 同理加 `client` 前缀）。`RpcServer` 的 `TransportConfig` 就是为这个抽的——它在 `RpcServer.kt` 里（Forge 会同步这个文件，所以不要把它挪到新文件）。
- **线程规则**：`gui.*` handler 的入参在 RPC 连接线程，一切客户端状态读写都要 `ClientTasks.onClient(client)`（`client.execute{}`，在客户端线程时内联执行）。**续接 future 也要用 `ClientTasks.thenOnClient`**：`thenApply` 在前一个 future 已完成时会跑在 RPC 线程上。截图先入有界队列，`MinecraftRenderMixin` 调原 `GameRenderer.render` 并 flush 后再由 `Capture.afterFrame` 在客户端线程读回新帧，PNG 编码/落盘才丢到 `Util.ioPool()`。**tick 不是新帧屏障**；不要用 `RenderSystem.recordRenderCall` 代替明确渲染完成点。业务等待仍是被动 tick 回调，同 §7 的"只观察不驱动"。
- **点击报告与坐标**：四种点击方式都返回 `guiX/guiY`（slot = 槽中心）。截图 `transform.pixelsPerGuiXY=[sx,sy]` 来自实际渲染投影，不能用经过 ceil 的 guiScaledWidth 反推；PNG 坐标 = `[guiX*sx-crop[0], guiY*sy-crop[1]]`，`cropInGui` 保留浮点精度。`gui click --png` 反算 Screen 坐标；变换不是 GLFW 窗口坐标。
- **点击与光标**：`Screen.mouseClicked/mouseReleased` 直接派发，slot 走 `GameMode.handleInventoryMouseClick`，服务端裁定。continuous 模式保留真实光标 warp（失焦时 MC 不更新内部鼠标）；轻量模式使用虚拟 Screen 光标，让手持物品/提示/高亮在后台也跟随 RPC 点击，不干扰桌面指针。`--no-cursor-move` 禁止更新光标。状态确认仍以服务端数据为准，截图不等于网络确认。
- **轻量运行**：见 `docs/plan-lightweight-client.md`。`RenderControl` + `RenderSchedule` 只门控 GameRenderer，不取消 runTick/自然 tick/网络/窗口事件泵。`clientRenderMode=continuous` 默认不变；`demand` 按需完整出帧，`gui-only` 显式跳世界/HUD（像素语义可能不同）。配置优先级 JVM属性 → 环境变量 → config/mcdebug.json；`clientLoopFps=20`（20..260）、`clientIdleFps=1`（0..loopFps，0=只响应截图/切屏）。仅在稳定世界/容器界面节流，加载/overlay/其他 Screen 保持正常渲染；模式不省略资源/GL初始化，不承诺低内存。失焦不自动暂停，显式暂停界面仍遵循引擎语义。
- **探测要设上限**：CLI `--timeout` 管连接/握手（毫秒），`--request-timeout` 管请求（秒；0=无限）。普通请求默认30秒，`wait.until` 默认遵循服务端 tick timeout、不额外套墙钟限制；显式选项可覆盖。超时结束本地等待并关闭连接，**不代表远端操作取消/回滚**。版本检查读 server.ready，不再先发 status。发现 socket 只代表端点存在，世界就绪还需检查 `gui.status.inWorld`。
- **mixins**：客户端有 `AbstractContainerScreenAccessor`（GUI矩形/hoveredSlot）、`MinecraftRenderMixin`（渲染完成屏障/调度/限帧）、`GameRendererMixin`（失焦策略；`renderWithTooltip` 一个 MixinExtras `@WrapOperation` 同时做虚拟光标与 screen guard，别再拆回两个 `@ModifyArg`）、`RenderSystemMixin`（节流时可被 RPC 唤醒的 limitDisplayFPS）、`GuiGraphicsMixin`（draw log，只观察不改）、`ClientPacketListenerMixin`（只吞 settle barrier 自己的 suggestion 回包）、`NativeImageAccessor`/`ClientTextTooltipAccessor`。锁定1.21.1，不加旧版分支。Loom 1.15 在 remapJar 直接重映射注解值，不需要 refmap；必须用真实客户端验证注入点，编译通过不等于注入成功（`defaultRequire: 1`，注入失败即启动失败）。`@Inject` handler 要么带全部目标参数要么一个都不带，多重载各写一个。
- **契约与测试**：启动时仍将 `GuiOps.methods()` 与 `Methods.CLIENT_METHODS` 对账，将 mismatch 写进 `gui.status`。test classpath 含 client output，用于无GL的纯调度/几何单测；真实截图/线程/mixin 仍靠客户端冒烟验证。停止时取消截图和 tick waiters 并关闭客户端 RPC。
- **自包含 jar（踩过的坑）**：发布 jar 是**单个**文件丢进 `mods/`，所以 `contract` 的源码被 `sourceSets.main.kotlin.srcDir` 直接编进 mod（不是 `implementation project(':contract')`，那样 dev 能跑、装到别的项目里就 `NoClassDefFoundError`）。`verifyJarSelfContained` 守住这条线。
- **一条命令进存档（快捷方式）**：MC 从 1.20 起支持 `--quickPlaySingleplayer <存档目录名>`，所以「起客户端 + 点存档」可以合成一行（`<存档目录名>` 是 `saves/` 下的**目录名**，不是显示名；Gradle `--args` 支持引号，带空格的目录名写成 `\"New World\"`）：
  ```bash
  DISPLAY=:0 ./gradlew :core:runClient --args="--width 1280 --height 720 --quickPlaySingleplayer \"New World\""
  ```
  已验证：客户端直接进世界，`mcdebug/socket`（集成服务器）随之出现，不需要点标题界面/存档列表。**没有这条需求时手动那套仍然有效**（`gui click --text Singleplayer` → `gui click --gui <存档行>` → `gui click --text "Play Selected World"`），存档列表坐标用 `gui screen --brief` 取。
  **坑**：同一个 gameDir 只能有一个客户端实例。上一个实例没死透时新实例会 `Failed to read level <name> data ... session.lock: already locked` 并**退回标题界面**（看起来就是"卡在大厅"）。重启前先确认 `ps -eo pid,args | grep devlaunchinjector` 为空，不要手删 `session.lock`。
- **怎么验**：`DISPLAY=:0 ./gradlew runClient`（mcdebug 自己的 run/）验证代码；把发布 jar 拷到**消费者项目**的 `run/mods/` 再起它的 `runClient` 验证打包（Loom dev 会把生产 jar 重映射到官方名，`gui.status.jarPath` 显示 `run/.fabric/processedMods/...` 那个副本）。已按此在 ic2-fabric-1.21.1（1.21.1 + official mappings，窗口 2560x1440 @ 显示器 (640,360)、guiScale 6、`gui` 427x240）完整跑通「加载已有存档 → `inv.setSlot` 摆物品 → `gui open-block ic2_120:batbox` → `--mode gui` 截图（1073x1175）→ `gui click --slot 0`（报告中心 (188,47)、`slotAfter` 空、`carried` 红石 x7）→ 再截图（sha 变化）→ 服务端 `be.getNbt` 确认 `Items=[]`」；PNG→GUI 往返也精确：槽中心 (188,47) ↔ PNG (384,150) ↔ 回读 `guiX=187.98`。
- **后续GUI增强已实现**：见 `docs/plan-gui-input-regression.md`。`GuiInputOps` 提供当前Screen的 key/type/drag；`GuiInputModifiers` + `ScreenInputMixin` 只在事件调用栈覆盖静态修饰键查询，禁止全局键盘注入/跨请求持键。拖拽单操作、被动tick、有界30秒，切屏/改尺寸/错误/停止时释放原目标一次；CLIENT_STOPPING先调用GuiInputOps.close再关ClientTasks。`gui.disconnect`在服务端自身线程请求正常halt，客户端被动等shutdown后走原版清理/标题页，不停止进程、不从RPC泵runTick。加载/连接中拒绝，超时不回滚已请求的保存。
- **golden回归**：CLI-only `gui compare` 和 SDK `GuiApi.assertScreenshot`（远端inline PNG）可由消费者JUnit使用，无新DSL/plugin。PngCodec是有界CRC校验RGB/RGBA8非交错PNG实现，无AWT/ImageIO/native-image反射依赖；不支持格式明确拒绝。默认精确RGBA，可显式tolerance/差异比例；尺寸不同直接失败，diff不能覆盖输入/别名，不自动更新golden。比较不匹配CLI exit1、SDK AssertionError。单测stdout capture必须@Isolated，不能与其它命令并发抢System.out。
- **settle（`GuiSettle`）**：barrier 用 `ServerboundCommandSuggestionPacket`（服务端主线程 `ensureRunningOnSameThread` 后按序处理，回包排在此前请求引起的包之后）；**不能用 ping**——`handlePingRequest` 在网络线程直接回 pong，可能早于点击被处理。barrier id 从 `Int.MIN_VALUE` 递增，绝不与 `ClientSuggestionProvider`（0 起、空闲 -1）冲突。指纹=Screen 身份/槽位/carried/控件（文本、位置、active/visible），**刻意不含 container data slots**（运行中的机器照样能 settle）。被动观察 client tick，`waitTicks`/`maxTicks` 只是上限，超时返回 `settled:false` 不报错。
- **gui.act（`GuiDevOps`）**：动作白名单 click/key/type/drag/close/openBlock/reinit/none（不含 act/stop/disconnect）；顺序=动作→settle（click 自带 settle 时跳过）→**先截图再取 screen**（截图强制新帧，hover 状态与图一致）→ problems(cursor 起)。动作失败也返回 views（`ok:false` + `error`）。
- **截图（`Capture`）**：只 `glReadPixels` 裁剪矩形（绑 READ_FRAMEBUFFER 后恢复原绑定，flipY + alpha 置不透明，与 `Screenshot.takeScreenshot` 语义一致），失败回退旧的整图下载+裁剪。`pixelHash` 是 RGBA 像素 SHA-256（区别于 PNG 文件 `sha256`）。`format:"none"` 不编码不落盘，且**不更新** `--png` 点击用的 lastTransform。`annotate` 在干净 PNG 编码之后再画，写 `*.annotated.png`，主 PNG 永不带标注；标注目标在 afterFrame（客户端线程、同一 Screen）采集。`drawLog` 只在有请求要它的那一帧 arm（`RenderControl.renderFrame` 前后 begin/end），上限 5000 条。
- **draw log 坐标**：pose 矩阵变换后的轴对齐包围盒，单位 = Screen/GUI 单位（与 `gui click --gui` 一致）；HUD 也会被记录（整帧）。
- **screen guard（`ScreenGuard`）**：只包 `Screen.renderWithTooltip`，世界/HUD/overlay 异常照旧崩。捕获后该 Screen 实例不再绘制，`gui.reinit`/关屏清除；`clientScreenGuard=false`（JVM 属性/env/`config/mcdebug.json`）恢复原版崩溃。ERROR 日志经 appender 进 ProblemLog，不要再手动 record 一次（只在 appender 不可用时）。
- **ProblemLog**：log4j root logger 加 WARN 级 appender，环形 500 条、单条截断；`since` 用返回的 `next` 游标。安装失败只 WARN 不阻止启动。
- **唤醒（`RenderControl.wake`）**：只在我们的限帧器正在节流时 `glfwPostEmptyEvent`。**glfwInit 之前 post 会留下 GLFW 错误，原版 `_initGlfw` 的检查直接崩溃**（RPC 探测可能那么早到，真机冒烟踩过）。连续模式/非节流状态不接管原版 limitDisplayFPS。
- **hotswap（`Hotswap` + CLI `AgentAttach`）**：Instrumentation 由 CLI 动态 attach 的单类 agent（`cli/src/main/java/com/mcdebug/agent/InstrumentationAgent.java`，纯 JDK，生成单类 jar，**不能**把 CLI fat jar 当 agent 加载——会把 kotlin/clikt 塞进目标 system classpath）放进 `System.getProperties()["mcdebug.instrumentation"]`。字节码：Knot classpath 读到的正是该文件时走 `getClassByteArray(name,true)`（Fabric pre-mixin 变换），再过 Mixin `transformClassBytes`。**全有或全无**：先用 `SchemaDiff`（ASM 读新字节 vs 反射读已加载类）列出每个类增删的方法/字段，有任何结构变化（非 JBR）就一个都不重定义；`redefineClasses` 本身对一批是原子的。**不要恢复"批量失败逐个重试"**——那会部分生效，新代码调用尚不存在的成员即 `NoSuchMethodError`（真机踩过）。只有全部成功才推进 `since`。CLI 端 attach 用反射（没有 jdk.attach 的运行时给明确错误）。
- **reloadScreen（`ScreenReload` + `ScreenClassLoader`）**：只对当前 Screen 类、其嵌套类和显式 `also` 前缀 child-first，其余（mod 注册表/单例/MC）一律委托 Knot，绝不重复初始化。容器 Screen 用同一 menu 重建（`ScreenArgs` 按参数类型填 menu/Inventory/title/parent/player/Minecraft/Options，取参数最多的可填构造器），**不走 setScreen**：`AbstractContainerScreen.removed()` 会关掉 menu；按 setScreen 顺序 `added()`→`init()`→直接赋 `Minecraft.screen`。init 失败恢复旧 Screen。已知限制：新实例字段从零开始、Java 包私有访问跨加载器失败、Mixin 不作用于副本、之后正常打开 GUI 仍是原类。
- **hover / drawn text（`GuiPointerOps`/`GuiTargets`）**：hover 移光标后强制渲染一帧（容器用 gui 模式，其余 window 模式，`format:none`+drawLog），tooltip 以该帧 draw log 的 `op:"tooltip"` 为准。`--text` 先匹配控件；没有控件匹配时 `prepare` 渲染一帧取 draw log 的 `op:"text"` 矩形中心，结果 `mode:"drawnText"`。click/hover/scroll 共用 `GuiTargets`。
- **`cursor:"hide"`**：截图请求的光标策略决定它在哪一帧被采集——`RenderControl.renderFrame` 按队首请求的策略渲染，`afterFrame` 只取策略相同的请求，其余留到下一帧。hide 帧把传给 `renderWithTooltip` 的鼠标坐标换成屏幕外，下一帧恢复；实体预览（背包里的玩家）仍会动。
- **仍未做**：`--repeat`连拍、离屏定尺寸截图、全局游戏键绑定/移动、IME组合输入、连接尚未完成时的独立取消RPC、跨GPU像素确定性/自动基线更新、reloadScreen 时把旧 Screen 字段状态迁移到新实例、Screen 类以外的结构改动免重启（需 JBR）。

---

## 14a. Session 管理（本机 Linux）

性能实测见 `docs/client-performance.md` 和 `docs/plan-client-performance.md`；`scripts/client-performance.py` / `summarize-client-performance.py` 以独立空源码 consumer 测发布 jar，不能混用本仓库 dev classes。CPU 100%=一逻辑核；按实际/proc采样子窗口统计，不把RSS当堆/显存、不把软件GL收益外推硬件GPU。重跑前使用全新输出目录/离线存档副本，正常 owned stop 清理；Python 测试：`python3 -m unittest discover -s scripts -p 'test_*performance.py' -v`。

设计见 `docs/plan-session-management.md`。CLI 新目录 `cli/.../session/`：`SessionRecord` 显式 JSON schema（不用 Gson 类反射，兼顾 native CLI）、`SessionRegistry` 原子写/锁/CAS、`SessionRuntime` OS/RPC 边界、`SessionManager` 生命周期。命令 `session start|attach|list|status|logs|stop`，根 `--session NAME` 自动选择两平面，`--sessions-dir`/`MCDEBUG_SESSIONS_DIR` 控制 registry；无隐式全局 current session，batch 必须继承会话/registry。与显式 transport 参数互斥。

- 第一版 backend 是目标项目自己的 Gradle wrapper + runClient（qualified task 也只允许 runClient），Linux setsid、日志重定向，无新 daemon、无 Gradle 插件、无独立 MC 实现。project 默认 CWD、gameDir 默认 project/run，子模块须显式 gameDir；显式相对路径相对 CWD。world 必须是已有 saves 目录名，不自动建/删世界，拒绝路径穿越及逃逸 symlink。
- Registry 默认 XDG_STATE_HOME/mcdebug/sessions 或 ~/.local/state/mcdebug/sessions，每会话 JSON 含 schemaVersion/recordId、本次 token、启动配置、wrapper 与真实 MC 的 PID/startTime、instanceId、端点和日志。recordId 是 CAS 世代，不能只用 sessionToken（attached token 可以为空）。锁不能跨主线程 RPC/长等待持有；同 canonical gameDir 的 mdb.lock + mdb-owner.json 覆盖跨 registry/bootstrap 窗口。
- server.ready 现在必须有 process 对象：pid、startTime（OS ProcessHandle 的 ISO Instant）、instanceId（每 JVM UUID）、sessionToken、canonical gameDir。两平面同 JVM 共用 RpcEnv 身份（Forge 同步 RpcEnv/RpcServer）；改共享文件两端都要编译。客户端原子发布 mcdebug/client.json，gui.status 同样返回 process。Session 路由不额外调用 status；实际 RPC 连接握手绑定完整 expectedProcess，禁止端点换实例后继续操作。
- 客户端在 RPC 绑定前持有 mcdebug/client.lock 的进程锁，直到 JVM shutdown；不替代/删除 MC 的 session.lock。被取消或被新 token 超越的延迟 bootstrap 不绑定 RPC/不覆盖发现文件，在 CLIENT_STARTED 正常退出；启动过程中新增 stop 标记也会在 CLIENT_STARTED 检查。
- managed launch 显式关闭两平面 TCP，并将两 socket 固定在自身 gameDir。全局 mod 端口/默认配置不变；Session v1 不把远端/独立多人服务器冒充集成服，外部目标继续用不带 --session 的显式 transport。
- start 有限等待：未指定 world 返回 client_ready；指定后必须 inWorld 且 singleplayer 才 world_ready。超时/失败打印记录并非0退出，不能把遗留 socket 当就绪。logs 是有界 UTF-8 tail，不跟随。
- stop 只允许 owned；attached 即使 --force 也拒绝。先核对真实 PID/startTime/instanceId/token，使用同一个已验证连接调用 gui.stop；--force 只能终止身份仍匹配的 owned MC，绝不枚举/杀共享 Gradle daemon。RPC 已关闭时 force 仍须核对完整发现身份和活 PID/startTime。启动中没有真实身份时，只写本 token 的 session-stop.json、取消自己且 PID/startTime 匹配的 wrapper；保留 stopping/占位，不谎报 stopped。保存世界期间仍持有 gameDir 锁。
- 新 RPC `gui.stop` 参数必须带 instanceId/sessionToken，切客户端线程调 Minecraft.stop；同步了 Methods.CLIENT_METHODS 与 SDK。正常操作用 session stop，raw RPC 仅有实例校验，不是 owned/attached 权限边界或鉴权。token 是进程归属关联值，不是认证凭证。
- 验证：CLI session 目录单测注入 SessionRuntime，不启动 MC/Gradle；临时 Unix/TCP 与假 shell wrapper 可测进程边界。帮助测试只用 parse 捕获 PrintHelpMessage，**不要在 JUnit 内调用 Clikt.main（会 exitProcess 杀掉测试 worker）**。真实集成验证须用隔离 gameDir/存档副本，通过 mdb 自己 start→双平面→截图→attach 禁止停止→owned stop，最后清理所有测试进程。

## 15. 容器测试环境重建手册（Kilt + TConstruct + IC2）

本节记录 mcdebug 的**本地隔离测试容器**重建步骤。该容器用于在 Kilt + TConstruct + IC2 环境下验证 mcdebug 的 RPC 能力（`shoot` 远程武器、`reflect` 任意反射、挖矿/掉落测试等），与生产服务器完全隔离。

### 15.0 唯一硬规则：API 问题看本地源码（自 ic2-fabric 移植）

遇到任何 Minecraft / Fabric API 的问题（类名、方法签名、继承链、事件、网络包），**直接查文件系统中的本地源码，不要猜、不要靠记忆、不要用 javap**：

- MC 反编译源码（按版本）：`../mc_source_{version}/`
  - `../mc_source_1.21.1/`（Mojang 官方名）
  - `../mc_source_1.20.1/`（yarn 名，1.20.1 归档线用）
- Fabric API 源码：`../fabric-api_{version}/`
  - `../fabric-api_1.21.1/`、`../fabric-api_1.20.1/`（注意：fabric-api 树里它自己的 API 声明可信，但其中引用的 MC/JDK 方法名曾被工具损坏过，MC 名以 `mc_source_1.21.1` 为准）
- **1.21.1 线（`port-fabric-1.21.1` 分支起）mcdebug 用 Mojang 官方映射**（`loom.officialMojangMappings()`），与 `mc_source_1.21.1` 直查一致；1.20.1 线（`main`）仍是 yarn。yarn→mojang 成员改名可复用 ic2 工具链：`../ic2-fabric-1.21.1/docs/yarn-to-mojang-1.21.1.tsv`（类/方法/字段）+ `tools/yarn2mojang.py` + `tools/bulk-rename-pass*.sed`。
- mojang 侧两个 Kotlin 陷阱：私有字段与公有方法同名时（如 `Level.registryAccess`、`MinecraftServer.tickCount`、`BoundingBox.minX`），Kotlin 解析到私有字段报错，要用**调用形态**（`registryAccess()`、`minX()`）；`Registry.getId(x)` 在 mojang 是数字 id（IdMap），取注册名要用 `getKey(x)`。
- 需要重新生成 MC 源码时：`sh gradlew genSources`，产物在**项目根目录**下的 `.gradle/loom-cache/minecraftMaven/net/minecraft/`（不是用户目录），解压其中 `*-sources.jar` 即得（按当前 mappings 命名）。
- `mc_source_1.21.1` 里查不到的东西（fabric 专属 API）才去 `fabric-api_1.21.1` 查。

### 15.1 为什么需要这个容器

- mcdebug 是 Fabric 服务端调试工具，但生产环境是 **Kilt（Forge 兼容层）+ Forge mod**（TConstruct/Mantle/IC2）。
- 纯 Fabric 最小环境无法验证 Forge mod 交互；生产服务器禁止直接操作。
- 本容器在**本机 Docker** 运行（用户明确要求测试容器建在本地，不隔 SSH 操作远程），复刻生产依赖版本。

### 15.2 容器概况

| 项 | 值 |
|---|---|
| 容器名 | `mcdebug-tconstruct-luck-local` |
| 镜像 | `servers-fabric-btrfs:jdk17`（本机已有） |
| 工作目录 | `/server`（挂载宿主机目录） |
| Minecraft 端口 | 25665 |
| mcdebug RPC 端口 | 25680 |
| 宿主机数据目录 | `/home/wangyu/mcdebug-tconstruct-luck-local` |
| JVM | `java -Xmx5G -XX:+UseG1GC -jar fabric-server-launch.jar nogui` |

### 15.3 重建步骤

#### 15.3.1 准备数据目录

```bash
# 数据目录位于本机磁盘（非 NFS），删除重建即可
rm -rf /home/wangyu/mcdebug-tconstruct-luck-local
mkdir -p /home/wangyu/mcdebug-tconstruct-luck-local/{mods,config,logs,mcdebug}
```

#### 15.3.2 收集 mods（版本锁定，逐个核对 sha256）

| jar | 来源 | sha256 |
|---|---|---|
| `fabric-server-launch.jar` | 旧目录保留 / 远程测试目录 `/tmp/mcdebug-tconstruct-luck-20260803/` | — |
| `fabric-api-0.92.11+1.20.1.jar` | 旧目录 / 生产 `fabric1.20.1-1/mods/` | `3278787c04ad...` |
| `fabric-language-kotlin-1.13.9+kotlin.2.3.10.jar` | 同上 | `fecd5e6dd6ae...` |
| `architectury-9.2.14-fabric.jar` | 同上 | `bd7a7032bedb...` |
| `ForgeConfigAPIPort-v8.0.3-1.20.1-Fabric.jar` | 同上 | `627d28ae4f5c...` |
| `Kilt-20.1.14+build.639304-local.jar` | **本地构建** `develop/Kilt/build/libs/` | `69b129191369...` |
| `Mantle-1.20.1-1.11.104.jar` | 生产 mods | `6052e47c3981...` |
| `TConstruct-1.20.1-3.11.2.166.jar` | 生产 mods | `653b49d73481...` |
| `ic2_120-0.4.jar` | **本地构建** `develop/ic2-fabric/core/build/libs/` | `d751882acdab...` |
| `base-2.3.15+1.20.1.jar` | 生产 `kaleidoscope_cookery` jar 内嵌 `META-INF/jars/` 提取 | `886a8ca7809a...` |
| `loot-2.3.15+1.20.1.jar` | 同上 | `4cf639ebfb0e...` |
| `transfer-2.3.15+1.20.1.jar` | 同上 | `e71ab765097f...` |
| `mcdebug-0.4.15.jar` | **本地构建** `develop/mcdebug/build/libs/` | `4ade2bb6623d...` |

要点：
- **Porting Lib 2.3.15 三个 jar**：从生产 `kaleidoscope_cookery-1.3.0-hotifix-fabric+mc1.20.1.jar` 的 `META-INF/jars/` 提取（生产环境由它提供 2.3.15；Kilt 内嵌的是 2.3.9）。生产环境依赖解析为 2.3.15，测试容器必须复刻。
- **不要放独立 JEI**：ic2 0.4 已修复无 JEI 启动（`Ic2_120.kt` 的 `UuCostIndex.onRebuild` 回调加了 `isModLoaded("jei")` 保护，commit `8989177d`）。若用旧 ic2 jar 则需 JEI 或会启动崩溃。
- 配置目录 `config/`：`fml.toml`、`forge-server.toml`、`mantle-server.toml`、`tconstruct-common.toml`、`mcdebug.json`（RPC 端口 25680）、`ic2_120.json` 从旧目录复制。
- `server.properties`：`server-port=25665`、`online-mode=false`、`enable-command-block=true`、`level-type=minecraft:normal`。
- `eula.txt`：`eula=true`。
- `libraries/`、`versions/`：Fabric loader 首次启动自动下载，无需手工准备。

#### 15.3.3 启动容器

```bash
docker rm -f mcdebug-tconstruct-luck-local
docker run -d --name mcdebug-tconstruct-luck-local \
  -p 25665:25665 -p 25680:25680 \
  -v /home/wangyu/mcdebug-tconstruct-luck-local:/server \
  -w /server \
  --memory=6g \
  servers-fabric-btrfs:jdk17 \
  java -Xmx5G -XX:+UseG1GC -jar fabric-server-launch.jar nogui
```

等待就绪（脚本在数据目录下，轮询日志不阻塞）：

```bash
/home/wangyu/mcdebug-tconstruct-luck-local/wait-done.sh \
  /home/wangyu/mcdebug-tconstruct-luck-local/logs/latest.log 300
```

验证：
```bash
docker ps --filter name=mcdebug-tconstruct --format "{{.Names}} {{.Status}}"
nc -z 127.0.0.1 25680 && echo "RPC OK"
# 0.4.x 时代是纯 TCP：node dist/cli.js --port 25680 status（TS 客户端 0.5.0 起已退役）
java -jar mcdebug-cli.jar --tcp 127.0.0.1:25680 status
```

#### 15.3.4 部署新构建

```bash
# 服务端 jar（改完 Kotlin 后）
cd /home/wangyu/server/develop/mcdebug && ./gradlew build
cp build/libs/mcdebug-<version>.jar /home/wangyu/mcdebug-tconstruct-luck-local/mods/

# CLI（Kotlin，0.5.0 起）
./gradlew :cli:copyCliJar

# 重启容器加载新 jar
docker restart mcdebug-tconstruct-luck-local
# 等待 Done + RPC ready（见 wait-done.sh）
```

### 15.4 常用测试命令

```bash
# 通用
mcdebug status / find-entities / entity spawn/list-items/collect-items

# 远程武器（shoot，服务端 RPC world.useItemHold）
mcdebug shoot --item minecraft:bow --ammo minecraft:arrow --target <uuid> --hold 20
mcdebug shoot --item minecraft:crossbow --ammo minecraft:arrow --target <uuid> --hold 30 --repeat 2
mcdebug shoot --item ic2_120:mining_laser --nbt '{"Energy":200000}' --target <uuid> --hold 0
mcdebug shoot --item tconstruct:shuriken --nbt @shuriken.json --target <uuid> --hold 0

# 任意反射（reflect，服务端 RPC reflect.*）
mcdebug reflect 'net.minecraft.entity.LivingEntity' --members
mcdebug reflect 'net.minecraft.world.item.Items' --field DIAMOND        # 静态字段 → $ref
mcdebug new 'net.minecraft.world.item.ItemStack' --args '[{"$ref":1},3]' # 带参构造
mcdebug reflect --ref 2 --method setCount --args '[64]'                  # 实例方法
mcdebug refs / raw reflect.release '{"ref":1}'

# 挖矿 / 掉落（attack，服务端 RPC world.attackBlock）
mcdebug attack --x 100 --y 65 --z 100 --face up --item tconstruct:pickaxe \
  --nbt @pickaxe-luck3.json --gamemode survival
```

### 15.5 已知注意点

- **假玩家热键栏 slot 0 = 主手**（`selectedSlot` 默认 0）。`shoot` 的弹药放副手 + slot 8，**绝不能写 slot 0**（会覆盖手持武器）。
- **运行时是 intermediary 映射**（Kilt）：反射字符串必须用运行时名；`reflect` 通过内置 yarn/mojang 映射表自动转换（打包在 jar 资源 `mappings/*.gz`，jar 约 2MB）。
- **`tickActiveItemStack` 反射名**：yarn 名在运行时不存在，须用 `method_6076`（Mappings 已处理）。
- 测试生物（鸡）会自行移动、受击面积小——目标设近（2-5 格）或生成多只；IC2 镭射直线无下坠但射程有限。
- 无 JEI 环境：ic2 0.4 已修复，其他 mod 若报 `ClassNotFoundException mezz.jei` 检查是否用旧 ic2 jar。
- 容器数据目录在本机磁盘，可整体 `rm -rf` 重建；生产服务器不受任何影响。

### 15.6 版本升级路径

- **Kilt**：`develop/Kilt` 构建后替换 `Kilt-*.jar`（sha256 变化）。
- **ic2**：`develop/ic2-fabric/core/build/libs/ic2_120-0.4.jar`。
- **mcdebug**：见 §15.3.4。版本号在 `gradle.properties` 的 `mod_version`，升版后 jar 名变化需同步 CLI 版本匹配。
