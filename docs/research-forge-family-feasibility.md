# mcdebug 在 Forge 系 1.21.1 上运行的可行性研究

调研日期：**2026-10-30**。所有版本和日期结论均在当日对照一手来源核实，
包括 GitHub API、发布与源码、Modrinth API、Sinytra Probe 兼容性服务、Gradle Plugin Portal
和 `files.minecraftforge.net`。标为**[未验证]**的内容未得到确认。

方法说明：除指向文档页的链接外，调研直接查询在线 API，包含 GitHub REST、Modrinth v2 和
`https://probe.sinytra.org/api/v1/probe`，并引用响应。mcdebug 自身的
`build/libs/mcdebug-0.7.0.jar` 于 2026-10-30 使用 Loom 1.15 构建，已解包检查。
下文所引外部说明均译为中文，技术名称和原始数据保持原样。

---

## A）Sinytra Connector

### A1. 版本、维护状态和支持模块

**是否支持 1.21.1，2026 年是否仍在维护？** 两者均是，但 1.21.1 已是旧版本维护线，
不再是主要版本线；项目网站仍有相反说法，对应页面已经过时。

| 项目 | 数据 | 来源 |
|---|---|---|
| 仓库 | `Sinytra/Connector`，未归档 | [Sinytra/Connector](https://github.com/Sinytra/Connector) |
| 仓库最后推送 | **2026-10-30T10:11:45Z**，与本次调研同日 | GitHub REST `repos/Sinytra/Connector` |
| 默认分支 | **`26.2.x`**，1.21.1 位于 `1.21.x` | 同上及 `branches` |
| 分支 | `1.20.1`、`1.21.x`、`26.1.x`、`26.2.x`、`dev`、`master` | `repos/Sinytra/Connector/branches` |
| 最新 1.21.1 发布 | **`2.0.0-beta.17+1.21.1`**，发布于 **2026-08-15** | [发布页面](https://github.com/Sinytra/Connector/releases/tag/2.0.0-beta.17%2B1.21.1) |
| `1.21.x` 最后提交 | **2026-09-26**，`5a2f668a`，添加覆盖设置与 mixin 配置过滤器 | `commits?sha=1.21.x` |
| 全项目最新发布 | `3.1.0-beta.4+26.2` 和 `3.0.0-beta.7+26.1.2`，均为 2026-10-30 | `gh release list` |
| 最新 1.20.1 长期支持版 | `1.0.0-beta.49+1.20.1`，2026-07-04 | `gh release list` |
| Modrinth 下载量 | 27,710,970 | [Connector 项目页](https://modrinth.com/mod/connector) |

⚠️ **官方资料存在矛盾。** [官方 FAQ](https://connector.sinytra.org/faq) 声称：
“✅ 1.21.1 是主要支持版本，将获得新修复和兼容性改进；⚠️ 1.20.1 是长期支持版本。”
但页脚为“© 2025 Sinytra”，GitHub 记录与其矛盾：默认分支是 `26.2.x`，
`1.21.x` 自 2026-09-26 后无提交，而 `26.2.x` 每天都有提交。
应将 FAQ 视为过时说明：**1.21.1 处于维护状态，偶尔获得修复，并非积极开展兼容性工作的主线。**
Connector 未被放弃，但 1.21.1 兼容性改进已不是 FAQ 所称的优先事项。

**Forgified Fabric API（FFAPI）：Fabric API 的替代品。**

| 项目 | 数据 | 来源 |
|---|---|---|
| 最新 1.21.1 发布 | **`0.116.15+2.3.6+1.21.1`**，发布于 **2026-10-15** | [发布页面](https://github.com/Sinytra/ForgifiedFabricAPI/releases/tag/0.116.15%2B2.3.6%2B1.21.1) |
| 仓库最后推送 | 2026-10-23 | `repos/Sinytra/ForgifiedFabricAPI` |
| 1.21.1 分支 | `1.21.1`，存在 | `branches` |
| 主 FML 模组 ID | **`fabric_api`**，因此满足 Fabric 模组的 `"fabric-api": "*"` 声明 | [Connector 开发文档](https://moddedmc.wiki/en/project/connector/latest/docs/developers) |

FFAPI 版本号包含**所跟踪的上游 Fabric API 版本**：`0.116.15` 对应 Fabric API `0.116.x`，
与 mcdebug 在 `gradle.properties` 中的 `fabric_api_version=0.116.17+1.21.1` 属于同一小版本线。

**支持模块通过实测确认。** 解包两个 JAR 并比较其嵌套模块后得到：

- 上游 `fabric-api-0.116.17+1.21.1.jar`，来自 [Modrinth](https://modrinth.com/mod/fabric-api)，含 **49** 个模块。
- `forgified-fabric-api-0.116.15+2.3.6+1.21.1.jar` 含 **44** 个模块。

**上游存在、FFAPI 1.21.1 未包含的模块，共 6 个：**

| 模块 | 上游状态 |
|---|---|
| `fabric-crash-report-info-v1` | 当前有效且未弃用，属于**实际缺口** |
| `fabric-dimensions-v1` | 当前有效且未弃用，属于**实际缺口** |
| `fabric-command-api-v1` | 上游位于 `deprecated/`，不构成当前 API 缺口 |
| `fabric-commands-v0` | 上游位于 `deprecated/`，不构成当前 API 缺口 |
| `fabric-keybindings-v0` | 上游位于 `deprecated/`，不构成当前 API 缺口 |
| `fabric-rendering-v0` | 上游位于 `deprecated/`，不构成当前 API 缺口 |

**FFAPI 存在、上游聚合 JAR 未包含的模块，共 1 个：** `fabric-gametest-api-v1-2.0.5`。
上游未将 GameTest 纳入发布聚合包。上游没有列出全部支持或不支持模块的文档页，
模块列表只能从 JAR 或仓库树推导，本次调研采用了该方法。
**不确定性：** FFAPI 声称通过检查保证 `net.fabricmc.*.api` 包的完整 Fabric API 兼容性，
但明确不保证内部实现 API，见 [FFAPI README](https://github.com/Sinytra/ForgifiedFabricAPI/blob/1.21.1/README.md)。
因此其承诺是 API 层面一致，行为一致仅按尽力原则实现。

**对 mcdebug 的影响：未使用这 6 个缺失模块。** mcdebug 使用 `fabric-api-base`、
`fabric-lifecycle-events-v1`、`fabric-api-lookup-api-v1`、`fabric-transfer-api-v1`，四者均存在。

### A2. Mixin、Kotlin、加载器 API 与 Transfer API

**（a）注入客户端渲染类的 mixin。** Connector 支持无 refmap 的 mixin，即 Loom 1.5+
直接重映射 mixin 类的模式。“Connector 无法处理无 refmap mixin”的跟踪问题
[#795](https://github.com/Sinytra/Connector/issues/795) 已于 **2024-04-06 完成并关闭**；
Sinytra Adapter 中“支持无 refmap 方法补丁和 ModifyConstant 注入器”的
[Adapter #12](https://github.com/Sinytra/Adapter/issues/12) 于 2026-07-15 关闭。
客户端渲染 Fabric 模组会被定期测试，其中有通过案例。
官方 Probe 服务对 **1.21.1**、Connector `2.0.0-beta.17+1.21.1` 和 NeoForge `21.1.264` 报告：

| 模组 | 结果 |
|---|---|
| Indium，仅 Fabric 的 Sodium 兼容渲染器 | ✅ 通过，`1.0.35+mc1.21`，测试于 2026-10-22 |
| Krypton，仅 Fabric 的网络优化模组 | ✅ 通过，`0.2.8`，测试于 2026-10-22 |
| Fabric Language Kotlin | ✅ 通过，`1.14.2+kotlin.2.4.21`，测试于 2026-10-22 |
| Mod Menu | ❌ 失败，`11.0.5`，测试于 2026-10-22 |
| Carpet | ❌ 失败，`1.4.147`，测试于 2026-10-24 |

来源为 `POST https://probe.sinytra.org/api/v1/probe`，请求为
`{"platform":"modrinth","slug":…,"game_version":"1.21.1"}`，
人工可读列表见[兼容性页面](https://connector.sinytra.org/compatibility)。
Probe 文档自身也提醒：它在**游戏外**对模组 JAR 执行 Connector 代码转换并验证结果；
即使 JAR 补丁成功，仍可能有只有进入游戏才触发的问题，见同一[兼容性页面](https://connector.sinytra.org/compatibility)。

**mcdebug 使用的具体形式恰好属于持续出问题的形式。**
mcdebug 客户端平面含 10 个 mixin 类，均无 refmap，且使用**不带描述符的裸 intermediary 方法名**。
直接检查 `build/libs/mcdebug-0.7.0.jar` 已确认：

```
RuntimeVisibleAnnotations:
  org.spongepowered.asm.mixin.injection.Inject(
    method=["method_25295"]
    at=[@org.spongepowered.asm.mixin.injection.At(value="HEAD")])
```

常量池也完全使用 intermediary：`Lnet/minecraft/class_332;`（GuiGraphics）、
`net/minecraft/class_4587`（PoseStack）、`class_2960`（ResourceLocation）、`method_51448` 等。

以下未关闭或刚关闭的问题直接涉及此形式：

- **[FFAPI #260：与 Fabric Loom 1.14+ 构建的 Fabric 模组不兼容](https://github.com/Sinytra/ForgifiedFabricAPI/issues/260)**
  仍未关闭，创建于 2026-02-26，最后更新于 2026-08-03，标签为 `bug, 1.21.x`，评论确认 1.21.1 可复现。
  讨论中唯一已知补救是第三方补丁模组 Mishang Urban Construction Sinytra Connector Fix，尚无上游修复。
- **[FFAPI #266：使用 Loom 1.15 或更高版本构建的模组启动崩溃](https://github.com/Sinytra/ForgifiedFabricAPI/issues/266)**
  于 **2026-06-21 完成并关闭**。维护者 Su5eD 对比发现，1.15 JAR 的 mixin 缺少方法描述符，
  推测关联 FabricMC/tiny-remapper#164；Loom 1.16 仍有问题，1.17 再次包含描述符。
  报告者指出两个失败 mixin 均为 **`@WrapOperation`**，与 mcdebug 使用的注解类别相同。
  AGENTS.md 第 14 节要求一律使用 `@WrapOperation` / `@Inject`。
- **[Connector #2134：tick 方法映射错误](https://github.com/Sinytra/Connector/issues/2134)**
  未关闭，创建于 2026-03-02。模组作者诊断：将裸 intermediary 方法名转为 Mojang 名称时，
  重载目标会产生歧义。例如 GameRenderer 有两个 `pick`，`method_56153` 应转换为具体方法签名，而非只转为 `pick`。
  mcdebug 在重载很多的 GuiGraphics blit 系列上使用 `@Inject(method="method_25295")`，正属此类歧义。
- **[Connector #2295：内嵌 FART 1.0.14 存在竞态，随机产生未映射名称与 AbstractMethodError](https://github.com/Sinytra/Connector/issues/2295)**
  未关闭，创建于 2026-08-12，实测 8 个样本中 1 个输出损坏，表现为实现 MC 接口的 lambda 抛出 `AbstractMethodError`。
  **该问题由 mcdebug 自身账号 `yu1745` 针对 Connector 下 IC2 Refabricate 0.5 加 `fabric-language-kotlin` 提交**，
  附有上游修复建议。这是最直接的信号：同一消费者组合已经产生非确定性的转换失败。

⇒ **结论：原则上支持，但 mcdebug 的具体 mixin 形式属于已知风险形式，三个直接相关问题中仍有两个未关闭。**
试验前先将 Loom 升到 ≥ 1.17，依据 #266，并为所有 mixin `method=` 值补充方法描述符，
消除 #2134 一类歧义。这两项改动成本低，也能独立改善代码健壮性。

**（b）`fabric-language-kotlin`。** 已支持并持续测试。Probe 在 2026-10-22 报告
FLK `1.14.2+kotlin.2.4.21` 在 Connector `2.0.0-beta.17+1.21.1` 和 NeoForge `21.1.264` 上**通过**，
来源为 `probe.sinytra.org/api/v1/probe`。FLK 自身仍在维护，最新版本
`1.14.2+kotlin.2.4.21` 发布于 2026-10-09，见[发布记录](https://github.com/FabricMC/fabric-language-kotlin/releases)。
mcdebug 的 `fabric.mod.json` 中两个入口均使用 `{"adapter": "kotlin", …}`，因此 NeoForge 侧也必须安装 FLK。

⚠️ **剩余风险：与 Kotlin for Forge 共存。** NeoForge 整合包中常见的 Kotlin for Forge
曾在加入 FLK 后导致启动崩溃，见
[Connector #1654](https://github.com/Sinytra/Connector/issues/1654)，1.21.1、关闭于 2025-02-25，
和 [#1671](https://github.com/Sinytra/Connector/issues/1671)，关闭于 2026-02-23。
两者均已关闭，讨论中的解决方法是将 FLK 固定到 1.13.0。
**Probe 单独测试 FLK，不测试它与 Kotlin for Forge 共存。**
当前 FLK 1.14.x 与当前 Kotlin for Forge 能否正常共存仍为**[未验证]**。

**（c）直接使用 Fabric Loader API。** Connector 提供 Fabric Loader 分支
[Sinytra/ForgifiedFabricLoader](https://github.com/Sinytra/ForgifiedFabricLoader)，
在 NeoForged Fancy Mod Loader 之上实现 Fabric Loader API，内嵌于 FFAPI JAR。
已确认 FFAPI `0.116.15+2.3.6+1.21.1` 包含
`META-INF/jars/forgified-fabric-loader-2.5.68+0.18.4+1.21.1-full.jar`，
Connector 自身 `META-INF/MANIFEST.MF` 声明 `Fabric-Loader-Version: 0.19.3`。

该分支 `1.21.x` 的仓库树包含：

- ✅ `net/fabricmc/loader/api/FabricLoader.java`、`api/MappingResolver.java`
- ✅ `net/fabricmc/loader/impl/FabricLoaderImpl.java`、`impl/MappingResolverImpl.java`
- ✅ `net/fabricmc/loader/impl/launch/FabricLauncherBase.java` 和 `FabricLauncherFML.java`

因此 **mcdebug 接触的类均存在**。mcdebug 声明 `"fabricloader": ">=0.14.21"`，下限较低，
不会因加载器版本而被拒绝。其实际调用面较小，`FabricLoader` 使用四处，`FabricLauncherBase` 使用一处：

| 文件 | 用途 |
|---|---|
| `src/main/kotlin/com/mcdebug/util/Mappings.kt` | `FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace` |
| `src/main/kotlin/com/mcdebug/McDebugMod.kt` | 通过 `FabricLoader` 获取游戏目录和配置 |
| `src/client/.../ClientInfo.kt`、`McDebugClientMod.kt` | `FabricLoader` |
| `src/client/.../Hotswap.kt:182` | `FabricLauncherBase.getLauncher()` |

⚠️ **薄弱点是 `FabricLauncherBase`。** 这是内部实现类，在 Connector 下为 FabricLauncherFML 而非 Knot，
所以 `getLauncher().getClassByteArray(name, propagateError)` 使用 FML 语义。
mcdebug 已设置保护：`Hotswap.transformedBytes` 只在返回字节 `contentEquals(fileBytes)`，
即开发类路径场景，才信任启动器的 mixin 前处理流水线，否则回退到原始文件字节；
`MixinEnvironment…activeTransformer` 转型也有空值保护。
预期 `gui.hotswap` 在 Connector 下会降级或报告失败，而非使客户端崩溃，
但这是推断，**[未验证]**，必须实测。

**（d）Fabric Transfer API 并不缺失，这是本次调研最有用的发现。**
FFAPI 1.21.1 提供完整移植，解包已确认：

```
META-INF/jars/fabric-transfer-api-v1-5.4.4+6244b11119.jar
```

`1.21.1` 分支的模块源码包含 mcdebug 使用的全部 API 类：
[storage/Storage.java](https://github.com/Sinytra/ForgifiedFabricAPI/tree/1.21.1/fabric-transfer-api-v1/src/main/java/net/fabricmc/fabric/api/transfer/v1/storage)
中的 `Storage`、`SlottedStorage`、`StorageUtil`、`StorageView`、`TransferVariant`、`StoragePreconditions`，
[item/ItemStorage.java](https://github.com/Sinytra/ForgifiedFabricAPI/blob/1.21.1/fabric-transfer-api-v1/src/main/java/net/fabricmc/fabric/api/transfer/v1/item/ItemStorage.java)、
[fluid/FluidStorage.java](https://github.com/Sinytra/ForgifiedFabricAPI/blob/1.21.1/fabric-transfer-api-v1/src/main/java/net/fabricmc/fabric/api/transfer/v1/fluid/FluidStorage.java)、
`transaction/Transaction.java`、`transaction/TransactionContext.java`、
`transaction/base/SnapshotParticipant.java`、`context/ContainerItemContext.java`、
`base/SingleVariantStorage.java`、`base/CombinedStorage.java`，以及客户端
`client/fluid/FluidVariantRendering.java`。
还包含 NeoForge 桥接包 `org.sinytra.fabric.transfer_api.compat`，其中有 `NeoItemStorage`、
`NeoFluidStorage`、`ItemStorageItemHandler`、`FluidStorageFluidHandler`、`NeoCompatUtil`，
通过 [TransferApiNeoCompat.java](https://github.com/Sinytra/ForgifiedFabricAPI/blob/1.21.1/fabric-transfer-api-v1/src/main/java/org/sinytra/fabric/transfer_api/TransferApiNeoCompat.java)
将 `ItemStorage.SIDED` / `FluidStorage.SIDED` 与 NeoForge 的
`Capabilities.ItemHandler.BLOCK` / `Capabilities.FluidHandler.BLOCK` 双向连接，
并通过 `COMPUTING_CAPABILITY_LOCK` ThreadLocal 打断两个查询系统间的递归。

FFAPI README 明确表示：尽可能将 `FabricItem`、`ItemStorage`、`FluidStorage` 等 Fabric API
桥接到对应 NeoForge API。

**与上游的差异已通过仓库树比较确认，值得了解但不阻塞 mcdebug。**
FFAPI 传输模块缺少五个上游原版集成 mixin：`BucketItemAccessor`、`ChiseledBookshelfBlockEntityMixin`、
`CrafterBlockMixin`、`DropperBlockMixin`、`HopperBlockEntityMixin`。
这些原版路径由 NeoForge 自身能力补丁处理，可能是有意设计，
符合 FFAPI 尽量减少 Minecraft 代码修改的目标。

**已知未关闭的 Transfer API 问题涉及第三方互操作，不表示 API 缺失或无法使用：**
[FFAPI #215](https://github.com/Sinytra/ForgifiedFabricAPI/issues/215)，1.21.1，
Iron Furnaces / Functional Storage 向 Oritech 机器自动输出时崩溃，自 2025-04-08 起未关闭；
[FFAPI #276](https://github.com/Sinytra/ForgifiedFabricAPI/issues/276)，1.21.1，
FFAPI 导致 Every Compat 动态箱子的物品传输损坏，自 2026-06-29 起未关闭。
后一问题中，FFAPI 的能力注册会静默覆盖另一模组的能力提供者。

### A3. Connector 能否用于原生 Forge 1.21.1？不能

原生 Forge 1.21.1 确实存在，Forge `52.1.20` 同时为该版本的 latest 和 recommended，
来源为 `https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json`。
但**没有面向它的 Connector 构建**：

- Connector `1.21.x` 分支的 `META-INF/neoforge.mods.toml` 声明：

  ```toml
  [[dependencies.connector]]
      modId="neoforge"
      type="required"
      versionRange="[21.1.97,)"
  [[dependencies.connector]]
      modId="minecraft"
      type="required"
      versionRange="[1.21,1.22)"
  ```

  这是强制 NeoForge 依赖，已下载发布版 `-full` JAR 验证。
- Modrinth 的 Connector 项目加载器标签是 `["forge","neoforge"]`，但**每个 1.21.1 版本都只标注 neoforge**；
  forge 标签只出现在 1.20.1 的 `1.0.0-beta.x` 线，来源为 `fabric-version` API，
  `GET /v2/project/connector/version`。最后的 Forge 版本是 `1.0.0-beta.49+1.20.1`，2026-07-04。
- 仓库描述和 README 都说明它是允许 Fabric 模组在 **NeoForge** 上运行的兼容层。

**⇒ 原生 Forge 1.21.1 用户完全不能选择 Connector。**

### A4. 实际限制与失败形式

**安装范围。** 1.21.1 只需要两个额外 JAR：**Connector + FFAPI**。
Connector `1.21.x` README 要求先安装 NeoForge、Connector，再下载作为 Fabric API 直接替代品的
Forgified Fabric API，二者**不能共存**。它会尽量避免加载被整合包管理器自动安装的 Fabric API，
但能不安装时应避免安装。
⚠️ [26.1.x 文档站](https://moddedmc.wiki/en/project/connector/latest/docs/introduction)
与 1.21.x README 不同，多列出第三个依赖 [Launchpad](https://github.com/Sinytra/Launchpad)。
但 Launchpad **没有 1.21.1 构建**：Modrinth `game_versions` 只有 `26.1, 26.1.1, 26.1.2, 26.2`，
仓库分支为 `26.1.x`、`26.2.x`、`master`。
因此 1.21.1 只需要 Connector + FFAPI，不需要 Launchpad。
Launchpad 建议仅适用于 Connector 不再自行加载 Fabric 模组的 26.x 版本线。

Connector 只分发**一个**附件 `connector-2.0.0-beta.17+1.21.1-full.jar`，
4.57 MB，包含 `mappings/mappings.tiny` 和 `mappings.tsrg`；Modrinth 也使用同一个文件。
1.21.1 没有运行时下载映射的精简 JAR 变体。

**Fabric mixin 与 Forge 对同一原版类的补丁是否会冲突？会，这是典型失败形式。**
[FAQ](https://connector.sinytra.org/faq) 说明，功能差异来自 Fabric 模组、Mixin、
NeoForge 补丁代码和其他模组之间的特定交互。未关闭问题中有以下具体案例：

- [#2151](https://github.com/Sinytra/Connector/issues/2151)：1.21.1 的 `ItemStack` mixin 注入失败。
- [#2169](https://github.com/Sinytra/Connector/issues/2169)：Forge 将 BlockColors 改为使用自己的注册表，
  使依赖它的 Fabric 模组损坏。
- [#2166] / [#2313](https://github.com/Sinytra/Connector/issues/2313)：Fabric 模组的 `onInitializeClient()`
  在 `Minecraft.<init>` 中执行时，可能与 NeoForge 自身 `EntityRenderersEvent.RegisterLayerDefinitions`
  合并竞态，导致无关原生模组出现 `No model for layer` 崩溃。问题未关闭，评论也在 Forge 1.20.1 复现相同特征。
  此问题明确**依赖时序**，两次启动缺失图层列表逐字节相同，却在中间启动中正常完成。
  客户端入口执行大量工作的模组可能遇到此类模式。
- [#2235] / [#2295](https://github.com/Sinytra/Connector/issues/2295)：FART 非确定性转换，见 A2（a）。
- [#2206](https://github.com/Sinytra/Connector/issues/2206)：Fabric 注册的生物群系未出现在 NeoForge 的 `DynamicRegistryManager`。
- [#2300](https://github.com/Sinytra/Connector/issues/2300)：基于 Jankson 的 Fabric 模组和 owo-lib 的问题。

**有文档记录、用户可见的失败形式：Mixin 安全保护。**
`config/connector.json` 默认启用 `"enableMixinSafeguard": true`。
其[配置文档](https://github.com/Sinytra/Connector/blob/1.21.x/docs/configuration.mdx)
说明，安装模组含无法修复的失败 mixin 时会停止加载并显示警告界面，避免直接崩溃；
警告只在转换完成后显示一次，默认启用。
因此 Connector 无法修补的 mixin 集合会在启动时**明确阻止加载**，而非悄悄损坏。
若 mcdebug 无 refmap 的 `@Inject` / `@WrapOperation` 目标从 intermediary 转为 Mojang 失败，
客户端平面最可能出现的就是这种结果。

其他已记录限制：不兼容 OptiFine；不支持 Mohist、Magma、Arclight 等自定义服务端 API，
Pojav、FoldCraft 等移动启动器，以及盗版启动器，见 FAQ。

**是否支持专用服务端？明确支持。** Connector 源码含
[ServerMainMixin](https://github.com/Sinytra/Connector/blob/1.21.x/src/mod/java/org/sinytra/connector/mod/mixin/boot/ServerMainMixin.java)，
在 `net.minecraft.server.Main.main` 调用 `ServerModLoader.load()` 的位置注入 `ConnectorLoader.load()`，
说明专用服务端是正式代码路径，而非客户端路径的偶然产物。
`neoforge.mods.toml` 设置 `displayTest = 'IGNORE_ALL_VERSION'`，注释说明双方均可选装 Connector。
因此无 Connector 的客户端可加入 Connector 服务端，反之亦然；纯服务端 Fabric 模组只需服务端安装 Connector。
**FAQ 只对混合服务端 API 给出否定意见，表明标准 NeoForge 专用服务端可用。**

对 mcdebug 而言，**服务端平面**（`world.*` … `store.*`）只需要在专用服务端安装
NeoForge + Connector + FFAPI，是试验中风险最低的一半。
**客户端平面**（`gui.*`）需在客户端安装同一组合，上述客户端问题也都发生在那里。
**建议先验证服务端平面，将客户端平面作为单独、风险更高的里程碑。**

### A5. mcdebug 的 Unix 套接字、任意反射和命名空间能否工作

**Unix 域套接字。** Connector 转换不涉及 `java.nio.channels` / `ServerSocketChannel` 的
AF_UNIX 使用方式，也不涉及 `File` / `Path` 处理。
NeoForge 下仍是相同的 JVM、进程和文件系统，变化只在模组加载器和 MC 类名。
**预期无需修改即可工作。** mcdebug 配置解析使用 gameDir，NeoForge 下也是相同的工作目录概念，
因此 `<gameDir>/mcdebug/socket` 应位于相同位置。实测仍为**[未验证]**，但未发现导致失败的机制。

**按名称进行任意反射：结果较为有利。**

Connector 如何处理命名空间，已从源码而非说明文字确认：

1. Connector 内嵌三个命名空间的 Tiny v1 映射，解包后的文件头为：

   ```
   v1   official   intermediary   mojang
   CLASS  eza  net/minecraft/class_3673  com/mojang/blaze3d/Blaze3D
   ```

   `mappings/mappings.tiny` 及并行的 `mappings.tsrg` 均位于
   `connector-2.0.0-beta.17+1.21.1-full.jar`。
2. 加载时把每个 Fabric 模组**从 intermediary 转到 NeoForge 运行时命名空间**，见
   [JarTransformer.SOURCE_NAMESPACE = "intermediary"](https://github.com/Sinytra/Connector/blob/1.21.x/transformer/src/main/java/org/sinytra/connector/transformer/jar/JarTransformer.java)。
   [IntermediateMapping](https://github.com/Sinytra/Connector/blob/1.21.x/transformer/src/main/java/org/sinytra/connector/transformer/jar/IntermediateMapping.java)
   有意只处理 `net/minecraft/class_`、`field_`、`method_`、`comp_` 这类 intermediary 名称，
   因为其他名称无需重映射，只会给简化查找替换式重映射器带来问题。
3. **运行时命名空间是 mojang，而非 intermediary。**
   [ForgifiedFabricLoader 的 LoaderUtil](https://github.com/Sinytra/ForgifiedFabricLoader/blob/1.21.x/src/main/java/net/fabricmc/loader/impl/util/LoaderUtil.java)
   声明 `public static final String RUNTIME_MAPPING = "mojang";`，
   [MappingConfiguration.getTargetNamespace()](https://github.com/Sinytra/ForgifiedFabricLoader/blob/1.21.x/src/main/java/net/fabricmc/loader/impl/launch/MappingConfiguration.java)
   返回该值。因此原先假设反了：Connector 下 Fabric 模组加载时并非看到 intermediary 名称，
   其字节码被改写为 Mojang 名称，游戏自身也使用 Mojang 名称。

   ⚠️ **[未验证]**：虽然已检查常量和配置类，但尚未在真实 Connector 1.21.1 实例中确认
   `FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace` 实际返回 `"mojang"`。
   这是可在约五分钟内完成的实测，也是整个 mcdebug Connector 路线的关键前提。

4. Connector **会改写反射**，但只支持非常狭窄、可静态分析的形式。
   [ReflectionRenamingTransformer](https://github.com/Sinytra/Connector/blob/1.21.x/transformer/src/main/java/org/sinytra/connector/transformer/patch/ReflectionRenamingTransformer.java)
   使用 ASM `SourceInterpreter`，仅在方法调用的前三个参数，即所有者、名称、描述符，
   均为**单个 LDC 字符串常量**时触发，例如字面量形式
   `Class.getDeclaredMethod("net/minecraft/class_310", "method_1551", "()V")`，随后原位改写 `cst` 值。
   运行时计算、拼接或从内嵌资源读取的名称均不会被处理。

**对 mcdebug 的三个结论：**

1. **mcdebug 已识别命名空间转换方向，在 Connector 下会选择正确方向。** `Mappings.kt` 使用：

   ```kotlin
   private val intermediaryRuntime: Boolean by lazy {
       runCatching { FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace == "intermediary" }
           .getOrDefault(true)
   }
   ```

   Connector 下得到 false，mcdebug 按 **Mojang 运行时**解析名称；这符合 Connector 已将其自身字节码
   重映射为 Mojang、实际 MC 类也为 Mojang 的情况。
   外部传入 intermediary 名称仍可用，因为 mcdebug 会使用自带映射表转换为 Mojang。
   **现有方向逻辑对 Connector 是正确的。**
2. **mcdebug 运行时计算的反射字符串不会被 Connector 改写，但它们也无需改写。**
   `ReflectCore.kt:119-121` 为 `val runtime = Mappings.runtimeClassName(name); Class.forName(runtime)`。
   所有者字符串是计算所得局部变量，不是调用点的 LDC 常量，`ReflectionRenamingTransformer` 无法也不会改动它。
   mcdebug 运行时读取 `/mappings/reflect.txt.gz`，构建时由 Loom 的 1.21.1 intermediary↔Mojang 映射生成，
   与 Connector 内嵌、同一 MC 版本的 `mappings.tiny`（`official` / `intermediary` / `mojang`）兼容。
   硬编码 intermediary 字符串的模组依赖 Connector 改写，mcdebug 不依赖。
   **这是本报告最支持 Connector 路线的发现。**
3. **剩余反射风险是名称歧义，mcdebug 已有部分防护。** Connector #2134 表明重载成员可能转换歧义。
   mcdebug 自带表已考虑这种情况：`Mappings.kt` 说明，共享一个 Mojang 名的多个 intermediary 成员
   会生成多个候选，由调用者按签名选择；`ReflectCore` 负责重载评分。
   mcdebug 在运行时按描述符自行消歧，比依赖 Connector 静态改写的模组受 #2134 影响更小。
   剩余风险位于 **mixin 注解**而非 `reflect.*`：`method=["method_25295"]` 这类无描述符的静态 LDC 常量
   正是 Connector 必须改写、也最易受歧义影响的路径，见 A2（a）。补充描述符即可消除该歧义。

---

## B）Kilt（KiltMC）

**方向是关键。** Kilt 与 Connector 的方向相反。
[KiltMC/Kilt](https://github.com/KiltMC/Kilt) 的描述为将 **(Neo)Forge 模组带入 Fabric 生态**的 Fabric 模组；
[Modrinth 项目页](https://modrinth.com/mod/kilt) 同样说明它允许在 Fabric 加载 (Neo)Forge 模组，且非常实验性。
README FAQ 明确区分：Connector 让 Fabric 模组运行于 Forge，Kilt 让 Forge 模组运行于 Fabric。

**对 mcdebug 的影响：** Kilt 不能让 NeoForge / Forge 用户运行 mcdebug，
只能让 Fabric 用户运行自己的 Forge 模组。
只有用户把服务端从 NeoForge 迁到 Fabric + Kilt 时才有帮助，改变的是用户环境，而非 mcdebug。

**是否仍维护？是，活跃程度与过时 README 所呈现的不同。**

| 项目 | 数据 | 来源 |
|---|---|---|
| 最新发布 | **`v21.1.18`，2026-10-28**，调研前两天 | [发布记录](https://github.com/KiltMC/Kilt/releases) |
| 默认分支 | `version/1.21.1` | `repos/KiltMC/Kilt` |
| 最后提交 | **2026-10-28T23:15:03Z** | `commits` |
| 仓库 pushed_at | 2026-10-30T12:16:47Z | `repos/KiltMC/Kilt` |
| 分支 | `version/1.20.1`、`version/1.21.1`、`version/26.1.x` 及多个 PR 分支 | `branches` |
| 1.21.1 发布节奏 | 18 次发布，21.1.11→18 均在 2026 年 8–10 月 | 发布记录 |
| 下载量 / 关注者 | 272,377 / 773 | Modrinth API |
| Modrinth `game_versions` | **仅** `1.20.1`、`1.21.1` | Modrinth API |
| 加载器 | 仅 `fabric` | Modrinth API |

**版本支持。** Kilt `v21.1.x` 对应 Minecraft **1.21.1** 与 NeoForge **21.1.x**。
其 v21.1.18 发布说明包含更新到 NeoForge 21.1.265，因此与 Connector 面向同一 NeoForge 1.21.1 线。
这也解释了 mcdebug 已归档的 1.20.1 线为何使用 Kilt 容器测试环境，见 AGENTS.md 第 12 节，
以及当时为何存在 `server.mantleInteract`、`server.forgeFluidCapability` 两个诊断 RPC。

⚠️ Kilt README **过时且与发布历史矛盾**，仍称 NeoForge 1.21.1 构建正在开发、尚需时间，
实际已发布 18 次。应以发布记录判断，不应只看 README。

**对 1.21.1 是否现实？** 技术上确有现成版本，但项目自己明确警告：Kilt 很不稳定，
可能损坏世界或崩溃，因此在 Modrinth 以 **Alpha**、CurseForge 以 **Beta** 发布；
除非问题在 Forge 也存在，否则不要向原模组开发者报告，来源为 `version/1.21.1` README。
调试与自动化模组的价值是对机器状态做可信断言，依赖主动提示世界损坏风险的 Alpha 转换层并不划算，
且风险转移到了用户整合包，而非 mcdebug。该项目有两位开发者、270 个未关闭问题。

⚠️ **不确定性：** Kilt 的 WHY.md 或[文档站](https://kilt.bluspring.xyz/) 可能有比 README 更新的支持矩阵，
本次未阅读。具体 NeoForge 模组的可用性也未像 Connector Probe 那样系统测试；
**Kilt 没有同等公开兼容矩阵**，评估特定整合包风险只能依赖零散案例。

**一个相关技术点：** Kilt 先将 Forge 模组从 SRG 重映射到 Fabric intermediary，再应用自己的修复器。
为此维护 NeoForge、FancyModLoader、ForgeAutoRenamingTool 和 `srg2intermediary` 的分支，
见 [KiltMC 组织仓库](https://github.com/orgs/KiltMC/repositories)。
v21.1.18 说明含实现反射修复器、修复 Sable: Ragdoll 及可能影响大量反射模组的修复。
这说明 Kilt 与 Connector 一样必须特别处理反射，只是转换方向相反；二者都不能消除反射适配成本。

---

## C）Architectury 与多加载器构建

### 1.21.1 的当前状态

| 组件 | 1.21.1 状态 | 来源 |
|---|---|---|
| **architectury-loom** | 最新发布 **1.17**，2026-07-08；仓库最后推送 2026-09-12，默认分支 `dev/1.17` | [发布记录](https://github.com/architectury/architectury-loom/releases) |
| **architectury-api** | **`13.0.11`**，2026-07-23，**仅发布 fabric 和 neoforge** | Modrinth API `GET /v2/project/architectury-api/version` |
| 1.21.1 对应分支 | **没有 `1.21.1` 分支**，只有 `1.21`，最后提交为 2026-07-23，合并 st0x0ef/1.21 的 PR #733 | `branches`、`commits?sha=1.21` |
| 缓存的 413 个版本 | 全版本加载器计数为 `fabric` 215、`quilt` 130、`forge` 120、`neoforge` 78，但 `game_versions` 含 1.21.1 的六个版本中 **forge 为零** | Modrinth API |

**⚠️ 原生 Forge 1.21.1 不是 Architectury 目标。** Architectury API 没有该版本的 Forge 构建，
只有 `13.0.6`、`13.0.8`、`13.0.11` 各自的 Fabric 和 NeoForge 两份构建。
[Architectury API 文档](https://docs.architectury.dev/api) 已不再以 Forge 为目标，
而是说明通过抽象 **Fabric 与 NeoForge** 的差异，在共享代码中编写一次逻辑并为两个加载器分发。
[Loom 文档](https://docs.architectury.dev/loom/introduction) 同样只有 NeoForge 使用和迁移说明，
没有现行 Forge 指南，只保留旧 MCP、1.16.5 或更低版本的说明。

**因此 Architectury 1.21.1 = Fabric + NeoForge，与 Connector 目标一致。
若 Forge 系包含原生 Forge 1.21.1，Architectury 也不能解决。** 这是本节影响最大的发现。

Architectury Loom 的[官方说明](https://docs.architectury.dev/loom/introduction)还指出：
仅支持 IntelliJ IDEA 与 Visual Studio Code，其他 IDE 未测试且不支持；
Loom 仍为实验项目，映射可选 Yarn、Mojang 官方映射或 MCP，要求 Gradle 8.1+。

### 共享代码与加载器分层中的 Kotlin 支持

**Architectury 没有专门、完整的 Kotlin 接入方案。**
2026-10-30 使用 GitHub 代码搜索 API，在 `architectury/architectury-loom` 搜索 kotlin 无结果，
在 `architectury/architectury-api path:docs` 搜索也无结果，Loom 文档没有 Kotlin 章节。
其 [README](https://github.com/architectury/architectury-loom) 说明该项目源自 Juuxel 的 Loom 分支，
后者又源自 Fabric Loom，因此 Kotlin 支持来自继承的 Fabric Loom，
并须自行接入加载器专属运行时：Fabric 使用 `fabric-language-kotlin`，
另一端使用 `kotlin-for-forge` 或 NeoForge 对应 Kotlin 运行时。
文档通过 <https://generate.architectury.dev/> 指向的 `architectury-templates`
仓库最后推送为 **2023-11-12**，已过时。

**mcdebug 的额外成本。** 其加载器专属调用面较小：

- **生命周期钩子**：Fabric API 的 `ServerLifecycleEvents` / `ServerTickEvents`
  对应 NeoForge `FMLLoader` 或事件总线，工作量小且偏机械。
- **传输与存储 API**：`ItemStorage.SIDED` / `FluidStorage.SIDED` / `Storage` / `Transaction`
  对应 NeoForge `Capabilities.ItemHandler.BLOCK` / `FluidHandler.BLOCK` 加 `IItemHandler` / `IFluidHandler`，
  或自行编写抽象层，工作量中等。FFAPI 的 `NeoItemStorage`、`ItemStorageItemHandler`、
  `SlottedItemStorageItemHandler` 等桥接代码可直接作为映射参考。
- **加载器 API**：`FabricLoader` 的游戏目录、模组元数据、`MappingResolver`
  对应 NeoForge `FMLPaths` / `ModList`，工作量小。但 NeoForge **没有 MappingResolver 对应物**，
  因为运行时本就使用 Mojang 名称，映射层可简化为恒等映射加用户名称表。
- **`Mappings.kt` / `reflect.*`**：NeoForge 不需要 intermediary 部分，Mojang 到 Mojang 的路径很简单，工作量小。
- **真正成本在 10 个客户端 mixin 类**：目前均针对 intermediary、无 refmap 且使用裸方法名，
  在 NeoForge 上必须改为 Mojang 名称，并对 NeoForge 修补后的原版类重新验证。
  两个加载器都会修改 mcdebug 观测的 `GuiGraphics`、`GameRenderer`、`Screen`、`RenderSystem`、`Minecraft`。
  Mixin 目标没有类似 Transfer API 的桥接层，兼容垫片不能代替验证。
  AGENTS.md 第 14 节还记录 Loom 1.15 将单值 `at` 写成数组，破坏消费者项目的 MixinExtras；
  `defaultRequire: 1` 会将注入未命中变为启动失败，必须在真实客户端验证。
  多加载器构建使加载器、mixin 重映射、补丁集的组合数增加。
- **成本也有可控之处**：客户端 mixin 在概念上与加载器无关，目标是两边都会修补的原版类。
  若仅有两三个 mixin，移植很简单；现在有 10 个且都使用 `defaultRequire: 1`，验证成本占主导。

### 2026 年实际使用的其他工具

- **Stonecutter**：KikuGie 开发的现代 Gradle 多版本管理插件。
  [Gradle Plugin Portal](https://plugins.gradle.org/plugin/dev.kikugie.stonecutter) 列出
  **`dev.kikugie.stonecutter` `0.9.8`，发布于 2026-08-31**。
  源码为 <https://github.com/kikugie/stonecutter>，文档为 <https://stonecutter.kikugie.dev/>。
  ⚠️ 元数据有矛盾：GitHub 仓库 `pushed_at = 2025-12-25T16:00:27Z`，默认分支 `display`，
  可访问的 `0.9` 最新提交为 2025-12-20，与 2026-08-31 的插件发布时间不符。
  尚无法解释，可能涉及镜像或改名，`stonecutter-versioning/stonecutter` 具有逐字节相同元数据。
  可用性以插件门户的版本与日期为准，仓库推送日期不可靠。
  Stonecutter 主要管理**多版本**，也用于**多加载器**，下文 MCPFabric 就这样使用。
- **Modstitch**：统一 Fabric 和 (Neo)Forge 官方工具链的 Gradle 插件，
  仓库 <https://github.com/modstitch/modstitch>，最后推送 2026-04-01，34 星；
  文档 <https://isxander.github.io/modstitch-docs/>，2026-10-30 渲染后仅有入门和项目介绍页面，内容较少。
  仍是 Fabric + (Neo)Forge，不包含原生 Forge。
- **Stonecutter 多加载器模板**：<https://github.com/rotgruengelb/stonecutter-mod-template>，
  多加载器、多版本 Minecraft 模组模板，2026-10-20 推送，51 星，声明支持 Fabric、NeoForge 和 Forge。
  真正需要 Forge 目标时，这是最直接的起点。
- **`kikugie/modkit`**：Minecraft 模组构建插件集合，2026-09-15 推送，0 星，尚不成熟，不宜依赖。
- **手动 `common` + `fabric` + `neoforge` Gradle 多工程**：最接近的已有项目实际采用此方式，见 D 节。
- ❌ **`EdwinMindcraft/forgified-fabric-loom`**：声称也适用于 Forge 的 Gradle 构建系统，
  最后推送为 **2021-03-28**，0 星，已停止维护，不应使用。

### ⚠️ NeoForge 原生兼容 Fabric 的说法：[未找到 / 未验证]

本次**未找到** NeoForge 官方声明、文档或发布说明称其可原生运行 Fabric 模组。
在 `docs.neoforged.net` 与网络搜索中，只找到第三方加载器比较和无关 GitHub 问题。
找到的资料均表明 Fabric 模组不等于 NeoForge 模组；让 Fabric 模组运行于 NeoForge，
通常依赖**第三方** Sinytra Connector、将 Fabric API 移植到 NeoForge 的 FFAPI，
以及 26.x 的 Launchpad。若有具体 NeoForge 声明，需另行核对；本次未找到一手来源，不作此断言。

---

## D）已有的跨加载器调试与自动化 RPC 模组

已有可信、较新的 1.21.1 项目，至少两个提供基于套接字或 HTTP JSON-RPC 自动化模组的
Fabric 和 NeoForge 构建。它们均不能直接替换 mcdebug，
但 `mcpfabric` 的功能重叠较多，是最接近的替代方案。

### D1. `mcpfabric`：最接近的功能对照 ⭐

| 项目 | 数据 |
|---|---|
| 仓库 | <https://github.com/denfry/mcpfabric>，通过 MCP 让 AI 代理控制和观察运行中的 Minecraft Java 版，支持 Fabric、NeoForge 和 1.21.1+ |
| 许可证 | MIT |
| 创建 / 最后推送 | 2026-10-05 / 2026-10-26 |
| 星数 | 14 |
| Modrinth | <https://modrinth.com/mod/mcpfabric>，5,503 次下载，更新于 2026-10-16 |
| **1.21.1 构建** | **两种均有**：`mcpfabric-0.8.0+1.21.1.jar`（fabric）和 `mcpfabric-neoforge-0.8.0+1.21.1.jar`（neoforge），均为 2026-10-16 |
| Modrinth 加载器 / 版本 | `["fabric","neoforge"]`；`1.21.1`–`1.21.11`、`26.1`–`26.3` |
| **构建策略** | 使用 [Stonecutter](https://stonecutter.kikugie.dev/) 让同一源码树面向多个 Minecraft 版本，每个版本分发独立 JAR，直接回答 C 节的工具链问题 |

其 README 描述的架构为 Minecraft 内嵌**本地 HTTP 桥接**，外加 TypeScript MCP 服务端。
使用 `POST /rpc`（JSON-RPC）、`GET /events`（SSE），**只绑定 127.0.0.1，并要求 bearer token**。
所有游戏访问通过主线程执行器 `server.execute` / `Minecraft.execute`。
工具组分为公共的 `info`、`world`、`entities`、`players`、`command`、`chat`、`events`，
和客户端的 `player`、`control`、`interact`、`inventory`、`vision`、`navigation`、`session`、`ui`。
**客户端和专用服务端均可使用。**

与 mcdebug 重叠的功能包括 `get_block` / `set_block` / `fill_blocks`、`query_entities`、
`run_command`、**PNG `screenshot`**、`get_screen` / `close_screen`、
获取当前控件的 **`get_ui`**、**`click_widget` / `set_text_field`**，后两者需默认关闭的
`enableUiControl` 开关；还有 `get_inventory`、`drop_slot` / `swap_slots`，
以及 **`open_container` / `container_transfer` / `close_container`**。

**不能直接替换 mcdebug 的原因：**

- 传输是**回环 HTTP**，而非 Unix 套接字加 TCP；身份认证为 bearer token，
  mcdebug 则有意使用不鉴权加防火墙的部署模型，见 AGENTS.md 第 1 节。
- 写操作通过**命令分派器**，如 setblock、summon、give、tp、效果、天气、时间，并捕获输出，
  不直接读写方块实体 NBT。没有 `be.getNbt` / `be.setNbt` / `be.tick`，也没有
  `snapshot.diff`、`trace.*`、谓词 DSL `wait.until`、`reflect.*`、`storage.*` / Transfer API、
  `gui.hotswap` / `reloadScreen` 或基准图比较。
- GUI 控制只到 `click_widget`、`set_text_field` 控件级，**没有按槽位点击、悬停提示捕获、
  绘制日志、标注截图或渲染几何信息**，缺少 mcdebug 的整套代理迭代流程。
- **信任与稳健性限制**：项目只有 14 星，调研时仅创建三周。
  README 将无隐藏遥测的声明限定为当前 **Fabric** 发布不发送使用分析，NeoForge 是否相同并未明确。
  **[未验证]**，本次未检查两个 JAR。其自身也提示 MCP Fabric 可赋予 AI 操作者管理员级控制。

### D2. `arclightpowered/minecraft-mcp`：架构最接近的参考 ⭐

作者为 IzzelAliz。

| 项目 | 数据 |
|---|---|
| 仓库 | <https://github.com/arclightpowered/minecraft-mcp>，向 AI 代理暴露本地 MCP 风格 JSON-RPC 端点的 Minecraft 客户端模组 |
| 许可证 | **MIT**，LICENSE 标明 2026 IzzelAliz 版权所有，`gradle.properties` 为 `mod_license=MIT`；GitHub 分类虽为 Other，文件本身是 MIT |
| 创建 / 最后推送 | 2026-05-13 / 2026-09-22，最后提交为 2026-06-01 |
| 星数 / 发布 | 5 星，**没有 GitHub 发布**，仅源码 |
| 加载器 | 支持 Fabric 1.21.1、NeoForge 1.21.1 |
| 传输 | 默认在 `/mcp` 提供本地 HTTP JSON-RPC，绑定 `127.0.0.1` 且要求 bearer token，发现文件为 `mcp/server.json` |
| **构建策略** | 手动多工程，根 `build.gradle` / `settings.gradle` 配合 `common/`、`fabric/`、`neoforge/`；NeoForge 子工程使用 NeoGradle 的 `net.neoforged.gradle.userdev`；属性为 `minecraft_version=1.21.1`、`fabric_loader_version=0.16.14`、`fabric_api_version=0.116.1+1.21.1`、`neoforge_version=21.1.222` |

功能范围与 mcdebug 非常接近：服务端与客户端桥接、数据包记录与过滤、**结构蓝图**工具、
**带断言的场景引擎**、库存与容器工具、玩家动作、截图、命令补全同步及条件或谓词属性。
文档目录含 `docs/protocol.md`、`docs/scenarios.md`、`docs/scenario-conditions.md`、
`docs/inventory-container-tools.md`、`docs/player-action-tools.md`、`docs/packet-tools.md`、
`docs/schematics.md`、`docs/headless.md`、`docs/agent-usage.md`。

这是 mcdebug 所考虑的多加载器重构中**最合适的现成参考实现**：MC 版本、加载器、
外部工具驱动真实游戏的概念均相同，使用 MIT 许可证，作者为知名 Fabric 模组开发者 IzzelAliz。
限制是只有 5 星、没有发布、2026-06-01 后无提交，
且 README 将其描述为**仅客户端**，入口为 `minecraft-mcp` / `MinecraftClientBridge`。

### D3. 其他 MCP 风格模组：能力较弱或目标不符

| 项目 | 加载器 | 支持 1.21.1 | 说明 |
|---|---|---|---|
| [mcp-server-mod](https://modrinth.com/mod/mcp-server-mod)，cuspymd | 仅 fabric | **否**，1.21.4+ | 1,585 次下载 |
| [fabric-api-mcp-server](https://modrinth.com/mod/fabric-api-mcp-server)，chapmanjw | 仅 fabric | **否**，1.21.11+ | 1,485 次下载，将服务端 Minecraft API 与 Fabric API 暴露为 MCP 工具，概念上最接近 mcdebug 服务端平面，但仅 Fabric 且版本过新 |
| [yuniko-software/minecraft-mcp-server](https://github.com/yuniko-software/minecraft-mcp-server) | 无，使用 Mineflayer | 不适用 | 800 星，流行的 Minecraft MCP；属于外部协议级机器人，可连接 Forge / NeoForge 等任意服务端，但不能访问模组内部的方块实体 NBT、服务端状态或客户端 GUI |

### D4. Carpet、GameTest 与其他 Forge 系自动化方案

- **Carpet**：仅 Fabric，Modrinth 加载器为 `["fabric"]`，且**不能通过 Connector 工作**。
  Probe 报告 `carpet` `1.4.147` 在 1.21.1 失败，测试于 2026-10-24；
  `carpet` `26.2` 在 26.2 试验中也为 ❌ 不兼容，见[兼容性页面](https://connector.sinytra.org/compatibility)。
  Carpet / TIS 类工具没有可用的 NeoForge 原生或 Connector 路线。
  Carpet 最接近 Fabric 侧的服务端可脚本化自动化工具，因此这是 mcdebug Connector 路线的重要负面案例。
- **NeoForge GameTest**：真实的一方进程内测试框架，但不是远程 RPC 桥接。
  见 [GameTest 文档](https://docs.neoforged.net/docs/misc/gametest)，用于运行游戏内单元测试，
  以及 [NeoForge 测试框架说明](https://github.com/neoforged/neoforge/blob/26.2.x/docs/TESTFRAMEWORK.md)。
  Fabric 对应[自动化测试说明](https://docs.fabricmc.net/develop/automatic-testing)，FFAPI 也带 `fabric-gametest-api-v1`。
  这些均在 JVM 内，无套接字、外部客户端、截图或 GUI 驱动。
- **CC:Tweaked**：Probe 标为 1.21.1 **原生 NeoForge**，`type: native`、加载器 neoforge，
  WorldEdit、JEI、EMI、REI、ModernFix、Embeddium 也一样。
  游戏内 Lua 计算机提供 HTTP 客户端和库存外设，可作为 NeoForge 远程自动化基础，
  但不能任意读写方块实体 NBT、截图或操作客户端 GUI。
  它也出现在跨加载器问题 [Connector #1231](https://github.com/Sinytra/Connector/issues/1231) 中，
  涉及 Fabric 的 Plethora Peripherals 与 Forge 的 CC:Tweaked。
- **Axiom**：跨加载器世界编辑器，有自己的服务端协议。
  [Connector #2190](https://github.com/Sinytra/Connector/issues/2190) 中 Axiom 5.4.1 在 NeoForge 1.21.1 启动崩溃的问题已关闭，
  Probe 报告 Axiom `6.1.3` 在 26.2 兼容。
  这是建造与编辑工具，不是测试自动化 RPC，不能替代 mcdebug。

### D5. 是否有项目能直接替代原生移植

**没有。** 但 `mcpfabric` 足够接近，值得单独评估，`arclightpowered/minecraft-mcp` 值得作为实现参考。
差距不是外部工具能否在 NeoForge 1.21.1 读写方块，mcpfabric 已能从同一源码树做到，
而是 mcdebug 的特定契约：Unix 套接字优先、`be.getNbt` / `be.setNbt` / `be.tick`、
`snapshot.diff`、`trace.*`、`wait.until` 谓词 DSL、`reflect.*`、Transfer API `storage.*`，
以及槽位点击、真实绘制提示框、标注截图、基准图比较、热替换和 Screen 重载组成的像素级 GUI 流程。
没有现成项目复现整套能力，各项目 README 也更偏向 AI 代理玩具，而非可由 JUnit 驱动的测试工具。
若目标是让 Forge 系用户用 mcdebug 调试机器，这些项目均不能替代 mcdebug。

---

## 成本最低的可信路线：结论与排序

### 必须先区分两个目标

**Forge 系 1.21.1 实际是两个不同目标，只有其中一个能低成本触达。**

| 目标 | 能否避免原生移植 | 证据 |
|---|---|---|
| **NeoForge 1.21.1** | **可以尝试** Connector + FFAPI，Architectury 13.x 和 mcpfabric 的 NeoForge JAR 也面向此目标 | Connector 的 `neoforge.mods.toml` 要求 `neoforge [21.1.97,)`；Architectury API 为 `13.0.11+neoforge` |
| **原生 Forge 1.21.1**，当前推荐 `52.1.20` | **不能**；Connector 仅 NeoForge，Architectury 没有 Forge 1.21.1 构建，Kilt 在 Fabric 侧 | Modrinth 上全部 1.21.1 Connector 版本均为 neoforge；Architectury 1.21.1 只有 fabric 和 neoforge |

1.21.1 生态已集中到 **NeoForge**。若用户确实使用原生 Forge 1.21.1，
可行选项只有原生 Forge 移植，或迁移到 NeoForge。

### 路线排序

**第 1：Connector，面向 NeoForge 1.21.1。尝试成本最低、风险中等、代码改动最少。**
这是唯一真正避免原生移植的选项，mcdebug 的现有设计也较适合：

- ✅ **反射设计已适配 Connector 的转换方向。** ForgifiedFabricLoader 的 `RUNTIME_MAPPING = "mojang"`，
  因而 `Mappings.kt` 的 `currentRuntimeNamespace == "intermediary"` 返回 false，
  mcdebug 按 Mojang 运行时解析，符合改写后的字节码和实际类名称。
  硬编码 intermediary 字符串的模组风险更大。
  前提是实测 `currentRuntimeNamespace`，见下文关键检查。
- ✅ **`ReflectionRenamingTransformer` 仅改写 LDC 字符串常量**，不动运行时计算的名称。
  mcdebug 使用自身 intermediary↔Mojang 表转换，且与 Connector 内嵌
  `mappings.tiny` 的 `official` / `intermediary` / `mojang` 命名空间兼容，无需它再改写。
- ✅ **`fabric-transfer-api-v1` 没有缺口**：FFAPI 1.21.1 带 `fabric-transfer-api-v1-5.4.4`，
  包含 `Storage`、`ItemStorage.SIDED`、`FluidStorage.SIDED`、`Transaction`、`SnapshotParticipant`、
  `StorageUtil` 及双向 NeoForge 能力桥接。最大的未知项已得到有利确认。
- ✅ **`fabric-language-kotlin` 已支持并经官方测试通过**，Connector 版本为
  `2.0.0-beta.17+1.21.1`，测试于 2026-10-22。
- ✅ **FFAPI 省略的 6 个模块均未被 mcdebug 使用。**
- ✅ **专用服务端是正式路径**，由 `ServerMainMixin` 实现，`displayTest = IGNORE_ALL_VERSION`
  使双方均可选装 Connector，因此服务端平面可只装在服务端。
- ✅ **仅需额外安装两个 JAR**：Connector + FFAPI，1.21.1 不需要 Launchpad。
  FFAPI 的 `fabric_api` 模组 ID 满足 mcdebug 的 `"fabric-api": "*"` 依赖。
- ✅ **失败会明确提示**：默认启用的 Mixin 安全保护 `enableMixinSafeguard`
  会在无法修补 mixin 时停止加载并显示警告界面。

**按优先级排列的风险与缓解措施：**

1. 🔴 **Loom 1.15 mixin 重映射问题，可主动修复。**
   mcdebug 的 `gradle.properties` 使用 `loom_version=1.15-SNAPSHOT`，JAR 含 intermediary 名称且无 refmap。
   [FFAPI #266](https://github.com/Sinytra/ForgifiedFabricAPI/issues/266) 记录 tiny-remapper
   丢失方法描述符造成的崩溃，涉及 `@WrapOperation`，mcdebug 则只用 `@WrapOperation` / `@Inject`。
   维护者确认 Loom 1.16 仍有问题，1.17 恢复描述符。
   **措施：将 Loom 升到 ≥ 1.17**，成本低、风险低，也能独立改善构建质量。
2. 🔴 **[FFAPI #260](https://github.com/Sinytra/ForgifiedFabricAPI/issues/260) 仍未关闭**，
   涉及 Loom 1.14+ 构建的 Fabric 模组，标签为 `1.21.x`。
   即使升级 Loom，也不能确认此类问题已消失。应预留排查成本，
   在真实 NeoForge + Connector 客户端测试**打包后的 JAR**，不能只测 `runClient`。
3. 🟠 **Mixin 注解的裸 intermediary 方法名有歧义**，见未关闭的
   [Connector #2134](https://github.com/Sinytra/Connector/issues/2134)。
   mcdebug 在大量重载的 GuiGraphics 上使用无描述符 `@Inject(method=["method_25295"])`，
   `injectors.defaultRequire = 1` 会把选错目标变成启动失败。
   **措施：为每个 mixin `method=` 添加显式描述符**，可低成本彻底消除此类歧义。
4. 🟠 **非确定性转换输出**，见未关闭的
   [Connector #2295](https://github.com/Sinytra/Connector/issues/2295)。
   FART 竞态实测 8 个样本中 1 个损坏，已在同一 IC2 Refabricate + FLK 消费者组合中出现，
   由 mcdebug 账号报告，表现为随机 `AbstractMethodError`。
   mcdebug 侧无法规避，只能视为已知不稳定来源；问题中附有修复 PR。
5. 🟠 **风险集中于客户端平面。** 找到的未关闭问题均在客户端，包含
   `onInitializeClient` 与模型图层时序 #2313、BlockColors 改写 #2169、ItemStack mixin 失败 #2151。
   最接近 Fabric 脚本化服务端工具的 Carpet 在 Probe 的 1.21.1 测试中也失败。
   **措施：分两步验证与交付。** 先验证 NeoForge 专用服务端 + Connector + FFAPI + mcdebug，
   服务端平面低风险且自包含；再把 `gui.*` 作为真实客户端中的单独里程碑。
6. 🟡 **`gui.hotswap` 可能降级**：它通过内部 `FabricLauncherBase.getLauncher()` 期望 Knot 语义，
   Connector 下实际为 FabricLauncherFML。代码已有原始文件字节回退和 mixin 转换器空值保护，
   预期只会报告热替换失败而非崩溃，但仍为**[未验证]**。
7. 🟡 **Kotlin 运行时共存**：Kotlin for Forge 与 FLK 曾导致崩溃，见已关闭的
   [#1654](https://github.com/Sinytra/Connector/issues/1654)、
   [#1671](https://github.com/Sinytra/Connector/issues/1671)。
   Probe 只测 FLK 单独运行，FLK 1.14.x 与当前 Kotlin for Forge 共存仍为**[未验证]**。

**得到确定结论的工作量：小（S）。** 升级 Loom → 添加 mixin 描述符 → 构建 →
在 NeoForge 1.21.1 专用服务端装 Connector + FFAPI + mcdebug，验证
`server.status` / `world.*` / `storage.*` / `reflect.*`；随后在客户端验证 `gui.status` / `gui.screenshot`。
**预计一两天即可将可能性转为明确可用或不可用的结论**，而非只给估算。
第一次启动就检查 `currentRuntimeNamespace`，因为它是其他步骤的前提。

**第 2：原生多加载器构建，采用 Architectury 或 Stonecutter / Modstitch 加 NeoForge 源集。**
这本身就是原生移植，但风险可控，也是最终支持原生 Forge 1.21.1 的唯一可能方向。

- Architectury Loom **1.17**，2026-07-08，以及 Architectury API **13.0.11**，2026-07-23，
  在 1.21.1 仍活跃，但仅面向 Fabric + NeoForge，没有 Forge 1.21.1，官方文档也不再列 Forge。
  Kotlin 没有专门支持，Loom 和文档搜索均无结果；模板生成仓库自 2023-11-12 起未更新。
- 主要成本是 **10 个客户端 mixin 类**的改名和重新验证，加载器接缝本身只涉及少量生命周期钩子、
  有 FFAPI `org.sinytra.fabric.transfer_api.compat` 可参考的存储映射，以及简化的 `Mappings.kt`。
  NeoForge 已使用 Mojang 名称，不需要 MappingResolver 对应物。
  Mixin 则须对 NeoForge 修补后的 `GuiGraphics` / `GameRenderer` / `Screen` / `RenderSystem` / `Minecraft`
  逐个验证，`defaultRequire: 1` 使每次未命中都导致启动失败，且无兼容垫片可代替。
- 现实工作量为 **中到大（M–L，数周）**，主要在验证而非编码。
- 应在 Connector 试验后再做，不应取代该试验；先用较低成本确认 mixin 集合能否适应另一套类补丁，
  才能合理评估重构成本。
- 若确需 **Forge** 目标，仍活跃的选项是 Stonecutter：插件门户的
  `dev.kikugie.stonecutter` `0.9.8`，2026-08-31。
  mcpfabric 正是这样从同一源码树分发 Fabric 和 NeoForge 1.21.1 JAR。
  可从 2026-10-20 推送、支持 Fabric + NeoForge + Forge 的 `rotgruengelb/stonecutter-mod-template` 起步。
  Modstitch，由 isxander 维护、最后推送 2026-04-01，是统一 (Neo)Forge 工具链的另一选择，但文档较少。

**第 3：Kilt，方向不符、风险收益最差。**
它仍维护，`v21.1.18` 发布于 2026-10-28，对应 NeoForge 21.1.265，也能下载 1.21.1 构建。
但它解决的是 Forge 模组在 Fabric 上运行，不能把 mcdebug 放到 NeoForge 服务端。
使用它意味着要求用户把服务端从 NeoForge **迁到 Fabric + Kilt**。
README 自称极不稳定、可能损坏世界或崩溃，Modrinth 以 Alpha 发布；
没有类似 Probe 的公开兼容性矩阵，特定整合包风险只能依赖零散案例。
对以可信断言为核心的模组，这是四种方案中风险收益最差的一种。

⚠️ **Kilt 能回答另一个问题：Fabric 原生 mcdebug 如何测试 Forge 系模组的机器？**
这正是已归档 1.20.1 线使用 Kilt 容器测试环境的原因，见 AGENTS.md 开头和第 12 节。
若真实需求是自己的 CI 或测试驱动 Forge 模组机器，Kilt 仍是合理工具，
只是它不是向 Forge 系用户分发 mcdebug 的渠道。

**第 4：原生 Forge 1.21.1 移植，成本最高且缺少生态配合。**
Forge 52.1.20 虽存在，兼容生态均不面向它：没有 Connector 构建、Architectury API 构建或对应文档。
投入意味着为单一模组版本线使用已被多加载器工具链放下的加载器，
除第 2 条的 10 类 mixin 问题外，还要适应第二套差异更大的补丁集。**不推荐。**

### 建议执行顺序

1. **先做低成本加固，按小时计，对各路线都有帮助**：Loom `1.15-SNAPSHOT` 升到 ≥ `1.17`，
   为 `src/client/resources/mcdebug.client.mixins.json` 所列类的每个 mixin `method=` 添加显式描述符。
   两项改动独立于路线选择，分别由 FFAPI #266 和 Connector #2134 支持。
2. **服务端平面 Connector 验证试验，按小时计**：NeoForge 1.21.1 专用服务端 +
   Connector `2.0.0-beta.17+1.21.1` + FFAPI `0.116.15+2.3.6+1.21.1` + FLK + mcdebug。
   首次启动记录 `FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace`，
   再通过 Unix 套接字与 TCP 测试 `server.status`、`world.*`、Transfer API `storage.*`、
   使用 Mojang 和 intermediary 两种类名的 `reflect.*`。
3. **客户端平面验证试验，一两天**：在真实客户端使用同一组合，测试 `gui.status`、
   `gui.screenshot`、`gui.click --slot`、`gui.screen`，尤其是 `gui.hotswap`。
   注意 Mixin 安全保护警告，它是否出现就是关键结果。
4. **作出决定**：两次试验均通过后，记录 NeoForge 1.21.1 支持 Connector 配置并固定精确版本，
   再加入冒烟脚本。若客户端因 mixin 失败，错误会具体且明确，
   可判断是重命名或补描述符即可修复，还是结构性问题，从而合理估算第 2 条路线。
5. **原生 Forge 1.21.1**：如实说明，迁到 NeoForge，否则没有现成路径。

### 明确尚未验证的事项

- Connector 1.21.1 运行时是否满足
  `FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace == "mojang"`：
  **只确认了源码常量与配置类，没有运行时确认**，这是整个 Connector 结论的前提。
- `gui.hotswap` 在 FabricLauncherFML 下是正常降级还是抛错。
- FLK 1.14.x 与当前 Kotlin for Forge 在同一 NeoForge 整合包中共存。
- 当前 FFAPI 1.21.1 是否能真正加载由 Loom 1.15 / 1.17 构建、无 refmap 且大量使用
  `@WrapOperation` 的模组，即 #266 的 tiny-remapper 修复后是否仍受 #260 影响。
- Kilt 过时 README 之外的当前文档支持矩阵，未找到公开兼容性数据。
- Stonecutter GitHub 的 `pushed_at`，2025-12-25，与插件 0.9.8 发布时间 2026-08-31 的矛盾。
- NeoForge 官方原生兼容 Fabric 的说法，**本次未找到**，不应断言。
- mcpfabric NeoForge 构建内容、许可证处理，以及遥测声明的适用范围。
