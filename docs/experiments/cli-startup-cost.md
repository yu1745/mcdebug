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

## 新进程与 100 次请求结果

以下均为毫秒，总耗时包含进程启动、初始化、通信、输出和退出。

| 场景 | 中位数 | p95 |
|---|---:|---:|
| 完整 mdb Native Image：版本输出 | 9.531 | 9.843 |
| 最小 Native Image：版本输出 | 3.641 | 3.973 |
| 最小 Kotlin/Native：版本输出 | 1.584 | 1.819 |
| 完整 mdb Native Image：单次 RPC | 9.598 | 10.070 |
| 最小 Native Image：单次 RPC | 4.475 | 4.844 |
| 最小 Kotlin/Native：单次 RPC | 2.581 | 2.782 |
| 完整 mdb：100 次批处理 | 187.817 | 195.383 |
| 完整 mdb：REPL 中 100 次请求 | 19.038 | 20.332 |
| 最小 Native Image：100 次请求 | 8.601 | 9.376 |
| 最小 Kotlin/Native：100 次请求 | 6.522 | 6.979 |
| 参考：/usr/bin/true | 0.729 | 0.894 |

## 长批次与开销定位

| 路径 | 100 次总耗时（毫秒） | 1000 次总耗时（毫秒） | 增量每请求估计（毫秒） |
|---|---:|---:|---:|
| 完整 mdb 批处理 | 187.817 | 1469.391 | 1.4240 |
| 完整 mdb REPL | 19.038 | 95.457 | 0.0849 |
| 最小 Native Image | 8.601 | 44.528 | 0.0399 |
| 最小 Kotlin/Native | 6.522 | 41.405 | 0.0388 |

增量估计为两组中位数之差除以 900，包含命令处理、序列化、端点处理和输出；不是纯网络耗时。

源码中 `runBatch()` 在每条 `-c` 命令调用 `buildCli().parse(...)`，重建整棵命令树；
`ReplCmd` 在一个既有 `RpcClient` 上直接调用 `client.call`，绕开重复建树。
二者都复用了连接，因此长批次的大差异不能解释为反复握手。

## 结论与边界

1. Kotlin/Native 在此机器、此原型下启动更快。同功能最小程序的版本输出约 1.58 对 3.64 毫秒，
   支持它有实际启动优势，但不是完整 CLI 移植后的保证。
2. 完整 Native Image 约 9.53 毫秒，而最小 Native Image 约 3.64 毫秒，
   说明应用初始化和功能范围也贡献了较多成本，不能把全部差额归因于运行时。
3. 高频请求更应先处理重复命令树构造；现有 REPL 已能复用连接和客户端。
   机器友好的无提示符输出、严格失败退出等仍需单独设计，不能直接把交互 REPL 当测试协议。
4. 最小探针没有完整 JSON 解析、CLI、发现、会话或全部协议校验；
   与完整 mdb 的比较只能定位可优化空间，不能据此承诺全功能替换的收益。

## 原始数据

本机原始样本与构建日志保存在 `build/cli-benchmark/`：

- `results-verified.json`：60 次正式样本、100 请求批次，含连接与请求数校验。
- `results-1000.json`：20 次正式样本、1000 请求批次。
- `provenance.json`：系统、编译器、源码基线、二进制大小与 SHA-256。
- `native-build.log`：首次完整构建的 Mordant 初始化失败。
- `native-build-fixed.log`、`minimal-build.log`、`konan-build.log`：成功构建日志。

完整 Native Image 约 33 MB、最小 Native Image 约 14 MB、Kotlin/Native 原型约 528 KiB。
原始文件不提交，复现后可自行保存。

运行方式参考 [Native Image 官方说明](https://www.graalvm.org/reference-manual/native-image/)和
[Kotlin/Native C 互操作文档](https://kotlinlang.org/docs/native-c-interop.html)。
