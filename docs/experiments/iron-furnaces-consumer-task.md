# J1：最终发布物验收（V2 审查后派发）

唯一 Terra/low worker 执行，沿用既有安全和写入边界。先在 fabric-target 跑一次 `sh gradlew build check`，记录实际日志、NO-SOURCE/警告和 remapped jar SHA256；不重新跑原版基线，不改 mcdebug 或全局配置。

在实验根建立独立 `consumer-fabric`，无目标源码、无 target classes、无 project dependency；安装最终 intermediary jar 与 Fabric API、FLK、mcdebug 0.8.0，保留 JEI Fabric。依赖从既有已核实版本取，不添加重复 energy jar（目标已嵌入）。记录所有 jar 与实际加载路径。最终工件变更后旧消费证据作废。

## 主代理查明的生产客户端路线

Fabric 官方 production run task 能使用 intermediary 发布物，而不是 Loom 默认开发 remap。依据：<https://docs.fabricmc.net/develop/loom/production-run-tasks>；主代理已下载并阅读官方 Maven 的 1.10.5 源码，位于 `evidence/loom-1.10.5-sources.jar`，相关类 `net/fabricmc/loom/task/prod/{AbstractProductionRunTask,ClientProductionRunTask,ServerProductionRunTask}.java`。不要只按新文档猜旧版本接口。

独立 consumer 可使用已核实的 Loom 1.10.5 / Gradle 8.12 / Java21 / MC1.21.1 / Loader0.19.5。使用 `ClientProductionRunTask`，mods 集合只指定最终发布 jar 与运行依赖；清除默认 consumer 自身 remapJar 工件输入，不能把 target 开发输出引入。该 task 默认用 KnotClient、原版客户端 jar、intermediary mappings 和运行库，不开启开发模式。

客户端仍必须经 mdb session start/stop。当前 session 后端只接受名为 runClient 的任务，并传 `--args=...`；production task 没有这个命令行 option。已实测 Loom 无条件注册根 runClient，Gradle 拒绝将它替换为不同类型，最终采用 **consumer 本地** 的 `:bridge:runClient`：一个提供 Gradle `@Option(option='args', …)` 的最小 task，按 Gradle/Ant 现有命令行解析器解析 session 带引号参数，在 option setter 内将 gameDir 写入根 productionClient 的 runDir、其余窗口/quickPlay 参数追加 programArgs。bridge 依赖根原生 ClientProductionRunTask，不在 doLast 才转参数；不自造 Java 启动器，不改 mdb。固定测试 username，明确离线占位 accessToken=0 和 version=1.21.1（Main.java要求这两项），核实 task help 与实际参数。useXVFB=false，使用自有显示实例并传 session --display。

如果此桥接存在真实工具不兼容，保存最小错误并交主代理，不退回普通 dev runClient 冒充生产验证。只允许记录明示的开发 consumer 证据等级。

## 本轮验收闭包

1. 完整 build/check；独立专用服启动，确认公共入口/注册/mixin 无客户端类加载错误，正常保存停止，只操作精确 owned 进程，不杀 Gradle daemon。
2. 独立生产 consumer 的 owned demand 客户端，game-consumer 与专属 registry。使用正常关闭的测试存档离线副本；保持玩家可达、安全地板及头顶空间，不并发打开原存档。
3. 实际持炉对地板放置，服务端读方块/初始化/玩家 attachment；空手打开55槽菜单，GUI投入原铁/煤、自然tick加工、无遮挡中途PNG并亲自读图、GUI取出，服务端玩家Inventory与炉库存守恒。give仅准备输入，不写预期输出或进度、不用be.tick。
4. 保存运行身份：FabricLoader development=false、runtime namespace=intermediary、目标实际 jarPath、发布jar hash；可用只读反射或启动日志和进程参数。没有这些身份事实不能声称生产客户端已验。
5. `j1-report.md`、原始 RPC/日志/PNG、未验证项与清理状态，交主代理待审查。生产启动失败则先找真实 owner，不擅自改运行工具或降低门禁。

不扩其它炉型/边界矩阵。V2 已验证的重启/满槽无需在本轮机械重做；若实际产品代码因发布物缺陷改变，再由主代理决定相关回归。
