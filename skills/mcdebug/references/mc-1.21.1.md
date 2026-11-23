# Minecraft 1.21.1

适用于 1.21.1 线的 Fabric 与 NeoForge（21.1.x）模组。通用工作流见 [SKILL.md](../SKILL.md)，本文只写本版本的差异。

## 环境与安装

| | |
|---|---|
| 游戏 JDK | **21** |
| Fabric | Loader 0.19.5、Fabric API 0.116.17+1.21.1 |
| NeoForge | 21.1.265 |
| 模组 JAR | `mcdebug-fabric-1.21.1-<version>.jar`、`mcdebug-neoforge-1.21.1-<version>.jar`（均内嵌 Kotlin 标准库） |

```bash
mcdebug jar --mc 1.21.1 --loader fabric   --output <gameDir>/mods
mcdebug jar --mc 1.21.1 --loader neoforge --output <gameDir>/mods
```

`mcdebug status` 的 `mcVersion` 为 `1.21.1`，`modLoader` 为 `fabric` 或 `neoforge`。

## 与其他版本的差异

- **键码**：输入后端是 GLFW，CLI 的键名与数值键码就是 GLFW 键码，与 26.3、1.20.1 的 CLI 层格式一致。
  GUI 输入只派发给当前 Screen。
- **`reflect.*` 名称**：NeoForge 在开发与生产都用 Mojang 官方名称，且只接受完整类名（短类名表只有 Fabric 有）。
  Fabric 开发环境是官方名称，生产环境是 intermediary，模组内置本版本的映射表
  （`mappings/reflect.txt.gz`）：Mojang 名、intermediary 名与短类名都能解析，
  `reflect.mappings` 返回 `{"available":true|false}` 报告映射表是否可用。
- **存储语义**：Fabric 用 Transfer API 的 `Transaction`，`storage.transfer` 是原子的（目标收不下则整笔不发生）；
  NeoForge 21.1 用能力（`IItemHandler` / `IFluidHandler`），没有事务，`storage.transfer` 是 dry-run 加尽力回滚，
  失败时可能已部分搬运，务必用 `storage get` 复查。
  `fluid.* --index` 在 NeoForge 只选择**报告**哪个罐，填充与抽取由整个 `IFluidHandler` 路由；
  `supportsInsertion/supportsExtraction` 在 NeoForge 表示**当前状态**，在 Fabric 是能力标志，不要跨加载器比较。
- **流体与能量单位**：Fabric 为 `droplet`（81000 = 1 桶）与 `E`，NeoForge 为 `mb`（1000 = 1 桶）与 `FE`，
  以 `server.status.fluidUnit` / `energyUnit` 为准。
- **数据组件**：`--nbt` 只表示 `minecraft:custom_data`；附魔、药水等内容用 `--components`。
- **快进**：支持原版 `/tick sprint`、`/tick rate` 与 `wait until --sprint`。
- **托管会话**：Fabric 走 `--args`，NeoForge 走 init 脚本，都可直接 `session start`；
  托管启动默认隐藏提示弹窗（Mixin 作用于 `ToastComponent.addToast`），`gui toasts on|off` 可运行时切换。
- **截图读回**：使用 OpenGL `glReadPixels`（驱动不支持裁剪读回时回退整图下载再裁剪），
  `graphics` 字段报告供应商、渲染器与 GL 版本；`--draw-log` 的 JSON 结构与 26.3 相同。

## 命令速查

```bash
mcdebug --session dev status
mcdebug raw --show-chat server.runCommand '{"command":"/time set day"}'
mcdebug wait until 'be[100,64,100].progress >= 100' --timeout-ticks 72000 --sprint
mcdebug raw wait.until '{"predicate":"storage[0,64,0].fluid.tanks.0.amount >= 81000","timeoutTicks":400}'
mcdebug gui hover --slot 0 --hold-shift
mcdebug reflect get --root server --path 'recipeManager.byName.keySet()' --limit 10000
```

## 开发者

源码与构建在仓库的 `versions/mc1.21.1/`：`JAVA_HOME=<JDK 21> ./gradlew build`（Fabric，同时执行 `:neoforge:build`）；
冒烟为 `versions/mc1.21.1/scripts/fabric-smoke.sh` 与 `neoforge-smoke.sh`。
