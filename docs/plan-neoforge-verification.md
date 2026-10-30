# NeoForge 移植的黑盒验收（CLI + `-h` + skill，无源码）

> 目的：回答“**只给一个 CLI jar 和它的 `-h`/skill，一个不知情的 agent 能不能把 NeoForge 版用起来**”，
> 而不是“源码里逻辑对不对”（那部分是 `docs/plan-neoforge-port.md` §11 与冷读审查负责的）。
> Fabric 版历史上经过同类验证；本文件把同一套验证固化成可重复的批次任务。

## 0. 角色与硬规则

| 角色 | 做什么 |
|---|---|
| 环境准备（维护者） | 用 `scripts/neoforge-consumer/` 起被测实例（发布 jar + KotlinForForge），准备每个 agent 的隔离目录 |
| 被测 agent（子代理） | 只看得到 **CLI jar**、`--help`/子命令帮助、`mcdebug skill --print` 的内容；在自己目录里跑任务、记录证据、写报告 |
| 维护者 | 汇总失败项，判定是“文档/skill 缺口”“环境问题”还是“NeoForge 侧 parity bug”，**不改协议/ABI**，必要时上报决策 |

**被测 agent 的硬规则**（写进每个批次的 `TASKS.md`）：

1. 不得阅读、搜索、推断 mcdebug 的源码或本仓库任何文件；只允许：`java -jar <CLI>`、它的帮助、
   `mcdebug skill --print`、以及你自己目录里的 `TARGET.md`/`TASKS-*.md`/`REPORT.md`。
2. 不得重启、停止、杀死被测实例，不得改动它的 mods/gameDir，不得执行 `./gradlew`。
3. 失败**不要修**：原始命令 + 原始响应（截断到关键字段）抄进报告。失败即为结论。
4. 只动自己批次的坐标区域（见 `TARGET.md`），避免和别的批次互相踩。
5. 每条任务都要有**可复核证据**：命令 + 关键 JSON 字段（不要只写"成功"）。

## 1. 环境形态

| 实例 | 起法 | 服务谁 | 关键事实 |
|---|---|---|---|
| `server-target` | 消费者项目 `./gradlew runServer` | B1 | 发布 jar + KFF 在 `run/mods/`；gameDir/`--socket` 见 `TARGET.md`；无显示 |
| `client-target` | 消费者项目 `./gradlew runClient`（`--quickPlaySingleplayer` 进世界） | B2 → B3（顺序） | 同一 gameDir 同时有 `<gameDir>/mcdebug/clientSocket`（客户端平面）与 `<gameDir>/mcdebug/socket`（集成服务器平面） |
| `session-target` | agent 自己 `mcdebug session start` | B4 | 另一份消费者项目副本（独立 gameDir，不能与上面共用） |

- CLI jar 一律用**被测构建**里 `:cli:copyCliJar` 的产物，交给 agent 的是副本（`/…/cli/mcdebug-cli.jar`）。
- skill 内容用 `java -jar mcdebug-cli.jar skill --print` 拿（与 jar 同版本，正是消费者 agent 会看到的）。
- 两个实例的 TCP 默认关闭/不依赖；发现文件（`mcdebug/port`、`mcdebug/clientPort`）就是 agent 的入口。

## 2. 批次任务

### B1 — 服务端平面（不碰像素）

| # | 任务 | 期望 |
|---|---|---|
| 1 | `status` / `status --health` | `modLoader` 报 `neoforge`、有 `fluidUnit`、health 有 TPS/维度/区块数 |
| 2 | `world.setBlock` + `getBlock` + `world.fillBox` + `world.replaceBox` | 状态/`props` 往返一致；`fillBox` 计数正确 |
| 3 | `inv.setSlot` / `inv.getSlot`（含 `nbt` 自定义数据往返） | 数量与 nbt 读回一致 |
| 4 | `be.getNbt` / `be.setNbt` / `be.setField` / `be.tick`（容器类 BE） | NBT 读写一致；`be.tick` 有上限（`ticks` 过大 → -32602） |
| 5 | `storage.list` → `storage.get` → `insert`/`extract`（`simulate` 与真实） | 句柄出现在 `list`；`simulate` 不改状态；真实调用改变状态 |
| 6 | `storage.transfer` 两个容器（item）；再来一次 fluid（水桶↔容器或桶↔桶） | `transferred`/`extracted` 与随后 `get` 的实际数量一致；单位是 mB |
| 7 | `fluid.info`/`fluid.get`/`fluid.insert`/`fluid.extract` | 与 `storage.*` 的同一实体数据一致 |
| 8 | `craft.craft`（原木→木板） | 产物 id/数量正确 |
| 9 | `scan.findBlocks` / `scan.countByBlock` / `scan.findEntities`（先 `entity.spawn` 一只猪） | 计数与坐标符合预期；找不到时 `count:0` 而不是报错 |
| 10 | `snapshot.take` + `snapshot.diff`（改一格后再 diff） | `equal:false` 且差异定位到该坐标；`ignoreMeta` 行为符合帮助 |
| 11 | `trace.start`（带 `maxTicks`）+ `trace.get` | 有帧数据；到期 `autoStopped:true` |
| 12 | `redstone.get` / `redstone.setLever`（先垫支撑方块，`state` 用帮助里的写法） | 拉杆放下成功、`powered` 变化 |
| 13 | `entity.spawn` → `entity.get` → `entity.setNbt` → `entity.attack`? → 清理 | 实体存在/字段可读；不支持的组合报明确错误 |
| 14 | `wait.until`（成立条件、超时条件各一次） | 成立 `matched:true`；超时明确 `-32006` |
| 15 | `reflect.resolve`/`get`/`set`/`call`（用帮助里给的类名写法） | 能读到 `Blocks.STONE` 之类；写非 final 字段成功；错误类名报 `-32602` |
| 16 | 错误契约：缺参、类型错、坐标越界、未知方法 | 分别是 `-32602`（点名参数）/`-32001`/`-32601` |
| 17 | 平面隔离：对服务端实例调用一个 `gui.*` 方法 | `-32601`（服务端没有客户端平面） |

### B2 — 客户端平面（像素与输入）

| # | 任务 | 期望 |
|---|---|---|
| 1 | `gui.status --brief`、`gui.status`（完整 JSON） | 有 `screen`/`inWorld`/`window`/`framebuffer`/契约自检字段 |
| 2 | `gui.screenshot --mode window`（默认落盘） | PNG 尺寸=窗口；返回 `pixelHash`/`sha256`/`transform`；文件真的存在 |
| 3 | `gui.screenshot`（`--crop`/`--annotate`/`--draw-log`/`--format none`/`--cursor hide` 各一次） | 各自语义符合帮助（裁剪尺寸、annotated 另存、drawLog 有 op 列表、none 不落盘） |
| 4 | `gui.screen`（递归控件树）+ `--brief` | 控件有 index/类型/文本/坐标；`--text` 能找到目标 |
| 5 | `gui.click --text <可点文案>` + `--shot window` | `ok:true`、`via` 是 text、切屏或状态变化可观测 |
| 6 | `gui.click --widget N` / `--gui X,Y` / `--png X,Y`（三种定位各一次） | 三种都能命中（用 `gui.screen` 给的坐标/序号） |
| 7 | `gui.hover --gui X,Y`（悬停到有 tooltip 的控件） | 返回该帧真正画出的 tooltip 文本 |
| 8 | `gui.scroll --dy N`（在可滚动的界面里） | `ok:true`，界面/控件位置变化 |
| 9 | `gui.key`（按 `E` 或 `Esc` 之类）+ `gui.type`（往输入框写 ASCII 与中文） | 键/文本生效；中文往返不乱码 |
| 10 | `gui.drag`（从一个 GUI 坐标拖到另一个） | `ok:true` 且起点/终点都在报告里 |
| 11 | `gui.settle` | 返回 `settled` 与 `ticks`，不报错 |
| 12 | `gui.problems --brief`（先在界面上制造一次点击引发的变化） | 返回 WARN/ERROR 列表，`next` 游标可用 |
| 13 | `gui.reinit` | 成功返回且界面仍可用（随后再截一张图） |
| 14 | `gui.reload-resources` | 成功返回（不要求像素变化） |
| 15 | `gui.reload-screen`（对当前容器界面） | 成功或明确说明需要什么参数；失败要给可操作错误 |
| 16 | `gui.hotswap`（故意给一个不存在的 `--classes` 目录） | 明确错误（不是崩溃、不是静默成功） |
| 17 | `gui.compare`（对两张同一画面截图做比较 → 期望相同；再跟一张明显不同的图比 → 期望不同/退出码 1） | 两种结果都要观察到 |
| 18 | `gui.reflect`（`--client`）读当前 Screen 的类名 | 返回 Screen 的真实类名 |

### B3 — 跨平面闭环（工具存在的理由）

| # | 任务 | 期望 |
|---|---|---|
| 1 | 服务端摆一个容器方块 + `inv.setSlot` 放物品 → `gui.screenshot --mode window` → `gui.screen` | 截图与控件树都与刚摆的场景一致 |
| 2 | 打开该方块的界面（用帮助里给的方式，如 `gui.open-block`/`gui.open-screen`）→ `--mode gui` 截图 | 界面打开、GUI 模式截图（尺寸=gui 区域） |
| 3 | `gui.click --slot N` 取出一件物品 → 服务端 `inv.getSlot` 复核 | 服务端槽位真空了/手上多了该物品（**以服务端数据为准**） |
| 4 | `snapshot.diff`（点击前后各一次） | 差异只出现在预期槽位 |
| 5 | `trace.start` → 点击 → `trace.get` | trace 里有该帧；无崩溃 |
| 6 | `screen.*`（服务端假玩家菜单）与 `gui.*` 对同一界面各取一次视图 | `screen.*` 给槽位 JSON（无像素），`gui.*` 给像素；两者槽位数一致 |
| 7 | 关闭界面（`gui.click` Esc 或 `gui.close`）→ `gui.status.inWorld` 仍为真 | 回到世界，客户端平面正常 |

### B4 — session 与批处理

| # | 任务 | 期望 |
|---|---|---|
| 1 | `mcdebug session start <name> --project <consumer> --display <:N> --wait-seconds 300` | 返回记录；`state` 到 `client_ready`；`clientSocket` 非空 |
| 2 | 带 `--world <存档目录名>` 再起一次 | `state=world_ready`，且服务端平面 socket 也在 |
| 3 | `--session <name>` 路由两平面（`status`、`gui status`、`gui screenshot`） | 两条命令分别落到两个平面，都成功 |
| 4 | `session status` / `session logs --lines N` | 状态一致；日志可读（选项是 `--lines`，没有 `--tail`） |
| 5 | `session stop` | `state=stopped`；真实进程消失；残留 socket 不应再可连 |
| 6 | `--batch`（`-c` 多条）与 `--command` 单条 | 退出码语义符合帮助（0/1/2/3） |
| 7 | `raw <method> '<json>'` 直调 + `repl`（如果帮助里推荐） | 与专用子命令结果一致 |

## 3. 报告格式（每个 agent 交一份）

```markdown
# <批次> 报告
实例: <endpoint / 发现文件路径>   CLI: <jar 路径与版本输出>
## 逐条结果
| # | 任务 | 结果 | 证据（命令 + 关键响应字段） |
|---|---|---|---|
| 1 | status | PASS | `status` → `modLoader=neoforge`, `fluidUnit=mb` |
| … |
## 失败项（原样）
### F1 <任务>
命令: …
响应: …（截断到关键字段）
期望: …（来自帮助/本文件）
影响: 阻塞/可绕过（说明怎么绕过）
## 帮助/skill 缺口
（文档没说清、必须靠猜的地方；这本身就是结论）
## 未能判定
（需要维护者用源码/另一实例才能判定的问题）
```

## 4. 判定规则（维护者）

| 现象 | 判定 | 动作 |
|---|---|---|
| 与帮助/skill 描述不一致 | 文档缺口或 parity bug | 先复现，再决定改文档还是改代码 |
| Fabric 上行为相同、无关 loader | 既有行为（不是本次移植引入） | 记录，不改 ABI |
| 只在 NeoForge 上失败、且需要改 RPC 字段/方法名/默认值才能修 | **协议/ABI 变更** | **停下上报**，由用户决定是否连 Fabric 一起改（默认不改） |
| 环境/显示/GL 相关 | 环境问题 | 换 display/重试，不算失败 |

## 5. 执行结果（2026-10-30/31，本机真机）

三轮验证：**① 冷读审查**（无记忆子代理，两轮，13 个接缝文件对 + `neoforge/build.gradle`）→ **② 修复一轮** →
**③ 黑盒验收**（四个批次，子代理只拿到 CLI jar + `-h` + `skill --print`，在隔离目录里驱动发布 jar 起的实例）。
跨 loader 差分（`scripts/parity-diff.py`，53 条任务）作为第 ④ 层并行跑。

被测实例：全部是 **NeoForge 21.1.265 / MC 1.21.1 + 发布 jar `mcdebug-neoforge-0.7.0.jar` + KotlinForForge**，
用 `scripts/neoforge-consumer/` 拷出来的消费者项目（B1 专用服、B2/B3 客户端 + 集成服务器、B4 由 CLI `session start` 自起）。

| 批次 | 范围 | 结果 | 关键证据 |
|---|---|---|---|
| B1 | 服务端平面 17 项 | **17 PASS / 0 FAIL** | `modLoader=neoforge`、`fluidUnit=mb`、契约 79/79；方块/物品/NBT/存储/流体/合成/实体/快照/trace/红石/wait/reflect/错误码全通 |
| B2 | 客户端平面 18 项 | **16 PASS / 1 PARTIAL / 1 FAIL** | 截图（含 annotate/drawLog/format:none/cursor:hide）、`gui.screen`、四种点击、scroll、key/type（中文往返）、drag、settle、problems、reinit、reload-resources/-screen、hotswap、compare、`reflect --client` 全通；FAIL 见 F1、PARTIAL 见 F2 |
| B3 | 跨平面闭环 7 项 | **7 PASS / 0 FAIL** | 服务端 `setBlock` 箱子+`inv.setSlot` 钻石×4 → 客户端 `gui open-block` 成功 → `--mode gui` 截图 352x336 = `cropInGui[125,36,176,168]`×2 精确吻合、drawLog 里 `minecraft:diamond` 画在槽 0 → `gui click --slot 0`（`slotAfter` 空、`carried` 钻石×4）→ 服务端 `inv.getSlot` 为空 → shift-click 后 `entity.getNbt` 的 Inventory 出现 `{diamond,4,Slot:8}`（可逆）；`trace` 91 帧抓到该槽由空变钻石那一帧；`snapshot.diff` 只有 6 条变化且全在预期槽位路径；`screen.*` 与 `gui.*` 都报 63 槽、slot0 内容一致（前者无像素字段）|
| B4 | managed session 7 项 | **7 PASS / 0 FAIL** | `session start`（无 world → `client_ready` 28.4 s；`--world` → `world_ready` 30.7 s）、`--session` 双平面路由、`-c`/`--batch` 退出码 0/1/2/3、`raw` 与子命令一致、`repl` 管道可用、`session stop` 4.1 s 且 socket 不可连、零残留 |
| ④ | 跨 loader 差分 53 项 | **53/53 一致（0 非预期差异）** | 4 项已文档化差异标 EXPECTED（loader 名/runtime 命名空间/短类名）、4 项夹具复位标 RESET |

### 验收暴露的问题与处置

**A. 移植/实现缺陷（已修，均不改协议/ABI）**

| # | 问题 | 处置 |
|---|---|---|
| 1 | `storage.transfer` 在 Fabric 侧丢失原子性（审查①发现） | 见 `docs/plan-neoforge-port.md` §11.3 #12：seam 加 `moveItemsAtomically/moveFluidsAtomically`；两个冒烟各加 6 条护栏（目标填满 → `transferred:0` 且来源不变） |
| 2 | NeoForge 物品槽 `capacity` 报 99（Fabric 64，且物理上插不进） | `min(槽上限, 栈真实上限/64)`；差分从 DIFF 变 same |
| 3 | 物品承载的**能量**存储从不返回 `targetAfter`（审查②） | `NeoForgeEnergyStorage` 记住宿主栈，`itemAfter()` 返回其 `copy()` |
| 4 | `itemStorageOf(container, side)` 忽略 side | side 非空且是 `WorldlyContainer` 时用 `SidedInvWrapper` |
| 5 | `fireUseEntity(hitResult=null)` 伪造成 `EntityInteractSpecific`，`EntityInteract` 监听者收不到 | 按 hitResult 分流到 `onInteractEntityAt` / `onInteractEntity` |
| 6 | `processResources` 只点名拷两个 mixin 配置，将来加共享资源会静默漏出 jar | 改成整树同步 `src/main/resources`（排除 Fabric 专属）+ `src/client/resources` |
| 7 | `gui.status.jarPath` 在 NeoForge 返回 `/`（`codeSource` 在模块层退化成 `/`） | seam 加 `LoaderApi.modJarPath()`（NeoForge 用 `ModList.getModFileById(...).file.filePath`）；实测打包态返回真实 jar 路径 |
| 8 | 客户端停止在 NeoForge 上是 shutdown hook，清理可能静默 no-op | 先 `Minecraft.execute{}` 到客户端线程（≤2 s），超时才就地跑一次，失败写 WARN |

**B. 文档/skill 缺口（已修，属"黑盒可用性"结论）**

| 来源 | 缺口 | 处置 |
|---|---|---|
| B1 | `fluid insert/extract --help` 写死 DROPLETS（本实例是 mB） | 帮助改成"单位随 loader 变，读 `server.status.fluidUnit`" |
| B1 | `fill-box` 在未加载区块报 -32008，帮助没指路 | 帮助补 `world forceload --cx/--cz`（并点明 `get-region` 的 auto-load 不持久） |
| B1 | 0 玩家服务器上实体 ~3 s 消失、`entity spawn` 不贴地，像 `find-entities` 坏了 | skill 加"实体夹具"一节（垫地板、`-c` 批处理秒完、先复查计数） |
| B1 | `inv.setSlot --nbt` 落到 `components."minecraft:custom_data"`，`getSlot` 原样回显造成"成功幻觉" | skill 说明 1.21.1 组件语义，断言要走 GUI/行为 |
| B1 | `snapshot.take` 不存在（真名 `capture`）、没有列方法的命令 | skill 写明方法名与"用分组 `--help` + skill 代替方法清单" |
| B1/G | `gui.*` 用服务端 transport 调用在 CLI 层就被拦（exit 1），永远看不到 -32601；`--socket` 对 `gui.*` 静默忽略、发现按 CWD 解析 | skill 新增 "4b. Two planes, one CLI" 一节 |
| B2 | **tooltip 断言的硬前提**：continuous 模式靠真实光标，窗口失焦时 hover 恒为空 | skill 顶部醒目说明；**agent/CI 会话要用 `--render demand`**（lite 的虚拟光标） |
| B2 | `gui.screenshot` 的 raw 参数面未文档化、未知参数静默忽略 | skill 加"旗标 ↔ raw 参数"对照表（并说明没有 `crop`，`mode:"gui"` 才是裁剪） |
| B2 | `hoveredSlot` 编号体系、drag 不回显起点、`--gui/--png` 缺 `via/before`、无屏时 key/type 复用 -32020 | skill 在对应小节逐条写明 |
| B2 | `gui compare` 失配时 stdout 是 JSON + 人类可读行、同画面因玩家模型动画仍需 `--ignore` | skill 回归一节写明 |
| B4 | `MCDEBUG_SESSIONS_DIR` 环境变量、`--render` 默认值（demand，非全局 continuous）、session 状态机、stop 后 wrapper 还会活 ~10 s、`session logs --lines`（无 `--tail`） | skill 会话一节补齐；CLI 帮助补 `--render` 默认值与 `--batch` 语义 |

| B3 | 世界帧像素"是否与摆放一致"不可直接定位：`gui screenshot` 没有区域裁剪旗标；下雪天两帧无编辑截图也差 91.4% 像素 | skill 补"世界帧怎么验"策略：容器/界面用 `--mode gui`（可定位到槽），世界帧用 `pixelHash` 变化 + 逻辑证据（`open-block` 成功、drawLog 里画出物品）+ `gui compare --ignore`；天气/实体动画是噪声源 |
| B3 | `snapshot`/`storage` 里空槽 `capacity` 的取值规则、`trace.get` 帧结构 `{tick, snapshot}`、`gui screen` 的槽坐标是**绝对 GUI 单位**而 `screen.*` 是容器相对坐标（两者偏移关系）未文档化 | skill 各补一行 |
| B3 | `mcdebug server --help` 打印根帮助且 exit 0（`server` 并不是命令组），像"拼写错误静默成功" | **不是缺陷**：clikt 的 `--help` 优先于子命令校验；不带 `--help` 时 `mcdebug server` / `mcdebug statu` 都是 **exit 1** 且后者还提示 `Did you mean status?`（实测）。结果记录在案，不改 |

**C. 原先"有意不改"、用户批准后已改的 5 项**（共享代码，两个 loader 同步；定性见 §5 的"用户批准后的共享代码收尾"表）

| # | 现象 | 处置 |
|---|---|---|
| 1 | `raw craft.craft` 传字符串 grid → `-32603 IllegalStateException`，不是 -32602 | 改成 **-32602** 并点名 `grid[i]` + 正确形状；两个冒烟各加一条回归断言，差分加一条任务（两侧一致） |
| 2 | `reflect get <大集合>` 静默截断（201/1060）无标记 | 摘要分支补 `truncated:true` + `returned:N`；内联数组分支的 `$truncated` 计数保留并写进 skill |
| 3 | `reflect set` 强制 `--field`（`get` 却接受 `--path`） | `set` 也接受 `--path`，末段当字段名（`overworld.dayTime`、`items[0].count`），以方法/下标结尾时报用法错误 |
| 4 | NeoForge 满罐报"不可注入"但实际能注入 | 罐 `capacity<=0`（容量未知）不再算"已满"；`storage --help` 直接写明两边 `supports*` 的语义差别 |
| 5 | `server.status` 没有能量单位字段 | 新增**加性**字段 `energyUnit` = `E`（Fabric）/`FE`（NeoForge）；两个冒烟断言，差分把它列为已文档化差异 |

（G6 顺带修：`reflect --class` 的帮助原先说"Fabric 短名/intermediary 也行"，NeoForge 上只认 FQN，帮助已改。）

**批准后的第四轮门禁**（2026-10-31，5 项共享代码改动落地后）：

| 门禁 | 结果 |
|---|---|
| 单测 | mod 147、CLI 141，全绿；两个构建 0 warning |
| Fabric 冒烟 | **51/51**（新增 `status fluidUnit=droplet`、`status energyUnit=E`、`craft grid cell type` 三条） |
| NeoForge 冒烟 | **45/45** + 契约 79/79（新增 `status energyUnit=FE`、`craft grid cell type`） |
| 跨 loader 差分 | **53/53 一致**（新增 `craft grid cell type` 严格比较；`status` 把 `energyUnit` 与 `fluidUnit`/`modLoader` 一起列为已文档化差异）。夹具修正：实体从 `pig` 换成 `armor_stand`（`Mob` 在 0 玩家服务器上会被 `checkDespawn` 丢掉，跨实例时序不同 → 之前那 1 条 DIFF 是夹具竞态，不是 parity 问题） |
| 行为实测 | `server.status.energyUnit` = `E`/`FE`；字符串 grid → `-32602 grid[0] must be null or an object like {...}`（两个 loader 同）；`reflect get <1290 项集合>` → `size:1290, truncated:true, returned:20`（两侧一致），内联大数组末尾 `{"$truncated":860}`；`reflect set --path tickCount --value 0` 真的按"末段=字段名"写入（`before/after` 可读） |

**F1（B2 hover）的定性**：**不是移植缺陷**。同一复现（`give emerald` → `open-screen inventory` → `hover --slot 36`）在
`demand`（lite）模式下完全正常：`hoveredSlot=36`、`stack=emerald x3`、`tooltips=["Emerald"]`；continuous 模式下失败的原因是
**窗口失焦时 Minecraft 不更新内部鼠标**（headless/Xvfb 无窗口管理器），而 continuous 依赖真实光标 warp。Fabric 侧同理（共享代码与同一 GLFW 语义），已在 skill 里定为"tooltip 断言的硬前提"。

**F2（B2 crop）的定性**：**任务包写错**。`gui.screenshot` 从来没有 `crop` 参数（只有 `mode=window|gui`），
`crop` 只是响应里的 `transform.crop` 字段。真实缺口是"未知 raw 参数被静默忽略"（已写进 skill 的参数表说明）。

## 5b. 复现方式

```bash
# ① 起一个被测实例（发布 jar + KotlinForForge，消费者项目）
F=$(mktemp -d /tmp/mcneo-XXXX); cp -r scripts/neoforge-consumer/. "$F/"; cp gradlew "$F/"; mkdir -p "$F/gradle/wrapper"
cp gradle/wrapper/gradle-wrapper.{jar,properties} "$F/gradle/wrapper/"
java -jar mcdebug-cli.jar jar --loader neoforge --output "$F/run/mods"
for j in $(find ~/.gradle/caches/modules-2/files-2.1/thedarkcolour -name '*5.12.0.jar' | grep -v sources); do cp "$j" "$F/run/mods/"; done
(cd "$F" && ./gradlew runServer)          # 或 runClient（--render demand，见 B2/F1）

# ② 把 CLI 交给一个"不知情"的 agent：只给它 jar、-h、skill --print 和本文件的批次表
java -jar mcdebug-cli.jar skill --print

# ③ 跨 loader 差分（两边都要起着）
python3 scripts/parity-diff.py --a <fabric socket> --b <neoforge socket>
```

## 5. 与 `docs/plan-neoforge-port.md` 的关系

§11 记录的是**移植实现与真机验证**（含已知的 loader 语义差异表 §11.4）；本文件记录的是**黑盒可用性验收**，
两者互为补充：前者回答“我们做了什么、为什么这样”，后者回答“外人拿着 jar 能不能用、哪里会卡住”。
