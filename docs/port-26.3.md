# Minecraft 26.3 平台适配

基于原生 `1.21.1` 分支的提交 `72a70c2`，开发分支为 `26.3`，工作树为 `mcdebug-wt-263`。
1.21 工作树保持不变。Minecraft 版本移植复用现有后端测试用例；
独立的 [JUnit 测试前端](testkit.md) 使用自己的宿主测试验证。

> 合仓后（见[单仓多版本说明](monorepo.md)），本文的构建命令都在 `versions/mc26.3/` 下执行，
> 冒烟脚本位于 `versions/mc26.3/scripts/`，CLI 在仓库根用 `./gradlew :cli:copyCliJar` 打包。
> 下文保留移植当时的原始记录，路径按此对应。

## 构建

使用 JDK 25 和项目自带的 Gradle 9.6.0 包装器：

```sh
./gradlew build :cli:copyCliJar
scripts/fabric-smoke.sh
scripts/neoforge-smoke.sh
```

Fabric 使用无需重映射的 Loom 1.17.21、Loader 0.20.1 和 API 0.166.2+26.3。
NeoForge 使用 26.3.0.103 和 ModDevGradle 2.0.148；Kotlin 仍为 2.3.10。
两个模组 JAR 均内嵌标准库和协议类。CLI 和协议模块保留 Java 17 字节码，
两套构建均使用 JDK 25。CLI 内嵌同一次构建的 Fabric 和 NeoForge JAR。

## 必须适配的 API 变化

- 开发和生产环境均使用官方名称。`ResourceLocation` 改为 `Identifier`，
  资源键通过 `identifier()` 访问。Fabric 生成的 gzip 资源改为短类名索引，
  不再转换 intermediary 成员名，也不再执行重映射任务。
  使用完整类名调用 `reflect.resolve` 时，`runtimeClass` 返回官方名称；
  相同的 `mojangClass` 不再重复返回，与 NeoForge 的既有行为一致。
- 注册表查询使用 `lookupOrThrow` / `getValue`，方块状态属性使用 `Property.Value`。
  组件补丁通过 `split()` 拆分；编码仍用原有 RPC 字段分别报告新增、移除和错误。
- 方块实体加载、实体保存和加载改为通过 `NbtIo` 使用携带注册表信息的
  `ValueInput` / `ValueOutput`。数值标签使用取值方法，复合标签的读取方法返回可选值。
  现有 NBT 测试已适配新 API，包括原有的 Unicode 往返验证。
- 合成使用 `CraftingRecipe`、配方资源键和 `assemble(input)`。
  合成剩余物仍由匹配到的合成配方提供。
- 假玩家使用 `snapTo`、基于装备槽的库存 tick、双精度跌落距离、永久无敌和新的重生数据。
  实体交互接收局部命中坐标。新的 `InteractionResult` 记录在物品上下文未表明发生物品使用时，
  仍保留旧 `action` 标签 `success_no_item_used` / `consume_partial`。
  NeoForge 现在会在 `Player.interactOn` 内触发交互事件，因此显式前置钩子返回 `PASS`，
  避免重复触发。其假玩家数据包监听器也按 NeoForge 自带 `FakePlayer` 的做法，
  对载荷通道探测返回 `false`：菜单数据同步在发送前会检查扩展载荷支持，
  直接读取连接通道属性的模组也可以查询无真实客户端的嵌入式 Netty 通道。
  监听器忽略发送（含回调）与断开请求，26.3 的发送回调类型为 `ChannelFutureListener`。
- Screen 和覆盖层状态由 `Minecraft.gui` 管理。输入使用 `KeyEvent`、`CharacterEvent`
  和 `MouseButtonEvent`。`InputEvents` 将既有 RPC 的 GLFW 键码、修饰键位掩码及
  按钮编号（0 左键 / 1 右键 / 2 中键）转换为 SDL 扫描码、键码和按钮。
  Unicode 文本输入发送完整码点，不再分别发送 UTF-16 代理项。
- 渲染拆分为状态提取与 GPU 渲染两个阶段。按需渲染和仅界面渲染的调度同时控制这两个阶段，
  保留自然 tick、任务和窗口事件。可唤醒的限速器改用 `LockSupport`，替代已移除的 GLFW 事件调用。
- GUI 观测钩子改为针对 `GuiGraphicsExtractor` 和 `GuiTextRenderState`，
  覆盖直接文字、控件文字、平铺精灵、物品和提示框。Screen 异常保护包装两个加载器共用的
  最终状态提取方法，替代原先针对加载器分别设置的绘制目标。
  虚拟光标同时应用于图形提取器和 Screen 参数。
- 截图使用原版异步 GPU 读回，无需调用 OpenGL 帧缓冲 API 即可支持渲染后端。
  读回前冻结帧几何、控件矩形和绘制日志；有界容量预留在读回、编码和取消期间始终持有。
  PNG 编码使用 `NativeImage` 的通道写入器，每个输出只编码一次。
- 界面稳定屏障仍使用命令补全建议。数据包线程分派现在接收 `PacketProcessor`，
  因此对应的 mixin 描述符也随之变化。
- NeoForge 以事务型 `ResourceHandler` / `EnergyHandler` 替代旧物品、流体和能量能力。
  物品与流体搬运在同一事务内提交，模拟操作则回滚。`ItemAccess` 跟踪水桶替换和物品能量变化。
  流体单位仍为 mB，Fabric 仍使用微滴；`fluid.*` 的罐索引保留原有 NeoForge 行为。
  新 API 不再提供 `canReceive/canExtract` 能力标志，因此能量支持标志改为通过模拟单单位操作探测。
  两个加载器都按堆叠上限限制物品槽容量，空槽按 64 计算。
- `/tick sprint stop` 现在会在没有活动冲刺时抛错。原有冒烟断言改为检查错误文字；
  其他冒烟脚本变化仅更新目标版本和改名后的类。

## 验证

- 完整 `./gradlew build :cli:copyCliJar` 通过，包括两套 JAR 自包含检查。
- 原有根项目测试 155 项通过。原有 CLI 测试共 141 项，其中原有跳过项 5 项，
  无失败或错误。版本移植没有新增测试文件或测试用例。
- 两套原有专用服务端冒烟脚本均通过，各覆盖 81/81 个服务端方法。
- 原有 `parity-diff.py` 比较 53/53 项一致，没有非预期差异。
  已记录的 Fabric 专属短类名解析仍作为预期差异保留。
  消费者客户端使用相同离线用户名进行状态比较，任务文件未修改。
- Fabric 开发客户端已验证：创建世界、清空和输入包含 emoji 的名称、收到确认的界面稳定屏障、
  箱子 GUI、槽位与坐标点击、悬停提示、标注和裁剪截图、绘制日志、被动拖拽分配、Escape，
  以及正常保存和停止。GUI 为 176×168、缩放为 2 时，截图为 352×336，
  文字、物品和精灵均被记录。
- 隔离消费者只从 `run/mods` 加载 CLI 提取出的 JAR，不加载 mcdebug 源码集或独立 Kotlin 语言模组。
  两个加载器均通过托管会话到达 `world_ready`、打开箱子、通过点击改变服务端库存，
  并生成真实的裁剪和标注截图。NeoForge 悬停会记录实际提示框；
  原生右键点击将五件物品分为手持三件、箱中剩余两件。
- 已在 Xvfb/Mesa 下验证 OpenGL 和 Vulkan 读回。Fabric 显式使用
  `--graphicsBackend VULKAN` 启动，日志确认使用 llvmpipe 的 Vulkan 后端。
  已查看 352×336 PNG 和金锭提示框；这不代表两个后端的像素完全一致。
- CLI 也能在 OpenJDK 17 上启动并报告版本。
- 测试会话通过 CLI 正常停止，验证日志和截图保存在本机 `/tmp/mcdebug-263/`。
