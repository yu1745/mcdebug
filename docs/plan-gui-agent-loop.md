# GUI agent 迭代回路：settle / act / 标注 / draw log / screen guard / hotswap

目标：让 agent 在 MC GUI 开发里自己完成"改代码 → 看效果 → 操作 → 断言"的循环，不需要人，也尽量不重启客户端。在现有 `gui.*` 原语之上补齐，不引入新测试 DSL/Gradle 插件，不主动推进 tick，只做 1.21.1 线。

## 问题（改动前）

| 环节 | 现状 | 代价 |
|---|---|---|
| 点击后等结果 | 固定 `awaitTicks(20)` | 每次点击 ≥1s，一次调试会话几十上百次 |
| 一步操作 | click → screen → screenshot → 翻日志，4 次往返 + 4 次 CLI 启动 | 往返与 JVM 启动叠加 |
| 看图定位 | 模型要自己把像素换算成槽位/控件 | 容易点错 |
| `renderBg` 里画的东西 | 不是 widget，`gui.screen` 看不到 | 只能看图猜 |
| render 抛异常 | 原版崩溃报告退出 | 一个笔误 = 一次完整重启 |
| 改 Java/Kotlin | 必须重启客户端 | ≈25–30s/次 |
| 轻量模式 RPC | 每个客户端线程跳转都要等完剩余的 1/loopFps 睡眠 | 多跳 handler 叠加数十到上百 ms |

## 设计

### settle（`GuiSettle`）

- **服务端 barrier**：`ServerboundCommandSuggestionPacket(id, "mcdebug-settle-barrier")`。服务端在主线程 `ensureRunningOnSameThread` 后按序处理，因此排在此前的点击/菜单按钮之后；回包也排在那些请求引起的同步包之后。客户端 `ClientPacketListenerMixin` 在切到客户端线程之后识别自己的 id 并吞掉，其余 suggestion 回包照常交给原版。
- 不用 ping：`ServerGamePacketListenerImpl.handlePingRequest` 不切主线程，pong 可能早于点击被处理。
- id 从 `Int.MIN_VALUE` 起递增；`ClientSuggestionProvider` 的 id 从 0 起、空闲为 -1，不冲突；未匹配的 id 原版本来就忽略。
- **静默**：barrier 回来后，指纹（Screen 身份、槽位物品+数量、carried、控件文本/位置/active/visible、GUI 尺寸）连续 `quietTicks`（默认 2）个自然 client tick 不变才算 settled，覆盖服务端下一 tick 才同步的状态。container data slots（进度/能量）**不在**指纹里，运行中的机器也能 settle。
- 没有连接（标题界面）时只做静默；`maxTicks` 只是上限，超时返回 `settled:false` 而不是报错。
- `gui.click` 默认 settle，`waitTicks` 变成上限（默认仍 20），`settle:false` 恢复旧的固定等待。

### `gui.act`

`{action, params, settle, screen, screenshot}`。动作白名单：click/key/type/drag/close/openBlock/reinit/none。顺序：动作 → settle（click 自带时跳过）→ 截图（强制新帧）→ `gui.screen`（与截图同一帧后的 hover 状态）→ 本次调用期间新增的 problems。动作失败时仍返回各视图，`ok:false` 加 `error{code,message,data}`。CLI 的 `--shot/--screen/--annotate/--draw-log` 挂在 click/key/type/drag 上，底层就是 act；参数校验在连接之前完成。

### 截图

- 只 `glReadPixels` 裁剪矩形（绑定 READ_FRAMEBUFFER 后恢复），flipY、alpha 置不透明，与 `Screenshot.takeScreenshot` 语义一致；失败时回退到原来的整图下载加裁剪。
- `pixelHash` = RGBA 像素 SHA-256；`format:"none"` 不编码不写盘（变化检测），且不更新 `--png` 点击所依赖的 lastTransform。
- `annotate`：干净 PNG 编码之后，在同一张图上画青色槽位框/品红控件框加 3×5 数字，写 `*.annotated.png`，并返回 legend（序号 → PNG/GUI 矩形）。主 PNG 永远不带标注，golden 不受影响。
- `drawLog`：`GuiGraphicsMixin` 只观察 `innerBlit`（两种重载）、`blit(...texel...)`、`blitSprite`/atlas sprite、`fill(RenderType…)`、`drawString`（String/FCS）、`renderItem`、`renderTooltipInternal`。每条带 pose 变换后的 GUI 单位矩形；blit 带贴图、归一化 UV、texel UV 和贴图尺寸，或 sprite 名。只在有请求要它的那一帧 arm，单帧上限 5000 条，未 arm 时每个 hook 只做一次静态字段判断。

### `gui.screen`

控件按深度优先递归收集（`ContainerEventHandler` 子项），序号即 `--widget N` 与标注里的数字；附带 hovered/focused/tooltip。容器还给出 `hoveredSlot` 及其 tooltip。

### problems 与 screen guard

- `ProblemLog`：在 log4j root logger 上挂 WARN 级 appender，覆盖整个客户端 JVM（原版、各 mod、集成服）；环形 500 条，单条截断，按 `seq` 游标分页。
- `ScreenGuard`：`GameRendererMixin` 用一个 MixinExtras `@WrapOperation` 包住 `renderWithTooltip`，同时替换虚拟光标参数，取代原来的两个 `@ModifyArg`。Screen render 抛异常时记 ERROR，该 Screen 实例从此不再绘制，世界、tick、RPC 照常运行；`gui.reinit` 或关屏后清除。`clientScreenGuard=false` 恢复原版崩溃。世界/HUD/overlay 的异常不受影响。

### hotswap 与 reinit

- CLI `gui hotswap`：先查 `gui.status.hotswap.available`，没有就按 `process.pid` 用 JDK attach API（反射调用）加载一个**单类** agent jar（运行时现场生成，只含 `InstrumentationAgent`）。agent 只做一件事：把 `Instrumentation` 放进 `System.getProperties()["mcdebug.instrumentation"]`，以跨过类加载器边界。
- mod 侧 `Hotswap.swap`：扫描输出目录里比上次成功 swap（初值为 JVM 启动时间）更新的 `.class`，只重定义已加载的类。字节码先走 Knot 的 pre-mixin 变换（仅当 Knot classpath 读到的就是同一个文件，即 Loom dev 的情形），再走 Mixin `transformClassBytes`。批量失败时逐个重试，报告具体失败的类；schema 变化给出 JBR 提示，CLI 退出码 2。
- `gui.reinit` = `screen.resize(...)`（`repositionElements` → `rebuildWidgets` → `init()`），保留同一个 Screen/menu 实例；hotswap 成功后默认自动执行。

### 轻量模式唤醒

`RenderSystemMixin` 只在我们节流时接管 `limitDisplayFPS`：截止时间算法与原版相同，但 `ClientTasks.onClient` 从其它线程入队后会 `wake()`（置标志加 `glfwPostEmptyEvent`），等待提前结束，紧接着 runTick 的 `runAllTasks` 就会执行这一跳。只在节流状态下 post 事件：glfwInit 之前 post 会留下 GLFW 错误，原版 `_initGlfw` 检查到就崩溃（第一轮冒烟实际踩到，见下文）。

### golden 回归

`gui compare --ignore x,y,w,h`（可重复，比例按参与比较的像素计算，diff 里遮罩显示为蓝色）、`--report`（自包含 HTML：expected/actual/diff 并排；未给 `--diff` 时在报告旁生成）、`--accept`（显式用 actual 覆盖或创建 golden，之后退出 0）。SDK：`assertScreenshot(..., ignore=)`、`ScreenshotRegion`、`ScreenshotReview.accept/writeReport`。

## 验证（2026-10-28）

- 构建：`./gradlew --offline build :cli:copyCliJar -x :forge:build` 通过（0 warning），`verifyJarSelfContained` 检查 37 项。JUnit：根项目 129 项通过；CLI 126 项通过，5 项依赖外部服的 smoke 跳过。新增纯单测覆盖 QuietTracker、ProblemBuffer、Annotator、draw-log 矩形变换、ClassScan、CaptureOptions、compare 遮罩/报告/accept、agent jar 内容、CLI 参数与 act 映射。
- 真机冒烟：隔离 consumer（发布 jar 放进 `game/mods`，外加一个测试 mod：`SmokeScreen` 与一个打开它的标志文件），Xvfb :113、llvmpipe、854×480、guiScale 2、`demand` 模式，存档是副本。
  - 18 个客户端方法，`contractMismatch` 为空；所有 mixin 注入成功（`defaultRequire: 1`）。
  - 箱子：`gui click --slot 0` 返回 `settle{barrier:acknowledged, ticks:2–3}`，carried/slotAfter 正确；`--annotate` 生成 63 个槽位标注，目视检查数字与槽位对应；`--draw-log` 得到 67 条记录，含 `generic_54.png` 的 texel `[0,0,176,71]`/256²、物品矩形与 tooltip。
  - 连续两次 `--mode gui` 截图之间有 28 个像素不同（最大通道差 2），位于底部小数裁剪行，是透出的世界在变，不是采集错误；用 `--ignore 0,333,352,3 --report` 比较后匹配。
  - `SmokeScreen`：`--widget 0` 点击，draw log 里有 `body A`；创建 boom 文件后 render 抛异常，客户端存活，`screenGuard.fault` 含堆栈，`gui problems --level error` 有对应记录；删掉 boom 文件后 `gui reinit` 清除故障。
  - hotswap：把 `label()`/`body()` 改成 B，`clientClasses` 用时 9s，`gui hotswap` 用时 1.2s（含 attach 与 CLI 启动），`redefined:[SmokeScreen]` 并自动 reinit，draw log 显示 `Version B`/`body B (hotswapped)`；再加一个字段后 hotswap 返回 `failed` 加 JBR 提示，退出码 2，客户端继续运行旧代码。
  - 第一轮发现并修复：`wake()` 在 glfwInit 之前被 RPC 探测触发导致启动崩溃。另外，scratchpad 路径太长，Unix socket 超过 108 字节绑定失败（这是环境问题，与本改动无关），所以游戏目录改放到短路径。
- 同机 A/B（同一存档、同一设置，Unix socket 直连，不含 CLI 启动；先跑上一版 jar，再跑本版）：

| | 上一版 | 本版 |
|---|---:|---:|
| 世界视图单跳 `gui.status` p50 | 37.1 ms | 0.8 ms |
| 开箱 `gui.screen` p50 | 37.0 ms | 0.7 ms |
| `gui.screenshot --mode gui` p50/p95 | 37.0/48.4 ms | 21.5/24.0 ms |
| `gui.click --slot` p50 | 988 ms | 102 ms（`settle:false` 时 1003 ms） |

  每项 10–60 个样本，单机、软件 GL，不代表硬件 GPU 或其它 modpack 下的表现。37 ms 这个值与采样脚本 13 ms 的间隔加上 50 ms 帧周期相位锁定有关，可以理解为"等完剩余睡眠"的典型值，不是严格的总体分布。
- 清理：三个 session 都正常 owned stop，没有残留 MC 进程；自建的 Xvfb :113、临时游戏目录、基线 worktree 已删除；其它会话的 Xvfb :91/:92 没有动。

## 第二轮：看 tooltip、Screen 夹具、免重启改结构

第一轮之后，迭代回路里还剩这几处卡点：

- tooltip 只能靠截图去猜；
- 客户端独有的 Screen 没有方块就打不开；
- 滚动列表没有办法操作；
- 手画的按钮/列表项无法按文字点击；
- golden 对比会被光标悬停状态干扰；
- 改 GUI 时最常见的是加按钮、加字段、加 lambda，这些改动 hotswap 应用不了，只能重启客户端。

### 设计

- **`gui.hover`**：移动光标（同点击，并派发 `Screen.mouseMoved`），然后强制渲染一帧（`format:none` + drawLog；容器用 gui 模式，其它用 window 模式），返回该帧 draw log 中 `op:"tooltip"` 的行，以及 hoveredSlot/hoveredTooltip/hoveredWidget。tooltip 以真正画出来的为准，能量条、液位这类在 renderBg 里手画的提示也包括在内。
- **`gui.scroll`**：在目标位置调用 `Screen.mouseScrolled(x,y,dx,dy)`，然后 settle；`handled` 表示是否有监听者处理了这个事件。
- **共享目标解析 `GuiTargets`**：click/hover/scroll 用同一套 slot/widget/text/gui/png/x,y 解析。`text` 先匹配控件文字；没有控件匹配时由 `prepare` 渲染一帧，按 draw log 里 `op:"text"` 的矩形中心定位，结果 `mode:"drawnText"`。找不到时，错误里的 `available` 同时列出控件文字和本帧画出的文字。
- **`gui.openScreen`**：`inventory`（与 E 键相同，创造模式下原版会转交创造背包）、`pkg.Class` 或 `pkg.Class#静态工厂`。参数按类型注入（`ScreenArgs`）：Screen 父界面、Player、Minecraft、Options；构造器取能填满的参数最多的那个，工厂取参数最少的。构造或 init 失败时返回堆栈，并关闭半初始化的界面。
- **截图 `cursor:"hide"`**：截图请求带上光标策略；`renderFrame` 按队首请求的策略渲染这一帧（mixin 把传给 `renderWithTooltip` 的鼠标坐标换成屏幕外），`afterFrame` 只采集策略相同的请求，其余等下一帧。
- **hotswap 全有或全无**：真机冒烟发现，旧实现在批量失败后逐个重试，导致 10 个类里 7 个已重定义、3 个失败，客户端处于新旧混合状态，新代码会调用还不存在的成员（`NoSuchMethodError`）。现在先用 `SchemaDiff`（ASM 读新字节 vs 反射读已加载类）列出每个类增删的方法/字段（例如 `added method init$lambda$0(...)`），只要有结构变化就一个都不应用；`redefineClasses` 本身对一批是原子的。
- **`gui.reloadScreen`**：建一个新的 `ScreenClassLoader`，只对当前 Screen 类、其嵌套类和 `also` 前缀 child-first，其余类一律委托 Knot（mod 注册表、单例不会重复初始化）。用同一套参数注入构造新实例：容器 Screen 拿到同一个 menu、玩家背包和标题，按 `added()` → `init()` → 赋值 `Minecraft.screen` 的顺序切换，**不调用** `removed()`（`AbstractContainerScreen.removed()` 会关掉 menu）；普通 Screen 用旧实例的第一个 Screen 类型字段作为父界面，走 setScreen。init 失败时恢复旧界面。

### 验证（2026-10-28，同一套隔离环境）

- 构建与单测通过（新增：CaptureOptions cursor、SchemaDiff 两侧一致与变化列表、CLI hover/scroll/open-screen 映射与连接前用法错误）。
- 真机：
  - `open-screen local.smoke.SmokeScreen#fixture` 打开成功；不存在的类返回 `CLASS_NOT_FOUND`，非 Screen 类返回 `NOT_A_SCREEN`，退出码 2。
  - `scroll --dy -3`：`handled:true`，settle 2 tick，画面计数 100→97。
  - `hover --gui 165,40` 返回 `tooltips:[["Energy 1000/4000"]]`（renderBg 手画）；背包里 `hover --slot 36` 返回 `Redstone Dust`。
  - `--text "scroll 1"` / `"body A"` 走 drawnText；`--text zzz` 返回的 available 含画出的文字。
  - `--hide-cursor`：tooltip 条目从 1 变 0。背包 gui 模式连续三张 hide 截图互不相同，差异全部在玩家模型（idle 摆臂，1156 像素），另有 4 像素在底边小数裁剪行。
  - hotswap：加方法后返回 `changes:["added method extra()..."]`、`applied:none`，退出码 2，按钮文字不变；改回纯方法体后 3 个类成功重定义，按钮变为 `Version B`。
  - reloadScreen（容器，基于 `player.inventoryMenu`）：先拿起 12 个红石；加字段、方法和 lambda 按钮后 hotswap 被拒；`reload-screen` 成功，containerId 仍为 0，carried 仍是红石 ×12，新按钮出现且点击生效；放回槽 36 后 slotAfter 为红石 ×12、carried 为空。普通 Screen：父界面从旧实例字段取得；init 抛异常时返回堆栈，旧界面保留。
  - 顺带验证了用 mcdebug 自己的 `gui hotswap` 热替换 mcdebug 自身的客户端类：消费端的发布 jar 与 dev 输出都是 Mojang 名，方法体改动直接生效。
- 清理：session 正常 stop，Xvfb :113 与临时游戏目录已删除。

## 未做

reloadScreen 的旧字段状态迁移、Screen 类以外的结构改动免重启（需 JBR）、`--repeat` 连拍、离屏定尺寸截图、IME 组合输入、跨 GPU 像素确定性。启动耗时（MC 加载阶段）本轮没有优化，思路是用 hotswap 和 reinit 把"重启"移出迭代回路。
