# 持续独立移植与skill反馈循环

用户要求外层持续执行，不能在中间里程碑结束后等待催办。外层负责skill迭代和实验审查；正式移植main agent只接冻结skill、任务和必要原始输入，自行派worker、做源码决策和验收，向外层反馈skill改进。

## 本轮链路与输入

- A2：外层 → `a2_sol_main`（显式gpt-6.1-sol/low/fork_turns=none）→ `implement`（由main显式派gpt-6.1-terra/low/fork_turns=none）。worker不递归，唯一写target。
- 根：`/home/wangyu/.cache/mcdebug-port-skill-eval/round-a2-sol-terra`；冻结skill文件/哈希和37份原版基线输入清单见manifest.json。
- source：只读reference的固定41fde020636b79154e1c454a893962c639d89e1b。干净target、consumer、gameDir、session与evidence都在本轮根。
- 禁止读旧Fabric实现、补丁、roadmap、交接和目标证据。可读通用工具skill、对应版本MC/API源码及标准依赖缓存。没有给移植方案、owner清单或逐项修复答案。
- 固定验收：普通铁炉真实放置→完整菜单GUI投入→自然tick→中途进度双层读图→GUI取出守恒；满输出不耗输入；正常重启保留库存/设置/玩家产物/记录并继续加工；完整build/check、专用服侧隔离、独立生产发布jar核心路径。

## 裁决与下一轮

正常编译/运行返工由本轮main处理。外层只审查实际源码/原始日志/图片、授权边界和完成事实，不偷给旧解法。若外层必须提供实现提示，记录为干预，不能算无提示独立成功。

main反馈需分为：skill歧义/缺失、执行不遵守、工具或环境问题、正常工程返工。只采纳可泛化且有证据的skill改动；具体模组知识留在实验报告。重大改动后以新干净上下文/目标再跑强配置，不在已带答案的目标续跑冒充独立重放。

可接受条件：强配置独立完成固定切片、外层审查无未关闭阻断、没有靠外层解题才通过，反馈没有未处理的阻断型skill问题。单次成功可据此进入试测，不声称统计稳定性。随后冻结相同skill及契约，用全新Terra/low main→Luna/low worker执行B；不提供A已解补丁或路线。B失败亦按真实原因反馈；不换强worker补完后算B通过。

本阶段不commit/push、不改生产、mcdebug本体、全局配置或插件。结束/换轮必须核对owned会话和进程退出。

## A2当前检查点

### 当前执行：A3/r2复审通过，B1已启动

A3 main最终A–D PASS，R1/R2 CLOSED。外层独立读dev-progress.png、main-production-after-take.png与主代理真实GUI转移日志，复核最终jar哈希fe86ef9c218a8bf48e78e4cda7ace7b3cc5d626560ee30aaaba2ac24c366dec6，运行dev=false/intermediary与目标来源；亲自按最终审计6个PID查/proc均不存在，两session stopped。四份冻结skill哈希与manifest逐项一致。r2反馈无阻断缺口；可选override ledger/更详细bridge代码不在本次冻结前新增。A3过程中外层没有提供实现方案，只在收尾要求文档pending状态与已完成事实一致。

B1：`/home/wangyu/.cache/mcdebug-port-skill-eval/round-b1-terra-luna`；已显式spawn `b1_terra_main`（gpt-6.1-terra/low/fork_turns=none），要求自行spawn唯一gpt-6-luna/low。skill直接复制A3冻结快照，manifest校验四文件完全相同；仅共享37份原始baseline证据及固定reference，任务门禁与A3相同。禁读任何A2/A3实现/路线/目标证据，不由main接管worker编码冒充弱配置通过。B1尚未有执行结论，持续工作中，不commit/push。

用户后续补充（B1运行期间）：明确要求环境准备阶段生成/提取两端API和两端genSources MC源码，放在目标工程旁../作为只读参考，而不是仅写“查源码”。外层已读Mek当前/旧worktree AGENTS及SOURCE.txt、IC2 AGENTS§12，以及用户授权Windows tmp下AE2（现无AGENTS，PORTING.md指向IC2/NeoForge原版参考）。据此新增源端NeoForge API/源MC与目标Fabric API/目标MC四来源清单、授权根内../port-reference布局、实际生成/获取/提取、精确版本/映射/归档哈希/AT-AW元数据。这是当前工作副本r3候选，quick_validate通过；B1冻结r2不变，不能把其结果当r3验证。B1后需由独立main/worker实测r3新增源码环境阶段，不重做原版基线，不给既有环境解答。

最新源码环境验证覆盖上述待办：用户改为明确要求独立6Luna只拿source-workspace.md单测，失败专门改该文档直到可照执行。外层已连续4个fresh Luna/low测试，前三次PARTIAL用于证据驱动改进，第四次独立通过53归档/覆盖/命名审计。全部临时根与外层验证根已清理，仅存报告/元数据/日志，详source-workspace-luna-test.md。测试通过的source-workspace文档SHA256=f504ee048dd33da846854939d1c0fabf1995b287aad3e735fc1e367144739c4c。只证新增准备阶段可独立执行，不冒称已用r3重新完成整个移植；B1继续冻结r2。

B1过程偏差：worker违反初始禁javap要求，main已纠正并保留原始记录；worker自报至少14次functions.exec ReferenceError(constr未定义)，其中一次明确调用拼写错误，其余逐次原文未齐，不能证明宿主故障。外层仅要求原始诊断/归因证据、保持Luna与唯一writer；没有给移植实现或格式修复代码。后工具恢复，common/client编译通过。main自行拦下EventHandler注释空壳R1，要求补回真实爆炸行为；完整构建/运行尚未验收。

### 最新状态：A2功能通过，r2修订已进入A3

外层已直接查看目标与生产PNG，核对Config/入口/能量/能力接缝源码、最终构建/生产启动/目标jar origin与哈希、保存前后JSON、实际加工64满槽完整超时证据及清理。A2普通铁炉功能切片通过，保持首次registry越界与外层契约输入纠正的独立记录；不是零偏差实验。main报告 `round-a2-sol-terra/evidence/main-review.md`、worker原始索引delivery.md及根skill-feedback.md均已审。未认证其它炉型/自动化/能量互操作/JEI/爆炸与虹煤分支。

采纳反馈得到r2：session示例与派单环境显式registry；发布物许可检查落到实际jar/内嵌依赖；增加按需production-consumer参考（官方生产任务/最小桥接或官方profile监督JavaExec，两者不强制锁死一种）。移除delegation中实验因果归因语句，保留该知识在实验文档。不因本轮反馈猜测删掉多处视觉/角色guard。quick_validate通过。

A3已正式派发 `a3_sol_main`（gpt-6.1-sol/low/fork_turns=none），应自行派gpt-6.1-terra/low。全新根 `/home/wangyu/.cache/mcdebug-port-skill-eval/round-a3-sol-terra`；r2四文件哈希见manifest，输入仍仅37份原版基线，不提供A2实现/路线/证据。用户任务从开始就明确真实加工64等同一门禁。若r2独立重放完整通过且无新的阻断型skill问题，则冻结同版r2与相同任务，进入干净Terra→Luna测试；不再无理由重跑强配置。

A3继续检查点：main已显式派Terra/low，完整build已过。main自主开出并修正R1（generator进料顺序/factory条件）与R2（彩虹煤附魔书限制）；R2新增mixin首次Shadow父字段的启动失败已原样保留并修正，修正后owned客户端启动/建世界。外层未给任何实现提示。r2隔离设置已实际核查session-start-fixed.log，registry/log均在本轮runtime/sessions，未见A2式默认registry漂移。main审计142原Java、445原资源与许可完整保留；外层独立打开dev-progress.png确认无遮挡火焰/非零进度，原始NBT83/160。真实GUI已取出2锭，实际加工到炉64锭+原铁1（总投入67），正完成满槽观察/非默认设置/正常保存重启。生产consumer/专用服与最终清理仍待本轮main交付；尚不冻结或启动B。

main自行建立roadmap并查看原版基线PNG；worker已从源码干净副本进入javac。main自行定位Config平台owner、Fabric持久attachment路线、JEI依赖重复，正常推进中。未宣称build/游戏或本轮通过；外层尚未提供实现答案。

继续进度：compile-08与build-01已通过；main自行审查并要求补LICENSE/NOTICE打包。worker首次owned启动漏显式sessions-dir，将一条记录及日志写到默认registry；main自行阻断、正常stop并确认相关PID退出、保存原始偏差，后续用本轮session/registry。外层未提供修正答案。该项记为执行约束违例，不因已清理而抹去；通用skill的session示例是否应显式registry是待审改进候选。生产owned-session示例缺口也由main自主提出，尚待其实际生产执行证据。

外层干预记录1（契约输入纠正，非实现解题）：A2主代理报告使用直接布置64输出的满槽夹具，外层发现自己初始派单虽写满输出不耗输入，却漏了实验原有更严格条件“64必须实际加工所得”。外层立即补该验收条件，允许原夹具保留为单层探测，不提供实现方法/代码/旧证据。该偏差首先归为外层任务输入遗漏，不能借此给通用skill加样本专属64规则或判main不遵守未收到的条件。后续A/B统一明确同一边界来源条件；本轮不谎称零外层干预。

B1外层技术审计介入：普通铁炉新hash生产路径已重放并清理后，外层发现RainbowCoal的全局燃料/余物与附魔书拒绝hooks似仅在自家BE特判，要求main独立对照；main确认R3并派原Luna修复。另要求检查Registration先于Config.load的注册期配置读取，R4仍待MC缓存时序核查。此类明确共享行为不能以固定普通煤切片通过代替；不将外层发现算main独立发现，不声称B1无提示通过。源码环境单测已完成，不受此次移植返工影响。

根据B1已确认的R3/R4，当前工作副本delegation审查节新增两项通用检查：被删除/改签名的平台入口逐项映射到目标实际调用方及范围；注册/构造参与的配置核对实际缓存时机。没有写虹煤专属实现，也没有扩大固定切片测试矩阵。此为r3候选、尚未新独立整轮重放，B1冻结r2不变；source-workspace已测SHA不变。quick_validate通过。

外层原始证据复核（旧hash221e091e2276e02a09e8578d0e0f0c51132b9e37afed5c398432f9b55abfdf53）：dev满输出before/after均RecipesUsed64、成品64/原铁1/煤1且Cook/Burn0；持久化保存前Cook58/Burn617/原铁1，重启后成品1且RecipesUsed1、非默认Settings一致，玩家列表保有真实新放置[27,70,-5]。列表另含诊断播种[24,70,-5]，原报告明确披露，不将其算真实放置证据。最终R3/R4新hash仍需受影响路径与生产身份复核，不能把旧hash标成最终产物。

外层复审R3/R4最终修复：实际jar SHA256 5a594b79dbf431ce3a07d23645379483bdc1204a99e1c3b852ff621a81caceef；生产RPC dev=false/intermediary；LIT=true状态实际getLightEmission=0且config=true；原版炉自然midburn Cook8/Burn193/虹煤damage1，完成后铁锭1、虹煤damage1保留。外层亲自打开anvil-rejected-clear.png看到两输入/红X，并查对应GUI JSON输出空。最小受影响路径支持修复，不把旧hash普通煤长流程自动归为最终hash重新全跑。owned清理与最终报告待main交付。

最终身份纠正：worker收尾发现R3/R4客户端实际dev=true/named，故铁砧PNG与GUI JSON只证明dev客户端路径，不能称新hash生产客户端通过；生产专服的dev=false/intermediary、原版虹煤自然消费与lightEmission证据仍有效。外层接受B1以受协助且未完整通过结案，不为追全绿继续无限返工。最终新hash生产客户端及完整普通炉GUI未验证；上段“生产”仅限明确核过身份的服务端证据。已要求main报告同步纠正。

采纳main最终反馈的另两项通用改进：关键原始响应/截图与产物身份及时落盘；连续只有等待摘要时主代理核对最后成功动作、工具、进程/端点后确定下一动作。既有工具约束透传和production身份门禁保留，不重复堆规则。这些delegation候选修改尚无fresh完整移植重放，source-workspace测试结论与SHA不变。

## 本轮最终裁决

A3 Sol→Terra固定切片独立通过，B1 Terra→Luna已实际执行并以“受协助、未完整通过”结案：外层发现R3/R4、曾介入进度诊断，最终新hash缺生产客户端普通炉重放。具体结论见round-b1-terra-luna/review.md、skill-feedback.md；不以修复后的部分通过抹掉独立性和验收缺口。外层亲自ps复核记录15个server/client/display PID均不存在，另核最后registry launcher977676/wrapper977743退出、session=stopped。失败显示:126留下stale socket，不删除不属可靠owner证明的锁/socket；其它测试树保留作实验产物，不触碰生产。源码环境四轮单测已完成且所有其临时根清理，报告source-workspace-luna-test.md。当前skill格式校验通过；已测source-workspace SHA保持f504ee048dd33da846854939d1c0fabf1995b287aad3e735fc1e367144739c4c，最新delegation候选改进未进行fresh全模组重放。没有commit/push。

用户随后要求Pi对比相同源码准备任务，并追加zai-coding-cn GLM5.3Flash/5.3及Antigravity Gemini3.8Flash。测试均冻结旧source-workspace SHA f504ee…，low。已完成MiniMax与GLM5.3Flash均将实际带FabricItemStack/AttachmentTarget注入的MC源错称干净vanilla。应用户要求，仅source-workspace.md补清工程自定义变换与loader/依赖变换区别、实际类声明rg检查、SOURCE/报告标注和未核实不得宣称未应用；当前SHA5f11120847287c2eb1cfca04f2aa14bd76d8e2b580cee03af7a6aa8aa97978ae，quick_validate通过，新增段落尚未fresh重测。运行中的Pi保留旧冻结副本，不动态补答案。

最终用户纠偏：参考源码可用性优先，来源措辞错误不单独判准备失败；移除额外分类核验和vanilla概念，source-workspace从15302字节精简到7101字节。当前SHA1dc46675e04280c10ff3d547f75a3d67eaec8cd6122c2c6cbf7ae1b739ef6086，quick_validate与示例Python语法通过，尚未fresh模型重测。五次Pi均完成源码准备，具体耗时/报告备注/执行边界偏差见source-workspace-model-comparison.md；全部五个隔离根与六个模型越界创建的/tmp临时产物已在留证后清理，标准缓存保留。

## C1 Sol→Luna（进行中）

用户要求对照Terra→Luna失败改试Sol主代理。已创建全新round-c1-sol-luna，冻结与B1完全相同r2四份skill文件及37份原始基线，manifest逐项验hash；只替换主代理为gpt-6.1-sol/low，要求唯一gpt-6-luna/low worker，fork none。新main只获冻结skill/原始源与基线/同一固定验收，不给旧实现或具体修复答案。本轮不混入后来精简的source-workspace及审查模板，便于比较主代理配置。主代理/root/c1_sol_main已派发，尚无结论，持续工作。

另有用户要求的DeepSeek V4.1 Flash none源码准备单测在run-none-hxfbpcvu进行，使用当前精简版source-workspace SHA1dc46675e04280c10ff3d547f75a3d67eaec8cd6122c2c6cbf7ae1b739ef6086；Pi thinking=off已核实际出站reasoning_effort=none。与旧low轮同时换了文档，不作为仅思考等级的单变量比较。

C1路径执行偏差：Sol main自行发现Luna复制/文件落盘自报与本轮空target不符，查明writer未指定workdir，误写mcdebug checkout/target与evidence。main独立停止盲目撤销、要求只读清单和路径证明，随后在正确本轮target读回实际构建文件；外层git status核实C前无这两目录、tracked文件无diff，仅授权证明属于本轮的新文件清理，未给移植实现答案。恢复继续原Luna，不将其称FS宿主故障，也不抹掉执行偏差。

DeepSeek none补测已完成：430.642秒，90次出站均none，四类主要源码/版本映射/覆盖/哈希通过；附加Distmarker源码查找未完成且误判无公开来源。实际任务输入为当前1dc466…精简版，不将与旧low耗时差异单独归因思考。保留raw报告与核查，确认无相关进程后删除隔离根及/tmp/probe.gradle，共享缓存保留。C1 Sol→Luna仍进行，当前无构建绿/游戏证据。

## Pi 插件配置与四组移植对照恢复

用户要求暂停 Pi 实验先修改 subagent 插件。已实现 `subagentToolsEnabled` 布尔项（默认 false），Session 内存覆盖优先于项目配置、用户配置；`/subagent-tools reset` 回到配置，新 Session 清内存覆盖。重建安装 bundle；133 测试文件通过、2446 tests passed/15 skipped、4 种真实 `pi -p` 启动工具可见性通过、8 类故障注入均被测试检出。按用户后续授权提交推送 yu1745/pi-subagents master，commit cd097d0550da22eb23b0746f676bdb7159e53244。未修改用户全局配置。

用户说“继续”后，2026-11-01T06:21:48Z 并行启动 `round-pi-sol-{gemini,deepseek,minimax,glm}`。四组 Pi 主代理均 openai-codex/gpt-6.1-sol low，唯一 writer 分别为 antigravity/gemini-3.8-flash、codebuddy/deepseek-v4.1-flash、codebuddy/minimax-m3.1-flash、zai-coding-cn/glm-5.3-flash，均 low。冻结与 C1/B1 完全一致的 r2 四文件与37份原版基线并逐项验 hash，复用 C1 初始 A-D 任务，仅替换实验根、writer 模型及宿主调用适配；不混入最新候选 skill，不提供 C1 实现发现。项目配置启动即可暴露 Agent/Watch 等工具，四组实际 startup.json 均证明 main 型号正确且工具在首次请求前可用。

使用长期 RPC 宿主而非 print，支持后台子代理完成通知和 Watch；此宿主差异在比较时单独说明。选择 Gemini 组观察是否自主调用 Watch，未在任务中提示 Watch。每组独立 `.pi/agents/port-worker.md` 固定实际模型/low，禁止 worker 递归，保留原始 RPC、工具调用、worker transcript、启动时间与 PID。host 配置/凭证副本私有，最终清理；不把其内容输出到报告。启动器 `/home/wangyu/.cache/mcdebug-port-skill-eval/pi-port-comparison.py`，状态读取器 `pi-port-status.py`。当前仍是执行中，不能把模型配置或主代理启动算移植通过。

C1 更新：完整 build/check/remapJar 已通过；Sol 自主发现并返工许可证入包、配方条件方向、JEI Fabric 插件入口等接线问题。真实生存放置/55槽菜单已有原始证据，首次燃料加工触发空栈强转导致崩溃，仍由原 Luna 修复、重建与重放。A-D及最终生产 jar 验收尚未完整完成；早期误写目录已清理，执行偏差保留。
