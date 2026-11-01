# Pi 子代理中途汇报工具

用户要求补齐 Codex 式子代理主动向父代理发送中途消息，并核查提示词是否需专门引导。实现位于已安装的 yu1745/pi-subagents，现有四组移植实验保持其启动时冻结版本，不热更。

## Codex 源码依据

核查 OpenAI 官方 openai/codex commit `67f0994b532b8740f14b5b738af7b7b2a915b1d6`：

- [内置多代理角色文本](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/prompts/src/model_messages/multi_agent.rs)：子代理被告知可用 send_message 联系运行代理，final 会交给父代理；同时允许模型目录覆盖这些内置默认文本，不能将其称为所有部署的完整提示词。
- [工具描述](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/core/src/tools/handlers/multi_agents_spec.rs)：send_message 的描述说明及时投递且不启动新轮；没有规定固定周期汇报。
- [实际 handler](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/core/src/tools/handlers/multi_agents_v2/send_message.rs)：使用 QueueOnly，与 followup_task 区分。

## Pi 实现

新增仅供托管子代理使用的 `send_parent_message({message})`，目的地由宿主绑定到直接父代理。工具描述和两种子代理 system prompt 模式使用简短说明：影响计划的重要发现、需要裁决的阻塞、必要的范围变化主动汇报，附依据和需求，继续不依赖答复的工作；最终结果仍按任务格式正常返回。没有固定周期或每步汇报要求。

根父代理使用现有 steering 通知并唤醒空闲父代理；嵌套消息仅到直接父代理。这个空闲根唤醒行为是 Pi 的适配，并非照抄 Codex QueueOnly。父代理说明区分仍运行时 steer 与已返回时 resume。回执只表示入队，不声称父代理已阅读。

结果等待另需处理：原 `get_subagent_result(wait:true)` 会一直阻塞到终态，使父代理无法处理子代理决策请求。因此中途消息会释放同一父代理的结果等待（含并行 sibling 等待），worker 不被停止，最终结果不被消费。发送早于注册等待的情况有保留标记，取消等待仍只取消 collection，旧 session/已停止子代理回调被拒绝。

原生通信不依赖递归开关或扩展加载；`extensions:false`、`isolated:true` 仍可汇报，`disallowed_tools: send_parent_message` 则同时移除工具和相应提示。固定目标避免子代理指定任意接收方。

## 验证

单测涵盖投递、sender 身份、终态/消费保持、嵌套归属、并行等待、先发后等、取消、空消息和失败投递。真实 SDK faux 模型集成覆盖 replace/append 两模式的“子代理发问→父代理从结果等待返回→steer→子代理继续并交最终结果”，以及 disallowed_tools 的工具/提示同步消失。相关五个测试文件 38 条通过。

真实模型小任务：Sol/low 主代理、Gemini3.8Flash/low worker；任务要求先由 worker 查明两份配置的时间单位冲突，再由 main 裁决，用户任务和实际派单均未点名工具。Gemini 实际主动调用 send_parent_message，提出有输入依据的请求；main 选择毫秒，同一 worker 经 resume 交付1000ms/2000ms，main独立验证物理时长守恒。总耗时49.153秒。限制：Gemini 发中途消息后结束了当前执行轮等待裁决，故此样本不能证明模型会一直保持执行等待答复；该路径由 SDK 集成测试验证，也不能据单样本保证所有 Flash 都主动汇报。

原始证据：`/home/wangyu/.cache/mcdebug-port-skill-eval/parent-message-live-probe/host/` 的 rpc.jsonl、worker-sessions、manifest.json、audit-summary.json；交付 normalized.json 和 evidence/review.md。probe Pi 已正常退出，私有凭证副本已删除。

最终 `npm run build` 与 `npm run check` 通过：lint/typecheck/bundle一致性通过；135测试文件通过、1跳过，2454 tests passed/15 skipped。首次完整并行运行中，新增SDK测试的默认5秒时限触发一次超时；按同仓其它SDK集成测试改用30秒时限，断言未削弱，最终全套48.44秒通过。13类故障注入均被测试检出；首次对跨父代理唤醒的变异未检出，补齐事件循环排空后重测检出，详 evidence/mutation-checks.json 和 checks/ 原始记录。

插件提交 `1fc517a`（feat: let subagents send interim messages to parents）。现有四组实验保持冻结旧宿主，不将此功能视为已在那四组验证。新启动Pi会话加载本次已重建bundle。
