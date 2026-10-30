# mcdebug 的 NeoForge 21.1.x（MC 1.21.1）移植可行性

| 项 | 值 |
|---|---|
| 调研对象 | 当前 `1.21.1` 线（mod_version 0.7.0，Fabric loader 0.19.5 + Fabric API 0.116.17，Mojang 官方映射，Kotlin 2.3.10，Gradle 9.4.1） |
| 目标 | **NeoForge 21.1.x（MC 1.21.1），服务端平面 + 客户端平面一次做完** |
| 目标能力 | 与 Fabric 版**完全一致**：同一个 CLI 驱动、同一套 RPC 方法名/DTO；agent 闭环（观察世界/BE/库存/流体 → 操作方块与 GUI → 截图从像素角度确认渲染） |
| 调研时间 | 2026-10-30 |
| 结论 | **可行。且比 0.6.3 删掉的那次 1.20.1 Forge 移植便宜得多**：约 **68% 的 mod 代码可以原样复用**，核心风险集中在客户端 mixin 注入点与 `storage.*`/`fluid.*` 的能力层重写 |
| 状态 | **已实现并真机验证**（2026-10-30）：服务端平面契约 79/79、存储/流体 ability 断言、客户端平面 mixin 全部生效 + 真实截图/点击闭环、CLI 内嵌双 loader jar。实施结果与实测差异见 §11 |
| 建议工期 | 原生移植 **17–27 人日**（单人 3.5–5.5 周）；若先用 Connector 探针拿结论，额外 **1–2 人日**（实际按本方案推进，见 §11 的落地清单） |
| 一手证据 | 已在本机真机跑通 NeoForge 21.1.265 的**两个** spike：服务端 **PASS**、客户端 **PASS**（含 MixinExtras `@WrapOperation` 与 `glReadPixels`），见 §3；外部生态事实见附录 A 与 `docs/research-forge-family-feasibility.md` |

---

## 0. 结论摘要

1. **目标选择是对的。** 在 MC 1.21.1 上，“Forge 系”事实主线是 NeoForge：21.1.x 有 **260 个版本、最新 21.1.265（2026-10-25）**，Modrinth 上 1.21.1 的 NeoForge 项目 **24,085** 个 vs 经典 Forge **5,536** 个；而且**两边生产环境都是 Mojang 官方名**（经典 Forge 52.0.0 起 “Runtime Official mappings”），所以 `reflect.*` 不需要再造映射表。
2. **本仓库已有的技术债恰好都被这次调研清掉了**：0.6.3 删除的 1.20.1 `forge/` 原生移植（nested ForgeGradle 构建 + 共享源码 + KFF + 契约 parity 冒烟）是一份**可直接复用的形态样本**（`git show forge-port:forge/build.gradle`）。
3. **耦合面比想象中小得多**：服务端 31 个文件里只有 **10 个**碰到 loader API，客户端 35 个文件里只有 **4 个**；10 个客户端 mixin 全部注入 vanilla 类，与 loader 无关（其中 2 个用 MixinExtras `@WrapOperation`，NeoForge 内置，源码不用改）。
4. **真正的工程量在三处**：① `storage.*`/`fluid.*`/能量 的 Fabric Transfer API → NeoForge Capabilities 适配（约 600–900 行新代码）；② 客户端平面的接线与真机验证（`hotswap` 取字节、`ClientLifecycleEvents`、限帧/帧屏障）；③ 验证基础设施（`neoforge-smoke.sh` + 消费者项目生产 jar 真机闭环）。
5. **已实测确认的硬事实**（详见 §3）：NeoForge 21.1.265 运行时 **0 个 SRG 名**；**MixinExtras 0.5.5 由 loader 内置**（`@WrapOperation` 免发货）；**Kotlin stdlib 必须显式进游戏运行时**（`implementation` 不够，dev 用 MDG 的 `additionalRuntimeClasspath`，生产用 KotlinForForge 或内嵌 LIBRARY）；客户端侧 `@Mod(dist=CLIENT)`、`ClientTickEvent.Post`、`RenderFrameEvent.Post`、`Minecraft.runTick` 注入、`@WrapOperation`（照抄 `GameRendererMixin` 注入点）、`glReadPixels` 读回**全部实测通过**。
6. **已按本方案落地**（原生路线，用户已明确不走 Connector）：`neoforge/` 嵌套构建 + 8 个接缝 + 共享源码 Sync，两个平面都在真机跑通；实施结果、实测差异与踩到的坑见 §11。**另有更便宜的一条路**（作为历史备选保留）：Sinytra Connector + Forgified Fabric API 让**现有 Fabric jar 直接跑在 NeoForge 21.1 上**（1–2 人日可判定）。但它有一条**针对我们 jar 形状的、上游仍未关闭的**风险（我们的 mixin 是裸 `method_XXXXX` 名、无描述符、无 refmap；FFAPI #260 仍 open，Connector #2134 open），因此定位应是**过渡/兜底与风险探针**，不是长期方案。

---

## 1. 目标与验收标准

移植完成后必须满足（与 Fabric 版逐条对齐）：

| # | 验收项 | 判定方式 |
|---|---|---|
| V1 | `contract/Methods.kt` 里每个方法名在 NeoForge 服务端/客户端都已注册 | `scripts/neoforge-smoke.sh` + `scripts/contract-parity.py`（沿用现有工具） |
| V2 | 同一个 `mcdebug-cli.jar` 同时驱动 Fabric 与 NeoForge 两种实例 | CLI 不改参数即可跑通现有用例；`server.status.modLoader` 报 `neoforge` |
| V3 | 服务端平面语义等价（world/be/inv/storage/fluid/craft/snapshot/trace/redstone/entity/fixture/reflect/wait） | 现有 `src/test`（1,874 行，绝大多数与 loader 无关）在 NeoForge 构建里同样通过 |
| V4 | 客户端平面闭环：截图（含 crop/annotate/drawLog/pixelHash）、点击（slot/widget/text/png）、hover/scroll/key/type/drag、settle、screen 树、problems、compare | 消费者项目真机客户端走一遍 AGENTS.md §14 的流程 |
| V5 | agent 迭代回路：`gui.hotswap`/`gui.reloadScreen`/`gui.reload-resources` | 真机验证；降级行为要写进文档 |
| V6 | Fabric 侧零回归 | 现有 `./gradlew build` + `scripts/fabric-smoke.sh` 全绿 |
| V7 | 发布面：`mcdebug-neoforge-<version>.jar` 自包含、可单独丢进 `mods/` | `verifyJarSelfContained` 扩展到第二个 jar；纯 NeoForge 安装真机启动 |

---

## 2. 现状盘点：mcdebug 到底有多少 loader 耦合

统计口径：`src/main`（服务端平面）8,622 行 / 31 文件；`src/client`（客户端平面）5,302 行 / 35 文件。

### 2.1 服务端平面

**零 Fabric API 依赖（可原样复用）——21 文件 / 4,889 行：**

`rpc/RpcServer.kt`(599)、`wait/PredicateExpr.kt`(557)、`api/ReflectCore.kt`(539)、`util/NbtJson.kt`(293)、`api/WorldOps.kt`(273)、`api/ReflectOps.kt`(262)、`api/EntityOps.kt`(260)、`api/ScreenOps.kt`(258)、`api/SnapshotOps.kt`(255)、`api/CraftingOps.kt`(237)、`api/BlockEntityOps.kt`(187)、`util/ServerContext.kt`(183)、`api/InventoryOps.kt`(181)、`api/Params.kt`(146)、`rpc/RpcHandler.kt`(140)、`api/ServerOps.kt`(133)、`api/ScanOps.kt`(124)、`api/FixtureOps.kt`(106)、`rpc/JsonRpc.kt`(69)、`rpc/RpcErrors.kt`(58)、`rpc/RpcEnv.kt`(29)

> 关键点：这些文件用的是 **vanilla MC API + JDK + Gson**。因为两边都编译在 Mojang 官方名下，**不需要任何改名**即可两边同时编译——这是与 1.20.1 那次（yarn → Mojang 全库改名）最本质的区别。

**触碰 Fabric API 的 10 文件 / 3,733 行，但触点极小：**

| 文件 | 行数 | 耦合点 | 移植动作 |
|---|---|---|---|
| `api/WorldPlayerActions.kt` | 1,305 | `UseEntityCallback`（1 处调用点） | 改调 NeoForge `PlayerInteractEvent.EntityInteract`，或直接走 `Player.interact` |
| `api/StorageOps.kt` | 867 | Fabric Transfer API（`ItemStorage.SIDED`/`Storage`/`Transaction`/`SlottedStorage`） | **重写能力层**（保留 RPC/DTO 与前半段参数逻辑） |
| `wait/WaitOps.kt` | 279 | `ServerTickEvents.END_SERVER_TICK`（`install()` 一行） | 换 `ServerTickEvent.Post` |
| `api/FakePlayer.kt` | 271 | 同上（被动 tick 安装） | 同上；假玩家建议换 NeoForge `FakePlayerFactory` |
| `api/FluidOps.kt` | 304 | Fabric Transfer API（`FluidStorage.SIDED`/`SingleVariantStorage`/`CombinedStorage`） | **重写能力层** |
| `util/Mappings.kt` | 178 | `FabricLoader` + 运行时命名空间探测 + 生成的 intermediary↔Mojang 表 | 改为身份映射（运行时就是 Mojang 名），表可删 |
| `api/RedstoneOps.kt` | 171 | `ServerTickEvents` 安装 | 同 WaitOps |
| `api/TraceOps.kt` | 164 | `ServerTickEvents` 安装 | 同 WaitOps |
| `ServerPlane.kt` | 146 | `ServerLifecycleEvents.SERVER_STARTED/STOPPING` | 换 `ServerStartedEvent`/`ServerStoppingEvent` |
| `McDebugMod.kt` | 48 | `DedicatedServerModInitializer` + `FabricLoader` | 换 `@Mod` + `FMLPaths.GAMEDIR` + `ModList` |

### 2.2 客户端平面

**零 loader 依赖——29 文件 / 4,521 行**，包含截图（`Capture` 709）、GUI 操作（`GuiOps` 463 / `GuiPointerOps` 429 / `GuiInputOps` 414 / `GuiClickOps` 203）、`RenderControl`(167)、`DrawRecorder`(160)、`GuiSettle`(159)、`ScreenGuard`(96)、`Annotator`(101)、`ProblemLog`(138)、`ScreenReload`(131) 以及 **8 个 mixin**（共 10 个 mixin 中的 8 个；另 2 个 MixinExtras mixin 见 §2.2 表尾）（`GuiGraphicsMixin` 130、`AbstractContainerScreenAccessor` 35、`ScreenInputMixin` 31、`AbstractWidgetRenderMixin` 21、`ClientPacketListenerMixin` 19、`RenderSystemMixin` 17、`ClientTextTooltipAccessor` 13、`NativeImageAccessor` 12）。

**loader 耦合——4 文件 / 710 行：**

| 文件 | 行数 | 耦合点 | 移植动作 |
|---|---|---|---|
| `Hotswap.kt` | 206 | `FabricLauncherBase.getLauncher().getClassByteArray()` + Mixin `transformClassBytes` | **换 FML 侧取字节来源**（见 §6 风险 R2），逻辑本身可留 |
| `ClientInfo.kt` | 196 | `FabricLoader`（版本/jar 路径/命名空间） | 换 `ModList` + `FMLPaths` |
| `ClientTasks.kt` | 164 | `ClientTickEvents.END_CLIENT_TICK` | 换 `ClientTickEvent.Post` |
| `McDebugClientMod.kt` | 144 | `ClientModInitializer` + `ClientLifecycleEvents` + `FabricLoader` | 换 `@Mod(dist = Dist.CLIENT)` + `ClientPlayerNetworkEvent` |

外加 **2 个 MixinExtras mixin**（`MinecraftRenderMixin` 32、`GameRendererMixin` 39）：源码不用改（NeoForge 内置 MixinExtras 0.5.5），只需把 mixin config 从 `fabric.mod.json` 搬到 `neoforge.mods.toml`。

### 2.3 汇总

| 平面 | 文件 | 行数 | 原样复用 | 需改造文件 | 真正要重写的实现 |
|---|---|---|---|---|---|
| 服务端 | 31 | 8,622 | 21 文件 / 4,889 行 | 10 文件 / 3,733 行（多为 1–3 行触点） | `storage`/`fluid`/能量 能力层 |
| 客户端 | 35 | 5,302 | 29 文件 / 4,521 行 | 4 文件 / 710 行 + mixin 配置 | hotswap 取字节 + 接线 |
| **合计** | **66** | **13,924** | **50 文件 / 9,410 行（67.6%）** | 14 文件 | ≈ **1,500–1,800 行**新代码 |

对照 0.6.3 删除的 1.20.1 Forge 移植：那次是**整树拷贝 + 全库改名**，`StorageOps` 改 817/854 行、`FluidOps` 改 282/312 行、`Mappings.kt` 从 262 行重写成 32 行、`WorldOps` 改 553/1590 行。今天的 delta 明显更小，因为**映射不再需要换算**。

---

## 3. 已实测验证（本机 spike，非纸面推断）

两个一次性 spike 工程（`/tmp/neo-spike`、`/tmp/neo-client-spike`，不在仓库内）：ModDevGradle 2.0.148 + Gradle 9.4.1 + JDK 21 + Kotlin 2.3.10 + **NeoForge 21.1.265** + MC 1.21.1。

### 3.1 服务端 spike：PASS

```
[main/INFO] [mixin/]: SpongePowered MIXIN Subsystem Version=0.8.7 ...
[main/INFO] [MixinExtras|Service/]: Initializing MixinExtras via ...MixinExtrasServiceImpl(version=0.5.5).
[main/INFO] [ModDiscoverer/]: Mod List: mcdebug neoforge spike 0.1.0 (mcdebugspike) / Minecraft 1.21.1 / NeoForge 21.1.265
[Server thread/INFO] [mcdebugspike/]: SPIKE: runtimeNamespace srgNames=0 sample=[]
[Server thread/INFO] [mcdebugspike/]: SPIKE: mojangMemberNames(ServerLevel = getBlockState) = true
[Server thread/INFO] [mcdebugspike/]: SPIKE: mixinExtrasWrapOperationAvailable=true
[Server thread/INFO] [mcdebugspike/]: SPIKE: capability ItemHandler.BLOCK on chest -> net.neoforged.neoforge.items.wrapper.InvWrapper slots=27
[Server thread/INFO] [mcdebugspike/]: SPIKE: serverTickEventPost count=20 mixinApplied=true
[Server thread/INFO] [mcdebugspike/]: SPIKE: RESULT PASS
```

逐条含义：

| 事实 | 对移植的意义 |
|---|---|
| 工具链跑通（`./gradlew runServer`，二次运行 21s） | 现有 Gradle 9.4.1 可直接用；不必像 1.20.1 那样另起 Gradle 8 嵌套构建（仍建议独立 build 以隔离插件） |
| `[[mixins]] config="..."`（`neoforge.mods.toml`）生效，`pl:mixin:APP:...SpikeMixin` 命中 `MinecraftServer` | 服务端 mixin 注册方式确定；客户端同理（JSON 里用 `client` 段做侧别隔离） |
| `defaultRequire: 1` 下描述符写错 → 启动即崩（实测踩到 `InvalidInjectionException`） | 失败模式是**响亮**的，不是静默失效——正是我们要的 |
| `srgNames=0`，`ServerLevel.getBlockState` 可按 Mojang 名反射到 | **`reflect.*` 零成本**：不加映射表、不改 `ReflectCore`；`Mappings.kt` 退化为身份映射 |
| MixinExtras 0.5.5 由 loader 内置 | `@WrapOperation` 免发货、免 refmap 配置（`MinecraftRenderMixin`/`GameRendererMixin` 源码不动） |
| `ServerStartedEvent` / `ServerTickEvent.Post` 正常触发 | `ServerPlane` 与 4 处 tick 安装点直接对应 |
| `Capabilities.ItemHandler.BLOCK` 在箱子上取到 `InvWrapper`(27 槽) | `storage.*` 的能力层有明确落点；留意语义差异（§6 R3） |
| **`implementation 'kotlin-stdlib'` 不进游戏运行时**：首次事件回调抛 `ClassNotFoundException: kotlin.jvm.internal.Intrinsics`；改用 MDG 的 `additionalRuntimeClasspath` 后正常 | **这是最容易被低估的一条**：Kotlin mod 的 stdlib 必须显式供给（dev：`additionalRuntimeClasspath`；生产：KotlinForForge 或内嵌 `FMLModType: LIBRARY`） |

### 3.2 客户端 spike：PASS

同一套配置下的 `runClient`（`DISPLAY` + llvmpipe，1280x720），客户端专用 `@Mod(value="...", dist=[Dist.CLIENT])` 入口 + 3 个 mixin（照抄 mcdebug 的形态）：

```
[mixin/]: SpongePowered MIXIN Subsystem Version=0.8.7 ... Service=ModLauncher Env=CLIENT
[MixinExtras|Service/]: Initializing MixinExtras via ...MixinExtrasServiceImpl(version=0.5.5).
[Render thread/INFO] [mcdebugcspike/]: SPIKE: clientTickEvents=30 renderFrameEvents=60 runTickMixin=true wrapOperationMixin=true
[Render thread/INFO] [mcdebugcspike/]: SPIKE: screen=net.minecraft.client.gui.screens.TitleScreen mainRenderTarget=com.mojang.blaze3d.pipeline.MainTarget guiScale=3.0 window=1280x720
[Render thread/INFO] [mcdebugcspike/]: SPIKE: glReadPixels=true
[Render thread/INFO] [mcdebugcspike/]: SPIKE: RESULT PASS
```

| 验证项 | 实现方式（与 mcdebug 现有代码同形） | 结果 |
|---|---|---|
| 客户端专用入口 | `@Mod(value=..., dist=[Dist.CLIENT])` 普通 Kotlin class（NeoForge 已无 `DistExecutor`） | ✅ 加载，`Env=CLIENT` |
| 客户端 tick | `NeoForge.EVENT_BUS` + `ClientTickEvent.Post` | ✅ 30 次 |
| **帧完成屏障** | `RenderFrameEvent.Post`（NeoForge 专有事件，**不需要 mixin**） | ✅ 60 次 |
| 普通 `@Inject` mixin | `@Mixin(Minecraft.class)` + `runTick` HEAD | ✅ 注入成功（计数器为真） |
| **MixinExtras `@WrapOperation`** | 照抄 `GameRendererMixin`：`@WrapOperation(method="render", at=@At(INVOKE, target="Lnet/minecraft/client/Minecraft;isWindowActive()Z"))` | ✅ 注入成功、`original.call` 正常 |
| 截图读回路径 | `Minecraft.getInstance().mainRenderTarget` + `bindWrite(true)` + `glReadPixels` | ✅ 可读回（`MainTarget`，guiScale 3.0，1280x720） |
| `@Accessor` mixin | `@Mixin(AbstractContainerScreen.class) @Accessor("leftPos")` | ⚠️ 编译并注册成功（`defaultRequire:1`），但标题界面不会加载该类，**运行时未实测** |

> 意义：客户端平面最危险的四个原语（往 `Minecraft`/`GameRenderer` 注入、MixinExtras、帧屏障、帧缓冲读回）在 NeoForge 21.1.265 上**已被真机证明可行**。剩余未实测的是「其余 8 个 mixin 文件的注入点在 NeoForge 补丁下是否仍然成立」（`GuiGraphics` 有 7 处 `blit` 注入、`RenderSystem`、`Screen`、`ClientPacketListener`、`AbstractContainerScreen`），这正是 WP4 + G2 门禁要逐条过的清单。

---

## 4. 目标侧事实核查（一手证据）

| 事实 | 值 / 结论 | 证据 |
|---|---|---|
| NeoForge 21.1.x 维护状态 | **21.1.265（2026-10-25）**，21.1.x 共 260 个版本，2026-10 近乎每周一发 | `maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml` |
| 运行时命名空间 | **Mojang 官方名**（installer 用反向 Mojmap 合并；ModLauncher 11 已无 `INameMappingService`）；实测 loader 类中 SRG 引用 **0** | 本地 `javap`/二进制扫描 NeoForge 21.1.265 universal jar + 附录 A |
| 构建工具链 | **ModDevGradle 2.x**（最新 2.0.148；维护者确认支持 Gradle 9）；1.21.1 MDK 用 2.0.91 + Gradle 8.14.2；备选 NeoGradle 7.0.170 | 附录 A；本机 mekanism-fabric 用 MDG 2.0.107 + Gradle 9.0.0 + NeoForge 21.1.200（同工作区先例） |
| Mixin / MixinExtras | sponge-mixin `0.16.4+mixin.0.8.7`；**MixinExtras 0.5.5 作为 `META-INF/jarjar/` 内置** | 本地 `unzip -l` + 启动日志 `MixinExtrasServiceImpl(version=0.5.5)` |
| mixin 注册 | `META-INF/neoforge.mods.toml` 的 `[[mixins]] config="..."`（`MixinConfigs` manifest 亦兼容） | 本地 spike 生效 |
| Kotlin | **KotlinForForge 5.12.0**：MC 1.20.6–1.21.8，内置 Kotlin 2.4.0 stdlib/reflect/coroutines/serialization，支持 `object` 作为 `@Mod`；坐标 `thedarkcolour:kotlinforforge-neoforge`。⚠️ 5.12.0 是 **1.21.1 的最后一个 KFF 版本**（2026-06-28），项目已转 6.x/新 MC。备选 **KotlinLangForge 3.0**（NeoForge 1.21.1 在支持表内） | 附录 A |
| 事件对照 | `ServerStartedEvent`/`ServerStoppingEvent`、`ServerTickEvent.Post`、`ClientTickEvent.Post`、`ClientPlayerNetworkEvent.LoggingIn/LoggingOut`、`PlayerInteractEvent.EntityInteract` | 本地 jar 类清单；spike 实测前两者 |
| 客户端事件（比 Fabric 更富） | `RenderFrameEvent.Pre/Post`（**帧完成屏障，可替代/补充 `GameRenderer` mixin**）、`ScreenEvent.*`、`ContainerScreenEvent.Render.*`、`ScreenshotEvent`、`InputEvent.*` | 本地 jar 类清单（`net/neoforged/neoforge/client/event/`） |
| 能力 API | `Capabilities.ItemHandler.BLOCK`（`IItemHandler`，槽位式）、`Capabilities.FluidHandler.BLOCK`（`IFluidHandler` + `FluidAction`）、`Capabilities.EnergyStorage.*`；注册走 `RegisterCapabilitiesEvent.registerBlockEntity(...)`；**没有 `Transaction`**（用 `simulate` 布尔 / `FluidAction.SIMULATE`） | 本地 `javap` + spike 实测 ItemHandler |
| 若走 Connector | Connector `2.0.0-beta.17+1.21.1`（Modrinth loader 标签仅 `neoforge`）、FFAPI `0.116.15+2.3.6+1.21.1`；**本轮实测**：FFAPI jar 内确实带 `META-INF/jars/fabric-transfer-api-v1-5.4.4+6244b11119.jar`，即 **`storage.*`/`fluid.*` 的 Transfer API 代码在 Connector 路线上可以不改**；FLK 经官方 Probe 服务测试通过；专用服务器是正规路径；命名空间 `RUNTIME_MAPPING="mojang"`（首次启动需实测确认） | 附录 A + `docs/research-forge-family-feasibility.md` |

---

## 5. 推荐架构

### 5.1 单源码树 + 8 个 loader 接缝（seam）

复制 10 个文件的老做法在 1.20.1 可行，但今天要复制的是 `WorldPlayerActions`(1305)、`StorageOps`(867) 这种大文件——**分叉会立刻腐化**。推荐先做一次**不改变 Fabric 行为**的接缝重构：

| 接缝 | 职责 | Fabric 实现 | NeoForge 实现 |
|---|---|---|---|
| `LoaderEnv` | gameDir、mod 版本、`isModLoaded`、`modLoaderName` | `FabricLoader` | `FMLPaths.GAMEDIR` + `ModList` |
| `ServerLifecycle` | 服务器启动/停止钩子 | `ServerLifecycleEvents` | `ServerStartedEvent`/`ServerStoppingEvent` |
| `TickHooks` | 服务端/客户端 tick 安装 | `ServerTickEvents`/`ClientTickEvents` | `ServerTickEvent.Post`/`ClientTickEvent.Post` |
| `PlayerUseHooks` | 实体交互回调 | `UseEntityCallback` | `PlayerInteractEvent.EntityInteract` |
| `StorageApi` | 物品/流体/能量 端点查找 + 插入/抽取/simulate | Fabric Transfer API | NeoForge Capabilities |
| `RuntimeNames` | `reflect.*` 的运行时命名空间翻译 | intermediary↔Mojang 表 | 身份映射（Mojang） |
| `ClientByteSource` | hotswap 的「pre-mixin 字节」来源 | `FabricLauncherBase` | FML `ModFile`/`SecureJar` + Mixin transformer |
| `ClientLifecycle` | 客户端启动/停止、侧别 | `ClientLifecycleEvents` | `ClientPlayerNetworkEvent` + `@Mod(dist=CLIENT)` |

收益：14 个耦合文件变成「共享主逻辑 + 每个 loader 一个薄实现」，改动只落在接缝文件里；同时**天然把 Fabric 侧回归面缩到 8 个文件**。

### 5.2 构建形态：独立的 `neoforge/` 嵌套构建（推荐）

沿用 0.6.3 删除的 `forge/` 形态：`neoforge/` 自带 `settings.gradle` + 独立 `gradle.properties`（可钉自己的 Gradle / MDG 版本），根 `settings.gradle` 通过 `delegate.gradle` 暴露 `:neoforge:build` 等任务；共享源码用 `Sync` 任务把 `../src/{main,client}/**`（排除接缝文件）拷到 `build/generated/shared/` 再编译。

- 为什么不用单 build 多 subproject：Loom 与 ModDevGradle 同 build 共存有插件级风险（都要接管 `minecraft` 运行时的生成），而 spike 证明 MDG 单跑完全没问题；嵌套构建把爆炸半径隔离，且**这个形态在本仓库已经被验证过一次**。
- 版本号仍以根 `gradle.properties` 的 `mod_version` 为唯一来源（两处构建都读它，与老 `forge/build.gradle` 一致）。

### 5.3 Kotlin 运行时：KFF（推荐）vs 内嵌 stdlib

| 方案 | 优点 | 代价 |
|---|---|---|
| **KotlinForForge 5.12.0**（推荐） | 官方路径；内置 stdlib/reflect/coroutines；`object` 可直接当 `@Mod`；与 Fabric 侧要求 fabric-language-kotlin **对称**（消费者本来就要多装一个语言包） | 多一个 jar 进 `mods/`；1.21.1 线已终止更新（稳定软件，可接受） |
| 内嵌 `kotlin-stdlib`（`FMLModType: LIBRARY` jar-in-jar，或 shadow） | 单 jar 自包含；CLI `mcdebug jar` 只写一个文件 | 需要自己处理 JarJar 元数据/LIBRARY 清单；与 KFF 共存会双份 Kotlin；`javafml` 下不能用 `object @Mod`（要改成普通 `class`，本仓库改造成本其实很低） |

**本轮 spike 的实测教训**：只写 `implementation "org.jetbrains.kotlin:kotlin-stdlib"` 一定在第一个 Kotlin 调用点炸 `ClassNotFoundException`；dev 必须 `additionalRuntimeClasspath`，生产必须走上面两条之一。

### 5.4 契约与命名兼容策略

- **方法名、DTO、错误码全部不变**（`contract/Methods.kt` 不动）；CLI 不做 loader 分支。
- `server.status.modLoader` 报 `"neoforge"`（现有实现是硬编码 `"fabric"`，改为从接缝读）。
- **handle 名保持 `fabric:item` / `fabric:fluid` / `teamreborn:energy`**：CLI 侧 `StorageCommands.kt` 把 `item|fluid` 硬编码翻译成 `fabric:item|fabric:fluid`，只要服务端继续应答这些名字，同一个 CLI 就能驱动两种 loader（0.6.3 前的 Forge 移植就是这么做的，并在它的冒烟脚本里断言了 `fabric:item`）。同时**接受 `neoforge:item`/`neoforge:fluid`/`neoforge:energy` 作为别名**，文档里写清语义差异（FE 能量单位 vs Team Reborn）。
- `reflect.*` 的帮助文本（CLI）目前写「Mojang 与 intermediary 名都可用」；NeoForge 侧应改成「Mojang 名（运行时即官方名）」，即 CLI help 需要按实例能力提示，或在文档中说明。

### 5.5 分发与工具链

- CLI fat jar 内嵌**两个** mod jar（`META-INF/mcdebug/mod.jar` + `META-INF/mcdebug/mod-neoforge.jar`）；`mcdebug jar [--loader fabric|neoforge]` 选择写出（默认 fabric，保持兼容）。
- `release.yml` 增加 `mcdebug-neoforge-<tag>.jar` 与 sha256；tag/`mod_version` 不一致仍失败。
- `session` 管理：ModDevGradle 的运行任务同样是 `runClient`/`runServer`（spike 已用 `runServer` 跑通），`SessionManager` 的 `runClient` 白名单无需改动；只有 "Fabric loader 崩溃识别" 那一处需要加 NeoForge 的崩溃特征（`FML`/`ModLoadingException`）。

---

## 6. 风险登记表

| # | 风险 | 影响 | 概率 | 缓解 / 验证 |
|---|---|---|---|---|
| R1 | **客户端 mixin 注入点与 NeoForge 补丁冲突**（`GameRenderer`/`Minecraft`/`GuiGraphics`/`RenderSystem`/`Screen`/`AbstractContainerScreen`/`ClientPacketListener` 都被 NeoForge 打过补丁） | 启动崩或功能静默失效 | 中（`Minecraft`/`GameRenderer` 两个最危险的注入点**已实测通过**，其余 8 个 mixin 文件待验） | 保留 `defaultRequire: 1`（宁可响亮失败）；可用 NeoForge 事件替代的注入点优先替代（`RenderFrameEvent.Post` 已实测可用，可替代帧屏障 mixin；输入侧有 `ScreenEvent.*`）；**必须在真机客户端逐个验证**（AGENTS.md §14.1 的教训）；`GuiGraphics` 的 7 处 `blit` 注入是剩余风险最集中处 |
| R2 | **hotswap 取字节**：Fabric 用 `FabricLauncherBase.getClassByteArray(name, true)` 拿 pre-mixin 字节；FML 没有同名 API | 仅 dev 体验降级（不影响线上功能） | 高 | 用 `ModList.getModFileById("mcdebug")` 的 `SecureJar`/`IModFile` 读自身 class 原始字节，再交给 Mixin `transformClassBytes`；失败时降级为「未加载类生效、已加载类提示重启」 |
| R3 | **能力语义差异**：Fabric Transfer 是「资源+事务+视图」，NeoForge 是「槽位+simulate」；无 `Transaction` | `storage.*`/`fluid.*` 细节语义（如嵌套事务、`CombinedStorage` 份数）不完全等价 | 中 | 保留 RPC 契约，语义差异写进 `README`/`server.status`；不可表达的语义（多视图合并）用 `IItemHandler`/`IFluidHandler` 的等价枚举实现；用 ic2 风格用例回归 |
| R4 | **KFF 1.21.1 终版**（5.12.0，2026-06-28） | 未来 KFF bug 无人修 | 低 | 稳定软件可接受；备选 KotlinLangForge 3.0；或内嵌 stdlib |
| R5 | **双 loader 长期维护成本** | 每次改动要过两套构建 + 两套真机验证 | 高 | 接缝架构 + 共享源码（67% 复用）+ 同一套 CLI/契约/测试；CI 双构建；文档把"改 loader 无关代码时两边都要跑冒烟"写进 AGENTS.md |
| R6 | Loom 与 ModDevGradle 同 build 冲突 | 构建不稳定 | 中 | 独立嵌套构建（本仓库已验证过该形态） |
| R7 | **只在 dev 验证、生产 jar 才暴露问题** | 打包后才发现（历史上真踩过：Loom 把单值 `at` 写成数组，消费者项目才崩） | 中 | 验收门禁 G2 强制：发布 jar + 消费者项目/纯 NeoForge 安装真机 |
| R8 | 若走 Connector 过渡：上游 open bug + 我们 jar 的形状（**裸 `method_XXXXX` 名、无描述符、无 refmap**，本轮已在本仓库 `build/libs/mcdebug-0.7.0.jar` 里核实） | 随机/静默失败（FFAPI #260 open；Connector #2134 open；#2295 FART 竞态） | 中-高（仅 Connector 路线） | 先 bump Loom ≥1.17（#266 已修复）+ 给所有 `@Inject/@WrapOperation` 的 `method=` 补完整描述符；定位为过渡/探针，不作为长期方案 |
| R9 | 版本线漂移（工作区已有 `mcdebug` 1.20.1 归档线、`ic2-fabric-26.2` 等 26.x 线） | 1.21.1 的移植可能重复投入 | 低-中 | 先确认目标线；接缝架构让后续 26.x 端口边际成本大幅降低 |

---

## 7. 工作分解与估算（单人，含验证）

| WP | 内容 | 人日 |
|---|---|---|
| WP0 | **接缝重构（Fabric 侧）**：抽 8 个接口 + Fabric 实现，行为不变；`./gradlew build` + `scripts/fabric-smoke.sh` 全绿 | 2–3 |
| WP1 | **NeoForge 构建骨架**：`neoforge/` 嵌套构建（MDG 2.x + Kotlin + 共享源码 Sync + `neoforge.mods.toml` + `[[mixins]]` + runs + KFF + `delegate.gradle` + jar 命名/版本） | 1–2 |
| WP2 | **服务端接线**：入口、生命周期、4 处 tick 安装、`UseEntityCallback`、`Mappings` 身份化、`RpcEnv.gameDir`、假玩家、`modLoader` 上报 | 2–3 |
| WP3 | **能力层适配**：`storage.*`（item/fluid/energy）重写 + `fluid.*` 对齐 + handle 名/别名 + 契约 parity | 3–5 |
| WP4 | **客户端平面**：client-only `@Mod`、`ClientTickEvent`/`ClientPlayerNetworkEvent`、10 个 mixin + config、帧屏障（`RenderFrameEvent` 与 `GameRenderer` mixin 的取舍）、hotswap 字节源、`ScreenClassLoader` 父加载器、限帧/唤醒在 NeoForge 上的真机验证、截图/点击/hover/settle 全链路 | 5–8 |
| WP5 | **CLI / 分发 / session / release**：内嵌第二个 jar、`mcdebug jar --loader`、`release.yml`、session 崩溃特征、`verifyJarSelfContained` | 1–2 |
| WP6 | **验证基础设施与门禁**：`scripts/neoforge-smoke.sh`（复用 `contract-parity.py`）、消费者项目生产 jar 真机闭环、golden compare 稳定性、Fabric 侧回归 | 3–4 |
| | **合计（两平面）** | **17–27** |
| | 仅服务端平面（WP0–WP3 + 部分 WP6） | 8–13 |

---

## 8. 验证计划（门禁）

| 门禁 | 内容 | 通过标准 |
|---|---|---|
| G1 | NeoForge dev 服务端：`neoforge/runServer` + `neoforge-smoke.sh` + `contract-parity.py` | 契约方法 100% 已注册；`world/be/inv/storage/fluid/wait/reflect` 抽样断言通过 |
| G2 | **生产 jar** + 纯 NeoForge 1.21.1 客户端/服务端（非 dev）+ 消费者项目 `run/mods/` | 启动无 mixin 失败；`gui.*` 全链路（截图 → 点击 → 再截图 → hover/compare）通过；`reflect.*` 用 Mojang 名可用 |
| G3 | 双平面并发 + `session start/stop/attach` + 双通道（unix socket / TCP） | 与 Fabric 版行为一致；`server.status.modLoader="neoforge"` |
| G4 | Fabric 侧回归 | 现有 `./gradlew build`、`./gradlew test`、`scripts/fabric-smoke.sh` 全绿；CLI 对两种实例行为一致 |

---

## 9. 备选路线与取舍

| 路线 | 成本 | 判定 |
|---|---|---|
| **A. 原生 NeoForge 移植（本方案）** | 17–27 人日 | ✅ **推荐**：确定性、单 jar、无中间层、能力/事件/客户端钩子都是第一方支持 |
| **B. Connector + FFAPI 跑现有 Fabric jar** | 1–2 人日探针；若长期依赖则需持续跟随上游 | ⚠️ **建议作为第一步探针与发布兜底**：能最快回答"我们的客户端 mixin 集合在另一套补丁集上是否成立"；但依赖第三方兼容层，且上游对我们这种 jar 形状仍有未关闭问题（§6 R8）——**不适合作为 agent 日常闭环的唯一通道** |
| C. Kilt | — | ❌ 方向相反（它把 (Neo)Forge mod 搬进 Fabric），无法把 mcdebug 放到 NeoForge 服务端 |
| D. Architectury / Stonecutter 多 loader | 相当于 A 的另一种工程形态 | ⚠️ Architectury API 13.0.11（1.21.1）**只有 fabric+neoforge**、且对 Kotlin 无第一方支持；若要做多 loader，现代选择是 Stonecutter/Modstitch。对"只要一个 NeoForge 目标"来说，A 更直接 |
| E. 只做服务端平面 | 8–13 人日 | ⚠️ 不满足本次目标（agent 需要像素级观察与 GUI 操作） |

---

## 10. 建议的下一步

1. **（1–2 天）Connector 探针**：bump Loom ≥1.17 + 给所有 mixin `method=` 补描述符 → 起 NeoForge 21.1 客户端/服务端 + Connector + FFAPI + FLK + 现有 `mcdebug-0.7.0.jar`，先服务端后客户端。无论成败都直接喂给 WP4 的注入点清单。
2. **（2–3 天）WP0 接缝重构**，Fabric 侧零行为变化（冒烟回归）。
3. **（3–5 天）WP1+WP2**：NeoForge 服务端最小可跑 + `neoforge-smoke.sh` + 契约 parity。
4. **（3–5 天）WP3** 能力层；**（5–8 天）WP4** 客户端平面；**（3–4 天）WP6** 真机门禁。
5. **（1–2 天）WP5** 分发/session/release + 文档（`AGENTS.md`、`skills/mcdebug/SKILL.md`、`README`）同步。

---

## 附录 A：证据来源

**本机实测（本轮）**
- 服务端 spike：`/tmp/neo-spike`（ModDevGradle 2.0.148 + Gradle 9.4.1 + Kotlin 2.3.10 + NeoForge 21.1.265），日志 `/tmp/neo-spike/spike.log`，`SPIKE: RESULT PASS`。
- 客户端 spike：`/tmp/neo-client-spike`（同版本；`@Mod(dist=CLIENT)`、`ClientTickEvent.Post`、`RenderFrameEvent.Post`、`@WrapOperation` 照抄 `GameRendererMixin` 注入点），日志 `/tmp/neo-client-spike/spike.log`。
- NeoForge 21.1.265 产物检查：`neoforge-21.1.265-universal.jar` → `META-INF/jarjar/mixinextras-neoforge-0.5.5.jar`；loader 类中 SRG 名计数 0；`Capabilities$ItemHandler` / `BlockCapability#getCapability(Level,BlockPos,BlockState,BlockEntity,C)` / `ServerTickEvent$Post` 签名由 `javap` 读出。
- 仓库内先例：`git show forge-port:forge/build.gradle`（1.20.1 原生 Forge 移植形态）、`git show forge-port:scripts/forge-smoke.sh`（契约 parity 冒烟）、`git show main:src/main/kotlin/com/mcdebug/util/Mappings.kt`（旧版 SRG 缺口说明）。
- 工作区先例：`../mekanism-fabric`（ModDevGradle 2.0.107 + Gradle 9.0.0 + NeoForge 21.1.200 + MC 1.21.1）。

**外部来源（含 URL 的完整清单见 `docs/research-forge-family-feasibility.md`）**
- NeoForge maven metadata（21.1.265）：<https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml>
- ModDevGradle（Gradle 9 支持、jar-in-jar）：<https://github.com/neoforged/ModDevGradle>、<https://github.com/neoforged/ModDevGradle/issues/326>
- NeoForge 模组元数据/`[[mixins]]`：<https://docs.neoforged.net/docs/gettingstarted/modfiles/>
- KotlinForForge 5.x（MC 范围、Kotlin 版本、`object @Mod`）：<https://github.com/thedarkcolour/KotlinForForge/blob/5.x/README.md>
- Connector 1.21.1 与 FFAPI：<https://modrinth.com/mod/connector>、<https://github.com/Sinytra/ForgifiedFabricAPI>（本轮下载 `forgified-fabric-api-0.116.15+2.3.6+1.21.1.jar` 核实内含 `META-INF/jars/fabric-transfer-api-v1-5.4.4+6244b11119.jar`；`connector-2.0.0-beta.17+1.21.1-full.jar` 的 Modrinth loader 标签为 `neoforge`）
- 上游问题：FFAPI #260（open）、#266（closed）、Connector #2134（open）、#2295（open）——GitHub API 于 2026-10-30 查询确认状态。

**未验证 / 待确认**
1. KFF 5.12.0 在 NeoForge 21.1.265 上的实际加载（本轮 spike 走的是 `additionalRuntimeClasspath` + `javafml` 路线）。
2. hotswap 在 FML 下取 pre-mixin 字节的具体 API（`SecureJar`/`IModFile` 路线待 spike）。
3. 生产（非 dev）jar 在纯 NeoForge 客户端的 mixin 注入点（G2 门禁项）。
4. `RenderFrameEvent.Post` 已实测触发（60 次/秒级），但**它相对 GUI 绘制完成的先后顺序**需在 WP4 里与现有「新帧屏障」语义对齐确认。
5. 其余 8 个客户端 mixin 文件的注入点（尤其 `GuiGraphicsMixin` 的 7 处 `blit`）在 NeoForge 补丁下的实际命中情况。
6. Connector 路线的运行时命名空间实测值（`RUNTIME_MAPPING="mojang"` 是源码常量，需首次启动打日志确认）。

---

## 11. 实施结果（2026-10-30，本机真机）

> 本节是**做完之后**的记录：哪些设计成立、哪些假设被真机推翻、实际差异与踩过的坑。
> 结论：§5 的“单源码树 + 接缝 + 独立嵌套构建”方案成立，**没有出现需要重写某一块的情况**；
> 真正花时间的不是 Minecraft API，而是 ① 能力模型语义对齐 ② 客户端注入点在 NeoForge 被打补丁后移位。

### 11.1 交付物（新增文件）

| 位置 | 内容 |
|---|---|
| `src/main/kotlin/com/mcdebug/loader/{LoaderApi,RuntimeNames,StorageApi,FluidComponents}.kt` | loader 中立接缝：环境/生命周期/tick/use-entity/运行时命名 + item/fluid/energy 存储 + 流体组件编码 |
| `src/main/kotlin/com/mcdebug/loader/fabric/{FabricLoaderApi,FabricRuntimeNames,FabricStorageApi}.kt` | Fabric 侧实现（Transfer API / `ServerLifecycleEvents` / 映射表） |
| `src/client/kotlin/com/mcdebug/client/loader/{ClientLoaderApi.kt,fabric/FabricClientLoaderApi.kt}` | 客户端接缝：client tick / started / stopping / gameDir / pre-mixin 字节 |
| `src/client/kotlin/com/mcdebug/client/ClientPlane.kt` | 与 loader 无关的客户端平面引导（原 `McDebugClientMod` 的主体） |
| `neoforge/{settings.gradle,gradle.properties,build.gradle,delegate.gradle,gradlew}` | 独立嵌套构建（ModDevGradle 2.0.148 + Kotlin 2.3.10 + Gradle 9.4.1 + 共享源码 Sync） |
| `neoforge/src/main/kotlin/com/mcdebug/loader/neoforge/{McDebugNeoForge,McDebugNeoForgeClient,NeoForgeLoaderApi,NeoForgeClientLoaderApi,NeoForgeStorageApi}.kt` | NeoForge 两个入口 + 两个接缝实现（Capabilities / `ServerTickEvent.Post` / `ClientTickEvent.Post` / shutdown hook） |
| `neoforge/src/main/java/com/mcdebug/loader/neoforge/NeoCapabilities.java` | Java 互操作垫片：capability 的 context 类型参数是 platform type，Kotlin 传 `null`（无侧面查询、Void 上下文）会报可空性告警 |
| `neoforge/src/main/java/com/mcdebug/client/mixin/MixinTargets.java` | **每个 loader 一份**的编译期注入点常量（见 11.3 的 `GameRenderer` 差异） |
| `cli/src/main/kotlin/com/mcdebug/cli/session/SessionManager.kt` | managed launch 现在按项目类型选启动方式：Loom（Fabric）继续用 Gradle `--args`；**ModDevGradle（NeoForge）改用 init script**（见 11.3 #11），并在 `session start` 时把判定打到 stderr |
| `scripts/stop-server.py` | 冒烟收尾用：按 unix socket 的 inode / 进程 cwd 定位游戏 JVM（不按名字猜、不碰共享 Gradle daemon），修掉“`(cd … && java …) &` 只 kill 子 shell”导致的孤儿专用服 |
| `scripts/neoforge-smoke.sh` | NeoForge 专用冒烟：起 dev 专用服 → 45 条 RPC 断言 → `contract-parity.py`（79/79 通过） |
| `cli` 侧 | `META-INF/mcdebug/mod-neoforge.jar` 内嵌、`mcdebug jar --loader fabric\|neoforge`（写一个 loader 的 jar 并删掉另一种命名/旧版本，避免同 modId 两份） |
| `release.yml` | 额外产出 `mcdebug-neoforge-<version>.jar` + `.sha256` |
| 根构建 | `include 'neoforge'` + `project(':neoforge').buildFileName = 'delegate.gradle'`，于是 `./gradlew :neoforge:build\|runServer\|runClient` 直接可用 |

### 11.2 真机验证证据（全部本机跑过）

1. **服务端**：`neoforge/gradlew runServer` 启动，日志 `mcdebug 0.7.0 initializing (neoforge)` → `mcdebug RPC ready`；`server.status` 返回 `modLoader:"neoforge"`、`fluidUnit:"mb"`；`python3 scripts/contract-parity.py` → **79/79**，rc=0。
2. **存储接缝**（NeoForge capabilities）：箱子同时列出 `vanilla:inventory` 与 `fabric:item`；`storage.insert` 3→3、`storage.get` 槽位/容量、`storage.transfer` 3→3（干跑先行 + 真实搬运）、`simulate` 不改状态；水桶 `storage.extract` → `extracted:1000`（mB）且 `targetAfter` 是空桶（`Capabilities.FluidHandler.ITEM` + `FluidUtil` 兜底）。
3. **客户端**：`neoforge/gradlew runClient` → **10 个 mixin 全部注入成功（0 个 Critical injection failure）**、`mcdebug client plane contract ok (30 methods)`、`clientSocket` 可连；`gui status` / `gui screen`（列到 NeoForge 自己的 `ModsButton`）/ `gui screenshot` 真实出图；`gui click --text Continue` → 屏幕从 `AccessibilityOnboardingScreen` 切到 `TitleScreen`，再 `--text Singleplayer` → `CreateWorldScreen`，两次截图 PNG 目视确认（854x480，guiScale 2.0）。
4. **消费者项目（发布 jar，纯 NeoForge）**：用 MDG 建了一个最小 NeoForge 项目（`@Mod("consumer")`，无 Kotlin），把 **CLI 写出的 `mcdebug-neoforge-0.7.0.jar`** 与 KotlinForForge 丢进它的 `run/mods/`：
   - 服务端：`mcdebug 0.7.0 initializing (neoforge)` → RPC ready，`server.status.modLoader=neoforge`、`fluidUnit=mb`，**契约 79/79**，箱子 capabilities/水桶 mB 抽取照常；
   - 客户端：**0 个 Critical injection failure**（10 个 mixin 全部生效）、`mcdebug client plane contract ok (30 methods)`、`gui screenshot` 出图（pixelHash 与 dev 实例逐像素一致）、`gui click --text Continue` 从 Accessiblity 页切到标题页；
   - 说明：发布 jar 不需要任何 remap/refmap，direct 装进别的项目即可用（与 Fabric 侧“必须验打包”那条坑不同，但同样做了验证）。
5. **managed session 在 NeoForge 项目上跑通**：`mcdebug session start consumer --project <consumer> --display :90 --world NeoWorld --width 1000 --height 600` → `world_ready`；`--session consumer` 路由两平面（`status` 报 `modLoader=neoforge`）、`gui screenshot` 拿到 **1000x600 的真实世界画面**、`world.setBlock`/`inv.setSlot`/`wait.until` 全通；`session stop` → `stopped`。
6. **Fabric 零回归**：`./gradlew build`（147 单测）+ `:cli:copyCliJar` 全绿；扩展后的 `scripts/fabric-smoke.sh` 51 条断言 + 79/79 通过。
   顺带修掉两个**既有的**冒烟脚本问题：① `(cd … && java …) &` 只 kill 了子 shell，专用服会变成孤儿（本次会话攒下 7 个仍在跑的 1GB 服务端），改成 `setsid` + 进程组收尾并保住 exit code；② `world.setBlock` 的状态参数名是 **`state`**（`api/Params.kt` 的 `stateMap`），冒烟里写成 `props` 会被静默忽略 → 退化成 `/setblock` 的形状推导 → 悬空拉杆被 `updateFromNeighbourShapes` 移除、`redstone.setLever` 再报 “not a vanilla lever”。这条长期被误判成 mcdebug 的 setBlock 有 bug。
5. **分发**：`mcdebug-cli.jar` 内嵌两份 jar；`mcdebug jar --output DIR` 写 Fabric 版，`--loader neoforge` 写 `mcdebug-neoforge-<v>.jar` 并**删除**同目录的 Fabric 版；两个构建各有 `verifyJarSelfContained`（NeoForge 侧 24 条必需条目）。

### 11.3 被真机推翻 / 修正的假设

| # | 假设（写方案时的判断） | 真机结果 | 处理 |
|---|---|---|---|
| 1 | `GameRendererMixin` 的 `@WrapOperation` 注入点可原样照抄 | **不成立**：NeoForge 把 `render` 里画 Screen 的那句从 `Screen#renderWithTooltip` 改成了 `ClientHooks#drawScreen(Screen, GuiGraphics, int, int, float)`；`method="render"` 扫到 0 个候选 → `defaultRequire:1` 直接崩客户端 | 操作数形状相同，于是把**注入点字符串抽成每个 loader 一份的编译期常量** `MixinTargets.SCREEN_DRAW`（mixin 主体仍共享，两份构建各编各的常量）。其余 9 个 mixin 未动 |
| 2 | `capability` 查询可以像 `ItemStorage.SIDED.find(world,pos,side)` 那样一行搞定 | Kotlin 侧 `null`（无侧面 / Void 上下文）撞 Java 泛型 platform type，编译告警且语义不明 | 加 `NeoCapabilities.java`（10 行）做 Java 互操作垫片；Kotlin 侧保持零告警 |
| 3 | “Fabric 的 `supportsInsertion/Extraction` 在 NeoForge 有对应物” | 不成立：`IItemHandler`/`IFluidHandler` **没有**能力标志 | 由槽位/罐状态**推导**（罐未满→可注、非空→可抽），并在 `storage.get` 里如实回报；差异写进 §11.4 |
| 4 | 流体数量可以跨 loader 直接比较 | 不成立：Fabric 用 droplet（**1 桶 = 81000**），Forge 系用 mB（**1 桶 = 1000**）；且 Fabric 的物品承载流体是**整桶交换**（`FullItemFluidStorage` 要求 `maxAmount >= containedAmount`） | 不换算（各 loader 报各自原生单位），改为**把单位写进协议**：`server.status.fluidUnit`（`droplet`/`mb`）；冒烟里两套期望值 |
| 5 | `reflect.*` 的短类名（`BlockEntity`）两端都能用 | Fabric 能用是因为**它打包了映射表**（短名 → 全名）；NeoForge 运行时名就是 Mojang 名，没有表，短名无从解析 | NeoForge 用 FQN；`reflect.resolve` 在名字相同时不再输出 `mojangClass` 字段；差异写进 §11.4 与 skill |
| 6 | `ItemHandlerHelper.insertItem` 可以直接拿来用 | 用错会把**示例栈的 count** 当成上限：`storage.transfer` 传的是 count=1 的模板栈，于是“搬 3 个”只搬了 1 个（Fabric 侧一直是对的） | 修正为只用 `maxAmount`，并把**这条契约写进 `StorageApi.insert/extract` 的 KDoc**；`neoforge-smoke.sh` 里加了 `storage.transfer` + `wait.until` 断言防回归。这是接缝本身抓到的一个真实 parity bug |
| 7 | 客户端 `CLIENT_STOPPING` 语义在 NeoForge 有对应事件 | 21.1 没有客户端 stop 事件（只有 `ClientTickEvent`、`ClientPlayerNetworkEvent`） | `onClientStarted` = 首个 client tick；`onClientStopping` = JVM shutdown hook（仍在进程退出前跑完，集成服务器存盘之后），差异写在接缝 KDoc |
| 8 | NeoForge 的 `KotlinForForge` 依赖按“库”引入即可 | `kotlinforforge-neoforge` 是纯 `FMLModType: LIBRARY` 容器（只有 jarjar 的 stdlib），**不含 mod 元数据**；`additionalRuntimeClasspath` 也不会让它被 FML 发现 | 按 KFF 官方文档用 `implementation 'thedarkcolour:kotlinforforge-neoforge:5.12.0'`（传递带出 `kffmod`/`kfflang`/`kfflib`，其中 kffmod 提供 `kotlinforforge` 这个 mod、kfflang 注册语言加载器） |
| 9 | MDG 需要单独的旧 Gradle | 不成立：**ModDevGradle 2.0.148 + Gradle 9.4.1 正常工作**（嵌套构建直接复用根 wrapper 的版本既可） | `neoforge/` 自带 wrapper（可独立钉版本），当前与根一致 |
| 10 | 客户端平面在 NeoForge 上要先解决“没有 client entrypoint” | NeoForge 的 `@Mod(value=..., dist=[Dist.CLIENT])` 就是 client entrypoint，且**同 jar 里的 common `@Mod` 也会在客户端构造**（顺序不保证） | 客户端入口自己兜底 `if (!Loader.installed) install(...)`；共享的 `ClientPlane.start()` 承担两 loader 相同的引导，`ServerPlane.installLifecycleHooks()` 自身幂等 |

| 11 | `mcdebug session start` 的启动方式（Gradle `--args`）可以直接复用到 NeoForge | **不成立**：Loom 的 `runClient` 走 `net.fabricmc.devlaunchinjector.Main`，它解析 `--args`；而 MDG 的 `runClient` 走 `net.neoforged.devlaunch.Main`，**第一个程序参数就是它自己的参数文件**，Gradle 的 `--args` 会把它替换掉 → devlaunch 把 `--gameDir` 当主类，`ClassNotFoundException: --gameDir`，客户端起不来 | 按项目类型分流：检测 `net.neoforged.moddev` → 不再传 `--args`，改成写一份 **init script**（`--init-script`）在 `projectsEvaluated` 里给 `runClient` 设 `gameDirectory` 并 `args(...)` 追加游戏级参数（`JavaExec` 的 `args()` 是追加、`--args` 是替换，这就是原方案会炸的根因）。已在消费者项目上验证：gameDir/窗口尺寸/quickPlay 三项都真正生效（1000x600 的窗口 + 直接进存档） |

| 12 | “dry-run + 尽力回滚”可以替代 Fabric 的 `Transaction`（合并 transfer 路径时删掉了事务） | **不成立**：冷读审查指出这是 Fabric 侧的**真实回归**——预移植代码在**一个** `Transaction` 里 insert+extract，目标收不下就整体不提交；共享层改成先提交 extract 再 insert、回滚只“尽力”（来源拒绝收回，或期间有并发 RPC 时，资源会凭空消失）。NeoForge capability 没有事务，但 Fabric 有，不能为了“两边代码一样”而丢掉 | seam 加两个可选钩子 `StorageApi.moveItemsAtomically` / `moveFluidsAtomically`（默认 `null` = 该 loader 无事务，调用方回退到尽力路径）：Fabric 按老语义实现（嵌套事务试算、外层事务落地、`accepted != extracted` 就不提交），NeoForge 不实现（capability 每次调用立即提交，语义已文档化）。能量两侧都没有事务 API，保持尽力路径。两个冒烟各加 3 条护栏（把目标箱子填满 → `transferred:0` **且**来源数量不变），防止再次回归 |

### 11.4 已知语义差异（跨 loader 使用时要记住）

1. **流体单位**：Fabric `droplet`（81000/桶）vs NeoForge `mb`（1000/桶）。读 `server.status.fluidUnit`。
2. **`fluid.*` 的 `--index`**：Fabric 能对**单个罐**注/抽；NeoForge 的 `IFluidHandler` 是存储级接口，`--index` 只用于**选定并报告**那个罐，注/抽交给 handler 自己路由（`partAt()` 返回同一 handler）。物品承载的流体（桶）不受影响。
3. **`supportsInsertion/supportsExtraction`**：NeoForge 侧**流体**按**当前**罐状态推导（未满→可注、非空→可抽；`capacity<=0` 视为容量未知而不是“已满”）、**能量**取 `canReceive()/canExtract()`、**物品恒为 `true`**（`IItemHandler` 没有 capability 标志，逐槽 `isItemValid` 又依赖具体物品，硬猜反而误导）；Fabric 侧三个标志是 Transfer API / Team Reborn 的**能力标志**。所以 NeoForge 的这两个字段描述的是“现在能不能”，空罐会报 `supportsExtraction:false`、满罐会报 `supportsInsertion:false`——跨 loader 比较这两个字段没有意义（`storage --help` 与 skill 都写了这一点）。
4. **`reflect.*` 短类名**：NeoForge 只支持 FQN；名字相同时没有 `mojangClass` 字段。
5. **物品承载的 item 存储**：两个 loader 都返回 null（`storage.*` 不暴露 item-in-item），行为一致。
6. **Kotlin 运行时**：NeoForge 版需要 `mods/` 里有 **KotlinForForge 5.12.x**（Fabric 版需要 fabric-language-kotlin）——两者都不是自包含 jar。CLI 的 `mcdebug jar --loader neoforge` 只写 mcdebug 自己的 jar。
7. **`gui.hotswap` 的字节来源**：Fabric 用 Knot 的 pre-mixin 流水线（`getClassByteArray(name, true)`）；NeoForge 直接读 classpath 副本，mixin 由 `Hotswap` 显式 `transformClassBytes` 施加一次。结构改动免重启的边界（标准 HotSpot 只能改方法体）两端一致。审查补充：NeoForge 这条是普通 classloader 资源查找，**没有校验拿到的就是被加载的那份**；查找失败会退化成“用调用方给的文件字节”，可能与被加载的类不一致——dev 已验，生产环境属未验的脆弱点。
8. **`gui.stop` 的触发时机**：21.1 没有客户端停止事件，NeoForge 侧是 JVM shutdown hook（跑在新线程、客户端可能已在拆），Fabric 侧是 `CLIENT_STOPPING`（客户端线程）。现在 shutdown hook 先 `Minecraft.getInstance().execute{}` 到客户端线程执行清理（最多等 2 秒），超时/客户端线程已死才就地执行一次，失败写 WARN——不再静默 no-op。
9. **能量单位现在有协议字段**：同一个 `teamreborn:energy` 句柄，Fabric 侧是 Team Reborn Energy（E），NeoForge 侧是 `IEnergyStorage` 的 FE；`server.status.energyUnit` 报 `E`/`FE`（与 `fluidUnit` 同一模式，用户批准后新增的**加性**字段）。跨 loader 比能量数值前先读它。
10. **`fluid.extract` 返回的 `fluid`/`tank` 是"被选中的罐"**：NeoForge 上 `partAt(idx)` 返回整个 handler（差异 ②），实际抽出的液体由 handler 自己路由，多罐且液体不同时报告字段可能与实际不符（单罐/同种液体时一致）。

### 11.4b 冷读审查驱动的修复（两轮，2026-10-30/31）

两轮**无记忆**子代理冷读（第一轮：全仓 port 相关代码；第二轮：13 个接缝文件对 + `neoforge/build.gradle`）
+ 一轮跨 loader 差分（`scripts/parity-diff.py`，45 个 loader 中立任务打在 Fabric 专用服与 NeoForge dev 专用服上）。
审查的独立价值：接缝构造本身骗不过"两侧实现逐方法对比"，差分则骗不过"同一个请求打在两个实例上"。

| 来源 | 问题 | 处置 |
|---|---|---|
| 审查① | `storage.transfer` 在 Fabric 侧丢了原子性（见 §11.3 #12） | 加 `moveItemsAtomically/moveFluidsAtomically` 钩子，Fabric 恢复单事务语义；两冒烟各加 6 条护栏（专用坐标：目标填满 → `transferred:0` 且来源槽位不变） |
| 审查① | `supportsInsertion/Extraction` 文档说"按槽/罐状态推导"，代码里物品是常量 `true` | 实测澄清：**空箱子在 Fabric 与 NeoForge 都是 `true/true`**，所以"按槽推导"反而会制造不一致——按代码事实改文档（§11.4 #3），物品恒 `true`，流体按罐推导，能量取 `canReceive/canExtract` |
| 审查② | 物品承载的**能量**存储从不返回 `targetAfter`（Fabric 通过 item context 读回，NeoForge 侧漏了） | `NeoForgeEnergyStorage` 记住宿主栈并在 `itemAfter()` 返回 `copy()`；vanilla 没有能量物品，只能用代码审查判定（已记录为不可真机验证项） |
| 审查② | `itemStorageOf(container, side)` 忽略 side（Fabric 的 `InventoryStorage.of(container, side)` 会走 `SidedInventory` 规则） | side 非空且容器是 `WorldlyContainer` 时改用 `SidedInvWrapper`，否则 `InvWrapper`（与 Fabric 的退化行为一致） |
| 审查② | `fireUseEntity(hitResult = null)` 在 NeoForge 上伪造成 `EntityInteractSpecific`，`EntityInteract` 监听者永远收不到 | 按 hitResult 分流：有命中向量走 `onInteractEntityAt`，没有走 `onInteractEntity`（就是真实数据包的两条路径） |
| 审查② | `processResources` 只点名拷两个 mixin 配置，将来加共享资源会静默漏出 jar | 改成整树同步 `src/main/resources`（排除 `fabric.mod.json`/`modid.mixins.json`）+ `src/client/resources` |
| 差分 | `storage.get`/`snapshot.capture` 里箱子槽位 `capacity` NeoForge=99、Fabric=64 | `IItemHandler.getSlotLimit` 对 vanilla 容器返回 `Container.getMaxStackSize()=99`，既与 Fabric 不一致也物理错误（插不进 99 个）；改成 `min(槽上限, 该栈真实上限/64)` |
| 差分 | 4 项属**已文档化**差异（`modLoader` 名字、Fabric 生产环境 runtime 是 intermediary 的 `class_xxxx` 名、短类名解析） | 在 `scripts/parity-tasks.json` 里标 `expect_diff`/`ignore`，差分报告区分 EXPECTED 与 DIFF，避免把"设计差异"当 bug |

用户批准后的共享代码收尾（2026-10-31，两个 loader 同步改）：

| 项 | 改动 |
|---|---|
| `craft.craft` 的 grid 单元格写成字符串 | 从 -32603（`IllegalStateException`）改成 **-32602**，消息点名 `grid[i]` 并给出正确形状 |
| `reflect get <大集合>` 的截断不可见 | 摘要分支补 `truncated:true` + `returned:N`（`size` 一直是全量）；内联数组分支的 `$truncated` 计数保留 |
| `reflect set` 只认 `--field` | 现在接受 `--path`，末段当作字段名（`overworld.dayTime` → 目标 `overworld`、字段 `dayTime`），末尾是方法/下标则报用法错误；`reflect --class` 帮助改成"Fabric 支持短名/intermediary，NeoForge 只认 FQN" |
| NeoForge 满罐报"不可注入"但实际能注入 | 罐 `capacity<=0`（容量未知/无限）不再算"已满"；两个 `supports*` 的语义写进 `storage --help` |
| `server.status` 缺能量单位 | 新增加性字段 `energyUnit`（`E`/`FE`），与 `fluidUnit` 对称 |

差分现状：**53/53 任务一致（0 个非预期差异）**——49 条 loader 中立任务 + 4 条夹具复位（`reset` 标记，不参与比较）+ `world.interactEntity` 两条分支（有/无命中向量，正好覆盖 `fireUseEntity` 的两条路径）；4 项已文档化差异标为 EXPECTED。
复现：

```bash
# A = Fabric 专用服（发布 jar + fabric-api + FLK），B = NeoForge dev 专用服
python3 scripts/parity-diff.py --a <fabric>/mcdebug/socket --b neoforge/run/mcdebug/socket --json /tmp/parity.json
```

注意两点（踩过）：① 服务端每条连接的第一行是 `server.ready` **通知**，差分工具必须按 id 找响应，否则会把握手当结果、把"全部一致"错报出来；② 任务表开头有 4 条复位任务（清方块夹具 / 杀猪 / 清掉落物），否则上一轮残留会让"状态型"任务假失败。

### 11.5 门禁状态

| 门禁 | 状态 |
|---|---|
| G1 服务端开服 + 契约 79/79 + `storage.*`/`fluid.*` 断言 | ✅ `scripts/neoforge-smoke.sh`（41/41 + 79/79） |
| G2 客户端 mixin 全绿 + 截图/点击闭环 | ✅ dev 实例真机跑通（§11.2 第 3 条）**且** 消费者项目里用发布 jar 复验过（§11.2 第 4 条，含 in-world 截图） |
| G3 Fabric 零回归 | ✅ `./gradlew build`（147 测试）+ `scripts/fabric-smoke.sh` |
| G4 发布面自包含 + 单 loader 安装 | ✅ 两个 `verifyJarSelfContained` + `mcdebug jar --loader` + **纯 NeoForge 消费者项目真机安装验证**（§11.2 第 4 条：服务端 79/79、客户端 0 mixin 失败、进世界截图） |

### 11.6 仍未做 / 建议下一步

1. ~~消费者项目真机复验~~：已完成（§11.2 第 4/5 条，服务端 + 客户端 + managed session 三条路径）。
2. `gui.*` 的**回归用例**（hover/scroll/key/type/drag/settle/compare/reloadScreen/hotswap）只验到了 click/screenshot/screen 三件套，其余方法在 NeoForge 上属于“注入点已生效、路径未逐个跑”。
3. ~~session managed launch 只认 Fabric~~：已按项目类型分流并真机验证（§11.3 #11）。仍需注意：MDG 项目的 `runClient` 任务名与 Loom 相同（都是 `runClient`），多子项目时限定任务名（`:sub:runClient`）的规则对两者一致。
4. 若要把“同一份发布 jar 同时支持两个 loader”做掉，可在 NeoForge 侧也读 `fabric.mod.json`？——不建议：两套 loader 的元数据/入口语义不同，保持两份 jar（CLI 已内嵌两份）更清晰。
