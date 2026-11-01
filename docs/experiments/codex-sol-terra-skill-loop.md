# Codex Sol → Terra：隔离对照与 skill 闭环

2026-11-01 完成 **4轮普通铁炉A–D移植任务和1轮准备环节前测**。全部使用原生Codex CLI 0.166.0、Sol/low主代理和唯一Terra/low worker。Sol负责源码决策、roadmap、独立diff/运行/读图审查，同时编辑本轮skill候选并给出证据；Terra负责实现和游戏操作。本轮未使用Pi。

## 四轮长任务结果

| 轮次 | 启动至宿主退出 | tokens（含缓存） | 固定范围 | 外层动作 |
|---|---:|---:|---|---|
| 原版基线 1 | 30分04秒 | 77,167,684 | A–D通过 | 外层纠正一次型号不可用误判 |
| 原版基线 2 | 43分27秒 | 140,095,965 | A–D通过 | 无外层解题 |
| r1 复验 1 | 28分32秒 | 90,096,249 | A–D通过 | 无外层解题 |
| r1 复验 2 | 38分32秒 | 117,321,903 | A–D通过 | 只修复外层评估清单，无解题提示 |

长任务总用量 **424,681,801 tokens**。另行的r2准备前测耗时5分09秒、3,634,825tokens，只验空工程准备、首次游戏启动、标题页RPC与正常退出；不算第五轮完整移植。分模型、缓存与官方单价折算见[用量报告](codex-sol-terra-token-cost.md)。

验收范围为普通铁炉：真实生存物品放置与玩家记录、完整55槽菜单、GUI投原铁/煤并自然加工/取出守恒、有火焰和非零进度的实际读图、实际加工64产物后的满输出边界、同世界同UUID保存重启及续加工、完整build/check、隔离专服加载保存停止、最终remapped jar独立生产客户端重放。生产两平面实际读回dev=false/intermediary，来源与最终jar哈希一致。

外层已独立打开关键PNG并核对原始状态、身份、哈希与退出；每轮Sol也直接阅读真实diff和原始证据，至少重放关键交互，逐条记录被拒绝的证据及返工。构建中的test均为NO-SOURCE，不声称完整自动测试覆盖。其余炉型、增强、自动化、能源和多人等未完成游戏覆盖。基线2另保留“tag存在与tag非空”资源条件的静态风险，未运行该反例；这不扩张普通铁炉A–D的通过范围。

## 已写回的 skill

正式文件：[SKILL.md](../../skills/neoforge-fabric-port/SKILL.md)。实验结束时写回的版本逐文件等于冻结的releases/r2，结构与本地引用检查通过。该版本5份Markdown合计 **46,569字节**，初版46,802字节。仅改4文件、5处内容：

1. 型号分配段压缩为实际派发、解析依据和按授权降级，保留独立审查边界。
2. 源码准备抽查实际API签名与方法体，识别Mojang/Yarn混合命名；class_NNN检索阴性不足以证明完整映射。
3. delegation删除重复六步循环，引用主skill的闭环，保留及时原始证据、双方读图和会话交接。
4. 现有服务端验收问句补充：拒绝/停止边界的其它执行前提在观察期间是否仍满足。
5. 原工具安装句改为首次启动前按jar元数据补齐语言适配器与加载器依赖。

前4项由两个全新r1长任务复验。第5项是r2相对r1的唯一增量，另开Sol→Terra准备前测：工具depends没有FLK，但入口adapter=kotlin；worker首次启动前装齐语言依赖，唯一游戏启动日志没有缺adapter错误，TitleScreen/RPC0.8.0及实际加载路径成立，随后正常退出。该前测采用Yarn/Loader0.20.1，局部验证依赖准备；四轮移植使用Mojang映射。

未把环境个例扩写为通用限制。已有规则捕获的配置、资源、JEI、日志覆盖和夹具问题保留在实验反馈中。两轮r1都在首次接受的满输出观察中保持燃料条件成立；四轮整体耗时和用量仍有明显波动，样本很少、依赖选择与缓存状态不完全相同，**未证明通用速度或token效率收益**。

后续按用户要求，正式skill又增加了可选的“NeoForge存档→Fabric加载”互操作测试，启用时同时执行服务端API操作/读回与截图视觉辨认。此新增项未在上述实验中实际运行；本报告的版本哈希、篇幅与审计对应实验收尾时的r2。

## 对照与隔离口径

固定源：Iron Furnaces4.3.2 / MC1.21.1 / NeoForge21.1.209，commit `41fde020636b79154e1c454a893962c639d89e1b`。四轮空target/consumer起步，同37份原版基线；规范化路径后的任务相同，未提供旧移植实现、补丁或解法。模型、思考强度和唯一writer配置相同。源端/目标端源码按实际版本生成或核验复用；Fabric API版本由代理选择，原基线1用0.115.1，其余长轮0.116.17，不能仅以时间差归因skill。

每轮独立CODEX_HOME、凭据副本、TMPDIR、会话、gameDir、存档和显示；共享标准Gradle/Maven缓存。这是工作目录/配置隔离，非OS文件访问沙箱。所有宿主和已记录owned运行进程退出，私有auth副本移除，未清共享daemon、锁或共享/tmp，未commit/push。

记录限制保留：首轮一次外层型号判断纠正；r1第二轮manifest被主代理改为任务记录，外层改用controller-inputs并补回哈希字段，仅修控制记录，未发送解法。准备前测有一次父目录AGENTS文件名搜索，未确认读取邻轮实现；显示保活也发生正常返工。基线2一份失败文件被覆盖后以标注来源的工具输出摘录补存；r1第二轮一份传输诊断没有完整原始输出，专服历史JVM PID未采样，清理依据受管退出、保存日志和最终根限定进程扫描。不能把这些描述成无偏差的完美隔离或完美留证。

## 复算与原始材料

实验根：`/home/wangyu/.cache/mcdebug-port-skill-eval/codex-sol-terra-v1`。权威输入在controller-inputs/，冻结版本在releases/，各轮host/保存真实会话与模型usage。outer-review/*-final-review.json连接外层复核与各轮原始证据，skill-applied.json固定正式skill哈希；retrospective-resource-conditions.json保留静态风险。原过程日志在outer-review/run-journal.md。

```bash
python /home/wangyu/.cache/mcdebug-port-skill-eval/codex-sol-terra-observe.py
python /home/wangyu/.cache/mcdebug-port-skill-eval/codex-sol-terra-usage.py
```

候选副本是提案，正式skill只合入上述已说明的内容。两轮r1、r2前测的输入均保持冻结，没有中途换skill。后续新任务使用正式skill；这些实验已收尾，无待启动轮次。
