# Minecraft 1.20.1（Forge）

只提供 **Forge** 形态（47.4.0），没有 Fabric / NeoForge 版本。通用工作流见 [SKILL.md](../SKILL.md)，
本文只写本版本的差异。这条版本线的源码是 1.20.1 分支的就地改写并已冻结，不会回合并到 1.21.1 / 26.3。

## 环境与安装

| | |
|---|---|
| 游戏 JDK | **17** |
| 前置 | **KotlinForForge 4.x**（模组 JAR 不内嵌 Kotlin 标准库，由它提供） |
| 模组 JAR | `mcdebug-forge-1.20.1-<version>.jar`（内嵌 MixinExtras，已 `reobfJarJar`，可直接放进 `mods/`） |

```bash
mcdebug jar --mc 1.20.1 --output <gameDir>/mods        # --loader 默认 forge，也是唯一可用的加载器
```

`mcdebug status` 的 `modLoader` 为 `forge`。托管会话不支持 ForgeGradle 工程：`session start` 会直接拒绝并提示，
需自行启动客户端（开发环境用工程自己的 `runClient`，生产环境用启动器），再
`mcdebug session attach NAME --game-dir <gameDir>`；连接会话不能由 mdb 停止。
轻量渲染模式需在启动前自行设置环境变量 `MCDEBUG_CLIENT_RENDER_MODE=demand|gui-only`。

## 与其他版本的差异

- **物品 NBT**：1.20.1 没有数据组件。`--nbt` 就是物品的完整 NBT 标签，`inv.getSlot` 原样回传；
  `components` 参数被忽略。附魔、自定义名称等直接写在该标签里。
- **快进**：没有原版 `/tick` 命令与 tick 速率管理器。`wait until --sprint` 被接受但只按自然速率等待
  （日志提示 `sprint requested but unsupported on 1.20.1`），长过程需要按实际时间等待。
- **`wait until` 谓词**：只有 `be[...]`、`inv[...]`、`block[...]`、`tick` 与 `sum/count` 聚合，
  没有 `storage[...]` 谓词源；要观察存储就用多次 `storage get` 加脚本判断。
- **`reflect.*`**：类名是官方名称；成员名在生产环境是 SRG 名，因此 `reflect.*` 只在开发环境有意义
  （这是本回移线的已知限制，它的主要用途是 GUI 与截图基准）。
- **提示弹窗**：没有 `gui.toasts`，`gui status` 不返回 `toastsEnabled`，调用 `gui toasts` 返回方法不存在（退出码 3）；
  教程、配方解锁与进度弹窗按原版显示，截图前先手动关闭或等待其消失。
- **按住修饰键**：没有 `--hold-shift/--hold-ctrl/--hold-alt`（这些选项会被旧模组忽略）。
- **存储**：句柄名与 Fabric / NeoForge 相同（`vanilla:inventory`、`fabric:item`、`fabric:fluid`、`teamreborn:energy`），
  流体单位 `mb`（1000 = 1 桶），能量单位 `FE`。Forge 能力没有事务，`storage.transfer` 是 dry-run 加尽力回滚。
- **服务端命令**：`server.runCommand` 仍向管理员聊天发送反馈（本线未移植 `showChat`，该参数被忽略）。
- **客户端渲染**：`--mode gui` 裁剪、`--annotate`、`--draw-log`、`--hash-only`、`--hide-cursor` 与
  `gui compare` 均可用；输入后端是 GLFW，键名与数值键码就是 GLFW 键码。

## 命令速查

```bash
mcdebug status
mcdebug session attach dev --game-dir <gameDir>
mcdebug raw world.setBlock '{"pos":[100,64,100],"block":"mymod:machine"}'
mcdebug raw inv.setSlot '{"pos":[100,64,100],"slot":0,"item":"minecraft:coal","count":8}'
mcdebug raw wait.until '{"predicate":"be[100,64,100].progress >= 100","timeoutTicks":1200}'
mcdebug --session dev gui open-block --x 100 --y 64 --z 100
mcdebug --session dev gui screenshot --mode gui --annotate
```

## 开发者

源码与构建在仓库的 `versions/mc1.20.1/`（Gradle 8.1.1 + ForgeGradle 6.0.6，只能在 JDK 17 上运行）：
`JAVA_HOME=<JDK 17> ./gradlew build`。本线没有冒烟脚本。
