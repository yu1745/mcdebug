# Codex 提示体系移植到 Pi 的可行性

2026-11-01。只读调研与离线装配验证，未启用新 profile、未修改日常 Pi 配置、未发起模型生成请求。

结论：可以移植 Codex 客户端可见的提示体系，Pi 1.4 已有整份提示替换、完整请求 hook、工具注册、子代理和脚本工具入口，主要工作可在扩展层完成。工具适配必须覆盖参数、返回值和消息/等待语义，不能仅替换名称。提示层迁移可实现，模型行为是否接近 Codex 仍需对照实验。

## Codex 的来源与范围

核查 OpenAI 官方仓库当前 HEAD `67f0994b532b8740f14b5b738af7b7b2a915b1d6`：

- [模型目录](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/models-manager/models.json) 的 `model_messages.instructions_template` 含当前模型基础提示。Sol 为 21,769 字符，Terra 21,420，Luna 18,037；不要把旧版 GPT-5 markdown 当作当前 Sol 的默认。
- [提示解析入口](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/prompts/src/model_messages.rs) 按模型元数据和内置 fallback 解析模式、权限、工具、持久任务等不同族的文本。
- [多代理角色与模式](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/prompts/src/model_messages/multi_agent.rs) 区分 root、subagent、显式派发和主动派发，目录可覆盖默认值。
- [压缩提示](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/prompts/templates/compact/prompt.md) 属于独立生命周期请求，替换主会话提示不会自动替换它。

“全套”应理解为按实际模型、角色、模式和功能装配当前生效的各层。不能把 Plan/Default、root/subagent、不同审批模式的模板同时拼进去。工具 schema 和 description 也是模型输入，必须一起处理。客户端不可观测的服务端实现不属于可完整复刻的范围。

## Pi 1.4 的实际支持

实际 CLI 安装位于 `/home/wangyu/.local/share/pi/releases/1.4.0/`。旧 npm 全局目录仍有 1.2.0 SDK，不能根据 `command -v pi` 的父目录推断 SDK 位置。本文最终离线验证使用实际 1.4.0 release 内的 SDK。

`dist/core/system-prompt.js` 的行为：

1. `customPrompt` / `--system-prompt` / SYSTEM.md 替换默认基础角色，并跳过默认 tools/rules/docs 段；因此并非只能在 Pi 原提示后追加 Codex 提示。
2. 项目 contextFiles、skills、APPEND_SYSTEM 仍可加入。通常应保留用户项目规则和真实可用的 skill 目录，主动控制追加来源。
3. `forceSystemPrompt` 可精确替换整份提示文本；使用它的组装器需要自行保留项目规则、环境和技能信息。
4. `before_agent_start` 可改变提示；`context_with_system` 可处理完整转录；`before_provider_request` 可处理最终请求。其他插件仍可能在后面改写，profile 需要明确装配顺序和归属。
5. 真正的工具声明独立存在，不能因为提示写了工具名称就认为能力已经注册。

1.4 的 OpenAI 适配已支持 `commentary`/`final_answer` phase 的保存和重放，也支持在 input 中用 `additional_tools` 声明工具。因此诊断不能只查顶层 `tools` 字段。OpenAI Codex provider 把开头 system 文本放进 Responses 的 `instructions`；需要精确保留其他 developer 层次时，应在请求组装处处理。

## 工具映射

| Codex 能力 | Pi 对应入口 | 适配重点 |
|---|---|---|
| functions.exec | codemode | 都支持 JavaScript、tools.*、并行调用和输出筛选；pragma、脚本运行时、store 持久化和挂起/恢复不同 |
| functions.wait / yield_control | 无直接相同的 codemode 单元恢复接口 | Pi 可把长操作交给后台 job；若保留 Codex 的 cell 语义则需额外适配 |
| exec_command / write_stdin | bash、bash_bg、jobs | timeout 单位、cwd、PTY/stdin、输出和会话 ID 不同，不能只改名字 |
| apply_patch | edit/write，或独立 patch 工具 | exact replacement 与 diff patch 是不同契约；偏好原格式时可提供兼容工具 |
| spawn_agent | Agent | 参数、模型继承、上下文继承、角色提示和递归配置不同 |
| followup_task | steer_subagent / Agent(resume) | 要按正在运行或已返回选择行为 |
| send_message | steer_subagent / send_parent_message | Codex queue-only 与 Pi 的 steer、根父唤醒、已结束任务处理并不完全相同 |
| wait_agent | get_subagent_result 加等待适配 | mailbox 任意消息、指定结果、有限超时和取消是不同条件；现有 wait:true 不是直接等价物 |
| view_image | read 图片 | 按实际图像输入支持组装说明 |
| MCP / Web / skills / 用户问题工具 | 已安装插件和相应适配 | 使用实际注册的能力与 schema；不能保留不存在的工具说明 |

OpenAI 的 [Codex Prompting Guide](https://developers.openai.com/cookbook/examples/gpt-5/codex_prompting_guide) 明确讨论了替代文件工具和自定义工具的适配，也强调工具格式与模型习惯的关系。这支持工具可移植的判断，但它针对该指南所述模型，不能据此保证 Sol 或 Flash 的同等效果。

## 比较合适的实现形态

提供一个可切换的 Codex prompt profile，通过同一个组装器选择模型基础提示、主/子角色、工作模式、真实环境与工具说明。技术上优先使用现有扩展和 SDK；确需精确行为的工具再加薄适配器。

该 profile 要同时覆盖主代理和 worker，并控制旧插件行为提示的重复注入。只替换 SYSTEM.md、保留其余插件原描述/动态提示，不构成完整替换。权限、并发槽位、消息格式、技能工具可用性都应由实际 Pi 环境生成。

压缩若也要求对齐，则通过 `session_before_compact` 接管摘要生成并返回 Pi 要求的 CompactionResult。普通 customInstructions 是追加焦点，不能当成完整替换压缩模板的开关。可以保留 Pi 的存储/截断基础设施，并适配模型看到的交接文本。

建议先测提示 profile：同模型、同思考级别、同任务、同工具实现，仅更换已适配的提示。再单独测需要补齐的消息/等待实现，避免把提示变化和宿主变化合并成一个无法归因的实验。评价独立审查、正确纠偏和最终完成情况；单独调用 watch 不能证明监督有效。

## 已完成验证

离线脚本：`/home/wangyu/.cache/mcdebug-port-skill-eval/codex-to-pi-feasibility/probe-prompt-replacement.mjs`。

使用实际 Pi 1.4.0 提示构造函数及公开 Sol 模板，六项检查通过：保留 Codex 基础文本、移除默认 Pi 身份、移除默认工具 guidelines、保留项目规则、确认追加仍可能发生、确认 force 可精确替换全部文本。模型请求与全局配置写入均为 0。结果为同目录 probe-result.json。

同时更正了前轮诊断脚本误指旧 npm SDK 的路径；修后的日常配置开/关请求重新用实际 1.4.0 截取至 `pi-effective-prompt-after-fix-1.4/`，工具声明包括 input 中的 additional_tools。具体结论见同目录的验证 JSON 与此前提示词栈审计的版本更正。

本轮尚未安装 profile、实现新工具适配或测试真实模型的行为变化。
