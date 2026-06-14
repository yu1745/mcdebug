# mcdebug — Minecraft Debug Server Mod

Fabric 1.20.1 + Kotlin Mod，提供 localhost JSON-RPC 服务，让 TypeScript CLI 远程读写世界/方块实体/物品栏/资源存储/快照/GUI，**用于 Mod 开发者用外部 TS runner 自动化测试自己的机器方块**。

```
┌─────────────────┐  JSON-RPC 2.0   ┌──────────────────┐
│  mcdebug (TS)   │  NDJSON over    │  DebugServerMod  │
│  CLI / Script   │  TCP 127.0.0.1  │  (Kotlin/Fabric) │
└─────────────────┘  default 25580  └──────────────────┘
```

## 1. 硬性约束

- **绝对不要**把 `build/libs/*.jar` 复制到 `run/mods/`。Loom dev-launch-injector 会自动从 `build/classes/kotlin/main` 注入 classpath，副本会和注入版本冲突，源码改了也不生效。
- **绝对不要**暴露 `tick.run` / `tick.runUntil` 之类主动推进 server tick 的 RPC。tick 由游戏引擎驱动，mod 只观察不控制。断言统一走 `wait.until`（注册 `ServerTickEvents.END_SERVER_TICK` 回调检查条件）。
- 不要给客户端加远程连接能力（v1 绑 127.0.0.1，假设本机可信）。
- 不要在 `src/main/resources/assets/mcdebug/` 之外加资源（避免污染上游资源命名空间）。

## 2. 目录结构

单 Gradle 项目（`settings.gradle` 只有 `rootProject`，无 `include`）。Gradle 插件、Kotlin test DSL、反编译源码参考均已不在仓库中（见 §12）。

```
mcdebug/
├── build.gradle              # Fabric 1.20.1 + Kotlin 2.3.10 + fabric-language-kotlin 1.13.9
├── gradle.properties         # 锁版本（参考 ic2-fabric 工作配置）
├── settings.gradle
├── CLAUDE.md
├── mcdebug-client/           # TypeScript CLI + SDK + 测试 runner
│   ├── package.json
│   ├── tsconfig.json
│   └── src/{client,api,commands,test-runner,types,...}.ts
│       test/*.test.ts        # 消费者侧的 mcdebug 测试用例
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
- 端口：默认 `25580`（高位、避开常用服务）

## 4. 端口解析顺序（两端统一）

1. JVM 系统属性 `-Dmcdebug.port=...` / TS `--port <N>`（最优先）
2. 环境变量 `MCDEBUG_PORT`
3. `<gameDir>/config/mcdebug.json` 中 `port` 字段
4. 默认 `25580`

修改端口的推荐方式：往 `run/config/mcdebug.json` 写 `{"port": 25581}`，不需要重启服务配置即可生效。

## 5. 常用命令

```bash
# 完整 build（Kotlin 重编 + remap jar）
./gradlew build

# 启动开发服务器（dev launcher 注入 classpath）
./gradlew runServer

# TS 端
cd mcdebug-client
pnpm install
pnpm build                 # tsc 编译到 dist/
node dist/cli.js --help    # 查看所有 CLI 命令
node dist/cli.js status    # 调用 server.status RPC

# npx 端（发布包或 GitHub 直装）
npx -y @yu1745/mcdebug status
npx -y github:yu1745/mcdebug raw storage.list '{"target":{"kind":"block","pos":[0,64,0]}}'
```

> 反编译 MC / Fabric API 源码（参考用，不参与编译、不进仓库）：`./gradlew genSources`，
> 产物在 `.gradle/loom-cache/...` 下，自行解压到本地未跟踪目录查看即可（`mc_source/`
> 和 `fabric-api_source/` 已被 `.gitignore` 排除，不要再加回来）。

## 6. 添加新 RPC 方法的流程

1. 在对应的 `*Ops.kt` 里加一个 `RpcHandler`（签名 `(MinecraftServer, JsonObject?) -> CompletableFuture<JsonElement>`），handler body 通过 `RpcContext.onServer(server) { ... }` 切到 server 线程
2. 错误抛出 `RpcException(code, message, data?)`（从 `RpcErrors` 选错误码）
3. 类型在 `RpcHandlerGroup.methods(): Map<String, RpcHandler>` 注册
4. 对应在 `mcdebug-client/src/api.ts` 的 `DebugApi` 加方法，参数与返回对齐
5. CLI 命令在 `mcdebug-client/src/commands/<group>.ts` 注册；没有专用 CLI 子命令时，至少更新 `raw`/REPL help 和 README 的 `npx` 示例
6. 重新 `pnpm build` 和 `./gradlew.bat build` 双端编译

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
- 改 TS：`pnpm build` 通过
- 改 entrypoint/资源：手动 `./gradlew runServer` 启动一次确认 mod 加载 + RPC 端口监听
- 改了 API/CLI 协议：两端一起改，TS 端的 `DebugApi` 强类型签名是契约
- 改了用户可见 RPC：更新 `README.md`、`mcdebug-client/src/commands/help-text.ts`，尤其要给 `npx ... raw namespace.method` 示例
- 不要新增 Kotlin test DSL / Gradle plugin / 注解扫描测试入口；测试编排放在消费者项目的 TS dispatcher
- 测试编排统一放在 TS 端 `mcdebug-client/src/test-runner.ts` + `test/*.test.ts`；mod 端不再做任何 test DSL / 注解扫描
- 改 mcdebug 默认端口 / 协议层：先在 plan 文件里讨论再动

## 9. 调试技巧

- 看 RPC 端口：`cat run/mcdebug/port` 或 `cat run/config/mcdebug.json`
- 实时看 server 日志：`run/logs/latest.log`（loom 写文件 + stdout）
- Loom 缓存了旧 remap jar：清 `run/.fabric/processedMods/`，再 `./gradlew build`
- TS 端快速调试：`node dist/cli.js raw world.getBlock '{"pos":[0,64,0]}'`
- npx 快速调试：`npx -y github:yu1745/mcdebug raw storage.list '{"target":{"kind":"block","pos":[0,64,0]}}'`

## 10. 版本更新规则

版本源是 `gradle.properties` 的 `mod_version`。不要手工逐个改版本号；使用脚本一次性同步 Gradle、npm 包声明、CLI version source 和 dist 输出。

```bash
node scripts/set-version.mjs X.Y.Z
```

脚本会更新：

| # | 文件 | 字段 | 用途 |
|---|------|------|------|
| 1 | `gradle.properties` | `mod_version=X.Y.Z` | Fabric mod 版本（Gradle → fabric.mod.json `${version}`） |
| 2 | `mcdebug-client/src/version.ts` | `export const version = 'X.Y.Z'` | **CLI 唯一真相源** — cli.ts 从这里 import |
| 3 | `mcdebug-client/package.json` | `"version": "X.Y.Z"` | npm 发布（mcdebug-client 子包） |
| 4 | `package.json`（根） | `"version": "X.Y.Z"` | `npx github:yu1745/mcdebug` 直接安装时读取 |
| 5 | `mcdebug-client/src/cli.ts` | `.version(version)` | **必须 import from version.ts，不能硬编码字符串** |

### 发版检查清单

```
□ node scripts/set-version.mjs X.Y.Z
□ cli.ts                                  grep -n 'version' 确认是 import 不是硬编码
□ ./gradlew build -x test
□ node mcdebug-client/dist/cli.js --version  ← 必须输出 X.Y.Z
□ git add -A && git commit -m "vX.Y.Z: ..."
□ git tag -a vX.Y.Z -m "..."
□ git push origin main && git push origin vX.Y.Z
□ gh run watch（确认 CI 成功）
□ rm -rf ~/.npm/_npx/*mcdebug* && npx -y github:yu1745/mcdebug --version ← 确认 X.Y.Z
```

### 踩过的坑

- **cli.ts 硬编码 version**（v0.3.0、v0.4.0）：cli.ts 的 `.version('0.2.0')` 忘了改成 import，导致 `--version` 和 `npx` 永远显示 0.2.0。**必须从 version.ts import，不允许出现硬编码版本字符串。**
- **根 package.json 遗漏**（v0.3.0）：只改了 mcdebug-client/package.json 没改根 package.json，导致 `npx github:yu1745/mcdebug` 安装的是旧版本号。两个 package.json 都要改。

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

## 11b. v2 已完成（变更记录）

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
- 鉴权 / 远程连接：v1 绑 127.0.0.1 假设本机可信。
- `scan.countByBlock` chunk 进度回调：O(N) 可接受。
- MCP adapter。

## 12. 已移除的子系统（避免被旧文档/旧 commit 误导）

下列内容在历史 commit / 旧版 CLAUDE.md 中出现过，但**当前已不在仓库**，不要再加回来：

- **Gradle 插件**（`gradle-plugin/` 子项目，曾含 `McDebugPlugin` / `McDebugExtension` / `McDebugTestTask` / `mcdebugTest` task）——已移除。`settings.gradle` 是单项目，无 `include`。测试编排改由 TS 端 `mcdebug-client/src/test-runner.ts` 承担。
- **JitPack 发布**（`jitpack.yml` + JitPack 坐标相关 commit）——已废弃。仓库不再通过 JitPack 发布任何构件。
  - `jitpack.yml` 已删除。
  - ⚠️ `build.gradle` 里 `maven { url "https://jitpack.io" }` 是**依赖拉取**用的（很多 Fabric mod 只在 jitpack 发版），**不是发布**，保留。
  - 当前发布通道只有 GitHub Release（`.github/workflows/release.yml` 把 remapped jar 上传到 tag release，CLI 的 `mcdebug jar` 命令据此下载 + sha256 校验）。
- **Kotlin test DSL / 注解扫描测试入口 / `scanPackages` 配置**——随 Gradle 插件一并移除。
- **`mc_source/` / `fabric-api_source/`**（反编译源码参考）——`.gitignore` 明确排除，永远不进仓库；需要时本地 `./gradlew genSources` 解压查看。

CLAUDE.md 维护规则：改了仓库结构（新增/移除子系统、改发布通道、改默认端口/协议）必须同步本文件，避免下次又被旧描述带偏。
