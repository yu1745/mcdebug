# 隔离的发布 JAR 性能测试消费者

此目录只是模板，**运行前必须复制到新的临时目录**。
模板不包含 mcdebug 源码集或资源。基准测试将重映射后的发布 JAR 安装到每个全新游戏目录的
`mods/` 中，验证消费者加载，而非 mcdebug 自身的 Loom 开发类。
它仍是 Loom 运行环境，不是官方启动器中的 intermediary 生产安装。

在 mcdebug 仓库根目录执行：

```bash
./gradlew compileClientKotlin :cli:copyCliJar build --console=plain
REPO="$PWD"
F="$(mktemp -d /tmp/mcp-XXXXXXXX)"
mkdir -p "$F/consumer/gradle/wrapper"
cp scripts/perf-consumer/build.gradle scripts/perf-consumer/settings.gradle scripts/perf-consumer/gradle.properties "$F/consumer/"
cp gradlew "$F/consumer/gradlew"
cp gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties "$F/consumer/gradle/wrapper/"
```

单独准备依赖并记录耗时。不要删除共享 Gradle、下载、页缓存或 Mesa 缓存，
不要终止共享守护进程，也不要把此次测量称为冷缓存测试。
在 `$F/consumer` 中使用复制后的包装器运行 `classes clientClasses`。

提供已运行的专用 X 显示设备（本次测量使用 Xvfb :108）和**已停止世界的快照**。
禁止使用正在运行的生产世界、已有消费者的 run/mods 或指向存档的符号链接。
控制器复制世界时排除 session.lock，不删除或绕过源世界的锁。

```bash
python3 scripts/client-performance.py --project "$F/consumer" --cli "$REPO/mcdebug-cli.jar" --mod-jar "$REPO/versions/mc26.3/build/libs/mcdebug-<version>.jar" --source-world "$REPO/versions/mc26.3/run/saves/New World" --out "$F/results" --display :108
python3 scripts/summarize-client-performance.py --manifest "$F/results/manifest.json" > "$F/summary.json"
python3 -m unittest discover -s scripts -p 'test_*performance.py' -v
```

输出目录必须尚不存在。默认执行 3 轮按拉丁方顺序轮换的试验，每轮 3 种模式：
预热 30 秒、世界空闲 20 秒、GUI 稳定 5 秒、GUI 空闲 20 秒，再以 1 秒间隔连续截取 20 张 GUI 图。
保留全新临时游戏目录、会话记录、日志和 PNG；控制器在启动下一实例前正常停止当前自有客户端。
失败时先检查 JSON 清理结果，不要通过删除 session.lock 或终止共享 Gradle 进程解决问题。

配置：MC1.21.1 / Loader0.19.5 / FabricAPI0.116.17+1.21.1 / FLK1.13.9+kotlin2.3.10；
854×480，GUI 缩放 2，渲染距离 2，模拟距离 5，maxFps60，关闭垂直同步，MC Xms256m/Xmx2G。
控制器显式选择使用 4 个 Mesa 工作线程的 llvmpipe 软件 OpenGL，
每次试验均检查实际图形标识。这不是硬件 GPU 利用率测量，也不是通用整合包基准。

CPU 100% 表示一个逻辑核心，并非整机。资源统计采用每阶段记录的实际 /proc 采样子窗口，
约 18–19 秒，不是严格覆盖整整 20 秒的 CPU 积分。RSS 不等于 Java 堆或显存；
VmHWM 是整个进程生命周期的内存峰值。截图耗时包含新帧调度、读回、裁剪、PNG 编码、
文件写入与哈希，以及持久 Unix RPC 响应，不是每次启动 Java CLI 的延迟。
完整测量计划见[客户端性能测量计划](../../docs/plan-client-performance.md)。
