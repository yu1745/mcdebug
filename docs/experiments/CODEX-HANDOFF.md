# Pi → Codex 工作交接（入口）

更新时间：2026-11-01；本文件由外层主会话编写。不是 Codex session 格式转换，不可把 Pi agent ID 当 Codex resume ID。

**Codex 最新进度覆盖：用户确认 Pi 已停后，Sol 主代理派发显式 Terra/low 唯一worker，V2完成并审过S1R，J1完成并审过最终build/check、独立生产专用服和生产客户端真实核心路径。普通铁炉纵向切片通过；所有owned进程退出。skill本轮补owner闭包和玩家UUID核对。最终证据与下一步以 roadmap 顶部及review的J1/A1节为准；尚缺干净目标独立重放，skill不冻结，B未开始，无commit/push。下文C11/游戏未知是迁移时历史。**

## 0. 接管门禁：先确认旧执行停止

**Codex 接管补记（2026-11-01）：用户已明确确认“pi已经停了”，并指定当前主代理为 GPT‑6.1‑sol，继续派发 worker 与反馈优化 skill。接管检查中 PID 22420/3700697/3700437/3699506 均不存在；未终止任何共享 daemon 或无关进程。以下是迁移时历史现场。最新继续状态见 `iron-furnaces-roadmap.md` 顶部 Codex 检查点。**

**暂停请求已发出，最后确认时尚未收到主代理的停止回执。不要与旧代理同时写文件/启动游戏。**

用户先说“暂停一下，我准备迁到codex运行你这个会话”，随后授权生成交接文档。Pi 外层已向主代理发送暂停：停止新派发、转告 worker、保存检查点、清理自有游戏/Xvfb。120 秒监督已关闭。主代理当时阻塞在 worker 的游戏验证调用中；steer 要当前工具结束后才处理。外层无法直接按嵌套 worker ID 控制它（返回 Agent not found），这不是它已停止的证据。

外层随后按用户要求检查进程：Pi宿主PID `22420`，子代理为宿主内会话，没有可单独kill的worker PID；杀该宿主会连外层会话结束，尚未执行。已请用户在 `/agents` 中停止任务，若不可用则退出原Pi。检查时还看见目标game-fabric客户端PID `3700697`、wrapper `3700437`、本轮Xvfb `3699506`（job anchor `3699498`）；这些只是当时记录，PID会过时，不能未经重新核对就kill。共享Gradle daemon `3572936` 不是目标，禁止终止。不要把其它旧Xvfb/:93或其它Codex实例算成本轮进程。

若收到后续暂停报告，以同目录 `iron-furnaces-review.md` 的最新检查点或新的 pause/checkpoint 文档为准，并更新此节。接管前检查当前工作树、最新 evidence 文件和精确实验 gameDir 进程；不要按 Java/Gradle 名字批杀。若旧 Pi 还在写，先由用户在原 Pi 中停止对应代理，不能两边竞争。不能声称已清理未核实的进程。

## 1. 用户真正要做什么

制作并实测通用 **NeoForge → Fabric 自动移植 skill**，让强模型主代理读源码、做架构与 roadmap、审查，较弱 worker 编码和游戏操作；通过 mdb 服务端状态 + 真实客户端 GUI/截图/read 图完成闭环。不是单纯要把 Iron Furnaces 编译过。

实验分两阶段：
- 阶段 A：`openai-codex/gpt-6.1-sol` 主代理，`openai-codex/gpt-6.1-terra` worker，全部 **thinking low**。优化 skill，用代表性模组跑一个完整炉型纵向切片。
- 阶段 B（**尚未开始**）：冻结 skill/任务后，干净目标用 Terra 主代理 + `openai-codex/gpt-6-luna` worker 比较。不可复用阶段 A 的已解补丁/答案冒充独立弱模型评测。
- 阶段 A 没完成，不能进入 B 或宣称 skill 已证明可完成移植。

用户明确偏好：中文简洁；所有 GPT 指 `openai-codex` provider，不得静默换 provider/model；所有新代理 low；最少必要新测试，重用现有测试，不造矩阵/mutation/过度防御。默认主/子模型可直接读图，不具备就拒绝此 skill 的视觉执行，不用文字猜截图。不要让模型自证自己型号，身份来自调用参数和宿主元数据。

Codex 的子代理工具/模型选择不一定与 Pi 一样。先核实可用能力；若不能按指定分工选择模型，明确报告并让用户决定，不伪造兼容 API、不把当前 Codex 模型冒充 Terra。不要为了“迁会话”改全局配置或插件。

## 2. 路径与保留状态

### 主仓库（skill/实验文档，不是目标 port）
`/home/wangyu/server-v2/develop/mcdebug-wt-121`

必读：本仓库 `AGENTS.md` 以及父级 `/home/wangyu/server-v2/AGENTS.md`、全局 `/home/wangyu/.pi/agent/AGENTS.md`。接管时 `git status --short`：`?? docs/experiments/`、`?? skills/neoforge-fabric-port/`。这些是本会话未提交产物，**不要 clean/reset 丢掉**。

Skill：
- `skills/neoforge-fabric-port/SKILL.md`
- `skills/neoforge-fabric-port/references/delegation.md`
- `skills/neoforge-fabric-port/references/verification.md`

实验文档：
- `docs/experiments/port-skill-evaluation.md`：总体方案/模型与环境
- `iron-furnaces-roadmap.md`：源 owner 和路线
- `iron-furnaces-worker-task.md`：完整 S1 契约（不要每次全塞给 worker）
- `iron-furnaces-review.md`：首轮失败审查，可能含后续暂停更新；旧“编译失败”不是最新事实
- `config-owner-control.md`：后续干净上下文/配置闭包实验及早停诊断

### 实验根（源码和原始证据在这里，不在主仓库里）
`/home/wangyu/.cache/mcdebug-port-skill-eval`

- `reference/`：固定上游只读 checkout，禁止修改/构建写入。
- `reference-dev/`：独立原版构建与运行副本，原版基线已完成，**不用重跑**。
- `fabric-target/`：唯一移植代码工作树，已有大量未提交改动，必须保留。
- `game-ref/`、`sessions/`、可能已创建的 Fabric gameDir/consumer：通过实际 session 记录确认，不猜端点。
- `evidence/`：原始命令/JSON/PNG/构建日志、各轮 `cN-report.md`。

样本是 Qelifern/IronFurnacesNeoForge，固定 commit **41fde020636b79154e1c454a893962c639d89e1b**，版本4.3.2、MC1.21.1、NeoForge21.1.209、Java21、MDG2.0.89，Apache2（含 assets，查 README-LICENSE.md）。默认分支4.4.2已是MC26.1.2，不得跟随。样本约142 Java文件，不是微型示例；只验一个铁炉用户闭环，不删除其他共享行为取假绿。

## 3. 最新可靠状态（外层亲自核过的范围）

### 原版基线
原版 `sh gradlew build` 成功（14条上游deprecated警告、test NO-SOURCE）。KFF最初 Maven单jar缺语言提供者，已通过补齐拆分依赖解决；不要重复走单jar失败路线，查 evidence/install 与 baseline 报告的最新记录。

主代理审过原始状态和 PNG：真实放置、GUI投入原铁/煤、加工/取出、满输出不耗输入、保存重启；`r1-progress.png` 清晰火焰/约半程箭头、无tooltip。原版是 dev classes，不是发布物consumer。原版结束时代理报告 owned 游戏/Xvfb清理。后续目标游戏是否启动，必须重新确认。

### Fabric：已经 compileJava 成功，尚未最终验收
**外层直接读过 `evidence/c11-compile.log`：BUILD SUCCESSFUL in 5s，12条JEI deprecated API warning，另有unchecked提示。**
命令：
```sh
cd /home/wangyu/.cache/mcdebug-port-skill-eval/fabric-target
sh gradlew --init-script /home/wangyu/.cache/mcdebug-port-skill-eval/evidence/c4-maxerrs.gradle compileJava
```
这不等于完整 build/check、客户端启动、S1、专用服或最终发布jar consumer通过。

最后已知主代理向 worker 派发“Verify Fabric furnace behavior in game”，随后用户要求暂停；其最终结果需要接管门禁确认。不要把“已派发游戏测试”写成“游戏通过”。

外层最近 git diff --stat：83个tracked文件变化，753 insertions/1567 deletions；另有未跟踪源码/资源，stat不含它们。代码正在推进，精确数量以暂停后为准。

主要改动 owner（以实际 diff 和 cN-report 核验，不可只信摘要）：
- C1 配置：Config + FurnaceConfigFile，保留50个实际定义键/默认/范围，init调用和14个BE配置值返回类型；JSON替代TOML是已记录平台差异。
- 入口/客户端生命周期、玩家attachment持久化、注册/ExtendedScreenHandlerType、真实menu打开与玩家槽、网络payload适配。
- 能量：teamreborn:energy:4.1.0已添加并嵌入；FEnergyStorage容量/限速/保存/通知，事务接缝。
- 物品transfer：真实Fabric Transaction，隔离检查部分接收守恒、抽取失败回滚。
- 燃料/remainder/JEI、BE更新/光照/邻居通知、GUI geometry accessor等逐owner迁移。
- C11只为两个Screen基类增加getGuiLeft/getGuiTop/getXSize/getYSize读取原版protected字段，不改布局。

已见新增未跟踪：`FurnaceConfigFile.java`、`mixin/`、`network/ClientMessages.java`、`tileentity/furnaces/ItemTransfer.java`、`util/FurnaceFuel.java`、`fabric.mod.json`、`ironfurnaces.mixins.json`。不要只保存git diff而漏新文件。

## 4. 为什么换过编排；哪些结论不能乱下

首个 worker 在完成基线后，对大P0/S1任务反复提前交接：clone后零实现→只build metadata→新增未接线helper；没有真实语义阻塞，Config错误原样。主代理纠偏三轮后停下，不换强worker代写。

用户要求继续后，外层授权干净上下文的Terra + 单一owner闭包。C1配置接线后连续完成C2…C11，首因不断前移，最终compileJava绿。这支持“短而自含、明确owner与本轮可验收状态”的编排更有效；**不能隔离证明是上下文长度还是任务粒度导致改善**。

早停技术原因仍未知：旧调用max_turns=160且正常返回未完成摘要，无明确exhaustion证据；原始旧worker `.output` 只记录首次baseline，不覆盖后续resume。主代理history读取remote-context-inactive。不要断言宿主限流/turn-limit bug，也不要归罪模型总体能力。

通用skill目前只补了两条证据驱动规则：身份取证不用LLM自述；工具名/help看似不支持时查真实调用路径及默认参数，后续视图失败不等于动作未发生。后续可根据本次owner编排证据精简改进，但不要规则堆砌。

## 5. 接管后的具体下一步

1. 先落实§0暂停门禁，核工作树、最新 `c*-report.md`、目标游戏验证/暂停报告。保留文件，不重新clone覆盖target、不重做reference基线。
2. 读skill/refs、roadmap及完整S1，再审当前实际target diff、meta/入口、最新构建。将旧“停止于Config”状态更新为C11成功及暂停时新状态。
3. 若尚无有效目标游戏证据，继续完成build/check及隔离owned Fabric客户端启动，按实际首因修复加载/运行问题，不盲扫全部代码。
4. 以一个owner闭包为worker派单单位：主代理先理解源码、定准确修改和本轮证据；worker执行，主代理审真实diff/日志。中间compile有其他owner错误可以是该子任务完成，不代表最终通过。不要给worker全量最终契约让它每轮都觉得必须一次做完全部。
5. 完成下述S1与独立consumer，再决定阶段A/skill是否可冻结。没有通过不开始阶段B。

## 6. 不可降低的S1最终验收

普通铁炉：注册物品→真实玩家放置（setPlacedBy/玩家炉列表）→空手打开带BlockPos菜单→GUI投入原铁/煤→引擎自然tick加工→DataSlots同步火焰/进度→GUI取出，服务端玩家Inventory守恒→正常保存stop/start后库存/设置保留且继续加工。
- 铁炉默认160 ticks，原铁→铁锭1:1；原19机器槽不能删。玩家slot/quickMove、XP/配方解锁保留。
- 仅加满输出槽不消耗输入这个边界，不扩大测试矩阵。原版测试证据无需重跑。
- 不写预期输出/进度、不用be.tick冒充自然完整用户路径；有界wait.until，字段先读。
- worker和主代理都实际read关键PNG，输出图片文件不等于视觉验证。
- 最终build/check、隔离专用服、独立Fabric consumer安装最终jar（无目标dev classes）重放核心路径。Gradle重映射consumer不能冒充未经重映射的生产客户端。
- 保留其他共享源码/算法/JEI，不伪造net.neoforged、不no-op、不排除必要源码假绿；capability事务/线程/同步/持久化语义要真实。

## 7. mdb与运行约束

CLI：`/home/wangyu/server-v2/develop/mcdebug-wt-121/mcdebug-cli.jar`（本轮0.8.0）；操作前读本仓库 `skills/mcdebug/SKILL.md` 或 `/home/wangyu/.agents/skills/mcdebug/SKILL.md`，实际命令help为准。

- 客户端只通过mdb session start/stop，独立registry/gameDir，demand模式，显示/Xvfb用自有实例。不要裸runClient绕会话归属，不按名字杀Java/Gradle。
- 工具jar放consumer的mods，不放mcdebug自己的run/mods。Fabric需fabric-language-kotlin，NeoForge需完整KFF依赖。
- mcdebug open-block实际走客户端gameMode.useItemOn。对地板face up持炉、不带clear-hand，可真实放置；返回NO_GUI_CHANGED可能只是没有打开Screen，读方块/手持消耗证明动作。用完空手再开GUI。
- 模型用read直接看PNG；不要image-reader代理降级。
- 服务端状态权威，screen.*无像素；gui.*才是真实客户端。
- 没有tick.run RPC；wait.until被动引擎tick，可用sprint；手动tick rate恢复。
- 日志读gameDir/logs/latest.log，不用docker logs。不接管真实玩家session/世界，不删session.lock，共享Gradle daemon不清杀。

MC/Fabric本地源码只读：
`/home/wangyu/server-v2/upstream/mc_source_1.21.1_fabric/{common,client}/net/minecraft/`
`/home/wangyu/server-v2/upstream/fabric-api_source_1.21.1/`
用对应版本源码核实API，不猜、不javap。

## 8. 全局安全/提交边界

搜索优先精确路径rg/fd；排除 `/home/wangyu/server`、`/backup`、`/home/wangyu/win` 及子树，禁止宽根/home扫描，超时缩小。生产数据和部署与此次无关，禁止写入、deploy、重启生产。

本轮无commit/push授权。主仓库只写skill/experiment文档；worker只写隔离target、evidence和自有运行目录。不改mcdebug本体/全局插件配置/邻近工程来让移植测试假绿。需要真正工具缺陷时先给证据和用户裁决。

## 9. 之前已完成的其他用户任务（不是当前待办）

Pi子代理插件 `/home/wangyu/.pi/agent/git/github.com/yu1745/pi-subagents` 已完成并push `880c997ea6f7bd288a5ec34bdb19a66bf9ae08d0` 到master：删除agent_bg、集中递归策略与 `/subagent-recursion on|off|depth N|status|reset`（补全）、保留顶层历史/结果与冷resume。2444 tests passed/15 skipped，bundle/check绿，最后核过工作树干净。不要再改插件，用户要求已完成。

用户授权添加Pi的Terra模型注册与scope：`~/.pi/agent/models.json` 仅新增openai-codex/gpt-6.1-terra，contextWindow用户指定272000、maxTokens128000、text/image、reasoning；enabledModels追加Terra/Luna，scopeModels保持true。没有读取/修改凭据。迁往Codex不意味着这些Pi设置会自动生效；无需复制私密配置。

## 10. Pi追溯标识（只作历史索引）

- 外层Pi session目录标识：`01a19318-e2b2-76a1-a422-a462037b266e`
- sol主代理：`0bb481c5-abce-432` / port-skill-lead
- 旧terra（baseline/失败大任务）：`8d1eb4a4-d251-47c`
- 新干净terra（C1起）：`e5aba4e0-79c3-4a9`
- 原始临时日志根：`/tmp/pi-subagents-1000/home-wangyu-server-v2-develop-mcdebug-wt-121/01a19318-e2b2-76a1-a422-a462037b266e/tasks/`

/tmp日志可能消失；主要知识应从本文件、实验文档、实际源码和evidence恢复。原始transcript可能含内部thinking/加密字段，不把其当handoff内容导入/复述；只需公共工具行为/结果。如需花费统计不能把单主代理usage当整个后代总成本。
