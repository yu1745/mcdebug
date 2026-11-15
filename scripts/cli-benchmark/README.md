# CLI 启动与 RPC 开销测量

最后更新时间：2026-11-15。

此实验比较完整 mdb Native Image、最小 Java Native Image 和最小 Kotlin/Native 探针。
两个最小探针功能对齐：输出版本，或连接回环 TCP、读取握手、发送指定次数的
`server.status` 并输出响应。它们只检查正常路径的响应标记，不包含完整 CLI、JSON 解析、
协议校验、超时、发现或会话管理，不可当成生产客户端。

## 构建

准备 JDK 25、需要的 JDK 21、GraalVM 和 Kotlin/Native，路径由本机环境指定：

```bash
export JAVA_HOME=/path/to/jdk25
export JAVA21_HOME=/path/to/jdk21
export GRAALVM_HOME=/path/to/graalvm
export KONAN_HOME=/path/to/kotlin-native
scripts/cli-benchmark/build.sh
```

产物位于 `build/cli-benchmark/`，不替换 PATH 中的 mdb。
GraalVM 25 构建完整 CLI 时，需要将 Mordant 的无状态实现类
`NativeImagePosixMppImpls` 设置为构建期初始化；脚本显式传递此选项。

## 测量

```bash
python3 scripts/cli-benchmark/measure.py \
  --image build/cli-benchmark/mdb \
  --minimal build/cli-benchmark/minimal \
  --probe build/cli-benchmark/probe.kexe \
  --rounds 60 --batch 100 --out build/cli-benchmark/results.json
```

脚本启动专用确定性回环 TCP 端点，不接触游戏或存档。各实现使用相同握手与响应，
每项先预热 5 次，再按固定随机种子轮换测量。父进程用单调高精度时钟，
计时包含进程创建、初始化、通信、输出到管道和退出，响应解析校验在计时之外。
每次 RPC 运行都检查响应内容、请求数量及服务端连接数；批次必须恰好复用一个连接。
REPL 使用临时 user.home，不修改用户历史文件。

测量包含版本输出、单次 RPC、一个进程中执行 N 次 RPC，以及 `/usr/bin/true` 参考下界。
完整 mdb 分别测 `--batch` 和 `repl`：前者每条命令重建命令树，后者直接使用既有 RPC 客户端。
输出保存每次原始墙钟耗时、用户态 CPU、内核态 CPU、总 CPU，以及各自中位数和 p95。
CPU 使用顺序子进程执行前后的 `getrusage(RUSAGE_CHILDREN)` 差值，进程完成并回收后采样，
包含子进程全部线程，排除 Python 驱动器和模拟服务端；总 CPU 为每次用户态与内核态之和。
模拟端点另用 `thread_time_ns()` 统计处理线程 CPU，等待处理线程收尾的操作在墙钟计时之外。
该服务端数值不含接收循环、线程创建或驱动器开销，不代表整机 CPU 时间。
短进程的用户态和内核态分项受内核记账粒度影响，应优先比较总 CPU。
可改为 `--batch 1000 --rounds 20` 观察较长批次。
批次总耗时除以请求数包含启动摊销和命令处理，不能直接称为纯网络延迟。

这是热文件缓存下的新进程测试，不是清空系统缓存的冷缓存试验。
模拟端点不含游戏主线程、tick、真实状态生成和 GUI 稳定等待；结果用于定位启动与前端开销，
不能直接外推游戏内端到端延迟，也不能把完整 CLI 与最小探针之差全归因于运行时。
