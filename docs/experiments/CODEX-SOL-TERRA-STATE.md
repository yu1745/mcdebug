# Codex Sol → Terra 完成检查点

本轮工作已完成：4轮普通铁炉A–D移植对照、1轮r2准备阶段前测均已结束，正式skill写回r2，外层证据/用量/清理审计通过。没有待启动或继续运行的本轮实验。原Pi实验保持取消，不恢复。

- 正式skill：skills/neoforge-fabric-port/SKILL.md，5文件46569bytes；与冻结r2逐文件一致，改4文件5处内容。
- 最终报告：docs/experiments/codex-sol-terra-skill-loop.md。
- 用量与官方单价折算：docs/experiments/codex-sol-terra-token-cost.md。
- 实验根：/home/wangyu/.cache/mcdebug-port-skill-eval/codex-sol-terra-v1/。
- 外层最终审计：outer-review/completion-audit.json；各轮*-final-review.json连接原始图、状态、版本身份、hash和清理事实。
- 权威冻结输入controller-inputs/，版本releases/r1与r2，正式写回记录outer-review/skill-applied.json。
- 原过程记录：outer-review/run-journal.md；所有失败/受限证据按原来源保留。

四轮均为Sol/low→唯一Terra/low/fork none，Sol同时实施主代理监督与skill候选优化。普通铁炉A–D通过：原基线1 30分04秒/77167684tokens（开局外层纠正型号误判），原基线2 43分27秒/140095965，r1-1 28分32秒/90096249，r1-2 38分32秒/117321903（外层清单修复，无解题提示）。r2前测5分09秒/3634825tokens，只验证空消费者准备与标题页RPC；有显示保活返工和过宽AGENTS文件名扫描记录。

合计428316626实验tokens，Standard基本单价折算USD35.0141508；仅Terra按API长上下文规则调整的另列参考USD38.4169830。Sol无长上下文倍率。不是订阅实付账单，不含外层supervisor。

结果不外推整模组全功能。基线2保留资源条件静态风险；r1-2专服历史JVM PID未采样，正常受管退出/保存日志/根限定进程扫描证明停止，不伪造精确历史PID。最终报告保留控制记录修复、覆盖日志摘录和局部诊断留存限制。

脚本在/home/wangyu/.cache/mcdebug-port-skill-eval/：codex-sol-terra-observe.py、codex-sol-terra-usage.py、audit-codex-sol-terra-completion.py。运行观测/用量复算不会发模型请求。启动器codex-sol-terra-loop.py现有5组均已启动过，拒绝重复launch；没有新轮调度。

当前仓库仅新增/修改skill与实验文档；未更改mcdebug业务代码，未commit/push。保留共享Gradle/Maven缓存与非owned daemon，未清锁或共享/tmp；所有实验私有auth副本已删除。搜索仍排除/home/wangyu/server、/backup，默认排除/home/wangyu/win。

后续用户变更：正式skill新增可选“NeoForge存档→Fabric加载”互操作测试，启用时同时执行server API操作/读回和截图视觉辨认。此新增段未运行实测，旧实验r2及完成审计保留为当时快照，勿用旧冻结版本覆盖该新增内容。
