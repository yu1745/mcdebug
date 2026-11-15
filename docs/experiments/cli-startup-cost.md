# CLI 启动与 RPC 开销实测

最后更新时间：2026-11-15。状态：已完成隔离回环端点实测。

[复现脚本与构建方法](../../scripts/cli-benchmark/README.md) · [使用指南](../usage.md)

## 条件与方法

AMD Ryzen 7 5800H、Linux x86_64；GraalVM CE 25.0.1+8.1，Kotlin/Native 2.2.20（`-opt`）。
完整 CLI 来自当前 26.3 源码，另外构建功能对齐的最小 Java Native Image 与 Kotlin/Native 探针。
本机 PATH 中的 mdb 实际是 JVM JAR 启动脚本，本次直接调用新构建的 Native Image 二进制，避免混淆。
最小探针只处理版本、TCP 握手和固定 RPC 正常路径，不能代替完整 CLI。

默认组每项预热 5 次、正式 60 次，随机轮换执行；较长批次组每项正式 20 次。
每次使用新进程，文件缓存已热，不清共享缓存。输出捕获到管道，响应校验在计时之外。
相同的专用 Python 回环 TCP 端点发送 `server.ready` 和确定性 `server.status` 响应。
服务端计数确认每个 RPC 场景恰好一个连接，且请求数与响应数匹配。
REPL 使用临时用户目录，不修改真实历史文件。本实验没有运行游戏，
不包含游戏主线程、tick、状态构造、GUI 或 Unix 套接字的成本。


CPU 使用每次子进程启动前、结束并回收后的 `getrusage(RUSAGE_CHILDREN)` 差值，
分别记录用户态、内核态及二者之和，包含被测子进程的全部线程，不含 Python 驱动器或模拟服务端。
模拟服务端处理线程另用 `thread_time_ns()` 计时；收尾屏障在墙钟计时之外，
此数值不含接收循环和线程创建开销，不能称为完整服务端或整机 CPU。
方法见 [Python resource 文档](https://docs.python.org/3/library/resource.html#resource.RUSAGE_CHILDREN)
与 [线程 CPU 时钟文档](https://docs.python.org/3/library/time.html#time.thread_time_ns)。

本次重测沿用上一轮三个二进制，SHA-256 已核对，避免把重新构建差异混入 CPU 比较。
睡眠与忙循环对照的墙钟均约 163 毫秒，总 CPU 分别约 12 和 164 毫秒，确认计时能区分等待与执行。

## 新进程与 100 次请求结果

以下均为毫秒，墙钟总耗时包含进程启动、初始化、通信、输出和退出。
CPU 表示实际消耗的处理器时间。各中位数独立计算，因此用户态和内核态中位数之和不一定等于总 CPU 中位数。
短进程的分项还受内核记账粒度影响，0.000 不代表没有执行用户代码，应优先比较总 CPU。

| 场景 | 墙钟中位数 | 用户态 CPU | 内核态 CPU | 总 CPU 中位数 | 总 CPU p95 |
|---|---:|---:|---:|---:|---:|
| 完整 mdb Native Image：版本输出 | 9.491 | 2.924 | 6.669 | 9.518 | 9.979 |
| 最小 Native Image：版本输出 | 3.672 | 0.000 | 3.505 | 3.698 | 3.906 |
| 最小 Kotlin/Native：版本输出 | 1.626 | 0.000 | 1.511 | 1.583 | 1.780 |
| 完整 mdb Native Image：单次 RPC | 9.626 | 2.957 | 6.882 | 9.710 | 10.168 |
| 最小 Native Image：单次 RPC | 4.417 | 0.404 | 3.790 | 4.252 | 4.563 |
| 最小 Kotlin/Native：单次 RPC | 2.580 | 0.000 | 2.259 | 2.357 | 2.571 |
| 完整 mdb：100 次批处理 | 190.626 | 136.301 | 50.484 | 187.137 | 193.953 |
| 完整 mdb：REPL 中 100 次请求 | 19.350 | 5.852 | 11.730 | 17.432 | 18.670 |
| 最小 Native Image：100 次请求 | 8.657 | 0.992 | 5.299 | 6.165 | 6.821 |
| 最小 Kotlin/Native：100 次请求 | 6.548 | 0.000 | 3.829 | 3.913 | 4.274 |
| 参考：/usr/bin/true | 0.731 | 0.000 | 0.545 | 0.614 | 0.701 |

## 长批次与开销定位

| 路径 | 100 次墙钟 | 1000 次墙钟 | 增量墙钟 / 请求 |
|---|---:|---:|---:|
| 完整 mdb 批处理 | 190.626 | 1475.912 | 1.4281 |
| 完整 mdb REPL | 19.350 | 90.194 | 0.0787 |
| 最小 Native Image | 8.657 | 45.088 | 0.0405 |
| 最小 Kotlin/Native | 6.548 | 41.378 | 0.0387 |

| 路径 | 100 次总 CPU | 1000 次总 CPU | 增量 CPU / 请求 | 1000 次服务端处理线程 CPU |
|---|---:|---:|---:|---:|
| 完整 mdb 批处理 | 187.137 | 1443.590 | 1.3961 | 48.814 |
| 完整 mdb REPL | 17.432 | 72.269 | 0.0609 | 29.919 |
| 最小 Native Image | 6.165 | 22.316 | 0.0179 | 28.121 |
| 最小 Kotlin/Native | 3.913 | 18.267 | 0.0159 | 27.677 |

以上均为毫秒。增量估计是两组中位数之差除以 900，包含命令处理、序列化、通信和输出，
不是纯网络耗时。客户端 CPU 与服务端处理线程 CPU 分开统计，未相互混入。

源码中 `runBatch()` 在每条 `-c` 命令调用 `buildCli().parse(...)`，重建整棵命令树；
`ReplCmd` 在一个既有 `RpcClient` 上直接调用 `client.call`，绕开重复建树。
二者都复用了连接，因此长批次的大差异不能解释为反复握手。

## 结论与边界

1. Kotlin/Native 的最小原型同时节省启动时间和 CPU，并非只是等待更少。
   版本输出总 CPU 为 1.58 对 3.70 毫秒；单次 RPC 为 2.36 对 4.25 毫秒，
   均与功能对齐的最小 Native Image 比较。
2. 长连接中 1000 次请求的客户端总 CPU 为 Kotlin/Native 18.27、最小 Native Image 22.32 毫秒，
   约减少 18%；增量每请求 CPU 约 0.0160 对 0.0179 毫秒，差距比独立启动时小。
3. 完整 mdb REPL 的 1000 次请求总 CPU 为 72.27 毫秒，批处理为 1443.59 毫秒。
   高频调用仍应优先减少重复命令树构造；这些是不同处理路径的实测对比，
   不是将全部差异严格归因于某一个函数的性能剖析。
4. 最小探针没有完整 JSON 解析、CLI、发现、会话或全部协议校验；
   与完整 mdb 的比较只能定位可优化空间，不能据此承诺全功能替换的收益。
   机器友好的无提示符输出、严格失败退出等仍需单独设计，不能直接把交互 REPL 当测试协议。

## 原始数据

本机原始样本与构建日志保存在 `build/cli-benchmark/`：

- `cpu-100.json`：60 次正式样本、100 请求批次，包含墙钟、CPU 分项、服务端处理线程 CPU 和连接校验。
- `cpu-1000.json`：20 次正式样本、1000 请求批次，同样包含完整 CPU 数据。
- `cpu-provenance.json`：本次脚本哈希、源码基线和二进制一致性检查。
- `cpu-accounting-control.json`：睡眠与忙循环的 CPU 计时对照。
- `results-verified.json`、`results-1000.json`：上一轮只测墙钟的原始样本。
- `provenance.json`：系统、编译器、源码基线、二进制大小与 SHA-256。
- `native-build.log`：首次完整构建的 Mordant 初始化失败。
- `native-build-fixed.log`、`minimal-build.log`、`konan-build.log`：成功构建日志。

完整 Native Image 约 33 MB、最小 Native Image 约 14 MB、Kotlin/Native 原型约 528 KiB。
原始文件不提交，复现后可自行保存。

运行方式参考 [Native Image 官方说明](https://www.graalvm.org/reference-manual/native-image/)和
[Kotlin/Native C 互操作文档](https://kotlinlang.org/docs/native-c-interop.html)。
