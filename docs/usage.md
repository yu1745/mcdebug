# mcdebug 使用指南

最后更新时间：2026-11-15。

[项目概览](../README.md) · [JUnit / Gradle 客户端测试](testkit.md)

示例中的 `mcdebug` 代表 `java -jar mcdebug-cli.jar` 或原生可执行文件 `mdb`。
除非显式指定端点或会话，否则请在对应工程或游戏目录中执行命令。

## 安装与构建

使用 **JDK 25** 和 Gradle 9.6.0 构建模组。独立 CLI 仍支持 Java 17+。
本次移植基于 `1.21.1` 的提交 `72a70c2`，版本实现差异和验证说明见
[26.3 平台适配](port-26.3.md)。两个加载器的 Minecraft 运行时均使用官方名称。

```bash
./gradlew build              # Fabric 模组、协议模块及 JAR 自包含检查
./gradlew :cli:copyCliJar    # CLI 完整 JAR，输出到仓库根目录，不进 Git；内嵌两个加载器的 JAR

java -jar mcdebug-cli.jar --help
```

NeoForge 26.3 通过独立的嵌套构建使用**同一份源码**
（`./gradlew :neoforge:build|:neoforge:runServer|:neoforge:runClient`），
冒烟脚本为 `scripts/neoforge-smoke.sh`。
架构、验证结果和加载器差异见 [NeoForge 移植说明](plan-neoforge-port.md)。

编码代理可使用 Claude Code 技能了解工作流与常见问题；具体命令仍以 CLI 的 `--help` 为准。
CLI 携带与自身版本匹配的技能：`mcdebug skill` 将其写入
`~/.agents/skills/mcdebug/SKILL.md`，支持 `--output DIR` 和 `--print`，源码位于 `skills/mcdebug/`。
`mcdebug skill --name neoforge-fabric-port` 会把内嵌移植技能及其参考文件安装到
`~/.agents/skills/neoforge-fabric-port/`，同样支持 `--output DIR`。

下文的 `mcdebug` 均可替换为 `java -jar mcdebug-cli.jar` 或原生可执行文件 `mdb`
（见[原生可执行文件分发](#原生可执行文件分发)）。带标签的版本会在 GitHub Release 中附上
`mcdebug-cli.jar` 和模组 JAR；CLI 也携带同一次构建的模组 JAR，可通过 `mcdebug jar` 提取。

Fabric 工程创建说明：<https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up>。
NeoForge 工程创建说明：<https://docs.neoforged.net/docs/gettingstarted/>。

## 部署

构建产物是**普通模组 JAR**，复制到目标环境的 `mods/` 后重启即可。
服务端和客户端使用同一个 JAR（`environment: "*"`，包含两个入口）；
消费者工程无需添加依赖、插件或脚本。先移除旧 JAR，同一 `mods/` 中存在两份 mcdebug 会加载失败。
CLI 内嵌自身构建的模组 JAR，以下命令会安装相同版本，无需下载：

```bash
mcdebug jar --output path/to/consumer/run/mods                  # Fabric：mcdebug-<version>.jar
mcdebug jar --loader neoforge --output .../run/mods             # NeoForge：mcdebug-neoforge-<version>.jar
```

两种模式都会删除该目录中另一加载器的 JAR，因为相同 `modId` 的两个副本会中止模组加载。
两个 JAR 均内嵌未重定位的 Kotlin 标准库，mcdebug 无需 Fabric Language Kotlin 或 KotlinForForge。

单人客户端在同一进程中运行两个平面，因此 `world.*` / `be.*` / `inv.*` 和 `gui.*`
可同时操作同一个集成服务器。

## 传输与端点发现

Unix 域套接字是主要通道，用于本机并发访问，不对外暴露；TCP 用于跨机器访问。
二者独立绑定：单边失败只记录 WARN，两边均失败才报错。

| 项目 | 服务端平面 | 客户端平面（`gui.*`） |
|---|---|---|
| Unix 套接字 | `<gameDir>/mcdebug/socket` | `<gameDir>/mcdebug/clientSocket` |
| 发现文件 | `mcdebug/port` | `mcdebug/clientPort` |
| TCP | 25580，默认**启用** | 25581，默认**禁用**，不要暴露真实玩家客户端 |
| JSON / 属性 / 环境变量 | `socket`、`tcpPort`、`tcpEnabled` / `-Dmcdebug.*` / `MCDEBUG_*` | 相同名称加 `client` 前缀，如 `clientSocket`、`-Dmcdebug.clientSocket`、`MCDEBUG_CLIENT_SOCKET` |

两个平面的解析顺序相同：CLI / JVM 属性 → 环境变量 → `<gameDir>/config/mcdebug.json` → 默认值。
发现文件按尽力写入原则处理：`port` 存放套接字**路径**，`tcpPort` 存放实际绑定端口，
仅在 TCP 绑定后写入；`tcpPort=0` 表示请求临时端口。

CLI 通过 `--socket` / `MCDEBUG_SOCKET` / 发现文件查找套接字，不会自行在传输方式之间回退。
跨机器访问必须显式指定 `--tcp host:port` 或 `--client-tcp host:port`。
`gui.*`（包括 `raw gui.*`）自动使用客户端平面，其他方法使用服务端平面。

## CLI 使用方式

```bash
mcdebug status                                   # server.status，使用 Unix 套接字
mcdebug raw world.getBlock '{"pos":[0,64,0]}'
mcdebug --tcp <host>:25580 status                 # 跨机器访问
mcdebug -c 'status' -c 'world get-block --x 0 --y 64 --z 0' --batch   # 共用连接，遇错立即停止
mcdebug snapshot capture --from 0,64,0 --to 2,66,2 --include block,inventory
```

退出码：`0` 表示成功，包括业务层的 `ok:false` / `equal:false`，因此仍须检查 JSON；
`1` 表示 CLI 用法或解析错误；`2` 表示服务端拒绝请求，包括 `-320xx` 在内的任意 JSON-RPC 错误；
`3` 表示方法不存在，通常是模组 JAR 过旧。连接时 CLI 会比较自身与模组版本，不一致时向标准错误输出警告。

## RPC 命名空间

没有专用命令时，使用 `mcdebug raw <namespace.method> <json>`。

`world.attackEntity` 通过物品堆叠的原生属性遍历为假玩家装配属性，包含数据组件与动态物品属性。
攻击后会移除最初安装的修饰符，即使攻击使物品堆叠损坏或发生变化也一样。

`server.runCommand` 的命令反馈随 RPC 响应的 `output` 返回，默认**不再**向玩家发送原版管理员聊天提示。
需要恢复该提示时使用 `mcdebug raw --show-chat server.runCommand '{"command":"/time set day"}'`，
或直接在 RPC 参数中传入 `"showChat":true`（是否广播仍取决于服务器的 `sendCommandFeedback` 游戏规则）。
`/say` 等本身就会发送聊天消息的命令不受影响。旧版 CLI 连接新模组时得到的是静默行为；
旧模组忽略 `showChat`，保持原有聊天行为。

| 命名空间 | 用途 |
|---|---|
| `world.*` | 方块、区域、使用、攻击、交互和强制加载 |
| `be.*` | 方块实体 NBT 与字段；`be.tick` 对单个方块实体执行 N 次 ticker，每次 0..12000 次 |
| `inv.*` | 对原版 `Container` 进行槽位级访问（`getSlot` / `setSlot`）；搬运物品优先使用 `storage.*` |
| `storage.*` | 统一访问方块、实体和物品堆叠中的物品、流体与能量 |
| `fluid.*` / `craft.*` / `scan.*` / `entity.*` / `fixture.*` | 按罐访问流体（通用形式为 `storage.*` 加 `fabric:fluid`）、配方、搜索、实体和区域测试夹具 |
| `snapshot.*` / `trace.*` | 捕获和比较状态，在自然 tick 上记录帧 |
| `screen.*` | 用假玩家打开和操作服务端菜单（`AbstractContainerMenu`），**只有槽位 JSON，没有像素**；加载器拒绝方块的 `MenuProvider` 时，`openBlock` 回退为空手右键；`clickButton` 对应原版 `clickMenuButton` |
| `gui.*` | **客户端平面**，提供真实 GUI 截图和 Screen 点击，见下文 |
| `redstone.*` | 读取信号、设置或脉冲驱动拉杆、触发邻居更新 |
| `wait.until` | 在自然服务端 tick 上等待谓词，不驱动 tick；`--sprint` 通过原版 tick 管理器快进 |
| `reflect.*` | 从根对象（`server` / `level` / `be` / `entity` / `player`）、引用或类出发，沿字段或取值方法的 `path` 解析、读取、写入、调用和实例化；开发与生产环境均使用 Mojang 官方名称 |
| `gui.reflect.*` | **客户端** JVM 中的同一反射核心，根对象为 `minecraft` / `screen` / `player` / `level` / `server`，可访问打开的 Screen、控件和客户端缓存；CLI 使用 `reflect ... --client` |

`storage.*` 按顺序解析句柄：原版 `Inventory` → 加载器物品存储 → 流体存储 → 能量存储。
Fabric 使用 Transfer API，NeoForge 使用事务型 `ResourceHandler` / `EnergyHandler` 能力。
目标格式为 `{kind:"block"|"entity"|"item", ...}`；结果中的 `vanilla:inventory`、`fabric:item`、
`fabric:fluid`、`teamreborn:energy` 等句柄 ID 属于协议，两个加载器保持一致。
优先使用这些通用 API，并用 `be.tick` 推进机器自身进度，少用模组专属 NBT 直接修改。

流体数量使用各加载器的原生单位，见 `server.status.fluidUnit`：
Fabric 为 `droplet`，1 桶 = 81000；NeoForge 为 `mb`，1 桶 = 1000。

服务端不提供 `tick.run` / `tick.runUntil`：`trace.*` 和 `wait.until` 只观察自然 tick。
唯一有界例外是 `be.tick`，它只运行单个方块实体的 ticker。
缓慢过程的加速使用下文的**原版 tick 速率管理器**。

### 快进：压缩缓慢过程的等待时间

作物生长、需要多个游戏日的机器等长时间过程无需按现实时间等待。
加速复用原版 tick 速率管理器，mcdebug 不新增驱动 tick 的 RPC；
引擎仍决定何时发生 tick，只是去掉 50 毫秒的步进限制。

```bash
# 单次快进直到条件满足，冲刺预算为超时加余量；命中、超时或断连时自动停止
# 必须设置有限的 --timeout-ticks
mcdebug wait until --predicate "be[100,64,100].progress >= 1000" --timeout-ticks 72000 --sprint

# 有界冲刺：全速执行 N 个 tick，原版会自动停止；时间后缀为 t / s / d
mcdebug raw server.runCommand '{"command":"/tick sprint 3d"}'
mcdebug wait until --predicate "be[100,64,100].progress >= 1000" --timeout-ticks 72000
mcdebug raw server.runCommand '{"command":"/tick sprint stop"}'   # 提前停止

# 持续修改速率，不会自动恢复，须自行还原
mcdebug raw server.runCommand '{"command":"/tick rate 200"}'
# ...
mcdebug raw server.runCommand '{"command":"/tick rate 20"}'
```

- tick 域的语义不变：`wait.until` 超时和 `trace.start --max-ticks` 按 tick 计数，
  `snapshot.diff` 默认忽略 `/tick` / `/time`。
- `server.health` 在加速时报告实际 TPS，上限随目标速率变化，冲刺时上限为 10000。
- 只有由 tick 驱动的状态会加速，按墙钟计时的模组逻辑，例如 `System.currentTimeMillis` 冷却，不会加速。
- 冲刺期间原版使用 `hasTimeLeft=false` 运行 tick，部分可选区块或连接工作会延后。
  看门狗和自动保存会考虑当前速率。
- 不要混用手动 `/tick sprint` 和 `wait --sprint`，后一次冲刺请求会覆盖前一次预算。
- 集成服务器中的游戏必须未暂停，暂停期间冲刺不会推进 tick。

参数格式：盒状区域为 `{"from":[x,y,z],"to":[x,y,z]}`，两个角可任意排序且均包含在内；
实体查询按完整方块范围处理。缺少参数或类型错误返回 `-32602` 并指出参数名；
非预期服务端异常返回 `-32603`，附带 `data.exception` 和 `data.at` 中的堆栈顶部。
`snapshot.diff` 默认忽略持续变化的 `/tick` 和 `/time`，设置 `"ignoreMeta":false` 可关闭：

```bash
mcdebug raw snapshot.diff '{"before":{"tick":1,"blocks":[]},"after":{"tick":9,"blocks":[]}}'   # equal:true
mcdebug raw scan.findEntities '{"box":{"from":[0,64,0],"to":[0,64,0]}}'   # 查询该方块范围内的实体
mcdebug raw reflect.resolve '{"class":"net.minecraft.world.level.block.entity.BlockEntity"}'   # 返回官方名称 runtimeClass
mcdebug raw reflect.get '{"root":"server","path":"tickCount"}'
mcdebug reflect get --root server --path 'recipeManager.byName.keySet()' --limit 10000   # 大型结果分页，支持 --offset
mcdebug raw reflect.get '{"root":"be","pos":[0,64,0],"path":"items[0]"}'
mcdebug raw inv.setSlot '{"pos":[0,64,0],"slot":0,"item":"minecraft:diamond_sword","count":1,"components":{"minecraft:enchantments":{"levels":{"minecraft:sharpness":5}}}}'
mcdebug raw screen.clickButton '{"screenId":"<id from screen.openBlock>","button":0}'
mcdebug raw reflect.set '{"root":"be","pos":[0,64,0],"field":"litTime","value":200}'
mcdebug raw reflect.call '{"root":"level","method":"getBlockState","args":[{"new":"net.minecraft.core.BlockPos","args":[0,64,0]}]}'
mcdebug reflect get --client --root screen --path 'children[0].message.string'   # 客户端平面
```

谓词 DSL 支持 `be[x,y,z].<path> <op> <value>`、`storage[x,y,z].fluid.tanks.0.amount`、
`AND/OR/NOT`、`sum(...)` 和比较运算，详见 `mcdebug wait until --help` 及
[AGENTS.md 第 7 节](../AGENTS.md#7-tick-设计原则)。

## 托管会话（Linux 本机客户端）

会话管理真实客户端的生命周期及两个 RPC 平面。无需在另一个终端手动运行 Gradle：
mdb 启动**目标工程自己的**包装器，等待就绪后返回，客户端继续运行。
不会新增守护进程或使用另一套 MC 实现。消费者工程应先安装当前模组 JAR；
mcdebug 自身的开发启动会注入类，自己的 `run/mods/` 中**不得**再放一份 mcdebug JAR。

```bash
java -jar mcdebug-cli.jar session start demo --project . --world "New World" --render demand
java -jar mcdebug-cli.jar session status demo
java -jar mcdebug-cli.jar --session demo gui status
java -jar mcdebug-cli.jar --session demo raw server.status '{}'
java -jar mcdebug-cli.jar --session demo gui screenshot --mode window --out /tmp/window.png
java -jar mcdebug-cli.jar session logs demo --lines 80
java -jar mcdebug-cli.jar session stop demo
```

消费者有子模块时，显式选择实际游戏目录。相对路径以当前工作目录为基准，而非 `--project`：

```bash
java -jar mcdebug-cli.jar session start ic2 --project ../ic2-fabric-1.21.1 --task :core:runClient --game-dir ../ic2-fabric-1.21.1/core/run --world "New World" --render demand
```

| 命令或选项 | 行为 |
|---|---|
| `session start NAME` | 默认使用当前目录、`runClient` 任务、`project/run` 游戏目录、`demand` 渲染、854×480 窗口和 180 秒等待时间 |
| `--world SAVE` | gameDir/saves 下已有的存档目录名；等待单人世界就绪，省略时只等待客户端主菜单就绪 |
| `--game-dir PATH` / `--task TASK` / `--display DISPLAY` | 显式指定运行目录、完整 runClient 任务名、子进程显示设备 |
| `--wait-seconds N` | 有限的启动或停止等待时间；启动超时表示错误，不保证进程已停止 |
| `--show-toasts` | 托管客户端默认隐藏提示弹窗；传入后在启动时显示，也可用 `gui toasts on` 运行时切换 |
| `session attach NAME --game-dir PATH` | 验证已有本机客户端，**不取得停止权限** |
| `session list` / `session status NAME` | 报告元数据、PID 身份、端点、状态和错误 |
| `session logs NAME --lines N` | 返回有限行数的 UTF-8 日志尾部，不持续跟随 |
| `session stop NAME [--force]` | 正常停止自有客户端；显式强制停止仅可终止身份完全匹配的自有 MC 进程，不得终止共享 Gradle 守护进程或连接来的客户端 |
| `--session NAME` | 将 gui.* 路由到客户端，将其他方法路由到其集成服务器；没有全局当前会话 |
| `--sessions-dir PATH` | 会话注册表目录，依次回退到 MCDEBUG_SESSIONS_DIR、XDG_STATE_HOME/mcdebug/sessions、~/.local/state/mcdebug/sessions |

批处理继承所选会话与注册表。某个平面的共享连接打开后，若在 `-c` 命令中改变该平面的
会话、传输方式或超时时间，会拒绝执行，避免悄悄复用另一个目标；不同会话应分次调用。
托管启动会禁用两个 TCP 监听器，将 Unix 套接字固定在所选 gameDir 中。
会话第一版不处理跨机器或多人服务端配对，此时请在 `--session` 之外使用显式传输选项。

托管启动默认隐藏提示弹窗（移动教程、配方解锁、进度，以及走标准弹窗队列的模组通知），
避免遮挡截图或改变画面。`session start --show-toasts` 在启动时开启；
`mcdebug --session NAME gui toasts on|off` 在当前客户端进程内切换，关闭时清理已显示和排队中的弹窗，
重新开启后只显示之后的弹窗。`gui status` 的 `toastsEnabled` 报告当前状态。
模组通过启动令牌环境变量 `MCDEBUG_SESSION_TOKEN` 识别托管启动；普通启动与 `session attach`
保持原版行为。旧 CLI 启动的托管客户端同样隐藏弹窗，可用 `raw gui.toasts '{"enabled":true}'` 打开；
新 CLI 启动旧模组时弹窗照常显示，`gui toasts` 返回方法不存在（退出码 3）。

会话要求当前的 `server.ready` 和 `mcdebug/client.json` 携带进程身份：PID、操作系统进程
startTime、每个 JVM 的 instanceId、启动令牌与规范化 gameDir。
仅凭 PID、套接字文件或 Gradle 包装器 PID 均不够。端点被替换时会失败，
不会悄悄把命令发到另一场游戏。`--session` 不能与显式套接字或 TCP 覆盖选项混用。

同名、同配置且仍存活的启动会复用会话；同一规范化 gameDir 上的其他活动实例、配置或会话
都会被拒绝，即使使用不同注册表也一样。会话操作不会删除世界或 Minecraft 的 session.lock。
启动过程中停止可能报告 `stopping` 并返回非零退出码：启动令牌标记会取消迟到的 MC 启动，
但 mdb 不会伪造 JVM 已退出的身份。重试前应检查状态与日志。
该令牌用于关联所有权，**不是 RPC 身份认证**。

`gui.stop` 是底层身份校验停止 RPC，参数为 `instanceId` 和 `sessionToken`，
普通使用应通过 `session stop`。部署或发布此版本后，也可直接调用：

```bash
mcdebug --session demo raw gui.stop '{"instanceId":"INSTANCE_FROM_GUI_STATUS_PROCESS","sessionToken":"TOKEN_FROM_GUI_STATUS_PROCESS"}'
```

原始 RPC 有意绕过 CLI 的自有或连接会话策略，后者不构成安全边界。
详见[会话设计与安全规则](plan-session-management.md)。
启动时间、CPU / RSS 和截图延迟的实测数据来自 9 次隔离消费者试验，原始数据与限制见
[客户端性能报告](client-performance.md)。

## 客户端平面：`gui.*`

可直接进入存档启动客户端，无需逐级点击菜单。参数是 `saves/` 下的目录名，不是显示名称：

```bash
DISPLAY=:0 ./gradlew :core:runClient --args="--width 1280 --height 720 --quickPlaySingleplayer \"New World\""
```

每个游戏目录只能运行一个客户端。残留实例会持有 `saves/<level>/session.lock`，
下次启动会记录 `Failed to read level ... already locked` 并回到标题界面。

| 方法 | 功能 |
|---|---|
| `gui.status` | 客户端状态：Screen、窗口与 GUI 缩放、帧缓冲、端点、**已加载 JAR 的路径**和契约自检 |
| `gui.screen` | 当前 Screen：以 GUI 单位表示的容器槽位、悬停槽与提示、控件（含嵌套控件）的索引、文字、坐标、尺寸、值、焦点和提示 |
| `gui.screenshot` | 真实 PNG；`--mode window` 捕获帧缓冲，`--mode gui` 只捕获容器 GUI；附带 PNG 像素到 GUI 单位的转换信息，可选 `annotate` 编号标注、`drawLog` 帧绘制日志和仅返回像素哈希的 `format:"none"` |
| `gui.click` | 按容器槽位、**控件索引**、控件文字（可回退到本帧实际绘制的文字）、GUI 坐标或**上一张截图的 PNG 像素**点击，界面稳定后返回 |
| `gui.hover` | 将光标移到目标并渲染一帧，返回**该帧实际绘制的提示框**（含自定义 `renderBg` 提示）、悬停槽位和控件 |
| `gui.scroll` | 在目标处调用鼠标滚轮事件 `Screen.mouseScrolled`，随后等待界面稳定 |
| `gui.openScreen` | 打开 `inventory`、`pkg.Class` 或 `pkg.Class#staticFactory`，用于无方块的 Screen 夹具 |
| `gui.act` | 一次往返完成 click/hover/scroll/key/type/drag/close/openBlock/openScreen/reinit/reloadScreen、界面稳定、可选 `gui.screen`、截图及期间问题日志 |
| `gui.settle` | 被动等待服务端处理完先前发送的操作，且 GUI 状态保持稳定 |
| `gui.problems` | 按游标分页读取客户端 JVM 的 WARN / ERROR 日志及被捕获的 Screen 渲染异常 |
| `gui.hotswap` / `gui.reinit` | 从编译输出目录重新定义变化的类，全部成功才应用；重新运行当前 Screen 的 `init()` |
| `gui.reloadScreen` | 在新类加载器中用编译输出重建当前 Screen，允许其类发生**任意**变化，沿用同一个容器菜单 |
| `gui.openBlock` | 执行客户端自身的方块使用路径，打开机器 GUI |
| `gui.close` / `gui.connect` / `gui.reloadResources` | 关闭界面（Esc）、加入 `host[:port]`、执行客户端 F3+T 资源重载 |
| `gui.key` / `gui.type` | 向当前 Screen 派发单次按键或 Unicode 字符事件，支持仅在本次事件生效的逻辑修饰键 |
| `gui.drag` | 在同一个 Screen 上有界执行按下、分段 mouseDragged 和释放，使用自然 tick 被动延迟 |
| `gui.disconnect` | 保存并离开世界、返回标题界面，不停止 MC 或客户端 RPC |
| 仅 CLI 的 `gui compare` | 本地 PNG 基准图回归比较，无需 RPC 或客户端 |

```bash
# 代理迭代：在服务端平面布置机器，在客户端平面观察结果。
mcdebug raw world.setBlock '{"pos":[100,64,100],"block":"minecraft:chest"}'
mcdebug raw inv.setSlot   '{"pos":[100,64,100],"slot":0,"item":"minecraft:diamond","count":5}'
mcdebug gui open-block --x 100 --y 64 --z 100
mcdebug gui screenshot --mode gui --annotate         # 只捕获机器 GUI，并生成槽位和控件编号的 *.annotated.png
mcdebug gui click --slot 0 --shot gui                # 权威服务端槽位点击、界面稳定、新截图，一次往返完成
mcdebug gui click --widget 2                         # 使用 gui screen 或标注 PNG 中的控件索引
mcdebug gui click --png 48,78                        # 使用上次截图的 PNG 像素坐标
mcdebug gui hover --slot 0                           # 返回真实绘制的提示、悬停槽位和控件
mcdebug gui screenshot --mode gui --hide-cursor      # 基准图不含悬停、提示或手持堆叠
mcdebug gui problems --brief                         # 客户端启动以来的 WARN / ERROR 与渲染异常
mcdebug gui close
```

以下约定可减少排查时间：

- 截图等待**新渲染的帧**，不只是下一个 tick。`delayTicks` 可在请求该帧前添加被动延迟；
  `frameId` 和 `clientTick` 描述被捕获的帧，不代表此前服务端操作已得到确认。
- `--mode gui` 将容器矩形裁剪到帧缓冲边界。`transform.cropInGui` 保留小数边界，
  `pixelsPerGuiXY=[sx,sy]` 描述实际渲染投影，PNG 坐标为 `[guiX*sx-crop[0], guiY*sy-crop[1]]`。
  不要从四舍五入的 GUI 宽度推导缩放。`gui click --png` 会自动执行逆变换，
  但本客户端进程中必须先截图，否则返回 `reason:"NO_SCREENSHOT"`。
- 点击使用正常 Screen 事件或服务端权威槽位数据包。连续渲染模式下，默认移动真实光标，
  悬停和手持物视觉需要窗口焦点；轻量模式使用虚拟 GUI 光标，不移动桌面指针，也不要求焦点。
  `--no-cursor-move` 禁用光标更新。应以服务端状态为准，不能仅凭像素判断。
- 两种传输下的普通请求都有 **30 秒期限**。可用 `mcdebug --request-timeout 10 gui status`
  做短探测，或用 `--request-timeout 0` 关闭 CLI 期限。截图另有客户端 30 秒上限，
  包含被动延迟和编码，关闭 CLI 期限不会关闭此上限。`wait.until` 默认只采用服务端 tick 超时，
  不额外加墙钟期限，显式选项可覆盖。`--timeout` 控制连接与握手的毫秒数。
  请求超时会关闭本地连接，**不保证远端取消或回滚**。
- 版本警告使用 `server.ready` 握手，不额外发送状态 RPC。端点必须实现当前握手协议，
  不兼容旧服务端。
- `gui.connect` 要求客户端不在世界中，先用 `gui disconnect` 保存并离开。
  断开操作会明确拒绝加载覆盖层期间以及正在进行的 ConnectScreen 取消操作。
  等待是被动的，超时不会撤销已经请求的停止或保存。
- Java / Kotlin 方法体变化可热替换，见下文 `gui hotswap`。
  当前 Screen 自身类的结构变化（新字段、方法或 lambda）使用 `gui reload-screen`；
  其他类的结构变化需要重启，除非客户端使用带 `-XX:+AllowEnhancedClassRedefinition`
  的 JetBrains Runtime。纹理、语言和模型使用 `gui reload-resources`。
  怀疑部署未更新时，检查 `gui.status.jarPath`。

### 代理迭代流程

以下能力使代理能修改 GUI 代码并观察结果，无需人工操作或重启：

- **等待稳定而非固定休眠。** `gui.click` 原先固定等待 20 tick（1 秒），现在先发送服务端屏障：
  命令补全建议往返会由服务端主线程在先前数据包之后处理，再等待槽位、手持堆叠、Screen
  和控件保持 2 tick 不变。`waitTicks` 只是上限，`settle:false` / `--no-settle` 可恢复固定等待。
  容器数据槽（进度、能量）不参与稳定判定，因此运转中的机器仍能稳定。
  `gui settle` 单独执行同样的等待，例如用于 `be.setNbt` 之后。
- **每步一次往返。** `gui act`，或 `click` / `key` / `type` / `drag` 上的
  `--shot gui|window`、`--screen`、`--annotate`、`--draw-log`，会同时返回动作结果、
  稳定报告、截图、Screen JSON 和调用期间的问题。动作错误会以 `error` 随视图一同返回，
  代理仍能看到发生了什么。
- **可供模型操作的图像。** `--annotate` 在原始 PNG 旁写入 `<name>.annotated.png`：
  青色数字是槽位索引（`--slot N`），品红数字是控件索引（`--widget N`），
  `annotations` 提供包含 PNG 矩形的图例。原始 PNG 不会被修改。
- **绘制日志。** `--draw-log` 返回捕获帧中的每次 blit、填充、文字、物品和提示绘制，
  以及以 GUI 单位表示的屏幕矩形。`blit` 含纹理、归一化 UV、纹素 UV 和纹理尺寸，
  `blitSprite` 含精灵名。`renderBg` 中手绘的进度箭头和能量条不是控件，
  代理可通过此日志将其作为数据检查。每帧最多 5000 项。
- **低开销变化检测。** `--hash-only`（`format:"none"`）跳过 PNG 编码和文件写入，
  返回 RGBA 像素的 SHA-256，即 `pixelHash`。每次只读回裁剪矩形。
- **渲染异常不终止客户端。** 当前 Screen 的 `render` 抛错时，记录 ERROR，
  在 `gui problems` / `gui.status.screenGuard.fault` / `act.screenFault` 中显示，
  只停止该 Screen 绘制。修复后热替换并执行 `gui reinit`。
  `clientScreenGuard=false` 恢复原版崩溃行为。
- **热替换。** 先 `./gradlew classes`，再执行
  `mcdebug gui hotswap --classes build/classes/kotlin/client`。
  首次调用向同用户的本机客户端附加一个仅含单类的 JDK 代理；上次替换后变化的类经过
  Fabric 和 Mixin 转换器后重新定义，随后重新初始化当前 Screen，可用 `--no-reinit` 跳过。
  操作全部成功才应用：在标准 HotSpot 上，结构变化会在应用前逐类列出，
  如 `added method init$lambda$0(...)`、`added field ...`，退出码为 2，客户端保留旧代码，
  避免部分替换使新代码调用不存在的成员。
- **重载 Screen 类自身。** `mcdebug gui reload-screen --classes build/classes/kotlin/client`
  从输出目录将当前 Screen 类及嵌套类装入新的子优先类加载器，可用 `--also PREFIX` 扩展范围，
  然后替换为新实例。新增按钮、字段和 lambda 均可。容器 Screen 围绕**同一个菜单**重建且不关闭菜单，
  因而保留服务端容器、槽位和手持堆叠；普通 Screen 使用旧实例的父界面。
  新实例字段会重新初始化，其他类不变；访问同包其他类的 Java 包私有成员会失败；
  正常重新打开 GUI 时仍使用最初加载的类。
- **Screen 测试夹具。** `gui open-screen inventory | pkg.Class | pkg.Class#factory`
  可打开任意客户端 Screen。构造器或工厂可接收 `Screen` 父界面、`Player`、`Minecraft`、`Options`。
  可在模组开发源集中提供用测试数据填充 Screen 的工厂，Kotlin 使用 `@JvmStatic`。
  容器 Screen 需要服务端菜单，应使用 `gui open-block` 或基于 `player.inventoryMenu` 的工厂。
- **悬停、滚动与绘制文字。** `gui hover` 返回下一帧真实绘制的提示，包括能量条和液罐液量。
  `gui scroll --dy N` 滚动列表。`--text` 优先匹配控件标签，再匹配本帧绘制字符串，
  因而也能操作手绘按钮和列表项，此时 `mode:"drawnText"`。
- **稳定的基准图。** `--hide-cursor` 只在捕获帧把光标移出屏幕，去掉悬停高亮、提示框和手持堆叠，
  下一帧恢复正常。背包中的玩家等实体预览仍会动画，应使用 `gui compare --ignore` 遮罩。
- **轻量模式及时响应。** `demand` / `gui-only` 中，传入 RPC 会唤醒限速帧循环，
  不再等待 1/loopFps 休眠的剩余时间。

```bash
mcdebug --session demo raw gui.act '{"action":"click","params":{"slot":0},"screen":true,"screenshot":{"mode":"gui","annotate":true}}'
mcdebug --session demo raw gui.settle '{"maxTicks":40,"quietTicks":2}'
mcdebug --session demo raw gui.screenshot '{"mode":"gui","drawLog":true,"format":"none"}'
mcdebug --session demo raw gui.click '{"widget":2}'
mcdebug --session demo raw gui.problems '{"since":1,"level":"error","limit":50}'
mcdebug --session demo raw gui.reinit '{}'
mcdebug --session demo gui hotswap --classes build/classes/kotlin/client   # 原始 gui.hotswap 需要先附加代理
mcdebug --session demo raw gui.reloadScreen '{"dirs":["build/classes/kotlin/client"]}'
mcdebug --session demo raw gui.hover '{"slot":0}'
mcdebug --session demo raw gui.scroll '{"gui":[100,80],"dy":-3}'
mcdebug --session demo raw gui.openScreen '{"screen":"com.example.dev.Fixtures#machineScreen"}'
mcdebug --session demo raw gui.screenshot '{"mode":"gui","cursor":"hide"}'
```

单机实测条件：llvmpipe 软件 OpenGL、854×480、`demand` 模式、相同世界，
通过 Unix 套接字发送 RPC，不包含 CLI 启动；比较此前 JAR 与本次 JAR：

| 指标 | 此前 | 当前 |
|---|---:|---:|
| 世界视图中单次调用（`gui.status`，p50） | 37.1 ms | 0.8 ms |
| 打开箱子时的 `gui.screen`（p50） | 37.0 ms | 0.7 ms |
| `gui.screenshot --mode gui`（p50 / p95） | 37.0 / 48.4 ms | 21.5 / 24.0 ms |
| `gui.click --slot`（p50） | 988 ms | 102 ms |
| Screen 方法体变化生效 | 重启客户端，约 25–30 秒 | `classes` 9 秒 + `gui hotswap` 1.2 秒 |
| Screen 新增字段、方法或 lambda 生效 | 重启客户端，约 25–30 秒 | `classes` + `gui reload-screen`，无需重启 |

### Screen 输入与基准图断言

先用 `gui click` 为控件取得焦点。按键和文本仅针对当前 Screen，不操作系统按键或全局移动、调试绑定。
Ctrl / Shift / Alt / Super 是仅在本次事件中生效的逻辑修饰键，不改变物理键盘状态。
`--clear` 发送全选和退格，而非调用 `EditBox.setValue`。
输入报告会区分事件已派发、已处理与实际插入文字；应通过 `gui screen` 的控件值或权威服务端数据验证状态。

```bash
mcdebug --session demo gui key --key A --ctrl
mcdebug --session demo gui type --text '机器 GUI 😀' --clear
mcdebug --session demo gui key --key ENTER
mcdebug --session demo gui drag --from 100,80 --to 220,80 --steps 8 --step-ticks 1
mcdebug --session demo gui disconnect --wait-ticks 200
mcdebug --session demo raw gui.key '{"key":"A","modifiers":2}'
mcdebug --session demo raw gui.type '{"text":"机器 GUI","clear":true}'
mcdebug --session demo raw gui.drag '{"from":[100,80],"to":[220,80],"steps":8,"stepTicks":1}'
mcdebug --session demo raw gui.disconnect '{"waitTicks":200}'
```

同一时间只允许一个活动输入操作。拖拽总期限为 30 秒，分 1..128 段，每段被动等待 0..20 tick，
总计不超过 400 tick。Screen 或尺寸变化会中止剩余步骤，并向原目标释放输入。
`ok:false` / screenChanged 也可能表示按键成功跳转到另一个 Screen，而非没有作用。
错误或取消不会回滚已发生的部分编辑。文本必须是有效 Unicode，最多 4096 个 UTF-16 单元；
换行和控制键请用 `gui key`。

本地基准图比较不会隐式更新基线，也不会自动对齐或缩放图像：

```bash
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --diff artifacts/diff.png
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --channel-tolerance 2 --max-different-ratio 0.001
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --ignore 0,333,352,3 --report artifacts/review.html
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --accept   # 显式替换或创建基准图
```

可重复设置 `--ignore x,y,w,h`，遮罩光标、动画箭头或带小数裁剪边缘等 PNG 区域。
差异比例以实际比较的像素计算，差异图中的遮罩显示为蓝色。
`--report` 生成独立 HTML 页面，展示预期图、实际图与差异图供审查。
只有 `--accept` 会写入基线，随后退出码为 0。

不匹配时退出码为 **1**，JSON 报告像素计数、差异比例、最大通道差和边界；两张图尺寸必须相同。
默认精确比较包含透明度和隐藏 RGB 的 RGBA，容差和允许差异像素比例必须显式指定。
差异输出不能覆盖输入图或其别名。使用有界纯 Java PNG 编解码器，无需 AWT / ImageIO，
支持非交错 RGB8 / RGBA8，明确拒绝调色板、灰度、16 位、交错 PNG 和 tRNS。
须自行固定 GUI 缩放、尺寸、渲染模式、光标、提示、动画与目标 OpenGL / 字体环境；
比较功能不保证不同 GPU 产生确定一致的像素。

SDK / JUnit 消费者可在本地比较，也可请求新的**内联** PNG 并远程断言：

```kotlin
ScreenshotComparison.compare(expected, actual, diff = diff).requireMatch()
api.gui.assertScreenshot(expected, diff = diff, mode = "gui", channelTolerance = 0,
    ignore = listOf(ScreenshotRegion(0, 333, 352, 3)), hideCursor = true)
api.gui.act("click", mapOf("slot" to 0), screenshot = ScreenshotOptions(mode = "gui", annotate = true))
api.gui.hover(mapOf("slot" to 0))           // 该帧实际绘制的提示框
api.gui.openScreen("com.example.dev.Fixtures#machineScreen")
```

`expected`、`actual` 和 `diff` 均为 `Path`。SDK 断言不匹配时抛出 `AssertionError`，
保护基线且不会尝试读取远端截图文件系统路径。不需要新的测试 DSL 或插件。

### 轻量运行模式（Minecraft 26.3）

保留真实 MC 客户端及其模组、资源和网络生命周期，不修改 MC 分支实现。
将以下内容放入客户端 `<gameDir>/config/mcdebug.json` 后重启：

```json
{"clientRenderMode":"demand","clientLoopFps":20,"clientIdleFps":1}
```

| 模式 | 渲染策略 |
|---|---|
| `continuous`，默认 | 正常连续渲染，普通玩家客户端的行为不变 |
| `demand` | 空闲时每秒渲染 1 帧，截图触发包含世界背景的完整帧 |
| `gui-only` | 调度相同，但省略世界和 HUD 渲染，保留真实 Screen 渲染；世界预览或背景可能不同，必须显式启用 |

`clientLoopFps` 范围为 20..260，循环还会处理自然客户端 tick 与 RPC 任务。
`clientIdleFps` 为 0..loopFps，0 表示仅在截图请求或 Screen 变化时渲染。
限速只在世界已加载且没有 Screen 或只有容器 Screen 时生效。
启动、覆盖层、资源重载和其他 Screen 保持正常渲染，避免完成回调无法执行。
轻量模式阻止自动失焦暂停，但显式暂停界面仍遵循 MC 规则。

优先级：`-Dmcdebug.clientRenderMode` / `MCDEBUG_CLIENT_RENDER_MODE` / JSON / 默认值；
`clientLoopFps` 对应 `MCDEBUG_CLIENT_LOOP_FPS`，`clientIdleFps` 对应 `MCDEBUG_CLIENT_IDLE_FPS`。
例如，使用消费者自己的任务和存档：

```bash
MCDEBUG_CLIENT_RENDER_MODE=demand MCDEBUG_CLIENT_IDLE_FPS=0 DISPLAY=:0 \
  ./gradlew runClient --args='--width 854 --height 480 --quickPlaySingleplayer "New World"'
mcdebug raw gui.status '{}'
mcdebug raw gui.screenshot '{"mode":"gui","delayTicks":0}'
```

`gui.status.render` 报告策略、已渲染与跳过次数，以及待处理截图数。
它减少的是**稳定运行后的绘制开销**，不能省去图形上下文、首次资源或模型加载，
也不保证降低常驻内存。部分模组会在渲染钩子中更新状态，须用目标模组验证按需模式，
或对相关 Screen 使用连续模式。详见[设计与验证](plan-lightweight-client.md)。

## JUnit 5 测试运行器

依赖接入和独立 `frontend/` 构建见[测试前端接入说明](testkit.md#github-maven-依赖)。
本节介绍已有的服务端网格 API。

`mcdebug-testkit` 中的 `com.mcdebug.runner` 将 mcdebug 集成到 JUnit 5：
为测试类添加 `@McDebugTest`，注入 `TestContext`，即可获得网格分配的 `origin`、
每项测试区域清理、强制加载和可选的方法级并行能力，并使用包装 `DebugApi` 的辅助方法：

| 分组 | 辅助方法 |
|---|---|
| 放置与世界 | `place`、`placeAsPlayer`、`setBlocks`、`getBlockId`、`getBlockProp`、`assertBlockId`、`assertBlockNotId` |
| 方块实体 | `getBeField`、`getBeNumber`、`setBeField` |
| 库存 | `getSlot`、`getSlotItem`、`getSlotCount`、`setSlot`、`insertItem`、`assertSlotHas`、`assertSlotEmpty`、`assertSlotCount` |
| 等待与跟踪 | `waitUntil(ctx, predicate, timeoutTicks)`、`waitTicks`、`withTrace`、`traceBoxAround` |
| 谓词构造器 | `blockId`、`blockProp`、`beField*`、`invItem*`、`invCount*`、`tickEquals`、`tickGreaterOrEqual`，生成服务端谓词 DSL 字符串 |

```kotlin
@McDebugTest
class MaceratorTest {
    @Test fun grindsOre(ctx: TestContext) {
        place(ctx, ctx.origin, "<mod>:macerator")
        insertItem(ctx, ctx.origin, "minecraft:iron_ore", 1, 0)
        ctx.api.be.tick(ctx.origin, 20 * 20)     // 运行方块实体自身的 ticker，无需等待 20 秒墙钟时间
        waitUntil(ctx, invItem(ctx.origin, 3, "minecraft:raw_iron"), 25 * 20)
        assertSlotHas(ctx, ctx.origin, 3, "minecraft:raw_iron")
    }
}
```

也可直接在测试类路径中使用 `mcdebug-sdk`：

```kotlin
import com.mcdebug.sdk.DebugApi
import com.mcdebug.sdk.RpcClient
import com.mcdebug.sdk.RpcClientOptions

RpcClient(RpcClientOptions()).use { rpc ->
    val api = DebugApi(rpc)
    val before = api.snapshot.capture(mapOf(
        "box" to mapOf("from" to listOf(0, 64, 0), "to" to listOf(0, 64, 0)),
        "include" to listOf("block", "inventory", "energy"),
    ))
    api.redstone.pulse(listOf(0, 65, 0), 4)
}
```

网格运行器用于服务端检查。对真实客户端执行 JUnit 断言，请使用[客户端测试前端](testkit.md)。

## 原生可执行文件分发

JVM 冷启动约 790 毫秒；GraalVM 原生可执行文件约 **11 毫秒**启动，无运行时依赖，
适合在服务端脚本中调用 mcdebug。

```bash
./build.sh                                                # 输出 dist/mcdebug-cli.jar
GRAALVM_HOME=/path/to/graalvm ./scripts/build-native.sh    # 输出 dist/mdb，约 42 MB
./dist/mdb --socket <sock> status                          # 本地 Unix 套接字
./dist/mdb --tcp <host[:port]> status                      # 跨机器 TCP
```

## 许可证

见 [LICENSE](../LICENSE)。
