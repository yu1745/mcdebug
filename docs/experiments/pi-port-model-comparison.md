# Pi Sol 主代理 → Flash worker 移植对照

状态：执行中，尚无完整 A–D 验收结果。2026-11-01T06:21:48Z 同时启动四组。

| 组 | 实际 worker 会话型号 | 思考 | 完整移植结果 | 耗时 |
|---|---|---|---|---|
| Gemini | antigravity/gemini-3.8-flash | low | 未完成；配额阻塞后收尾，用户主动停止 | 76分34.3秒至main返回未完成 |
| DeepSeek | codebuddy/deepseek-v4.1-flash (x0.11) | low | 开发A证据支持，production准备；整体执行中 | 未结束 |
| MiniMax | codebuddy/minimax-m3.1-flash (x0.08) | low | 首jar已生成，运行准备；完整build/check待核 | 未结束 |
| GLM | zai-coding-cn/glm-5.3-flash | low | 开发A/B有证据，C有缺口，production待验 | 未结束 |

截至08:06Z：除Gemini因额度终止后已完成自主审查，另三组main仍在blocking结果等待，尚未进行实施后的独立审查。四组均未完整通过A–D。

四组 main 均为 `openai-codex/gpt-6.1-sol`，启动参数明确 `--thinking low`。worker 型号与思考等级按实际 JSONL `model_change` / `thinking_level_change` 记录，不以模型自述证明。Codebuddy 型号价格后缀来自宿主注册表。

外层随后审计所有已落盘 worker assistant 响应，`thinkingLevel` 均为 low。Gemini 的实际 `responseModel` 为 `gemini-3.8-flash-n`；DS/MiniMax 的该字段与指定型号一致、无价格后缀；GLM响应未提供该字段，不能据此证明服务端内部型号。审计快照与读取脚本在父实验目录 `pi-port-model-audit.json` / `pi-port-model-audit.py`，收尾时需刷新，当前不是最终请求数。

使用 C1/B1 冻结 r2 的四份 skill 文件与37份原版基线，manifest 保存逐文件 SHA256。固定源 commit `41fde020636b79154e1c454a893962c639d89e1b`，Iron Furnaces 4.3.2，MC1.21.1，NeoForge21.1.209 → Fabric1.21.1。复用 C1 初始 A–D 任务，只替换实验根、worker 模型和 Pi Agent 调用适配，不提供先前轮次实现或诊断答案。

外层独立输入审计 `pi-port-input-audit.json`：四组各41份文件哈希全部匹配C1 manifest；任务文本把ROOT/WORKER替换为占位符后逐字相同。

每组 main 自行规划、派唯一 writer、审查源码和证据；writer 不继承上下文、不递归。完整结果需覆盖真实生存放置、55槽GUI投取与自然加工、实际加工64个输出后的满槽边界、同世界/UUID正常保存重启，以及最终 remapped jar 在真实 production consumer 中的核心重放。编译通过不能代替行为验收，未测试的其它模组行为需单列。

## 宿主与隔离

实验根 `/home/wangyu/.cache/mcdebug-port-skill-eval/round-pi-sol-{gemini,deepseek,minimax,glm}`；各自 `target/consumer/runtime/evidence`、项目 agent 文件、RPC 会话、worker 会话与私有配置目录独立。只共享只读原始源码/基线和标准依赖缓存。此隔离为独立工作目录和任务写入边界，不是 OS 文件系统沙箱。

主代理使用长期 RPC 宿主，使后台完成通知和 Watch 可持续运行；不是 `pi -p`。Pi 插件版本为本地已提交的 `cd097d0550da22eb23b0746f676bdb7159e53244`。项目 `.pi/subagents.json` 中 `subagentToolsEnabled:true`，四组 `host/startup.json` 均证实首次模型请求前 `Agent`、`get_subagent_activity`、`watch_subagent` 等工具可用。用户全局配置未修改。

此处不是日常完整插件栈：使用`--no-extensions`后显式加载本轮subagents、Codebuddy、Antigravity和只读audit扩展，禁自动技能/上下文文件/提示模板发现；worker仅基础工具、`extensions:false`、不继承上下文。本轮工具已启用的事实与日常Pi默认关闭工具时的提示词审计必须分开，不能相互替代归因。后续插件开发未热加载进这四个旧宿主。

宿主脚本 `/home/wangyu/.cache/mcdebug-port-skill-eval/pi-port-comparison.py`，只读状态脚本同目录 `pi-port-status.py`。`host/rpc.jsonl` 为原始 RPC 事件，`host/audit.jsonl` 为 main 工具调用，`host/worker-sessions/` 为实际 worker JSONL，`host/status.json` 保存开始时间/PID/宿主状态。凭证副本不属于证据内容，不复制到报告；最终关闭相应宿主后清理。

## Watch 观察

事先选 Gemini 组观察；任务未提 Watch，也未要求调用。实际 main 在派发 writer 后，自主调用 `watch_subagent`，`action:start`、`interval_seconds:240`，工具返回 `Started supervision watch for agent 696eab4a-0bc9-4f9 every 240s`，`isError:false`。原始记录在该组 `host/rpc.jsonl`，工具调用 ID `call_b90d0aa48b1746a18f77b95fabd1c1fe|fc_0b7764ee7d8b27f9016ae6dad46f1481919af4956771ee3da9`。

这证明本次 Sol 会主动启用 Watch；尚不证明后续周期证据实际送达或改变了审查行为。注册后main长时间阻塞于 `get_subagent_result(wait:true)`，直到worker因配额报错才恢复；截至该阶段raw RPC没有`subagent-supervision`送达消息。需区分Watch注册成功、忙时延期和实际消费证据，不能称已产生持续审查效果。

## 已核对的中间里程碑

GLM：06:44:06Z 的原始 `./gradlew build -q` 返回 `exit=0`，两份 jar 实际存在；原始调用与结果另存该组 `host/monitor-p0-build-proof.json`，tool call ID `call_3bbebbabd35946958151bebd`。当时中间产物 SHA256 为 `d2daaa0ac7ceeab4566f5532cba74e1cf04ea29acf8bcec2e6446e987a2f5cc8`。随后 `runtime/sessions/port-target.json` 为 owned/demand、显示`:217`、客户端PID1904570，实际 session 日志出现服务端 RPC 就绪和 Player908 加入隔离世界。这只是开发环境构建/启动里程碑，不是 A–D 或最终生产 jar 验收；main尚需审查功能保留、实际diff及用户路径。

GLM早期A证据仍不能直接接受：`a-place.json` 为对石头位置操作的FAIL；后来另一位置的 `a-open.json` 确实空手SUCCESS、55槽。早期 `a-placed-be.json` 的 CookTimeTotal 为2，原版基线为160，玩家记录出现两个位置。外层只保留快照到父实验目录 `external-pi-review/glm-a-early`，没有给main/worker此问题的技术提示。07:04Z之后worker自行报告配置默认值未赋值并着手修复，需等真实读回与新工件重新验证；既不能拿早期错误直接判最终失败，也不能把自报已找到原因当作修复通过。

GLM后续自主返工读回：新的BE初值CookTimeTotal=160；中途CookTime27/160、BurnTime1094，图中火焰和白箭头非零进度清晰可见（外层已实际打开 `a-progress.png`）。worker也确实调用read打开该PNG，07:07:54Z工具返回`isError:false`、content_types=`text,image`，不是仅自称看过，见父实验目录 `pi-port-image-audit.json`。GUI slot46读回有4个铁锭。仍需main独立审查、完整服务端守恒及后续A–D；本次实际投料为`minecraft:iron_ore`，原基线给的是Raw Iron，夹具差异需如实记录，不将其默默当作完全相同输入。

GLM的B中间证据支持自然加工边界：原始命令通过GUI投入64矿石，使用bounded `wait.until/sprint` 等到输出64；BE含对应RecipesUsed64；再GUI投入8个输入，400 ticks后输入8、输出64均不变。过程原文另存父目录 `external-pi-review/glm-b-provenance.json`，没有把直接注入64产物当作通过。仍待main审查。随后worker声称配置面板点击失败，准备把默认placeConfig所得Settings `[2,1,4,4,4,4]` 作为“非默认”持久化替代；该值与默认放置相同，C的非默认设置门禁仍有缺口，不能接受自评。外层未向实验内提示此问题，等待main独立审查。

DeepSeek：07:14:08Z开始的完整build实际返回 `BUILD SUCCESSFUL in 12s`，包括check/remapJar，test为NO-SOURCE、有Gradle弃用提示；两份jar已实际存在。原始调用和返回在DS组 `host/monitor-p0-build-proof.json`，tool call ID `call_00_eZKgTyk0wxhgM6Sohneb8109`。这只是构建里程碑，尚无游戏验收或实施后main审查。

DeepSeek后续A原始证据支持核心用户路径：同UUID `2654e3c3-150d-3857-a426-0b141796a4e0`、playerGameType0/instabuild0，炉物品1→0，BE默认CookTimeTotal160、玩家attachment保存真实放置位置，空手SUCCESS打开55槽。投料为原任务的raw_iron。外层已打开`A-17-mid-progress.png`：非零白箭头可见，火焰剩一小段黄色；同期BE Cook108/160、Burn144。GUI取4锭后BE输出空、玩家4锭，原始JSON与点击记录支持守恒。worker确实read该PNG并收到text+image。main尚未读目标PNG或实施后审查，因此只记录A证据支持，未标整轮通过；B尚在执行。

GLM首个C保存重启的原始UUID控制不成立：before为Player799 / `[672436830,-1696778822,-1885125826,1147135037]`，after为PortTester / `[796197932,-172805529,-1454453166,-1005507122]`。即使库存/记录数值相同，也不能据此声称同UUID通过。外层保留原始文件快照 `external-pi-review/glm-c-first`；worker随后在固定用户名下做第二个pending-input重启循环，可能补齐控制，需待实际原始证据和main审查，不能提前裁决最终失败或成功。

DS在C阶段也自行发现Loom随机用户名导致UUID改变，开始固定profile后重做。其session名`port-c1`（与Codex实验C1无关）在约07:47Z因Gradle daemon2339187消失中断；worker推断是“别的agent gradle --stop”。外层审计四组全部main/worker工具命令，没有实际`--stop`、pkill/killall或直接kill等调用；daemon日志只含正常退出或user interrupt的含糊关闭信息，不能确认责任来源。原始tail在父目录`external-pi-review/deepseek-daemon`，命令审计为`pi-port-daemon-stop-audit.json`。本项记为原因未确认的运行中断，不采信外部agent责任推断，也不归为模型响应慢。worker改自己项目`org.gradle.daemon=false`后继续重试，未改全局；有效C仍需完整同UUID、非默认设置、正常stop/restart证据。

GLM的production session `c14be2ee…`、`b6fa10f5…`也有daemon disappeared日志，随后自主启动`0853b5eb…`重试。不是仅DS一组的现象，但现有证据不能确定是共享资源、宿主生命周期还是其它原因；不将其归责给某个agent。此时GLM的consumer存档`New World`已实际存在，不能把全部启动等待解释成缺存档。外层不停止合法有界启动等待，不向实验内给环境/实现修复答案。

Gemini截至运行约64分钟有未收敛迹象：848次find只有243种参数，同一Config.java查询20次、BlockWirelessEnergyHeater.java18次；`target/src`最新实际mtime仍06:35:04Z，Java改动最后在06:30:39Z，约50分钟没有源码/资源变更，也没有jar或新的编译。原始会话仍持续产生查询，不能把API调用活跃等同交付进展。计数及实际文件mtime在父目录 `pi-port-worker-tool-audit.json`；此时未擅自终止worker或把最终结果标失败，已向父代理报告。main仍阻塞，Watch未送达，宿主因素需与模型能力分开说明。

Gemini后续在07:36:13Z遇到provider错误：`Quota reached. Please wait 2h59m33s`。main的wait因此返回，随后自主核实际diff、构建失败、无jar和空runtime/consumer，并resume同一个worker696eab4a…；07:36:42Z再次报约2h59m的配额等待。该时有1145条带gemini-3.8-flash-n/low的有效响应和2条quota error。main未换writer或接管代码，实施后独立审查已开始。阻塞触发是外部配额，不能全部归为模型能力；此前大量重复查询与无代码变更的过程也不能因此抹去。按父代理指令，外层不再给Gemini发prompt/resume或模型请求，不自动等待配额恢复重试，保留现有宿主和main自主结论，其余组继续。当时结论待main收尾，A–D均未验证。

Gemini main于07:38:22.762Z完成阻塞状态下的审查并返回`agent_settled`，距宿主开始4594.273秒（76分34.3秒），明确结论“未完成，不能判通过”，不是成功完成移植。外层实际读review/skill-feedback、构建末尾100 errors/BUILD FAILED in25s，确认runtime/consumer为空，无owned游戏/显示进程。A–C未验证；D构建失败，专服/production未执行，无最终jar/hash。main承认首轮S0跨度过大、blocking等待导致Watch未产生有效中途监督；终态工具证据有1000次find，且主要规则冻结skill已明确，不将未遵守要求包装成skill缺口。外层未给Gemini任何提示、没有换模型或触发恢复。当时按父指令保留idle Pi宿主及其私有配置；不能把游戏进程清理等同全部宿主清理完成。

用户随后明确确认由其本人主动停止旧Gemini实验。08:08:22.970Z的`host/shutdown`标记、08:08:23.921Z宿主exit0以及`host/agent/auth.json`/`models.json`清理均由该次用户操作产生，不再标为来源未知。本监控和父代理没有执行该次关闭；用户确认是停止归因的依据。主代理至返回未完成为76分34.3秒，宿主从启动到用户关闭共106分35.4秒；后续idle等待不计为模型工作。用户停止不是模型自然完成，也不改变此前重复查询、未收敛、provider配额错误和main恢复审查的独立事实；不会自动恢复或重试本组。

## 外层监控边界与当前进展

外层未给四组移植实现提示，没有替 main 或 writer 写移植代码。首轮监控时四个 main/worker 宿主进程均已按记录 PID 实际核对仍存活。该事实属于首轮历史检查点；最终状态见下方停止记录。首轮监控时四组各仅一个 Agent 派发，worker 均有持续工具活动，尚无提前结束需宿主续航的情况。所有结果仍待 main 自行闭环及外层复核。

后续如 main 已 settled、writer 也无活跃工作而任务明确未完成，可给通用继续提示，必须单独记录，不伪称完全无需宿主续航。工程返工、执行违例、环境故障和 skill 缺口分别归因。最终核查目标产物身份、原始状态与PNG及 owned 进程清理后，才关闭对应 Pi 并填写结果/耗时。

单次并行试验受宿主、缓存、网络和资源竞争影响，不能单独用耗时宣称某模型稳定更快；Pi 与 Codex 的宿主/模型组合也不是只更换 worker 的严格单变量比较。

## 已发现执行偏差与外层操作干预

E1（DeepSeek/MiniMax）：外层只读检查原始 worker JSONL，发现 DeepSeek 在 06:27:49Z、06:29:16Z、06:29:39Z 依次执行 `cd /tmp && rm -rf apisrc|nfsrc|fapisrc` 后新建并解压 API 源码；MiniMax 在 06:29:47Z、06:29:54Z 写入 `/tmp/nfsrc`、`/tmp/nfsrc2`。两个 writer 因而交叉使用了同一个 `/tmp/nfsrc`。`stat` 记录的目录 birth 时间与命令相符，但不能证明 DeepSeek 执行 rm 前目录不存在，不能声称没有删除旧内容。此事违反初始只写本轮根的边界，不是源码签名或移植实现提示；所见内容是同版本 NeoForge/API 参考源码，尚无读取旧移植解法的证据。

经父代理授权，06:33:47Z 向两个 main 的 RPC steering 队列各发送一次纯操作纠偏：由原 worker 后续只写本轮根，停止改删共享 `/tmp`，保留已有文件和原始偏差，不盲目清理，不换 writer、不热重载插件。完整原文在各组 `host/monitor-interventions.jsonl`，request ID 为 `monitor-boundary-deepseek` / `monitor-boundary-minimax`。不能按零偏差独立通过报告这两组。现有临时目录在双方不再使用且归属明确前不删除；这次干预没有给任何替代移植实现或源码答案。

两条 RPC 回应均为 `success:true/disposition:queued`；main 还阻塞于 `get_subagent_result(wait:true)`，所以不把“已入队”写成“已收到并执行”。06:36:16Z/20Z MiniMax 又在 `/tmp` 先删除再创建 `fapi`、`fapi2` 并解压 API jar；此时尚无 main 消费纠偏的证据。新增目录也保留原状。未因这条操作纠偏 abort 正在工作的 writer。

E2（DeepSeek）：06:39:37Z worker 执行 `find / -name '*.patch' -path '*neoforge*' 2>/dev/null | head -3`，未排除 `/home/wangyu/server`、`/backup`、`/home/wangyu/win`，违反初始搜索约束。此后约4分20秒无工具返回，不能归因于模型响应慢。外层先保存实际进程树：Pi1624985 → bash1796583 → find1796589/head1796590，命令行完全匹配。经父代理授权，于06:43:58Z只向find1796589发SIGTERM，0.1秒后确认退出，Pi仍存活；未终止writer/Pi或共享进程。证据在DS组 `host/monitor-stopped-global-find.json`。操作事实另发送main队列（`monitor-stopped-global-find`）；仍需等main实际消费。不提供移植实现提示，此为执行违例与外层操作干预，不是skill缺口。

E3（GLM）：06:38:10Z起多轮编译主动写 `/tmp/err1.txt` 等通用文件，06:44:06Z完整构建重定向至 `/tmp/build1.log`。这不是宿主自动产生的 Pi bash 输出日志，同样违反只写本轮根；文件名通用，不能假设此前不存在。原始 worker 命令与当前文件 inode/birth/modified 元数据另存父实验目录 `pi-port-glm-log-boundary-observation.jsonl` / `pi-port-glm-log-boundary-stat.txt`。发现时尚未删除这些文件或修改目标实现。

进一步核实：`err1.txt` 至 `err9.txt` 的 birth 均为2026-10-24，本轮重定向修改了原 inode，已证明覆盖本轮前存在的文件；不能仅写“可能存在旧内容”。原内容没有留存，无法据本轮记录恢复。`errA.txt` 与 `build1.log` 的 birth 才在本轮。不能删除旧路径并声称完成无损清理，也不能称本轮没有修改外部文件。

经授权向 GLM main 只发送一次同类事实提醒（`monitor-boundary-glm`），RPC `success:true/disposition:queued`。仅纠正日志写入边界，未给实现提示、未中断编译或运行。至此DS/MiniMax/GLM均已有明确执行边界偏差，不能称全过程隔离；Gemini仍需持续核查，不能以尚未发现偏差证明绝无偏差。

E4（MiniMax）：06:55:11Z worker 执行 `find / -name "ILevelExtension.java" -path "*neoforge*" 2>/dev/null | head`，未排除受保护目录，阻塞约5分08秒。外层按同类操作边界标准先向父代理报告具体归属并保存身份：Pi1624997 → bash1975777 → find1975799/head1975800。07:00:18Z只向该find发SIGTERM，0.1秒后确认退出，Pi仍活；随后shell/head自然退出。证据在MiniMax组 `host/monitor-stopped-global-find.json` 和 `monitor-global-find-precheck.txt`。操作事实向main队列另发送 `monitor-stopped-global-find-minimax`，不提供API签名或移植答案。未终止writer/宿主，此阻塞同样不能归因于模型响应慢。

E5（MiniMax）：07:26:27Z又执行 `find / -name "Codec.java" -path "*com/mojang/serialization*" 2>/dev/null | head -3`，同样未排除受保护目录。外层向父代理报告精确归属Pi1624997 → bash2251649 → find2251652/head2251653，按已授权边界处理在07:29:25Z只SIGTERM该find，0.1秒内退出，Pi仍活。阻塞约2分58秒，证明另存 `host/monitor-stopped-global-find-2.json`，不覆盖E4记录。未终止writer或重复排入泛化提示；已有搜索约束消息仍queued。两次搜索需作为两次实际操作干预记录，不能合并成一次或称模型已消费纠偏。


## 旧三组停止与资源收尾（2026-11-01）

用户要求重跑新实验后，父代理决定停止旧 DeepSeek/MiniMax/GLM 三组，保留当前结果。本次是外层主动停止，三组未完成 A–D 与 main 最终独立审查，不能算模型自然完成，也不能将未完成项一律归为能力失败。停止前保存源码状态、main 最后活动、worker 会话、owned MC 会话、实际进程和已有证据。DeepSeek 已有完整构建与较强 A 原始证据，B/C/D 尚未完整外核；MiniMax 已有 jar 和客户端过程证据，完整构建与 A–D 未核齐；GLM 已有完整构建、A/B 部分可靠证据，C 的 UUID/非默认配置与 D 的目标 jar 身份仍未通过外核。三组 main 此前均长期阻塞等待同一 worker，未做实施期审查；此前纯操作通知仍仅有 queued 证据。

按 owned 会话逐个先发 mcdebug session stop：DS prod3、MiniMax port-target 发布停止令牌，GLM port-target/prod-consumer 返回成功；随后各组 host/shutdown 有序退出，原生 manager 清理子代理/jobs。三个 host 最终均 exited、exit_code=0。复核无迟到的游戏 JVM；确认显示没有其他客户端后，只向本轮 owned Xvfb（217/231/250）及其明确归属的残留 wrapper 发 SIGTERM。最终 owned 游戏、worker、Pi、显示剩余为 0。

宿主退出后删除三组私有 host/agent 配置和凭证副本，未读取或输出凭证内容，未碰用户原始配置。共享 GradleDaemon 2181497、2817158 受保护而保留，未停止共享 daemon、未删除共享 /tmp 或锁文件。保留源改动、产物、会话和原始日志。最终证据目录为 `/home/wangyu/.cache/mcdebug-port-skill-eval/external-pi-review/old-round-stop-20261101T083325Z/`：checkpoint.json、managed-stop-results.json、host-shutdown-requested.json、display-foreign-client-audit.json、display-stop-and-private-config-cleanup.json、post-host-process-audit.json。Gemini 已由用户此前停止，本次未操作该组。
