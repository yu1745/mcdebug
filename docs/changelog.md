# mcdebug 变更记录

从 AGENTS.md §11b 外移的已完成变更记录（新条目继续追加在顶部）。
当前开发规则与不变式看 `AGENTS.md`；完整提交历史看 git log。

- **`server.runCommand` 走原版执行上下文（未发版）**：改用 `Commands.performCommand`（结果经 `CommandResultCallback` 取回），1.20.3+ 的 `/function`、`/return`、宏函数不再报 `This function should not run`。语法错误照旧抛 `-32603 command execution threw`；执行失败按 `sendFailure` 的红色消息判定，抛 `-32603 command failed: <消息>`；function 本身无结果回调时返回 `success:false` 加反馈文本。

- **reflect 大值分页（未发版）**：`reflect.get`/`reflect.call`（含 `gui.reflect.*`）新增可选参数 `offset`/`limit`（1..10000，CLI `--offset/--limit`），`ReflectCore.Window` 同时作用于内联纯值数组（默认 200）与 ref 摘要（默认 20），摘要带 `offset`/`truncated`/`returned`；`limit` 显式给出时 map 一律走摘要分页。用途：一次取全部配方 id、某注册表的整张标签表（`--class ...BuiltInRegistries --path ITEM.tags --limit 10000`）。单测 `ReflectCoreTest`。

- **通用玩法驱动补强（未发版，来自 Tinkers 1.21 移植的真实玩法回归）**：①`screen.openBlock` 先走 `MenuProvider`，被 loader 拒绝（Fabric 扩展 screen handler、写额外开屏数据的菜单）或为空时，改为空手右键方块的真实交互路径，回答带 `openedBy`；②新 RPC `screen.clickButton`（原版 `clickMenuButton`，切石机/织布机/附魔台/模组选择器）；③物品参数统一支持 `components`（原版 `DataComponentPatch` JSON，RegistryOps；`ServerContext.itemStackFromJson/itemStackToJson`），输出逐组件编码、失败列入 `#errors`，`nbt` 仍只表示 custom_data；客户端平面用客户端 level 的注册表编码；④`wait.until` 新源 `storage[x,y,z].<item|fluid|energy|inventory>.<path>`（复用 `storage.get` 的 JSON，`PredicateExpr.jsonPath`）；⑤`server.status.playerList`；⑥客户端 `gui.hover`/`gui.screenshot` 的 `heldModifiers`（`GuiInputModifiers.held`，只在该次渲染期间生效，CLI `--hold-shift/--hold-ctrl/--hold-alt`）。⑦修复 NeoForge dev 运行（`runServer`/`neoforge-smoke.sh`）自 `67626bb` 起缺 Kotlin stdlib：`additionalRuntimeClasspath` 补上（发布 jar 本就内嵌，不受影响）。

- **0.8.0 快进/时间压缩**：不新增 tick 驱动 RPC，加速一律复用原版 tick-rate manager。①`server.health.tps` 的 `coerceAtMost(20)` 改为 rate-aware 上限（sprint→10000，否则 `max(20, tickrate)`），加速下不再恒报 20；②`wait.until` 新增 `sprint:true`（CLI `--sprint`）：申请原版 sprint 预算 `timeout+pollInterval+20`，命中/超时/求值异常/断连/uninstall 时自动 `stopSprinting()`，`SprintOwnership` 纯状态机（单测 `SprintOwnershipTest`）保证并发 wait 只有最后一个结束才停全局 sprint；`timeoutTicks=0`（无限等）+sprint 拒绝 `-32602`。③手动加速直通 `server.runCommand` + 原版 `/tick rate`/`/tick sprint`（README/SKILL 记录工作流）。双端冒烟各 +6 断言（sprint 启停、health tps>20、`wait --sprint` 1200 ticks 墙钟 <30s、命中后油门已收）。

- **reflect 补齐（未发版）**：`api/ReflectCore.kt`（与平面无关：ref 表、path、重载、参数转换、JSON）+ `ReflectOps`（服务端 root）。
  - 入口：`root` = `server`/`level`/`be`/`entity`/`player`（原来只能从 static 或 ref 出发，MC 没有拿 server 的静态入口）。
  - `path`：`a.b[0]["k"]`，段=字段→无参方法→getX/isX，`name()` 仅方法；**无参数、无表达式、无 eval**，要参数用 `reflect.call`。
  - ref 表：每平面全局 LRU（1024）、同一对象同一 id、停服清空。**刻意不绑连接**：CLI 每条命令一个连接，绑了 ref 跨命令即失效。对象集合整体一个 ref + `size`/`items` 摘要（原来每个元素一个强引用 ref、永不释放）。
  - 新 `reflect.set`（非 final 字段，数字按字段类型并做范围检查，enum 用常量名，返回 before/after）；重载按匹配度打分（精确 < 装箱/子类 < 拓宽 < 收窄可容纳的整数字面量），平局报错要求 `argTypes`；整数参数保留 long 精度（原来先转 double）；`InvocationTargetException` 展开为目标异常。
  - **帮助文本禁 `$`**：clikt 用 mordant 渲染帮助，一段文本里两个 `$` 会被当数学公式解析并抛
    `Unexpected token when parsing inlines`；父命令打印子命令短帮助，所以**任何一处** `$` 都会让
    `mcdebug --help`/`-h` 整体退出 1（0.6.3 真机踩到，ref 对象写成 `{"$ref":N}` 导致）。现在 ref 对象
    统一叫 `{"ref":N}`（`$ref` 仍接受），并有 `HelpRenderingTest` 遍历整棵命令树渲染帮助兜底。
  - **gui.act 的后续视图失败不丢动作**：截图/`screen` 失败改为记录在 `screenshotError`/`screenError`
    里（`ok:false`），动作本身的结果照常返回。`mode=gui` 需要容器 Screen，非容器时错误码仍是
    `SCREEN_NOT_FOUND`，但 reason 改为 `NO_CONTAINER_SCREEN` 并提示 `--mode window`。
  - **客户端平面 `gui.reflect.*`**（`src/client/.../ClientReflectOps.kt`）：同一 [ReflectCore]，root 换成 `minecraft`/`screen`/`player`/`level`/`server`，一律 `ClientTasks.onClient` 在客户端线程执行；ref 表按 client 实例重建，`CLIENT_STOPPING` 清空（否则 Screen/控件图被钉在内存）。契约 `Methods.CLIENT_METHODS` 已加 8 个 `gui.reflect.*`，客户端启动自检会双向核对。CLI 侧同一套 `reflect` 子命令加 `--client`，经 `withPlaneFor` 路由到 `gui.` 前缀。ItemStack 的友好展示改为 ReflectCore 的 `itemSummary` 钩子（服务端带 NBT，客户端只有 id+count），core 本身不依赖服务端 util。

- **0.6.3 服务端平面整理**
  - reflect 映射表：删掉打包的 1.20.1 yarn/mojang 表（1.6MB，类名在 1.21.1 已对不上，如 BlockEntity 混淆名 `czn`→`dqh`），`generateReflectMappings` 构建时从 Loom 的 1.21.1 映射生成 intermediary↔Mojang 单表（~620KB）；运行时按 `currentRuntimeNamespace` 决定翻译方向（dev=Mojang、生产=intermediary），成员沿继承链查找，同名重载给出多个候选按参数挑。输出字段 `yarnClass/yarnName` → `mojangClass/mojangName`。顺带修了 `world.useItemHold` 在 dev 里找不到 `updatingUsingItem` 的问题（原来按 yarn 名 `tickActiveItemStack` 找）。
  - 参数与错误：`api/Params.kt` 集中所有参数读取，缺参/类型错一律 `-32602` 并写明参数名（原来 42 处 `getAsJsonArray` 缺参时是 `-32603 ... must not be null`）；意外异常 `RpcErrors.internal` 带异常类与栈顶帧。
  - `be.tick` 上限 0..12000（§1 例外条款）；`snapshot.diff` 默认忽略 `/tick` `/time`（`ignoreMeta:false` 才比较，CLI `--include-meta`）；`scan.findEntities` 改为整块包含语义（原来四周各扩 1 格）；`inv` 的 `slot` 接受数字；CLI `craft do` 的 `N=` 前缀真正按槽位放置并补齐 9 格；scan 帮助文本的返回形状改正。
  - 删除 Forge 1.20.1 移植与两个 Kilt 时期诊断 RPC（见 §12）；`WorldOps.kt` 拆出 `WorldPlayerActions.kt`。

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
