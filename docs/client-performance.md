# 客户端性能实测：2026-10-27

基线实现已提交为 `bd4367f`。本次新增只读加载/GL观测字段、标准库基准脚本与独立空源码消费者模板；未改变渲染调度策略。

## 主要结果

9次启动，三模式各3次，顺序 C/D/G、D/G/C、G/C/D。以下CPU/内存为每次独立试验值的中位数；截图延迟合并每模式60个请求。

| 指标 | continuous | demand | gui-only |
|---|---:|---:|---:|
| 世界视图空闲 MC CPU（100%=1核） | 185.00% | 28.47% | 25.07% |
| 箱子GUI空闲 MC CPU | 198.71% | 24.13% | 20.73% |
| 1Hz截图阶段 MC CPU | 196.85% | 24.35% | 20.83% |
| GUI空闲实际完整绘制 FPS | 59.73 | 1.00 | 1.00 |
| GUI空闲自然服务端 TPS | 19.99 | 20.02 | 19.97 |
| 世界空闲 RSS（MiB） | 1698.61 | 1545.40 | 1569.79 |
| GUI空闲 RSS（MiB） | 1707.76 | 1551.26 | 1575.95 |
| 全次试验观测到的进程 VmHWM（MiB） | 1800.80 | 1769.41 | 1762.68 |
| GUI截图 RPC p50（ms） | 26.52 | 35.12 | 27.07 |
| GUI截图 RPC p95（ms） | 33.59 | 68.75 | 59.73 |
| GUI空闲线程数 / FD（中位） | 80 / 370 | 80 / 370 | 80 / 370 |

- demand 的世界空闲 CPU 减少 **84.61%**，GUI空闲减少 **87.86%**。
- gui-only 分别减少 **86.45% / 89.57%**，但明确不绘制世界/HUD，背景/透明区域像素语义不同。
- 自然tick约20TPS继续运行，没有主动推进tick。轻量模式不是零开销：游戏循环、模拟、事件泵、blit仍保留。
- **并未证明大幅省内存或加速初始化。** RSS中位有所降低，但进程高水位接近；RSS不是Java堆或显存。
- demand 截图的中位和p95更高，符合低频调度等额外等待；不能把省CPU等同所有请求都更快。

## 加载耗时

起点为 Python 创建 `java -jar ... session start` 前；不含存档复制/部署或首次消费者准备。持续时间使用monotonic；PID创建时刻从Linux start_ticks/CLOCK_BOOTTIME追溯，约10ms量化。

| 从用户调用起点到达阶段（秒，中位数） | continuous | demand | gui-only |
|---|---:|---:|---:|
| 客户端RPC握手可用 | 12.14 | 12.00 | 12.13 |
| 已进入单机世界（inWorld+singleplayer） | 25.87 | 32.11 | 23.77 |
| 可视就绪（gameLoadFinished、无overlay、screenType=none） | 26.56 | 32.57 | 24.22 |
| 首张请求驱动的完整PNG响应 | 26.68 | 32.69 | 24.32 |
| MC进程创建→可视就绪 | 24.94 | 31.29 | 22.84 |

每模式3次的可视就绪范围：continuous **26.22–36.04s**，demand **26.21–32.63s**，gui-only **24.07–32.73s**。本组 demand 启动中位并没有更快，不能掩盖这一点；样本少且加载方差较大，不能把小范围差异归因为模式收益。当前轻量策略在加载/overlay期间仍完整运行资源与GL初始化，本来就不是启动裁剪。

首次独立消费者 Gradle `classes clientClasses` 准备日志为 **26s**（包含新daemon/配置/映射等，下载缓存已存在），单列不混入上表。没有清OS页缓存、Gradle/Mesa缓存或重启系统，因此**不是严格冷启动对比**。world_ready、RPC-ready、可视就绪与首PNG是不同事件；首图请求会强制出一个新帧。

## 环境和统计口径

- AMD Ryzen 7 5800H，16逻辑CPU/16核亲和范围；Linux6.17.0-20，OpenJDK21.0.10，Python3.12.3。
- 专用Xvfb :108，实际 Mesa24.0.5 / llvmpipe(LLVM17.0.6,256bits)，软件GL，LP_NUM_THREADS=4（只限制Mesa worker，不限制Java总线程）。**不能把这里的CPU收益外推成硬件GPU利用率或功耗收益。**
- 854×480，GUI scale2，renderDistance2/simulationDistance5，maxFps60、vsync=false。轻量模式外循环20Hz、默认idle heartbeat1Hz；主比较包含两者，是产品策略比较，不是同cap算法微基准。
- MC Xms256m/Xmx2G；消费者Gradle Xmx2G。全新MC JVM/gameDir、同一世界快照，30s固定warmup，20s世界空闲，开箱首图+5s等待，20s GUI空闲，然后20张截图按1秒间隔请求。
- 真实加载的是发布jar经独立Loom消费者处理的 `.fabric/processedMods/mcdebug-0.6.1-*.jar`，不是仓库classes；MC1.21.1/Loader0.19.5/API0.116.17+1.21.1/FLK1.13.9+kotlin2.3.10/Loom1.15.5/Gradle9.4.1。它仍不等同官方launcher的纯intermediary生产环境，也不是IC2/任意modpack性能基准。
- 测量中的release SHA256：`ca9d7eef2ca86a7f10016c7b20d1942ce19522776a4cc238ae6f60fdfa801b61`。9份复制jar均复核一致，原世界level.dat哈希未变。
- CPU使用真实MC PID的utime+stime差，100%=1逻辑核，包含集成服/JIT/GC/IO/同进程llvmpipe；不含控制器、Xvfb、private wrapper或共享Gradle daemon。CPU按阶段内实际约18–19秒/proc子窗口计算，不能称完整20秒总CPU或每张图CPU积分。原始数据保留了实际sample_duration_s。
- RSS/线程/FD约1Hz取样；VmHWM是OS进程生命周期高水位，不能称某阶段新增峰值。发现MC前无法采到完整线程/FD瞬态峰值；固定warmup不等于已证明GC/内存稳态或无泄漏。
- 截图是常驻Unix RPC完整延迟：排队/新帧/读回/crop/PNG编码/文件写入+hash/响应；inline=false、delayTicks=0，不含每张启动Java CLI及控制器二次hash验证。20张在0…19秒发出，不是满速吞吐。1Hz请求与1Hz heartbeat可能相位关联，p95只是60样本经验插值，不是总体尾延迟置信界。前5张/后15张均保留，不能把差异断言为编码器预热。

## 验证与复现

- 9次试验成功；180张周期GUI截图和18张首次窗口/GUI图均验证文件/hash、frameId递增；每次正常owned stop。全部真实MC及private wrapper已退出，socket清理完成。Xvfb被用户取消后确认display lock已消失；共享Gradle daemon未杀。
- 构建通过；JVM测试187通过、5个外部服smoke跳过；性能脚本18个stdlib测试通过。
- 完整原始数据和环境：[JSON gzip](benchmarks/client-2026-10-27.json.gz)。包括每次试验、采样、延迟、加载状态、清理结果和源码哈希；没有丢弃失败样本（本轮0失败）。
- [基准controller](../scripts/client-performance.py)、[汇总器](../scripts/summarize-client-performance.py)、[隔离consumer模板](../scripts/perf-consumer/README.md)、[完整测量方案](plan-client-performance.md)。

```bash
python3 scripts/summarize-client-performance.py --bundle docs/benchmarks/client-2026-10-27.json.gz
python3 -m unittest discover -s scripts -p 'test_*performance.py' -v
```

结论：当前优化主要是**稳定运行时少画帧以省CPU**。启动/资源常驻内存仍然重；若下一阶段追求启动与总体进程开销，应先针对测得的MC加载阶段和Gradle后台边界单独优化，而不是声称现有按需渲染已解决。
