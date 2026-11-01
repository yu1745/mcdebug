# P0/S1：Fabric 实施任务契约（阶段A，一个worker）

## 角色、边界、版本

执行者是现有worker `8d1eb4a4-d251-47c`，正式Agent请求 `openai-codex/gpt-6.1-terra`、thinking low，depth2不再递归。模型身份用调用参数及宿主解析元数据；不要求LLM自证。源 `/home/wangyu/.cache/mcdebug-port-skill-eval/reference` 固定commit41fde020636b79154e1c454a893962c639d89e1b，永远只读；reference-dev是已验基线副本，不改源码适应目标。

唯一目标写入 `/home/wangyu/.cache/mcdebug-port-skill-eval/fabric-target`，从固定reference创建独立checkout，先确认不存在，保留142 Java文件及资源/许可证。可写本隔离root的独立Fabric consumer、game-fabric、game-consumer、evidence、sessions。不得改mcdebug、全局配置/插件、邻近工程、生产或既有玩家存档，不commit/push/PR/Release。A独占roadmap与验收，worker不改范围/状态。

必读本repo AGENTS、skills/neoforge-fabric-port/SKILL.md与两个references、skills/mcdebug/SKILL.md、本目录iron-furnaces-roadmap.md、本任务单，已完成baseline-report与R1证据。搜索仅指定源码目录rg/fd，排除~/server /backup ~/win及子目录，禁止宽父目录扫描；超时立即停并缩小。MC/API查本地源码不猜、不javap。

## A已确定的纵向闭包

保留用户真实普通铁炉路径：注册/物品→真实客户端放置（setPlacedBy及玩家炉列表）→空手打开带BlockPos菜单→GUI从玩家库存投入原矿铁与煤→自然tick加工→DataSlots同步真实火焰/进度→GUI取出并服务端玩家Inventory守恒→保存owned stop/start→库存/设置保留且继续加工。输出满槽不消耗输入是唯一附加边界。不对其它炉型/增强/工厂/生成器/自动化/跨loader存档互通宣称验收，也不删除这些共享源码换假绿。

源owner及骨干行号已列roadmap。补充：BlockIronFurnaceBase底部getTicker通过checkType→BlockIronFurnaceTileBase.tick；默认铁炉160 ticks，配方1原铁→1铁锭，煤burn经160/200缩放，原版基线放置后Settings=[2,1,4,4,4,4]。菜单机器0输入1燃料2输出，全部19机器槽不能删。SlotIronFurnace.onTake/onQuickCraft调用unlockRecipes与craftedBy，需保留XP/配方解锁，不是只remove。FurnaceSettings默认autoIO=[0,0]、redstone=[0,0]；但placeConfig覆盖侧设置。BE更新、玩家attachment、配置/JEI是必要构造/初始化前置，不可no-op。

## 平台路线与不变量

- MC保持1.21.1/Java21/Mojang mappings；Fabric Loom及loader/API选可用同小版本稳定组合并记录。已有gradlew继续使用，可改目标构建，不换构建系统。build/check完整通过，NO-SOURCE如实报。禁排除必要源码、伪造net.neoforged、恒定返回/吞异常/禁mixin假绿。
- 原包名/类结构/算法/注册ID/NBT/组件/资源保留，原地只改平台边界。DeferredRegister用Fabric/vanilla注册及Supplier惰性引用，别另造领域模型。common/client entrypoint分离，客户端类不能进入专用服初始化。
- menu用Fabric ExtendedScreenHandlerType与ExtendedScreenHandlerFactory传BlockPos及原19槽/DataSlots；玩家Slot改vanilla Inventory/Slot。源码参照 `/home/wangyu/server-v2/upstream/fabric-api_source_1.21.1/fabric-screen-handler-api-v1/net/fabricmc/fabric/api/screenhandler/v1/`。不要因blockpos不同步换成假菜单。
- COMMON配置保留键/默认/范围及加载时机、.get()语义。允许在ironfurnaces自己的配置边界用小型typed值替代ModConfigSpec，必须真正读写配置并校验，不把所有值写死。禁止把整套NeoForge配置库克隆成兼容框架。
- attachment用Fabric持久attachment（Codec/持久边界）或针对玩家的最小NBT mixin接缝，保留炉列表和显示配置的保存/重启；仅改平台存储，不删setPlacedBy调用。参考Fabric attachment源码在明确目录fabric-data-attachment-api-v1。
- 燃料用FuelRegistry.INSTANCE（已有原版燃料，允许Fabric扩展）；保留上游160/200、增强和crafting remainder算法。必须查目标MC Item/ItemStack真实剩余物品签名，不生搬NeoForge stack扩展。NeoForge smelt事件不可照搬；保留vanilla craftedBy/配方XP，跨生态事件无同名等价可作为明确平台差异，不能删核心onTake。
- BE tag/packet用vanilla loadAdditional/getUpdatePacket/sendBlockUpdated接缝保留同步；动态lit光源以vanilla block properties/state函数实现，不误删照明。网络payload注册用Fabric networking，同方向/codec/主线程执行，所有GUI已有按钮仍接真实settings；不要静默不注册。
- 保留JEI：目标用同MC的Fabric JEI common-api/fabric-api及runtime artifact，插件loader检查换FabricLoader，燃料走上述统一边界。不可删插件/依赖换build绿。
- capability的item适配围绕WorldlyContainer及Fabric Transfer ItemStorage/SidedInventoryStorage，保持side与槽限制；模拟必须真不改状态，不把两步非原子搬运冒充事务。energy用Fabric适用的EnergyStorage边界（若引入TeamReborn energy-api记录版本/单位），FEnergyStorage继续保留容量/限速/保存/通知。可保留领域内最小接口适配但不伪造NeoForge包；不得把autoIO/energyOut变空。此部分如出现新语义或范围超出，提交具体owner/错误给A裁决，不自己删除非目标。
- 对无法直接映射的上游patch，查 `/home/wangyu/server-v2/upstream/mc_source_1.21.1_fabric/common/net/minecraft/`。未核实可见性不要拿旧AT结论。参考目录不能写。

## 执行/验收

先实现P0并build，若阻塞给首个真实根因与当前diff，不只列import清单；可自愈编译细节。两轮同一错误无新证据或必须改变语义时上报，不无限盲改。

B1已用现有CLI0.8.0；只用mdb session start/stop、独立registry/gameDir与demand；显示可用先核实，必要时自己的Xvfb，清理自有，不接管用户玩家。初始Fabric世界可UI新建或正常停服后离线复制测试存档（不得与原版同时开同一目录）。选空白位置，不用跨loader数据迁移当验收前提。mcdebug发布jar只装目标消费者mods，不写mcdebug自己的run/mods。

最终S1最小重放：
1. 玩家inventory服务端NBT确认炉1，真实gui.openBlock对地板face up持炉（不clear-hand），读回炉/朝向/初始化及炉1→0；NO_GUI_CHANGED不等于放置失败，读真实结果。
2. 空手打开菜单，gui screen核槽，give只准备原铁/煤，GUI真正投入，原始JSON全留；输入与燃料服务端读回。
3. 中途CookTime介于0和160，截图移光标/无tooltip，火焰及箭头可见；worker用read打开PNG写观察。禁止写预期输出/进度、be.tick证明整路径；用有界wait.until，字段先读；sprint仅自然引擎可用。
4. GUI取1铁锭放玩家槽；服务端炉及玩家Inventory合计资源守恒，输出槽拒绝由实际mayPlace保留。
5. 同一满槽边界，64铁锭必须由实际加工得到；输入维持且无复制丢失，唯一新增必要边界，不扩矩阵/mutation。
6. 正常保存owned stop/restart，核原库存/设置/玩家产物，并移除满输出后继续加工一件，不能只看到旧NBT。
7. 最终build/check，专用服侧隔离启动；独立Fabric consumer无目标dev classes、安装最终jar及必要runtime依赖，核心放置/GUI/加工/取出重放并read PNG。说明Gradle消费者重映射，不冒充未经重映射生产验收。

只新增针对真实失败的必要回归，不新造测试框架。所有命令帮助以当前CLI为准。每次请求检查业务ok/changed/settled而非exit0。

交付evidence/target-report.md、准确git diff与stat（不commit）、平台差异表、build日志/sha256、原始RPC/PNG（worker已read观察）/latest.log、重启与consumer证据、未验证项及清理。状态只能待审查。A亲自审diff/源码/原始状态/PNG再验收。不要修改通用skill，worker将歧义/模型错误/工具问题/环境阻塞/正常返工分类交A。
