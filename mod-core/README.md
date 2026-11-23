# mod-core

三个 Minecraft 版本**源码级共享**的纯 Kotlin 文件：零 `net.minecraft.*` / 加载器传递依赖，不发 JAR。
根构建把它作为不发布的 `:mod-core` 子项目**独立编译**一次（只有 Kotlin 标准库与 Gson 2.10.1，`apiVersion = 1.9`），
作为门禁：引用了 Minecraft、加载器或某个版本目录里的类就编不过。各版本构建按相对路径把它编进自己的模组 JAR（`srcDir` 或 `Sync`），所以发布 JAR 仍是
自包含的。共享范围的逐文件比对与原因见 [docs/monorepo.md](../docs/monorepo.md)。

| 文件 | 共享范围 |
|---|---|
| `rpc/JsonRpc.kt`、`rpc/RpcErrors.kt`、`rpc/RpcEnv.kt` | 1.20.1、1.21.1、26.3 |
| `wait/PredicateExpr.kt` | 1.21.1、26.3 |
| `client/input/GuiInputState.kt`、`client/render/RenderSchedule.kt`、`client/render/CaptureGeometry.kt` | 1.21.1、26.3 |

## 约定

- **不放任何 MC / 加载器引用**：import 只允许 Kotlin 标准库、Gson 与本目录内的类。`RpcEnv` 由入口点
  赋值 `gameDir`，传输层只读它，这正是它能离开 MC 的原因。
- **Kotlin `apiVersion = '1.9'` 可编译**：1.20.1 的运行时由 KotlinForForge 4.x 提供 1.9 标准库。
- **不要在这里加版本条件分支**：某个文件在目标版本有实质差异时，它就不属于共享集，留在各版本目录
  （1.20.1 的 `PredicateExpr`、`GuiInputState`、`RenderSchedule` 即如此）。
- 单测留在各版本的 `src/test`（`PredicateExprTest`、`GuiInputStateTest`、`RenderScheduleTest`、
  `CaptureGeometryTest` 等），它们通过编译后的类验证共享代码。
- 引入方式：Fabric 版本 `sourceSets.main.kotlin.srcDir('../../mod-core/src/main/kotlin')`，
  NeoForge 子构建 `syncShared` 里 `from('../../../mod-core/src/main/kotlin')`，
  1.20.1 只 `Sync` 上表第一行的三个文件。
