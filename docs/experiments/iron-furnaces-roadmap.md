# Iron Furnaces：阶段 A roadmap

## Codex 接管检查点（2026-11-01，优先于下方历史记录）

**最终本轮检查点：S1/S1R/J1与本次A1主代理审查通过（普通铁炉切片）。** J1原始证据与PNG由主代理独立复核，生产客户端实测 development=false / intermediary / consumer最终jar，无target dev classes，实际放置—GUI加工—取出守恒；专用服侧隔离启动和保存退出通过。V2/J1无产品源码更改；所有自有游戏、wrapper和Xvfb已退出。详细裁决见review的J1/A1节，worker原始报告 `evidence/j1-report.md`。

通用skill本轮两项改进为owner闭包派发与玩家重启固定UUID，格式校验通过。**尚不冻结skill、不开始B：下一步是干净目标独立重放**，不得复用本轮已解补丁/逐项答案当独立能力测量。其它炉型/声音/爆炸钩子差异/动态光照等未验范围保留；没有commit/push。

**最新继续结果：V2/S1R 主代理复审通过；同一 Terra/low worker 已转 J1。** 具体原始证据与裁决见 review 的 V2 节。`j1-build.log` 完整 `build check` 成功，测试相关任务 NO-SOURCE；最终 `ironfurnaces-fabric-1.21.1-4.3.2.jar` SHA256=`39b8a0a70c5a52cdaf919dc05206b946601d83cc24444984289e9cdfcc92e707`。主代理打开 jar 核实两个入口、配置/mixin类、mixin refmap 和嵌入 energy4.1.0 均存在。独立专用服/生产客户端尚待运行；不宣称 J1 或阶段 A 通过。

用户确认原 Pi 已停止，并指定当前 Sol 主代理继续承担 worker 派发与通用 skill 反馈优化两项职责。原 Pi 宿主及交接记录中的目标游戏/Xvfb PID 已核实不存在；保留两个仓库全部未提交改动。Codex 已以显式 `gpt-6.1-terra` / `low` 参数派发唯一 worker `s1_resume`，不复用 Pi agent ID。

- B1 原版基线已审通过，不重跑。
- C1–C11 已实施；C11 compileJava 成功不等于完整 build/check 或发布物验收。
- 主代理已独立查看 `evidence/v1-progress.png`：Iron Furnace、原铁、明亮火焰、约半程白色箭头、空输出，无 tooltip 遮挡；`v1-progress2.log` 同期服务端 CookTime=83 / total=160，菜单55槽（19机器+36玩家）。`v1-player-after-take.json` 显示玩家持有1铁锭。
- `v1-bulk-retry-nbt.json` 有实际加工所得64锭；`v1-boundary-screen.json` 是 DeathScreen，尚无完整满槽边界或正常保存重启证据。上述证据仅支持部分 S1，不据此宣称 S1R 通过。
- 当前派单 V2：恢复已有 fabric-v2 / sessions-fabric / game-fabric，完成满输出不耗输入、正常保存重启、库存设置保留并继续加工；不改代码，若发现缺陷先提交 owner 与证据。新证据 `v2-*`，交付 `v2-report.md` 待主代理审查。
- 待办：审查真实改动与 V2；完整 build/check、隔离专用服、最终 jar 独立 consumer、主代理复审。阶段 A 未通过，不启动 B，不 commit/push。

通用 skill 本轮仅补 owner 闭包派发及阶段证据边界，依据是旧大任务未收敛、C1–C11闭包使首因推进并编译成功；不将这次观察归因成已证明的上下文长度效应。

## 最新权威状态（覆盖下方早期准备记录）

正式terra/low Agent递归已通过，scope不再阻塞；不要求worker自证模型，宿主未附额外解析元数据。B0/B1已执行原版build、真实放置/GUI投入/自然加工/取出/满槽/保存重启，A已打开关键PNG。P0/S1经同一worker多次resume仍未接通Config等真实owner，compileJava失败；按连续无新证据停止规则暂停，不代worker完成、不换模型重开port。准确审查R1/R2/R3、修改依据和证据见 `iron-furnaces-review.md`；完整任务单见 `iron-furnaces-worker-task.md`。目标无运行进程，原版owned进程已清理。未通过Fabric纵向切片、发布物或skill冻结，阶段B不做。下方是准备时的历史路线，不将其“待预检/scope阻塞/无PNG”视为当前状态。

## 当前检查点

- 外层通知 enabledModels 已补入terra/luna后，正式 Agent terra/low 极小预检不再拒绝。worker返回 read 可用并已读 delegation 前8行；它无法自证实际provider/model，故只记录请求值与工具接受，不声称额外身份验证。随后正式启动 B0/B1 worker：`8d1eb4a4-d251-47c`，请求 `openai-codex/gpt-6.1-terra` / low，depth2不得再委派；只执行原版基线，不实现Fabric。
- A继续只读源码调研/roadmap；B0/B1已委派执行中，尚无port改动或已审查运行/PNG证据。worker任务明确独立reference-dev、原有build、JEI保留、CLI帮助、显示、owned demand session、UI创建存档、真实放置加工取出重启和一个满槽边界，交付原始证据并清理。
- 已确认隔离根原先不存在，再克隆 `Qelifern/IronFurnacesNeoForge` 的 4.3.2，检出固定 `41fde020636b79154e1c454a893962c639d89e1b`。路径 `/home/wangyu/.cache/mcdebug-port-skill-eval/reference`。该目录自检出后只读使用，不在里面构建或修改文件。
- clone 命令完成，输出 `HEAD is now at 41fde02 4.3.2`；工具日志 `/tmp/pi-subagents-jobs-3Vpeny/bglll4d9a.log`。
- 根目录 AGENTS 已在上下文读取；已读通用 skill、delegation、verification、repo mcdebug skill、gh-cli skill、实验计划。没有足以修改通用 skill 的失败/重放证据。

## 固定基线与隔离布局

源 MC1.21.1、NeoForge21.1.209、Java21、MDG2.0.89；源码 `build.gradle` 已核实 Java21/MDG2.0.89、JEI common-api/neoforge-api compileOnly、NeoForge JEI runtimeOnly、Parchment、生成资源及元数据任务。已用限定rg核实 gradle.properties：MC1.21.1、NeoForge21.1.209、Parchment2024.11.17（MC1.21.1）、JEI19.21.1.305。这些是源码版本，运行版本待B1。

计划布局（除 reference 外尚未创建）：`reference-dev/`（原版独立可构建副本，保持源版本）、`fabric-target/`（worker 唯一可写目标）、`consumer-fabric/`（最终 jar 独立消费者）、`game-ref/`、`game-fabric/`、`game-consumer/`、`evidence/`、`sessions/`。源码 reference 不进入这些工程的运行依赖。保留版权和 Apache2 文本；无提交、push、PR、Release。

## 已读 owner / 调用链

以下路径相对 reference：

1. `IronFurnaces.java`：构造器注册 COMMON Config、ModSetup、ClientSetup、Messages、Registration；item/energy capabilities 对整个炉族暴露。不是单个铁炉自足入口。
2. `init/Registration.java`：DeferredRegister 覆盖 block/item/BE/menu/recipe/component/attachment。`PLAYER_FURNACES_LIST` 与 `PLAYER_SHOW_CONFIG` 是持久 attachment；不能为了简单放置或界面删除调用。JEI 类型还出现在内部 RecipeTypes 中。
3. `blocks/furnaces/BlockIronFurnaceBase.java:94–126`：真实放置设朝向；setPlacedBy 设名称、totalCookTime、placeConfig、玩家炉列表；useWithoutItem 分流增强/外观/复制/普通菜单。`:224` 左右 interactWith 使用带 BlockPos 的 openMenu，并重置 GUI setting10。
4. `tileentity/furnaces/BlockIronFurnaceTile.java`：铁炉 BE 指定注册类型、Config.ironFurnaceSpeed/tier、菜单 owner；不是可替成 vanilla furnace 的空壳。
5. `tileentity/TileEntityInventory.java`：WorldlyContainer/MenuProvider/Nameable、19槽底层库存；NBT ContainerHelper 与 CustomName；BE更新包调用 NeoForge 方法。Fabric 需真实同步边界，不是删除 update hooks。
6. `container/furnaces/BlockIronFurnaceContainerBase.java:49–85`：菜单保留19机器槽（普通0输入/1燃料/2输出，3..5增强，6生成器，7..18工厂）再接玩家库存；InvWrapper/SlotItemHandler 是平台缝。`:159–209` DataSlots 同步 BurnTime、recipesUsed（本处实际为燃烧标尺）、CookTime、totalCookTime；其它模式也有必要构造前置，不能静默裁剪源码。
7. `BlockIronFurnaceTileBase.java:1020–1165`：普通模式关闭能量收发；server tick 减燃烧值、选配方、canSmelt；燃烧时长为上游燃料值乘 getCookTime()/200（并受增强影响）；保留 crafting remainder；有效时 cook++，达到 total 后 smelt、autoIO、dirty；更新 LIT。
8. 同文件 `:1687–1720`：输出组件相等且不超 min(maxStack,64) 才可加工；产物由配方获得，输入减1，记配方/XP；湿海绵与桶有特例。`:1811–1903` 保存 Items/CustomName（父类）、BurnTime/CookTime/CookTimeTotal/Augment/Jovial/设置/Energy/RecipesUsed；燃料依赖 EventHooks 与 NeoForge stack 扩展。loadAdditional 先读部分字段再读父库存，需原版重启对照，不擅自修上游。
9. `init/ClientSetup.java` 注册铁炉 Screen；`gui/furnaces/BlockIronFurnaceScreenBase.java:253–292` 按模式绘背景，火焰 getBurnLeftScaled(13)、进度 getCookScaled(24)。Screen 同时有配置按钮与设置包，不能用纯 vanilla GUI 冒充。

## 首轮纵向切片 S1（拟定，待原版基线后可派发）

用户路径：`ironfurnaces:iron_furnace` 正常注册 → 玩家实际放置 → 空手打开真实菜单 → 玩家库存向输入/燃料槽放入原料与燃料 → 自然引擎 tick 加工并观察中途火焰/进度 → GUI取出 → 保存、owned stop/start同一隔离世界、重新打开读回。

代表输入拟用原矿铁+煤，最终 ID/数量/燃烧时长/默认速度必须经完整配方/config读源码及参考端确认。禁止通过写输出或 CookTime/BurnTime 伪造成功。可用 give 准备玩家物品和平台/传送；最终放置必须真实 use-item 路径（需核实当前 CLI/SDK 能力），不能用 setBlock 替代 setPlacedBy 验收。

必须保留：注册ID/资源/包结构/加工算法/组件/NBT/菜单槽位规则/配置默认与时序/XP取出逻辑/真实设置同步。Fabric 路线：vanilla Container+Slot 保留领域owner；Fabric registry与extended menu opening适配；配置值保留语义但解除 ModConfigSpec 类型耦合；attachment 采用持久玩家数据边界；燃料和 crafting remainder 查目标官方源码/API再定；网络包保持方向/线程/有效性检查；原版库存和能量平台接口不许 no-op。

非目标是**未验收**其它炉型、generator/factory、增强、侧面自动化、跨loader存档迁移、多人互联；不代表获准删除这些源码或功能。共享owner使某非目标成为普通炉必要前置时，必须实施真实适配；若超过可收敛范围，升级主代理，不能靠 excludes 获得假绿。JEI 不能静默删除，Fabric 对应依赖/接线需核实后裁决。

## 有界里程碑

| ID | 行为/owner | 前置 | 门禁/证据 | 状态 |
|---|---|---|---|---|
| B0 | 工具/显示/隔离目录 | 无 | Java/CLI help、显示可用；不接管现有进程 | 待验证 |
| B1 | 原版参考构建/运行 | B0、reference-dev | 原有gradlew build；mdb owned demand；UI建世界；身份/RPC/PNG且A读图 | 待验证 |
| P0 | Fabric构建/入口/注册/资源及共享必要缝 | B1、正式terra预检 | 目标既有必要build/check，侧隔离启动，无源码删除假绿 | scope阻塞 |
| S1 | 铁炉放置—加工—真实GUI—取出 | P0 | 原始RPC/权威库存/中途与完成PNG/点击回执/问题游标 | 待执行 |
| S1R | 拒绝/满槽与保存重启 | S1 | 只补一个直接必要的输出满槽守恒边界；重启保留库存/设置，继续实际加工 | 待执行 |
| J1 | 最终jar独立consumer | S1R | 无目标dev classes，build产物hash；同核心路径；标明dev重映射 | 待执行 |
| A1 | 主代理审查/关键重放 | J1 | 实际diff、上游、原始状态和关键PNG独立打开；R-ID全部关闭才验收 | 待执行 |

不扩展测试矩阵、不做mutation；已有项目build/check门禁不可略。无专用单测时仅为真实适配故障补直接必要回归，不创建框架。

## 待补调研与任务单发布条件

先继续读 `Config`/`FurnaceSettings`、注册具体铁炉与菜单工厂、Block#getTicker、tick前半生命周期/autoIO默认、槽位输出XP/quickMove、Messages及两类设置包、JEI依赖链、access transformer；核实本地MC/Fabric源码真实可见性。当前仅完成调用链主要骨干，**此文不是最终可执行实现任务单**。

白名单恢复后先一次极小terra预检，记录实际model/工具与读PNG能力；worker固定depth2不再委派。随后分配B0/B1环境执行任务，A核对基线和关键PNG后再发P0+S1完整实现契约。所有任务透传精确源码范围rg/fd，禁止扫描~/server、/backup、~/win和宽父目录，超时停并缩小；参考只读，不改邻近工程、不删锁、不杀共享daemon；只用mdb session start/stop管理客户端。

## 清理与结论

本检查点只下载源码并阅读，未启动任何游戏/Gradle构建，无session、无PNG可读。阻塞仍为scopeModels，不做模型能力/skill有效性结论。通用skill未调整，等待真实失败与重放。
