# Session 管理第一版

目标：mdb 接管客户端生命周期与双平面寻址；复用目标项目的 Gradle runClient，不 fork MC、不引入旧版兼容或新测试 DSL。

## CLI

- `session start NAME --project PATH --task runClient --game-dir PATH --world SAVE --render demand`：project 默认当前目录、task 默认 runClient、gameDir 默认 project/run。world 可省略（启动到主菜单）；指定 world 时必须是 gameDir/saves 下已存在的目录名，不自动创建/删除存档。
- `session attach NAME --game-dir PATH`：附着已有本机客户端，不取得停止权限。
- `session list`、`session status NAME`、`session logs NAME --lines 80`、`session stop NAME [--force]`。
- 根选项 `--session NAME` 自动将 gui.* 路由到客户端，其余方法路由到同进程集成服务器；与显式 transport 覆盖互斥。不设置全局当前会话；batch 继承会话选择。
- 根选项 `--sessions-dir PATH` / `MCDEBUG_SESSIONS_DIR` 指定 registry；默认 XDG_STATE_HOME 或 ~/.local/state 下的 mcdebug/sessions。
- 第一版只管理本机 Unix endpoint；跨机/独立多人服务器仍用现有显式 TCP/socket 命令，不把另一个服务器 PID 冒充集成服。

## 进程身份与协议

复用 server.ready，新增 process 对象（两平面同 JVM 身份相同）：pid、startTime（ProcessHandle 的 ISO Instant）、instanceId（每 JVM UUID）、sessionToken（管理器为本次 launch 生成的随机 token；非鉴权）、gameDir（规范绝对路径）。CLI 握手解析并在 Session 请求时核对完整身份；正常连接不产生额外 status RPC。

客户端在绑定完成后原子发布 gameDir/mcdebug/client.json，包含 process 和实际 clientSocket。gui.status 同样包含 process。发现文件不是进程存活/世界就绪的证明；需要 ProcessHandle PID+startTime、握手身份验证，以及要求进入世界时的 gui.status.inWorld。集成服的实际 socket 从 mcdebug/port 发现，握手必须同一 process。

新增 gui.stop（真实客户端线程调用 Minecraft.stop，不推进 tick），同步方法契约、SDK与CLI帮助。Session stop 只允许 owned 会话；先检查 PID/startTime/instanceId/token，再经同一已验证连接请求正常停止。--force 只允许对身份仍匹配的 owned 客户端强制终止，不杀共享 Gradle daemon，不自动强杀附着客户端。

启动阶段没有真实 MC 身份时，不猜 PID、不杀共享 daemon。管理器写入本次 token 的 mcdebug/session-stop.json；客户端 CLIENT_STARTED 检查匹配 token 则立即正常停止。管理器可取消自己创建且 PID/startTime 匹配的 Gradle wrapper；即使已有 JVM 仍继续 bootstrap，启动时的 stop 标记也能阻止其遗留常驻。标记不删除，下一次不同 token 忽略。

## 元数据与并发

Registry 每会话 JSON，字段使用字符串路径/时间及普通数字，显式 JsonObject 编解码，避免 Gson 类反射及 native CLI 反射配置依赖。保存 launch 配置、owned 标志、本次 token、启动时间、logPath、wrapper 身份、已确认 MC 身份和端点。原子写与文件锁保护；长启动等待不占用 registry 全局锁，允许其他 CLI 看状态/请求停止。

同一规范 gameDir 的并发启动需跨 registry 防护：gameDir/mcdebug/mdb.lock 和持久 owner 记录覆盖 bootstrap 尚未生成 client.json 的时间窗；活 wrapper/MC 或当前启动锁存在就拒绝第二次启动。已有外部客户端的 live client.json 同样拒绝；不删除 Minecraft 的 session.lock，不复制本项目 jar 到自己的 run/mods。客户端还在绑定前获取 mcdebug/client.lock 并持有至 JVM shutdown，防止另一个新客户端覆盖活端点；被新 owner token 超越或已取消的旧 bootstrap 在绑定前退出该启动路径，避免延迟 JVM 覆盖新实例的发现文件。

启动直接使用目标项目 wrapper，参数数组传递，不拼 shell 命令。输出重定向到会话日志，Linux setsid 脱离调用终端；不新增常驻 daemon。runClient task 及 gameDir/存档参数需验证，带空格路径正确转义。

## 验证

- 单测：registry 原子读写/名称校验、同 gameDir 防重复、启动失败/超时/身份失配、owned/attached 停止边界、PID reuse 防护、日志UTF-8、批处理 --session 继承、双平面 identity 握手与路由。
- 编译：CLI test/copyCliJar、compileClientKotlin、完整 build（含 Forge 同步 RpcEnv/RpcServer）。
- 真实客户端：隔离 gameDir + 存档副本，session start（内部 Gradle）→ world_ready → --session gui 截图/服务端查询 → attach 身份验证与禁止停止 → owned stop → socket/进程清理。start 失败不宣称远端操作回滚，也不自动删除游戏数据。

## 已完成验证

- `./gradlew :cli:test :cli:copyCliJar compileClientKotlin build --console=plain` 通过；最后增补 batch 防串线后再次 `:cli:test :cli:copyCliJar build` 通过，CLI jar 已重建，Fabric/Forge 均构建成功。当前 JUnit 192 项：187 通过、0 失败/错误、5 项需要外部服务器的 runner smoke 跳过；无新增 Kotlin 编译警告。
- 实机隔离目录 `/tmp/mdb-session-cde69jxd/game with spaces`，从原世界复制到 `saves/Smoke World`（不复制 session.lock）；原世界未改。全部客户端由 mdb 内部启动 Gradle，无手动 runClient 常驻终端。
- `smoke`：demand/idleFps=0、854×480，带空格 gameDir 和世界名；返回 world_ready，PID 来自真实 MC 而非 wrapper。--session 的 server.status/gui.status/截图 batch 成功，两平面 TCP 都关闭。按需截图得到新的 frameId/clientTick。
- 同配置再次 start 复用了相同 MC 与 wrapper；另一 registry 对同 gameDir 的启动被拒绝；logs 有限 tail 正常。attach 获得 owned=false，即使 --force 也拒绝停止。
- owned stop 正常保存/退出、删除两 socket；attached 状态随后为 dead，后续 --session attached 调用失败，不回退到 CWD 端点。
- `menu`：使用 qualified `:runClient`、不指定 world，gui-only 返回 client_ready，主菜单截图成功。实测 Java FileLock 在客户端活着时不可重新取得，正常停止后可重新取得。两个真实 MC 与 private wrapper PID 均已消失；测试 Xvfb 已停止，共享 Gradle daemon 未杀。
- 补充 batch 安全边界：每个平面的已复用连接禁止中途改变实际 session identity/transport/deadline，防止第二条命令被错误地发给第一条连接；root session/registry 正常继承保持双平面各一连接。普通远程 TCP 的 process.gameDir 是对端 OS 的不透明路径，不用本机 Path 语法拒绝 Windows 对端。

## 第一版边界

- 需要有效显示服务与真实 MC/GL 资源初始化；不负责启动 Xvfb，不承诺低内存。直接 JVM launcher/export backend、远端 Session、缓存 prune/forget 不在本版。
- 启动中停止且从未确认 MC 身份时，仍保留 stopping/占位；即使 wrapper 已死，也不能仅凭它宣称所有潜在延迟 JVM 都退出。此不确定状态不提供自动强制遗忘，避免为了重试而杀错进程或删世界。正常已确认实例支持停止后再次启动。
- 当前实机验证为本仓库 dev classpath + Fabric API；未把新构建部署到用户正在使用的消费者工程/生产实例。消费者需同时更新当前 mod jar 与 CLI，旧协议/旧实例兼容不在本版。
