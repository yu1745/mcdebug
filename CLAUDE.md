# mcdebug — Minecraft Debug Server Mod

Fabric 1.20.1 + Kotlin Mod，提供 localhost JSON-RPC 服务，让 TypeScript CLI 远程读写世界/方块实体/物品栏，**用于 Mod 开发者自动化测试自己的机器方块**。

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

```
mc-debug-server/
├── build.gradle              # Fabric 1.20.1 + Kotlin 2.3.10 + fabric-language-kotlin 1.13.9
├── gradle.properties         # 锁版本（参考 ic2-fabric 工作配置）
├── settings.gradle
├── CLAUDE.md
├── mc_source/                # genSources 反编译产物（common + client）— MC 1.20.1 源码参考
├── fabric-api_source/        # Fabric API 1.20.1 源码参考（仅参考，不参与编译）
├── mcdebug-client/           # TypeScript CLI
│   ├── package.json
│   ├── tsconfig.json
│   └── src/{client,api,commands}/...
└── src/main/
    ├── kotlin/com/mcdebug/
    │   ├── McDebugMod.kt         # @Mod 入口，注册 server lifecycle 钩子
    │   ├── rpc/                  # 传输 + 协议层（JsonRpc/RpcServer/RpcDispatcher/RpcErrors/RpcHandler）
    │   ├── api/                  # WorldOps / BlockEntityOps / InventoryOps / CraftingOps / FluidOps
    │   │                         #   ScanOps / ServerOps
    │   ├── wait/WaitOps.kt       # wait.until — 被动观察
    │   └── util/{NbtJson,ServerContext}.kt
    └── resources/
        ├── fabric.mod.json       # 注意 entrypoint 用 "adapter": "kotlin"（Kotlin object 需要）
        └── mcdebug.mixins.json
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
- 错误码：JSON-RPC 标准 `-32700..-32603` + 自定义 `-32001..-32012`（在 `RpcErrors` 中）
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
./gradlew.bat build

# 启动开发服务器（dev launcher 注入 classpath）
./gradlew.bat runServer

# 反编译 MC 源码到 mc_source/（改动 Yarn 映射版本后必跑）
./gradlew.bat genSources
# 产物在 .gradle/loom-cache/minecraftMaven/net/minecraft/.../sources.jar
# 用 Expand-Archive 解压到 mc_source/{common,client}/

# TS 端
cd mcdebug-client
pnpm install
pnpm build                 # tsc 编译到 dist/
node dist/cli.js --help    # 查看所有 CLI 命令
node dist/cli.js status    # 调用 server.status RPC
```

## 6. 添加新 RPC 方法的流程

1. 在对应的 `*Ops.kt` 里加一个 `RpcHandler`（签名 `(MinecraftServer, JsonObject?) -> CompletableFuture<JsonElement>`），handler body 通过 `RpcContext.onServer(server) { ... }` 切到 server 线程
2. 错误抛出 `RpcException(code, message, data?)`（从 `RpcErrors` 选错误码）
3. 类型在 `RpcHandlerGroup.methods(): Map<String, RpcHandler>` 注册
4. 对应在 `mcdebug-client/src/api.ts` 的 `DebugApi` 加方法，参数与返回对齐
5. CLI 命令在 `mcdebug-client/src/commands/<group>.ts` 注册
6. 重新 `pnpm build` 和 `./gradlew.bat build` 双端编译

## 7. Tick 设计原则

- 任何"等 N 拍"或"等条件成立"的需求统一走 `wait.until`，禁止在 RPC 层提供 `tick.run` / `tick.runUntil`
- `wait.until` 实现：`ServerTickEvents.END_SERVER_TICK` 注册回调，每个自然 tick 检查一次条件；满足或超时才返回
- 谓词白名单：`be[x,y,z].<path> <op> <value>` / `inv[x,y,z].<slot>.<field> <op> <value>` / `block[x,y,z].<id|prop.k> <op> <value>` / `tick <op> <value>`，操作符限定 `==/!=/</<=/>/>=`
- 复杂断言（任意表达式、脚本）v2 再做，避免 RCE 风险

## 8. 提交前验证

- 改 Kotlin：`./gradlew.bat build` 通过（0 warning, 0 error）
- 改 TS：`pnpm build` 通过
- 改 entrypoint/资源：手动 `./gradlew.bat runServer` 启动一次确认 mod 加载 + RPC 端口监听
- 改了 API/CLI 协议：两端一起改，TS 端的 `DebugApi` 强类型签名是契约
- 改 mcdebug 默认端口 / 协议层：先在 plan 文件里讨论再动

## 9. 调试技巧

- 看 RPC 端口：`cat run/mcdebug/port` 或 `cat run/config/mcdebug.json`
- 实时看 server 日志：`run/logs/latest.log`（loom 写文件 + stdout）
- Loom 缓存了旧 remap jar：清 `run/.fabric/processedMods/`，再 `./gradlew.bat build`
- TS 端快速调试：`node dist/cli.js raw world.getBlock '{"pos":[0,64,0]}'`

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
□ rm -rf ~/.npm/_npx/*mcdebug* && npx yu1745/mcdebug --version ← 确认 X.Y.Z
```

### 踩过的坑

- **cli.ts 硬编码 version**（v0.3.0、v0.4.0）：cli.ts 的 `.version('0.2.0')` 忘了改成 import，导致 `--version` 和 `npx` 永远显示 0.2.0。**必须从 version.ts import，不允许出现硬编码版本字符串。**
- **根 package.json 遗漏**（v0.3.0）：只改了 mcdebug-client/package.json 没改根 package.json，导致 `npx github:yu1745/mcdebug` 安装的是旧版本号。两个 package.json 都要改。

## 11. 已知小问题 / TODO（v2）

- `be.setNbt` 用 `readNbt` 重置整个 NBT；某些 BlockEntity 子类可能不会完全重新初始化（红石信号、缓存等）
- `wait.until` 在断连时不会主动取消服务端回调，依赖 tick listener 的 `future.isDone` 自检
- `ScanOps.countByBlock` 在 box 很大时是 O(N)，加 chunk 进度回调再说
- YAML test runner 没做（`mcdebug script` 占位）
- MCP adapter 没做（对话中"Layer 4"）
- GUI/FakePlayer 没做（对话中"第二阶段"）
- 鉴权 / 远程连接 没做
