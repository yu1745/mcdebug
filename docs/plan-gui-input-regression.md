# GUI 输入、拖拽、断开与截图回归

用户明确继续补齐这四项；目标是现有真实 Screen/RPC 闭环的增强，不引入新测试 DSL/Gradle 插件，不驱动 tick，不做旧版本适配。只做一次隔离客户端功能冒烟，不重跑长时间性能基准。

## 输入契约

- `gui.key`：当前 Screen 的一次 keyPressed + keyReleased，参数 key（GLFW code 或名称）、modifiers（GLFW 位掩码）。只派发 Screen 事件，不注入系统键盘、不触发全局游戏移动/F键快捷键、不跨请求持有按键。
- `gui.type`：当前 Screen 的 charTyped 序列，text 有界且 Unicode 校验；clear 可选，使用逻辑全选/退格事件，不直接写 EditBox。字符事件不等于key事件；换行/控制键须用 gui.key。
- `gui.drag`：from/to 双坐标、space=gui/png、button、steps、stepTicks、modifiers、moveCursor。press→逐步 mouseDragged→release，位于客户端线程；stepTicks 是被动自然等待，绝不 sleep/tick.run。只允许一个活动手势，屏幕/尺寸改变即停止，异常/超时/停止也释放原目标。PNG坐标在开始时冻结映射，不跟随其它截图变化。
- Screen 常用 Ctrl/Shift/Alt 快捷键内部读取静态 has*Down，单传keyPressed modifiers不够。因此增加仅当前派发调用栈有效的 ThreadLocal modifier override mixin；结束/异常立即恢复，不改变玩家物理键状态。
- 输入限制当前 Screen；不模拟游戏内移动/战斗/全局调试快捷键。text/手势长度、tick总量与墙钟均有界，不让不可信参数长时间占用主线程。

## 主动断开

`gui.disconnect` 复用当前版本原版退出世界/连接清理流程，保存并关闭集成服务器，返回标题页；保留客户端进程、客户端RPC和资源。等待是被动客户端tick观察，不能把“已发起”当“已经断开”。不关闭 Session、不写停止token、不杀任何进程。连接尚未完成的情况必须安全拒绝或走已核实的取消路径，不假装世界已断开。

## 截图回归

- 本地 `gui compare --expected PNG --actual PNG [--diff PNG]`，无需MC连接；返回显式比较JSON，不匹配非0退出。
- CLI/SDK纯PNG比较工具与断言，可由消费者现有JUnit调用。默认像素精确比较，显式 per-channel tolerance 和允许差异像素比例；尺寸不同直接失败，绝不隐式缩放/对齐/更新golden。
- 输出不同像素数、比例、最大通道差、差异包围盒和可选diff图。保护expected/actual不被diff路径别名或symlink覆盖。
- 避免向 native CLI 引入 AWT/ImageIO 动态插件依赖：使用有界、CRC校验的标准库 PNG RGB/RGBA8 非交错codec（不支持的格式明确拒绝），zlib/filters由纯单测覆盖。
- SDK可请求 inline PNG 在调用方比较，不能假设远程RPC返回的文件路径本地可读。断言失败抛 AssertionError；不得自动接受或替换基线。
- 固定尺寸/GUI scale/render模式/光标/动画状态仍由消费者编排，比较不是任意mod/字体/GPU间像素确定性的承诺。

## 验证

同步Methods.CLIENT_METHODS、DebugApi、命令/help与README；构建自包含检查覆盖新input/mixin类。纯单测覆盖key/modifier/Unicode/手势状态和PNG filters/CRC/边界/差异阈值/路径保护。隔离客户端复用现有consumer模板和存档副本：输入搜索框含Ctrl+A/非ASCII、拖动原版滑条、退出世界后仍可截图，比较相同/不同PNG及SDK断言；最后正常owned stop并关闭专用显示服务。

## 已完成验证（2026-10-27）

- Fabric/client/CLI 构建、自包含检查和 CLI jar 更新成功；Forge 独立构建成功。首次在线构建遇到 piston-meta.mojang.com DNS 失败，随后使用已准备的缓存离线验证：`./gradlew --offline compileClientKotlin :cli:test :cli:copyCliJar build -x :forge:build`，以及在 forge 目录 `./gradlew --offline build`。
- JUnit 合计234项：229通过、0失败/错误、5项依赖外部服的runner smoke跳过；新增42项覆盖输入解析/状态、PNG/比较、CLI和远程inline SDK断言。stdout capture曾与其它测试并发争抢，已为该CLI测试类添加@Isolated后重跑通过。
- 仅一次独立consumer真MC冒烟，发布jar加载，13个客户端方法contractMismatch为空；软件GL/Xvfb :109、854×480、guiScale2、maxFps20、gui-only，未重跑性能基准。
- 搜索框输入 `机器😀`、逻辑Ctrl+A替换为 `GuiSmokeWorld` 成功；原版FOV滑条4段拖拽从Normal改到108。另一连接在20段被动拖拽期间关闭Screen，返回ok:false/screenChanged并释放旧目标，没有继续作用标题页。
- 相同GUI两张PNG精确比较通过；Java SDK真实请求inline PNG的assertScreenshot通过。拿起5个钻石后比较失败，识别1508个差异像素并生成diff，CLI退出1；未改基线。
- gui.disconnect保存并停集成服，原PID/instanceId不变，服务端socket消失、客户端socket仍在；断开后窗口截图、搜索框和滑条操作继续成功。最后session stop正常退出，复核真实MC/private wrapper、两socket和X109锁都已消失，共享Gradle daemon未杀。
- 没有声称验证硬件GPU/其它modpack/Native Image重建；本轮未做跨GPU像素确定性、全局游戏按键或连接中取消。
