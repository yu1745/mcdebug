# 在 owned session 中验证 Fabric 发布物

仅用于发布物里程碑。普通 dev runClient 即使从 mods 加载目标jar，也可能做开发重映射；它不替代生产验证。生产 Fabric Loader 对原版 Minecraft 的 obfuscated→intermediary 映射是正常启动过程，与将目标mod重映射到开发命名空间不同。

独立消费者只接最终发布jar和运行依赖，没有目标源码、build/classes、project dependency或dev-launch-injector。记录最终jar哈希，修改工件后重新验收；安装调试工具不改变被测mod的发布依赖。

## 选择启动方式

优先查当前构建版本实际提供的生产任务。Fabric Loom 的 `ClientProductionRunTask` / `ServerProductionRunTask` 可运行 intermediary 发布物，配置示例见 [官方文档](https://docs.fabricmc.net/develop/loom/production-run-tasks)。接口以所用版本源码为准。显式设置mod集合，避免默认把消费者自身的remapJar或目标开发输出带入。

当前 mdb session 后端需要 Gradle wrapper、名为 `runClient` 的任务（允许 `:bridge:runClient`）和 `--args`。生产任务未必有 JavaExec 的 `--args` option，Loom也可能已占用根runClient名称。可在消费者子项目加最小Gradle任务桥接：用 `@Option` 接收session参数，将gameDir配置到生产任务runDir，其余窗口/quickPlay参数追加programArgs，然后依赖原生生产任务。参数必须在依赖执行前配置，不能到桥接task的doLast才转发。不要为此修改mdb或全局插件。

另一条有效路线是在消费者内用普通Gradle `JavaExec` 配置官方Fabric启动profile的KnotClient、官方MC jar及对应库，由mdb session统一监督；核对官方元数据和哈希，不从整个Gradle缓存拼通配classpath。不要依赖目标dev classes或手写无归属的裸客户端进程。专用服相同原则，使用对应服务器发布启动方式。

两条路线均须核实客户端必需参数、离线测试身份、资源索引和显示环境。需要占位accessToken时用离线值，不读取真实凭据；涉及玩家持久化时固定UUID。客户端用自己的显示服务，避免生产任务内再起一个不受记录的显示实例。

## 必须读回的事实

- 显式本轮sessions-dir、独立gameDir和存档，owned start→world_ready→正常stop；只有client_ready时看当前UI，处理首次欢迎页等正常交互，不把socket存在当进世界。
- 实际加载的目标mod来源与最终jar哈希；`gui status` 的jar字段是调试工具自身，不能替代目标来源。可读FabricLoader的ModContainer origin、目标类codeSource或进程打开文件。
- 运行命名空间与开发模式、实际进程classpath；排除named开发环境、target classes和dev launcher。能调用FabricLoader运行时查询时优先读实际值，不只看启动脚本声明。
- 在该实例真实重放核心用户路径并看PNG；新进程启动成功不能覆盖交互、状态与视觉验收。保存原始日志、相关问题游标及正常退出证据。

若启动方式不兼容，保留最小错误并升级；可报告已经完成的开发消费者证据，但不得静默降低为生产通过。
