# 方案：客户端 GUI 打开 + 截图 + 点击（`gui.*`）

> 目标：让 agent 能对一个**指定的机器方块**做到「打开 GUI → 截图看图 → 点击操作 → 再截图」，
> 从而把机器 GUI 的开发/调试闭环交给 agent 自己跑。
>
> 已确认的边界：
> - 只做 **1.21.1 线**（`port-fabric-1.21.1`，Mojang 官方映射），1.20.1/Kilt 线本次不做。
> - 客户端跑在**本机 Linux `:0`（AMD radeonsi，真实 GPU）**，形态是开发用 `runClient`。
> - 不做 golden 基线比对（回归测试不是本方案的目的）；确定性显示设置只作为可选开关保留。

> **状态：P0 已实现并验证**（mcdebug 自己的 `runClient` + 消费者项目 ic2-fabric-1.21.1 的 `run/mods`），
> 落地细节与踩坑见 AGENTS.md §14；下面是设计与取舍的完整记录。
> P1/P2 未做，见 §9。

## 0. 前提：像素只能由客户端产生

截图需要渲染管线 + 材质 + GL 上下文 + 真实 `Screen` 实例，专用服务端三者皆无。
现有 `screen.*`（`src/main/kotlin/com/mcdebug/api/ScreenOps.kt`）是**假玩家开菜单拿槽位 JSON**，
它是「数据面」，永远出不了像素。

因此本方案新增第二个面：

```
                 ┌────────── 现有：服务端 mcdebug RPC ──────────┐
 CLI / agent ──► │ <gameDir>/mcdebug/socket + TCP 25580        │  world.* be.* inv.* screen.*（假玩家）
   (Kotlin)      └────────────────────────────────────────────┘
        │
        └────────► ┌────────── 新增：客户端 mcdebug RPC ─────────┐
                   │ <gameDir>/mcdebug/clientSocket + TCP 25581 │  gui.*（真实玩家 + 真实渲染）
                   │ 真实 Minecraft 客户端（装了项目 A 的 mod）   │
                   └───────────────────────────────────────────┘
```

- 客户端 agent 与服务端 RPC **互不依赖**：客户端可以连任意服务器，哪怕那台没装 mcdebug。
- 服务端 mcdebug 负责「把机器摆好、配好 NBT、把玩家 tp 过去」。
- 单人存档里两者同进程共存：loader 0.19.x 在客户端**不会**触发 `server` entrypoint
  （也没有 `ServerModInitializer`），所以由 client entrypoint 调 `ServerPlane.installLifecycleHooks()`
  把服务端平面挂到 integrated server 上。服务端 socket 是 `mcdebug/socket`，客户端是
  `mcdebug/clientSocket`，互不冲突。
  → **一个客户端进程里同时有数据面和渲染面**，这是 agent 最省事的拓扑。

## 1. 客户端 agent 放哪：同一个 jar

`build.gradle` 已经是 `loom { splitEnvironmentSourceSets() }`，`src/client/` 源集开箱可用，
不需要新仓库、不需要额外发布物。

| 文件 | 改动 |
|---|---|
| `src/main/resources/fabric.mod.json` | `"environment": "server"` → `"*"`；`entrypoints` 增加 `"client"` → `com.mcdebug.client.McDebugClientMod`（`"adapter": "kotlin"`）；增加 `"mixins": [{"config": "mcdebug.client.mixins.json", "environment": "client"}]`（现在 mixins.json 根本没被声明） |
| `src/client/kotlin/com/mcdebug/client/McDebugClientMod.kt` | `ClientModInitializer`，起客户端 RPC，注册 mixin 状态与自检 |
| `src/client/kotlin/com/mcdebug/client/GuiOps.kt` | `gui.*` handler 组 |
| `src/client/kotlin/com/mcdebug/client/Capture.kt` | 抓帧 + 坐标换算 |
| `src/client/resources/mcdebug.client.mixins.json` | 客户端 mixin（`Screen` 背景、`AbstractContainerScreen` 访问器） |
| `contract/src/main/kotlin/com/mcdebug/contract/Methods.kt` | 拆 `SERVER_METHODS` / `CLIENT_METHODS` |

传输层直接复用 `src/main/kotlin/com/mcdebug/rpc/RpcServer.kt`（双通道 NDJSON、多客户端、断连取消全现成），
只换一组配置键与默认值：

| 键 | 默认 |
|---|---|
| `clientSocket` | `<gameDir>/mcdebug/clientSocket` |
| `clientTcpPort` | `25581` |
| `clientTcpEnabled` | **`false`**（真实玩家客户端不该默认对 `0.0.0.0` 开无鉴权端口；跨机时显式打开） |

解析顺序沿用 AGENTS.md §4 的模式：`-Dmcdebug.clientXxx` → `MCDEBUG_CLIENT_XXX` → `mcdebug.json` → 默认。
发现文件：`mcdebug/clientPort`（socket 路径）、`mcdebug/clientTcpPort`（实际端口，仅绑定时写）——
和 `mcdebug/port` / `mcdebug/tcpPort` 是**两套**文件，服务端客户端各读各的。

**线程模型**：RPC 处理线程 → `minecraft.execute { }`（1.21.1 客户端主线程即渲染线程，
`Minecraft extends ReentrantBlockableEventLoop<Runnable>`，`Minecraft.java:257`）→
PNG 落盘丢 `Util.ioPool()`，异步完成 future，绝不阻塞渲染。

## 2. `gui.*` 方法（P0 最小闭环）

| 方法 | 语义 |
|---|---|
| `gui.status` | `clientReady / screenClass / screenTitle / player{name,dim,pos} / framebuffer[w,h] / guiScale / gameDir / logPath / jarPath / modVersion / protocolVersion`（`jarPath` = 实际加载的那份 jar，用来确认部署没搞错） |
| `gui.connect` | agent 主动登录：`ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)`（`ConnectScreen.java:60`）。不依赖启动参数/世界是否存在，是最通用的入场方式 |
| `gui.openBlock` | **真实右键**：`MultiPlayerGameMode.useItemOn(LocalPlayer, InteractionHand, BlockHitResult)`（`MultiPlayerGameMode.java:282`）。参数 `pos/dim/face/hand/sneak/waitTicks` |
| `gui.screenshot` | 抓帧写 PNG，返回 `{path,width,height,sha256,transform,inline?}` |
| `gui.screen` | 结构化当前界面：类名、标题、`bounds`、`widgets[]`、`slots[]`、`carried`、`focused` |
| `gui.click` | 三种定位：`slot` / `text` / `x,y`（GUI 坐标）或 `png:[x,y]`（上次截图的像素坐标） |
| `gui.close` | 关当前 screen |

P1：`gui.type`、`gui.key`、`gui.wait`（`ClientTickEvents.END_CLIENT_TICK`，`ClientTickEvents.java:41`）、
`gui.reloadResources`、`gui.screenshot --repeat/--mode offscreen`、滑条/拖拽。

**不开**任何主动推进 tick 的接口（与 AGENTS.md §7 的服务端原则一致）：客户端 tick 同样由引擎驱动，
等待一律走 `gui.wait`。

## 3. 点击：走 Screen 事件派发，不走 GLFW/Robot

关键结论（已核对源码）：

- `ContainerEventHandler.mouseClicked/mouseReleased/mouseDragged` 是**接口 default 方法**，
  按 `children()` 逐个派发（`ContainerEventHandler.java:38`）；`Screen` 继承之，`Screen.keyPressed` 覆写在 `Screen.java:130`。
  → mod 可以直接调 `screen.mouseClicked(x, y, button)`，等价于真实鼠标事件，**不需要窗口聚焦、不需要合成 GLFW 事件、不干扰用户桌面**。
- `AbstractContainerScreen.mouseClicked(d, e, i)`（`AbstractContainerScreen.java:281`）内部用
  `this.findSlot(d, e)` **只依赖传入坐标**（不依赖渲染期缓存的 `hoveredSlot`）→ 后台窗口下点击也准确。
- 槽位点击的最终出口是 `AbstractContainerScreen.slotClicked(Slot, int, ClickType)`（`:551`）→
  `minecraft.gameMode.handleInventoryMouseClick(containerId, slotIndex, button, ClickType, player)`
  （`MultiPlayerGameMode.java:400`），这就是真实客户端发包路径。
- `Slot.x` / `Slot.y` 是 public 字段（`Slot.java:15/16`），槽位屏幕坐标 = `leftPos + slot.x`、`topPos + slot.y`；
  `leftPos/topPos/imageWidth/imageHeight` 是 protected（`AbstractContainerScreen.java:35/36/53/54`）→ 一个 `@Accessor` mixin。
- 控件枚举：`ContainerEventHandler.children()` public（`Screen.java:333`）；
  `AbstractWidget` 有 `public boolean active`（`:37`）、`getX/getY/getWidth/getHeight`（`:273/283/197/53`）、`getMessage()`（`:217`）。

实现选择：

| 点击方式 | 实现 | 用途 |
|---|---|---|
| `slot` | 直接 `gameMode.handleInventoryMouseClick(containerId, index, button, clickType, player)`（= 受保护的 `slotClicked` 的出口，不必为它加 invoker）：`ClickType ∈ {PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, PICKUP_ALL}`。**显式传 clickType 而不是靠 `hasShiftDown()`**——后者读真实 GLFW 键盘状态，合成不出来 | 槽位操作最稳，agent 按槽号干活 |
| `text` | 文本匹配 `AbstractWidget.getMessage()`，点中心 | 按钮/开关，不需要算坐标 |
| `png:[x,y]` | 用上次 `gui.screenshot` 的 transform 反算成 GUI 坐标再派发 | 「我看图上这个像素，点它」——agent 最自然的操作方式 |
| `x,y` | 直接 `screen.mouseClicked` / `mouseReleased` | 自定义画布控件、拖拽把手 |

滑条走 `AbstractSliderButton` 的 value 设置（P1，实现时按源码确认方法名）。

**光标 warp（实测加进来的）**：`MouseHandler.onMove` 是 private，改用
`GLFW.glfwSetCursorPos(window, windowX, windowY)`（窗口坐标 = GUI 坐标 ×
`getScreenWidth()/getGuiScaledWidth()`）。不 warp 时手持物品、悬浮提示、槽位高亮都停在
真实光标处，截图里会出现"你没点过的位置有东西"，agent 会误判；`--no-cursor-move` 可关。
`client.screen == null`（鼠标被 grab）时跳过，避免把视角甩飞。

## 4. 截图：抄 vanilla F2，外加坐标自描述

抓帧（**渲染线程**）：

```kotlin
val image = Screenshot.takeScreenshot(minecraft.mainRenderTarget)  // Screenshot.java:76
Util.ioPool().execute { image.writeToFile(path) }                  // NativeImage.java:469
```

- 主 RenderTarget 的 FBO 在帧间保留上一帧内容，vanilla 的 F2 就是在非渲染期抓的
  （`KeyboardHandler.java:378` 调 `Screenshot.grab`）→ 「随时抓」是安全的，不需要自己 hook 渲染循环。
- 需要「等 GUI 稳定几帧」时用 `ScreenEvents.afterRender(Screen)`（`ScreenEvents.java:134`，
  签名 `(Screen, DrawContext, mouseX, mouseY, tickDelta)`）或 tick 计数，抓第 N 帧。
- 模式：`mode=window`（整窗 framebuffer）| `mode=gui`（按 `leftPos/topPos/imageWidth/imageHeight` 裁剪，
  用 `NativeImage.resizeSubRectTo`，`NativeImage.java:656`；裁剪区在 framebuffer 里按 guiScale 放大）。
- **坐标自描述**（实际字段）：`transform = {framebuffer:[w,h], window:[w,h], guiScale, guiScaled:[w,h],
  pixelsPerGui, crop:[x,y,w,h], cropInGui:[x,y,w,h], pngSize:[w,h]}`（`crop` 是 framebuffer 像素、
  `cropInGui` 是 GUI 单位，两者随 `--mode gui` 给出），这样 agent 能把图上任一像素反算成 GUI 坐标；
  `gui.click {png:[x,y]}` 由客户端代劳换算（只认同一客户端进程里最近一次截图的 transform，
  否则 `-32602 reason:"NO_SCREENSHOT"`）。
- 跨机时用 `inline:true` 回传 base64（`NativeImage.asByteArray()`，`NativeImage.java:527`；
  NDJSON 单帧上限 64 MiB，854×480 PNG 约 100–400 KB）。本机形态直接读磁盘文件即可。
- 可选确定性开关（默认关）：`--width/--height` 固定启动尺寸、固定 `guiScale`
  （`Window.setGuiScale`，`Window.java:387`）、`hideGui`、纯色背景。
  注意 `AbstractContainerScreen` 的背景会**模糊世界**，视角一变背景就变 → 需要稳定背景时用
  mixin 拦 `Screen#renderBackground` 填纯色（实现时按源码确认签名）。

## 5. 「打开指定的机器」的三个坑

1. **距离**：服务端校验 reach → 先用现有能力 `server.runCommand "tp <player> x y z"`。
2. **潜行**：`sneak=true` 时很多机器变成「插入物品」而不是开 GUI → `gui.openBlock` 默认确保不潜行。
3. **手持物品**：IC2 扳手 / tinkers 工具会触发工具行为而不开 GUI → 默认先切空手
   （客户端改 `selectedSlot` + 发 `ServerboundSetCarriedItemPacket`），或 `clearHand:true`。

另外留一条兜底路（P1）：服务端 `screen.openForPlayer {player,pos}` —— 现有 `screen.openBlock`
把 `FakePlayerPool` 换成真实 `ServerPlayer` 即可，客户端收到 `ClientboundOpenScreenPacket` 自动开屏。
适合「这机器不是右键打开的」或需要绕开客户端判定时。

## 6. agent 开发闭环的实际命令序列

用占位符写，任何 Loom/Fabric 项目 A 都适用：`<A>` = A 的仓库根，`<module>` = 客户端所在模块
（单模块项目就是根），`<gameDir>` = A 客户端 run 目录（多模块是 `<A>/<module>/run`）。

```bash
# 0) 构建 mcdebug，产出普通 mod jar（一次；改 mcdebug 代码后重做）
cd <mcdebug> && ./gradlew build

# 1) 把 jar 放进客户端 mods/ —— 和现在服务端部署完全同一种做法
cp build/libs/mcdebug-<ver>.jar <gameDir>/mods/
#    同一目录不要留两个版本的 mcdebug jar（Loader 会报 duplicate mod id）

# 2) 起客户端（后台常驻；用 A 自己的 runClient 任务，多模块带 :<module>: 前缀）
cd <A> && ./gradlew :<module>:runClient
#    要固定窗口大小用 --args 覆盖（JavaExec 的 --args 会覆盖 build.gradle 里已写的 programArgs）：
#      --args="--width 1280 --height 720"
#    要直接进档再加 --quickPlaySingleplayer <level>；没有这个档就先起到主菜单

# 3) 等就绪（读 gameDir 下的发现文件，不要 sleep 猜时间）
mcdebug gui status        # 客户端面：<gameDir>/mcdebug/clientSocket
mcdebug status            # 服务端面：<gameDir>/mcdebug/socket（单人档时同进程）

# 4) 摆机器 + 站过去（数据面）
mcdebug world set-block --pos <x> <y> <z> --block <A>:<machine>
mcdebug server run-command "gamemode creative"
mcdebug server run-command "tp <player> <x> <y+1> <z+2>"

# 5) 开 GUI（渲染面）
mcdebug gui open-block --pos <x> <y> <z>

# 6) 看图 + 点（agent 循环）
mcdebug gui screenshot --mode gui --out /tmp/gui.png   # → agent 用 read_image 看这张图
mcdebug gui screen                                     # 控件/槽位清单（按名字点，不用算坐标）
mcdebug gui click --text "<Button>"      # 或 --slot 3 / --png 120,44
mcdebug gui screenshot --mode gui --out /tmp/gui2.png  # 比对前后

# 7) 只改贴图/语言/模型时不用重启客户端
mcdebug gui reload-resources   # Minecraft.reloadResourcePacks()  Minecraft.java:886
```

**入场姿态三种，挑 A 顺手的**（都不给本方案引入项目耦合）：
`gui.connect` 主动连任意服务器（不依赖启动参数、不依赖世界是否存在，最通用）；
`--quickPlaySingleplayer <level>` 直接进已存在的单人档（integrated server → 一个进程里同时有数据面和渲染面）；
无参数起到主菜单，再用 `gui.screenshot` + `gui.click` 自己点进去——零假设，顺便自证能力。

## 7. 部署方式：和现在完全一样

一个 jar 两端通用（`environment: "*"` + 双 entrypoint）：**构建产物直接当普通 mod 用，放进目标环境的
`mods/`**。服务端就是 AGENTS.md §15.3.4 那套（`./gradlew build` → `cp` 进 `mods/` → 重启）；客户端把同一个
jar 放进客户端环境的 `mods/`（真实客户端 `.minecraft/mods/`，或 A 的 Loom `<gameDir>/mods/`）。
**A 侧零改动**：不改 build.gradle、不加依赖、不装插件、不需要额外脚本。

由此：

- **不存在「mcdebug 依赖某个项目」这回事**。mcdebug 侧只有 jar + CLI，两端靠 gameDir 下的发现文件会合。
- **配置是 gameDir 级的，与 A 的仓库结构无关**：`<gameDir>/config/mcdebug.json` 里写 `clientSocket` /
  `clientTcpPort`，或 `-Dmcdebug.clientXxx` / `MCDEBUG_CLIENT_XXX`。哪个 gameDir 起了客户端，endpoint
  就在那个 gameDir 的 `mcdebug/` 下——多模块、多 run 目录都无所谓，按 gameDir 天然隔离。
- **dev 环境与真实客户端一致可用**（`run/mods` 里的 jar 由 Loader 加载；A 无需为 mcdebug 改构建）。
- 不要往 mcdebug **自己**的 `run/mods/` 放本项目产物（AGENTS.md §1 硬规则，那条约束的是本仓库的
  dev-launch-injector 冲突，与 A 的 `run/mods/` 无关）。

两件顺手要做的小事，都属于「让 cp 这一步可观察」，不是新机制：

- `gui.status` 回传 `gameDir` / `logPath` / `jarPath`（`ProtectionDomain.getCodeSource()`）/ `modVersion`
  → 「现在加载的到底是哪份 jar、客户端日志在哪」一眼可见；GUI 抛异常时 agent 直接去读日志。
- 现有 `warnVersionMismatch` 保留：CLI 版本与 `gui.status.modVersion` 不一致时 stderr 警告。

客户端是**长驻后台任务**：启动一次，agent 多轮复用；只有改 Java/Kotlin 代码才重启，贴图/语言/模型走
`gui reload-resources`。

**世界从哪来：用户已经有的存档**。方案不做"主动创建世界"这类内容，预期工作场景是
「用户有存档 → agent 加载对应存档 → 用服务端平面 `world.*`/`be.*`/`inv.*` 摆机器、写 NBT →
`gui.openBlock` 打开机器 → `gui.screenshot` 截图 → 点击 → 再截图」。
加载存档不需要新能力：起客户端后 `gui click --text "Singleplayer"`（或 `--gui x,y` 点存档列表项）、
`gui click --text "Play Selected World"` 即可（`--quickPlaySingleplayer <level>` 启动参数更快，
但那是项目 A 的启动配置，不属于本方案）。
`scripts/gui-session.sh` 是**可选便利**、不是接入路径：把 `cp` + 起 A 的 runClient + 等发现文件合成一条
命令，参数是 `<A>` / 任务名 / `<gameDir>`，不含任何项目特定知识。

## 8. 契约与流程改动清单（照 AGENTS.md §6 走）

1. `contract/.../Methods.kt`：拆 `SERVER_METHODS` / `CLIENT_METHODS`（可保留 `ALL` 全集）。
   **必须做**：`RpcContractTest` 目前双向校验服务端 dispatcher 与 `Methods`，加客户端方法不拆就直接构建失败。
2. 客户端侧自检：init 时 `require(GuiOps.methods().keys == Methods.CLIENT)`（loom split source set
   下是否有 `clientTest` 源集待确认，没有就用运行时自检兜底），并在 `gui.status` 里暴露方法数。
3. `cli/.../DebugApi.kt` + 新 `ClientApi`（复用同一个 `RpcClient`）：`gui.*` 强类型包装。
4. `cli/.../Main.kt`：全局加 `--client-socket` / `--client-tcp host:port`；新命令树 `gui status|connect|open-block|screenshot|screen|click|close|reload-resources`。
5. `README.md`：新增一节 + `raw gui.screenshot` 示例。
6. 版本：`mod_version` bump（客户端 agent 与 CLI 版本要一致，CLI 已会警告不一致）。
7. Forge 侧（`forge/`）：本次**不做**（1.20.1 线不在范围内），但要在 README/AGENTS.md 注明
   「Forge 版没有 `gui.*`，`forge-smoke.sh` 的契约比对需要跳过 CLIENT 集合」。

## 9. 分期

- **P0（闭环）— 已完成**：`src/client` + client entrypoint + `gui.status/openBlock/screenshot/screen/click/close`
  + CLI 子命令 + 在 mcdebug 自己的 `runClient` 与消费者项目 A（ic2-fabric-1.21.1 的 `run/mods`）各验证一遍
  （加载已有存档 → 摆机器 + 写 NBT → 开机器 → 出 PNG → 点一下 → 再出 PNG）。
  额外顺带做完的：`gui.reloadResources`、`gui.connect`、`--no-cursor-move`、`--path-only`、`--brief`。
  回归：`scripts/forge-smoke.sh` 增加平面隔离断言（服务端必须对 `gui.*` 报 `-32601`）+
  对照 `contract/Methods.kt` 的 80 个服务端方法逐个校验（ALL OK）。
- **P1**：`gui.type/key/wait`、滑条与拖拽、`screenshot --repeat`、`screen.openForPlayer`、离屏固定尺寸模式、
  `gui.disconnect`（进世界后没有按键输入就出不来，`gui.connect` 目前只能在非世界画面用）。
- **P2（可选）**：确定性显示开关、golden 基线比对、Xvfb/llvmpipe 无人值守脚本、反向注册
  （客户端入服时把 endpoint 推给服务端，CLI 只连服务端即可发现客户端）、类热重载加速循环。

## 10. 风险与坑

| 风险 | 说明 |
|---|---|
| 窗口必须真的在渲染 | 窗口最小化时客户端可能停止渲染 → 截图是旧帧。`:0` 桌面上保持窗口可见即可 |
| `hasShiftDown()` 类真实键态 | 合成不出来 → 所有「按住某键」的语义都改成显式参数（clickType / redstone 等），不要假装按键 |
| 客户端 tick 语义 | `gui.wait` 的 tick 是客户端 tick；单人档 integrated server 会随客户端暂停/限速 |
| GUI 代码报错 | GUI 抛异常时 MC 会显示异常界面或只写日志 → `gui.status.logPath` 要给出来，agent 直接读日志；截图也能看到异常界面 |
| 重启成本 | 改代码要重启客户端（几十秒）→ 贴图/语言/模型改动走 `gui reload-resources` |
| 同名多版本 jar | 客户端 `mods/` 里同时留两份不同版本的 mcdebug jar → Loader 报 duplicate mod id 起不来；换版本时先删旧的 |
| 本项目自己的 run/mods | 不要往 **mcdebug 自己**的 `run/mods/` 放本项目产物（AGENTS.md §1 硬规则，与 A 的 `run/mods/` 无关） |
| 1.20.1 线 | 本次不做；将来要做时，`main`(yarn) 与 1.21.1(mojang) 是两条线，客户端 agent 需要移植一次；若要给 Kilt 客户端截图，还需验证 Connector/Kilt 的客户端支持（未验证） |

## 11. 实现状态与验证证据（P0）

| 项 | 结论 |
|---|---|
| 打包 | 一个发布 jar（`environment: "*"` + 双 entrypoint）当普通 mod 用；**踩坑**：`implementation project(':contract')` 不会被 Loom 打进 jar → 别的项目里 `NoClassDefFoundError`，改成把 contract 源码编进 main 源集，并加 `verifyJarSelfContained`（挂在 `check` 上）守住 |
| mixin | 只有 `AbstractContainerScreenAccessor`；Loom 1.15 在 `remapJar` 直接重映射注解值，**不需要 refmap**（`javap -v` 验证 jar 里是 `@Accessor("field_2776")` / `@Mixin(class_465)`） |
| 开发环境验证 | mcdebug 自己的 `runClient`（`DISPLAY=:0`，1280×720，guiScale 2→`pixelsPerGui≈2.9977`）：`gui.status/screen/screenshot(window+gui)/click(text,slot,gui,png)/openBlock/close/reloadResources` 全通；slot 0 拿起钻石后 `gui screen` 与 PNG 都确认了变化 |
| 消费者项目验证 | ic2-fabric-1.21.1 的 `core/run/mods/mcdebug-0.6.1.jar` + 它的 `runClient`（guiScale 6，`pixelsPerGui≈5.995`）：`gui.status` 报 `jarPath=run/.fabric/processedMods/…`（证明跑的是发布 jar）、契约自检 ok；`be.setField EnergyStored=100000` + `inv.setSlot` 后 `gui open-block ic2_120:batbox` → `--mode gui` 裁出 1073×1175 的机器 GUI，能量条按写入的 NBT 显示为满 |
| 契约/回归 | `RpcContractTest`（两平面不相交 + 双向校验）、`./gradlew build`（含 `verifyJarSelfContained`、Forge）、`./gradlew test`、`scripts/forge-smoke.sh`（80/80 服务端方法 + `gui.status` 在服务端 `-32601`）全绿 |
| 与"只观察不驱动"原则 | 客户端等待也用 `ClientTickEvents.END_CLIENT_TICK` 回调，不提供任何推进 tick 的接口 |
| CLI 路由 | `gui.*` 自动走客户端平面（`--client-socket`/`--client-tcp` 可覆盖），其余走服务端平面；客户端 TCP 默认关 |
| 坐标闭环（实测） | `gui.click` 四种方式都回报落点 `guiX/guiY`（slot 方式是槽中心）；槽中心 (188,47) → PNG (384,150) → `gui click --png 384,150` 回读 `guiX=187.98`，与 `transform.cropInGui/pixelsPerGui` 完全一致 |
| 光标 warp 的前提 | `MouseHandler.onMove` 被 `isWindowActive()` 守卫：窗口失焦时 OS 指针动了、MC 内部鼠标不动，截图里手持物品/提示停在旧位置（状态不受影响）。`gui.status.windowFocused` 暴露该状态；先 `xdotool windowactivate` 即可 |
| 冒烟脚本一致性 | fabric/forge 两个冒烟脚本改为共用 `scripts/contract-parity.py`；此前 fabric 侧内联解析漏了"排除 `gui.*`"和"按 id 匹配响应"，报出假的 `inv.extract`/`gui.click` 缺失（假阳性） |
| 回归 | `scripts/fabric-smoke.sh`（一次性 1.21.1 专用服 + 发布 jar）ALL OK：13 项断言 + 80/80 服务端方法 + `gui.status` 在服务端 `-32601`；`scripts/forge-smoke.sh` 同样 ALL OK |

未做：`gui.key/type`（键盘输入）、拖拽、连拍、离屏定尺寸、golden 基线、Xvfb/llvmpipe、反向注册、
1.20.1/Kilt 客户端与 Forge 客户端平面。
