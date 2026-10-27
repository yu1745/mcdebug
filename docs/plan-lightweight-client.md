# 轻量客户端：复用 MC，按需出帧，不 fork

## 决策与边界

针对 Fabric 1.21.1 / Mojang mappings。复用真实 Minecraft、Fabric 客户端入口点、资源加载、世界/玩家、菜单网络及原版渲染链路；不复制 MC 源码，不另写 Screen 模拟器，不增加旧版本适配。

这是**轻量运行模式**，不是精简发行版。主要减少稳定运行时的绘制次数，不保证减少资源初始化时间、资源常驻内存或 JVM 冷启动时间。隐藏窗口、Xvfb、软件 GL 也不等于免 GL，软件渲染可能反而增加 CPU 开销。

## 配置

启动时读取，优先级：`-Dmcdebug.<key>` → 环境变量 → `<gameDir>/config/mcdebug.json` → 默认。

| JSON / 系统属性后缀 | 环境变量 | 默认 / 取值 |
|---|---|---|
| `clientRenderMode` | `MCDEBUG_CLIENT_RENDER_MODE` | `continuous`；也支持 `demand`、`gui-only` |
| `clientLoopFps` | `MCDEBUG_CLIENT_LOOP_FPS` | 20，范围 20..260 |
| `clientIdleFps` | `MCDEBUG_CLIENT_IDLE_FPS` | 1，范围 0..clientLoopFps；0 = 只在截图请求/切屏时出帧 |

- **continuous**：普通连续渲染。保持默认，不擅自改变玩家客户端或依赖 render hook 的模组。
- **demand**：稳定世界/容器界面空闲时跳过昂贵绘制；截图请求强制执行完整帧，包括世界背景和原 Screen。
- **gui-only**：调度与 demand 相同，但出帧时先清主 target 至中性背景并重新绑定，再跳过世界/HUD pass、渲染真实 Screen，避免旧画面在半透明部件下累积。透明背景、世界预览、自定义世界渲染回调可能不同，必须显式选择，不能称为任意 mod 功能无损。

在进入世界前、资源重载/overlay、非容器 Screen（例如连接/加载/暂停界面及自定义 Screen）都维持正常完整渲染。第一阶段只对稳定的世界与 AbstractContainerScreen 节流，避免加载阶段靠 render 完成回调而被饿死。

`gui.status.render` 报告配置、实际模式、累计绘制/跳过数和截图请求状态。实际模式 `idle` 表示最近一轮没有绘制，不表示游戏暂停。

## 实现

### 保留游戏循环

Minecraft 的 `runTick` 同时执行任务队列、自然 tick、清屏、GameRenderer、blit、窗口事件泵和限帧。

仅拦截调用 GameRenderer 的那个点，不取消 `runTick`，不把它的 boolean 参数改 false，不暴露主动推进 tick 的 RPC。外循环不降到 20 Hz 以下，以免主线程任务和网络响应严重积压。这里配置的是外循环上限，不改变 server TPS，也不保证繁忙主机必定达到 20 TPS。

稳定阶段降低外循环帧率，按需绘制；窗口事件泵、RenderCall 队列、blit 等仍保留，因此不宣称零 GPU 开销。仅给 GameRenderer.render 的第二参数传 false 实现 gui-only；不使用会连 GUI 一起关闭的 `Minecraft.noRender`。

轻量模式阻止失焦自动开暂停菜单，但用户显式打开的暂停 Screen 仍遵循原语义。集成服务器是否暂停仍由引擎决定；机器持续运行的测试应保持在非暂停容器界面。

### 截图是新帧屏障

截图请求先被动等待可选 delayTicks，再入有界队列，触发下一次目标模式渲染。GameRenderer 返回并 flush 后、下一轮 clear 前，冻结像素与变换再异步编码。等待 tick 不等于等待一帧，不能用增加 tick 数代替新帧屏障。

队列上限为 8，包含被动 delay、首个 client hop 前、待出帧及编码中的任务；编码超时但仍持有 NativeImage 的 worker 也占名额。所有阶段共享一个 30 秒墙钟 deadline 和同一个 settlement，避免 RPC 已超时但截图变换仍被发布；异常、超时、停止都释放各自所有权。PNG 编码一次，复用同一字节数组写文件、计算 sha256、按需 base64，不写盘后重新读回。裁剪用精确 copy 而非缩放器。

截图只证明已渲染客户端当前状态，不代表服务端确认了上一次操作。业务断言仍通过服务端数据面/被动 wait.until 或明确客户端条件观察；固定等待若干 tick 不是通用网络确认。

### 后台光标

轻量模式用最近一次 RPC 点击的 Screen 坐标渲染提示、手持物品和槽高亮，不 warp 桌面光标、不依赖窗口焦点；切屏清理坐标。continuous 模式保留正常物理鼠标行为，`--no-cursor-move` 禁止更新点击光标。

### 连接与超时

版本比较读取连接第一条 server.ready 通知，不再额外发 gui.status/server.status。协议握手严格匹配当前协议，不做旧服务器回退。

connect/握手超时和请求 deadline 分开。普通请求默认有限 deadline；wait.until 默认遵循服务端 tick timeout（可显式配置墙钟上限）。请求超时结束本地等待并关闭连接，**不代表远端操作已取消或回滚**。

## 验证标准

1. 纯单测：调度模式、安全阶段、heartbeat/零 heartbeat、参数边界；坐标裁剪/取整/两轴缩放/PNG 往返；RPC 握手、无额外 status、超时、断线、UTF-8。
2. 构建：compileClientKotlin、完整 build、自包含发布 jar 检查、CLI fat jar。
3. 真实客户端：在隔离 gameDir/存档副本运行，检查新 frameId、截图文件、槽位点击及服务端库存、资源重载、失焦、停止清理；demand 空闲 skippedFrames 增长而自然 tick 继续，idleFps=0 时两次截图之间仍能继续出新帧。
4. gui-only 单独验证，不以与完整世界截图完全相同为标准。需要完整像素语义的环境用 continuous 或经过目标 mod 验证的 demand。
5. 性能报告区分构建/启动、idle CPU/GPU、RSS、截图延迟。未测量的资源收益不写成既定数字。

不在本批：绕过 Gradle 的独立 launcher、EGL/OSMesa 重建、PBO 读回、精简资源加载、删模组或客户端世界、重写网络/菜单、任意 mod 的 render side effect 兼容框架。

## 本批验证记录

- `compileClientKotlin build :cli:copyCliJar` 通过，包含 Forge 委托构建、自包含 jar 的 17 项检查。Kotlin 编译无 warning/error；Forge 映射许可提示、Gradle 未来版本弃用提示仍来自原有工具链。
- JUnit 共 137 项：132 通过、0 失败、5 项需要外部测试服的 runner 冒烟跳过。包括 18 项调度/虚拟光标/坐标纯测试，以及 CLI 握手/并发/UTF-8/超时测试。
- 在独立 gameDir + 现有存档副本中，以 Xvfb 真 GL 客户端验证 demand 与 gui-only，`clientIdleFps=0`：熔炉自然完成一次冶炼、客户端前进 200 tick，期间绘制帧数不变，skippedFrames 增加 200；再次截图获得新 frameId/像素。未主动推进 tick。
- 箱子 5 颗钻石拿起/放回、服务端槽位验证、PNG↔Screen 坐标往返、inline PNG 与落盘 sha256 相等、无容器截图报错、资源重载后恢复按需出帧均通过。
- 容量/总 deadline：8 个超长被动延迟截图占满队列，第 9 个立即 `SCREENSHOT_QUEUE_FULL`；8 个任务约 30 秒全部 `SCREENSHOT_TIMEOUT`，期间自然前进 601 tick、没有额外出帧，随后正常截图恢复。
- 焦点验证：独立显示内让另一窗口获取焦点，`windowFocused=false`、`paused=false`，虚拟光标与拿起物品仍正确；窗口 OS 截图确认 idle 时保留最后完整 GUI，不闪空白。
- 空配置文件仅警告、环境变量覆盖仍成功启动。正常 stop 时待截图立即以 `CLIENT_STOPPING` 结束，两个平面 socket 均清理。

以上是真实 MC + Fabric API + mcdebug 的功能验证，不是 IC2/任意 mod 的性能基准，也没有测得启动时间、RSS 或 GPU 利用率的统一改善比例。
