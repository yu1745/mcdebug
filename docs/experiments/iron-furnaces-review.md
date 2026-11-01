# 阶段A审查与停止检查点

## Codex 继续审查（2026-11-01，覆盖旧停止状态）

### J1 / A1 复审：普通铁炉纵向切片通过

主代理已读取 j1-build、identity、prepare、place、open-prepare、progress、take、server、server-rpc、server-stop、problems-before、client-process、process-final 原始证据及 worker 报告，独立打开 `j1-progress.png`。观察到清晰亮火焰、约半程白箭头、原铁/空输出，无tooltip；同期权威 NBT CookTime82/160。

最终 jar 哈希 `39b8a0a70c5a52cdaf919dc05206b946601d83cc24444984289e9cdfcc92e707`，入口/内嵌energy/refmap已直接检查；build/check成功，测试任务NO-SOURCE。独立 consumer 原生 production task 的运行身份实测 development=false、intermediary，mod origin为consumer/mods最终jar；真实命令行为KnotClient，不含目标或consumer的build/classes。不是开发重映射consumer冒充生产验收。

生产客户端新炉(-7,163,4)真实放置、初始设置、物品消耗、attachment新增、空手打开55槽、GUI投入1原铁/1煤、自然加工、GUI取出均有原始数据；炉空，玩家旧64+新1守恒。problems前后均7条既有噪声，无核心操作新增错误；声音未验。专用服已在SERVER环境加载并达Done、正常保存退出；未加载坐标setblock失败不是创建证据，不要求为侧隔离启动门禁额外重跑。

客户端/专用服/各wrapper及自有Xvfb最终均已退出。产品源码在V2/J1未再修改，已审V2重启/满槽证据仍适用。S1/S1R/J1与本次A1审查通过，仅限普通铁炉纵向切片；不宣称全部炉型或全mod语义等价。前述爆炸/动态光照未验差异仍保留为非本切片限制。

通用skill尚未冻结：仍缺按最终skill/契约在干净目标上独立重放的证据。本轮不开始阶段B、不commit/push。下一动作是独立重放实验设计和执行，不能把本次已带答案上下文的worker续跑算独立重放。

### V2 复审：S1R 通过，J1 待执行

主代理已逐项读取 `v2-boundary-before.log`、`v2-restart-boundary.log`、`v2-restart-after.log`、`v2-continue.log`、`v2-continue-after.log`、`v2-place2.log`、`v2-attachment-list-before.json`、`v2-player-after-restart.log`、`v2-build-hashes.txt`、`v2-process-final.log`，结合此前独立查看的 v1 中途PNG。

- 正常重启后炉输入2/煤2/输出64/Settings一致；tick126→334及完整 timeout stderr/exit2证明200自然tick边界观察，输入未耗。
- GUI取64锭至玩家，后续 CookTime80/160，最终炉输出2、玩家64，总铁资源66守恒。取出触发 RecipesUsed 清零，worker错等>=65的超时属观测字段语义误判，未作为成功证据，也不据此改产品代码。
- 同一UUID `[507285622,1613313734,-2072650440,-771551905]` 再次正常重启，玩家64锭保留；真实放置位置(-6,163,5)从服务端方块、物品消耗到 attachment 列表写入和重启保留均有原始证据。
- 临时固定用户名配置恢复后的hash等于原build.gradle，正常stop后owned进程检查为空；没有更改领域源码。旧死亡造成原列表为空不被伪造修回。

S1R 本轮通过，发布物验收仍未通过。已向同一 Terra/low worker 派发 `iron-furnaces-consumer-task.md` 的 J1。通用 skill 仅在 verification 补“玩家持久化验收固定并核对UUID”，依据是实测Loom随机用户名与死亡干扰，以及固定UUID后重启真实数据保留。

用户确认 Pi 已停并授权继续两项职责：Sol 主代理派 Terra/low worker 执行、基于双方理解优化通用 skill。当前执行检查点以 roadmap 顶部为准；下方“停止于 Config/未运行目标”的记录仅描述首轮，不能作现状。

主代理独立读取 C2–C11 报告及实际入口、构建、菜单、放置、网络、库存和能量接缝 diff/源码。C11 compileJava 成功；尚不以此关闭完整构建和运行门禁。独立查看 `v1-progress.png`，对照 `v1-progress2.log` 的 CookTime83/160，确认清晰火焰与中途进度。`v1-player-after-take.json` 记录玩家1锭；`v1-bulk-retry-nbt.json` 记录炉输出64锭；满槽下一步却出现 DeathScreen，待 V2 补完边界和保存重启。

审查范围说明：入口 common/client 已分离；菜单保留19机器槽、玩家槽改 vanilla Slot；ExtendedScreenHandlerFactory 返回真实 BlockPos；输出槽保留 craftedBy/解锁逻辑，仅移除 NeoForge smelt event；item transfer 使用真实嵌套 transaction。运行证据仍需对应各项接缝，隔离脚本有桩的结果不等于真实 lookup 验收。

非 S1 未验差异：C8 将百万炉爆炸处理从 NeoForge 逐实体 knockback 钩子移至 finalizeExplosion HEAD，触发条件和时序并不等价；C10 光照值由 vanilla 状态缓存，配置重载后动态变化未验。未据此批准这些行为等价，也不扩展当前普通铁炉测试矩阵。最终报告须保留此限制，不能声称整个模组移植通过。

skill 调整：在 delegation 及入口补充单 owner 闭包和本轮/最终证据边界，来自大任务未收敛后 C1–C11 首因推进的观察；未证明任务粒度与上下文长度各自的因果贡献。quick_validate.py 通过；行为效果继续在 V2/后续发布物闭环观察。

## 身份/委派

A由外层请求openai-codex/gpt-6.1-sol / low；全部worker调用明确openai-codex/gpt-6.1-terra / low，正式Agent，同一实现worker `8d1eb4a4-d251-47c` 连续resume，无更换渠道/模型或进程绕过。宿主未附额外解析元数据。模型自述不作身份证据，无法自证不构成阻塞；极小预检turn-limit包装不是能力结论。scope补白名单后的正式调用与read返回已证明递归可用。

## B1原版基线：审查通过作为参考，不代表port

A已读原始baseline-report、boundary-full-after.json并亲自read世界world4.png、burning.png、restart-furnace.png、r1-progress.png：
- 世界1280x720、森林叶子前、完整HUD/空快捷栏、右上移动教学，世界画面不是菜单证据。
- burning.png灰面板Iron Furnace、原铁16/煤7，亮火焰；煤tooltip遮箭头，不能算清晰进度验收。
- restart-furnace.png输入2原铁/输出64锭/煤、火焰与玩家栏铁锭可见，与边界NBT相符。
- r1-progress.png亮火焰、箭头约半程白色、无tooltip遮挡；同期worker记录CookTime85/160。

R1（缺证据）要求放置/取出服务端玩家Inventory守恒及无遮挡中途进度，worker补了炉1→0、炉内1/玩家0→炉内0/玩家1，原始r1证据在cache/evidence，关键PNG A已读；关闭视觉缺口，最终Fabric同样必须服务端原始JSON审查。
R2（记录矛盾）要求清除baseline报告旧“未运行/当前阻塞”的互斥描述，worker已整理；不能把早期阻塞当当前结论。
原版既有build已通过，14条上游deprecation，test NO-SOURCE；KFF分拆依赖问题已解决。owned sessions/Xvfb均报告停止，精确PID检查为空。原版是dev classes，未声称发布物consumer。

## P0目标：未通过，当前停止

A直接执行git status/diff审查，而非只转述worker：target仅改build.gradle、settings.gradle，新增fabric.mod.json与未接线FurnaceConfigFile.java。tracked diff 22 insertions / 199 deletions；build改Loom1.10-SNAPSHOT（日志解析1.10.5）、官方映射、Fabric loader/API、JEI Fabric编译/运行依赖、保留generated资源。元数据入口仍指NeoForge类，不可启动。Config/Registration/menu/玩家持久化未改，compileJava依然因NeoForge Config类型缺失而失败。没有领域算法删除/no-op假绿；也没有完成任何Fabric行为。

R3（执行未开始/后续未持续推进，阻断）：首次完整P0/S1任务只clone+清点后返回；明确缩至P0a后仅构建元数据；再明确继续Config真实接线后只新增未接线辅助类，重复编译同一Config错误，未修实际owner。三次纠偏仍停在已授权且明确的下一步，没有新语义阻塞。按连续失败无新证据停止规则终止盲目resume，不再用同样提示耗费下一轮，不替worker亲自实现、不换强/弱模型接管。该停止分类为**本次worker执行未收敛**，不是技术不可移植结论，不是环境scope故障，也不能概括为模型总体能力结论。

未运行目标build/check、客户端/专用服、加工/GUI/重启、最终jar独立consumer。当前补丁不能使用或发布。target-report明确同样未完成；保留证据与工作树供外层决定下一步。没有提交、push；目标阶段无游戏进程。

## 通用skill调整与依据

1. 身份证据规则：用户直接纠正把LLM自证型号当阻塞的误区，已将SKILL改为调用参数+宿主元数据，不要求模型自证。成功正式Agent/read已重放；无额外身份预检。
2. 工具可发现性规则：worker基于open-block名字/help概述误判没有真实放置，A查GuiOps.kt:197–282与CLI clearHand=false，实际gameMode.useItemOn；同一worker重放放置成功，NO_GUI_CHANGED仍读回真实铁炉/初始化，后续完成GUI加工取出。已在通用反馈规则补“先核实调用路径/默认参数；后续视图失败不否定动作，读权威状态”。通用条文不含具体模组或专用代码。
3. 不因执行不收敛堆新skill规则：现有skill已明确真实owner/持续反馈/连续失败停止，因此R3按执行问题记录，不凭想象增补。

未独立干净重放冻结skill，阶段A未达到冻结/通过条件；不开始阶段B。

## 下一步由外层裁决

保留完整纵向契约，不能削成只编译或只炉GUI。可审查本次worker执行为何反复过早交接、决定是否允许后续不同任务编排；本轮不擅自另开完整port或换模型。若恢复，应从同target未接线Config和首个compile错误开始，而非重做参考游戏基线。当前不存在必须用户额外授权的API语义阻塞；真正缺少的是可持续推进的实施结果。

证据根 `/home/wangyu/.cache/mcdebug-port-skill-eval/evidence/`，核心baseline-report.md、target-report.md、target-compile-{1,2,3}.log、target-tracked.diff、target-config-file.diff。原始任务单与roadmap位于本目录。可信后代token/墙钟明细宿主没有附回，不填造数值或零成本。
