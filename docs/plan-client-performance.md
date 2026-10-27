# 客户端性能实测方案

接续提交 `bd4367f`（轻量渲染与 Session v1）。用户要求统计性能，包括加载耗时；在实际测量前不写收益数字。

## 环境与隔离

- 使用 `/tmp` 内独立空源码 Loom consumer，通过普通 mods 部署本次 remap release jar。没有本仓库 classes/contract 源注入，不修改既有 IC2 工程、其 run/mods 或用户世界。
- MC 1.21.1、Loader 0.19.5、Fabric API 0.116.17+1.21.1、FLK 1.13.9+kotlin.2.3.10、Loom 1.15-SNAPSHOT（记录实际解析版本）、Gradle 9.4.1、Java 21。
- 共享下载/映射/OS 页缓存不清除。消费者 Gradle 准备单列；每轮新 MC JVM、新 gameDir 与同一世界快照，**不是严格冷页缓存启动**。
- 专用 Xvfb，固定 `LIBGL_ALWAYS_SOFTWARE=true`、`GALLIUM_DRIVER=llvmpipe`、`LP_NUM_THREADS=4`；记录实际 GL vendor/renderer/version。软件 GL 的 CPU 收益不可当成硬件 GPU 百分比。
- 固定 854×480、GUI scale 2、renderDistance 2、simulationDistance 5、maxFps 60、vsync=false、pauseOnLostFocus=false；MC Xms256m/Xmx2G，Gradle Xmx2G。堆上限不是 RSS 上限。
- 基准 controller 清除隐式 JVM/Gradle 参数和旧 MCDEBUG 环境，只传明确配置；fixture JVM 属性额外固定 TCP=false，不固定 render/socket 以免覆盖每轮 Session 的隔离路径。每轮核对真实 socket、TCP、Jar 路径、process identity 与实际 render 配置。

## 试验设计

默认 heartbeat 1 FPS，外循环轻量模式 20 Hz；continuous 使用同一 options 的原版 60 FPS 上限。因此主比较是**产品策略整体差异**，包含绘制门控和外循环限帧，不是单独门控的微基准。idleFps=0 可另测，不能把零 heartbeat 的收益冒充默认配置。

三模式 continuous/demand/gui-only 至少三轮：C,D,G / D,G,C / G,C,D，完全串行；正常停止且核对 PID/socket 退出后才开始下一轮。每轮世界视图与相同箱子 GUI 各有固定 warmup/idle 窗口；固定频率顺序截图，保留首次/预热样本，不悄悄过滤失败。

## 加载时间线

全部持续时间用 monotonic/perf_counter，UTC 仅作元数据；OS tick 量化与轮询分辨率须披露。

1. Python 创建 `java -jar ... session start` 前：用户调用起点。
2. wrapper PID 创建：从 registry 的 wrapper 身份读取 `/proc/PID/stat` start_ticks，经 CLOCK_BOOTTIME 映射到本轮 monotonic。
3. 真实 MC PID 创建：从 client.json 确认身份后回读相同 OS start_ticks；不靠 Gradle 进程树猜 MC，不直接用 wall-clock Instant 混算 duration。
4. 身份匹配的 client plane `server.ready`：RPC transport 可用，不等于资源/主线程/世界可用。
5. 首条成功 `gui.status`：客户端线程可响应。
6. 首次 inWorld && singleplayer + server endpoint 身份匹配：世界/服务端可用。
7. 首次 gameLoadFinished && overlay为空 && screenType=none：本基准的可视加载完成判据，不声称所有区块/后台任务已加载完。
8. 首次 `gui.screenshot(mode=window, inline=false, delayTicks=0)` 请求/响应并验证 PNG/hash：请求驱动的新帧 + 编码落盘结果，不是引擎自然首帧。
9. `session start` 返回单列。其 world_ready 只验证世界状态，不替代步骤4/7/8。

为支持不靠截图探测加载阶段，gui.status 补充只读 `gameLoadFinished`、`overlay` 与 `graphics{vendor,renderer,version}`；直接复用本地源码确认过的 Minecraft/GlUtil API，不改游戏循环或 readiness 语义。

## 稳定运行与截图

- MC CPU = 100 × Δ(utime+stime)/SC_CLK_TCK/monotonic秒；**100%=一个逻辑核，可超过100%**。不含 Gradle wrapper/daemon，不把子进程累计 cutime/cstime 混入。MC 进程本身包含集成服务器、JIT/GC、IO、软件渲染线程。
- RSS/VmHWM 用 MiB，另记线程、FD、private wrapper 开销。RSS不是heap/PSS/显存；1Hz采样峰值与OS VmHWM分开。每次核对 PID/start_ticks，避免复用。
- natural TPS = server.status.tick 增量/各自观测时间差；clientTick单列。server.health.tps只是基于 smoothed MSPT 的估计，不能冒充实测 TPS。
- 实际绘制 FPS = render.frames 增量/时间；不使用 gui.status.fps（外循环FPS）或 capture.frameId（只为捕获递增）。skippedFrames 单列。
- idle 内只/proc低频采样，不截图；RPC status/health只在阶段边界取样，复用各一条 client/server NDJSON 连接。
- 截图延迟是完整 RPC：主线程 hop/被动排队/新帧渲染/GL读回/PNG编码/文件写入/hash/传输。当前不能拆成纯GPU耗时。模式、crop尺寸、bytes、sha、frameId、错误和超时均保留；同 GUI 以固定间隔请求，报告 n/median/p95/max。
- 另测一次 CLI 端到端截图以显示 Java CLI 冷进程开销，不与常驻RPC样本混算。

## 安全与边界

不删 MC session.lock，不杀共享 Gradle daemon；异常/信号时仍走已拥有 Session 的正常 stop。raw数据、日志和 fixture 保留用于复查，显示服务在全部客户端退出后停止。

当前 consumer 是 Loom dev runtime 对发布 jar 的真实重映射加载，不等同于官方 launcher 的纯 intermediary 生产 runtime，也不等同 IC2/任意模组的性能基准。GUI-only 省略世界/HUD，像素语义不同，不能只报更快却不披露。
