# Pi 提示词栈审计（2026-11-01）

本轮只读审计日常 Pi 配置；未修改插件、全局设置或移植 skill。结论是已有可复现的提示词一致性问题，值得先修；尚不能证明某一插件导致 Sol 派发后等待。

## 取证方式与边界

- 使用当前全局 Pi 1.2.0 SDK，以及当前配置的 6 个 package、38 个 extension 入口。
- 当前 cwd 为 mcdebug-wt-121；保留正常 AGENTS、skill 目录与工具配置。MCP 在诊断中关闭。
- 分别捕获无插件 Sol、正常 Sol、会话内打开子代理后的 Sol、配置默认 CodeBuddy DeepSeek、Astra，共 5 个新内存会话。
- 在最终 provider payload 形成后、模型请求发送前截断；不执行模型生成或工具调用。HTTP fetch 同时被禁用。启动时的模型目录、配额等请求也因此不能联网。
- 记录资源路径、文件哈希、工具说明、动态 hook 前后变化与最终请求提示词。所有最终捕获均无 extension hook 报错。
- 默认 DeepSeek 的诊断截断错误触发了 CodeBuddy fallback，故其 result.json 的最终 session.model 是备用模型；payload-prompt.json 才是本次请求模型，仍为 deepseek-v4.1-flash (x0.11)。这不是一次真实服务失败或模型效果测试。
- 插件工作区另有并行升级修改；本轮没有构建或覆盖它们。日常快照对应当时加载的 bundle 哈希，不能用当前源码状态替代先前实验的冻结快照。
- TUI 专属逻辑、已有目标、压缩后恢复、上下文压力提示分别按源码核查；新会话没有覆盖所有条件分支。

复现脚本：`/home/wangyu/.cache/mcdebug-port-skill-eval/audit-pi-effective-prompts.mjs`。
原始快照：`/home/wangyu/.cache/mcdebug-port-skill-eval/pi-effective-prompt-audit/`，其中 `summary.json` 是摘要。

## 实际构成

基础角色提示 → 当前可见工具的 promptSnippet / promptGuidelines → Pi 文档入口 → AGENTS 层级 → skill 目录 → before_agent_start 动态文本 → context / context_with_system → provider payload hook → 最终请求。

工具 description/schema 还会独立随请求发送。因此，只看 SYSTEM.md 或 before_agent_start 不足以还原模型收到的全部约束。

| 场景 | system 字符 | 工具数 | 工具 JSON 字符 |
|---|---:|---:|---:|
| 无插件 Sol，保留上下文和 skill 目录 | 62,265 | 4 | 3,242 |
| 正常 Sol，子代理默认关闭 | 64,836 | 7 | 7,519 |
| 正常 Sol，子代理打开 | 72,242 | 17 | 47,270 |
| 默认 CodeBuddy DeepSeek | 64,836 | 11 | 11,371 |
| 正常 Astra | 64,836 | 7 | 7,519 |

字符数不是 token 数。工具 JSON 字符包含参数 schema。mcdebug 目录的三层 AGENTS 共 54,111 字符（全局 757、server-v2 7,237、项目 46,117），占基础提示文本约 87%。这部分是当前位置的项目文档成本，不能归因于插件，也不代表隔离移植实验的提示长度。

## 已确认的问题

### 1. 子代理关闭时，bash 仍引用隐藏工具

正常 Sol 的工具只有 read/bash/edit/write/web_reader/web_search/create_goal，但 system rules 明确要求：

- 用 monitor 持续观察事件；
- 等待优先 jobs attach 或 monitor；
- 用 jobs list/output 查看后台任务。

jobs、monitor 当时均不在最终请求工具列表。来源是 pi-subagents 对 bash 的替换，以及子代理开关把配套工具隐藏。这是能力说明与实际能力不一致，优先级最高。

来源：`pi-subagents/src/jobs/patty/tools/bash.ts:78`。证据：normal-sol/system-at-model.txt、payload-prompt.json。

### 2. 图片代理的推荐与禁令并存

开启子代理后的 Agent description 无条件推荐 image-reader 用于看图；filter-image-reader 又向视觉模型追加“禁止调用 image-reader”，并在 tool_call 真正拦截。

模型收到两处相反的选择指令。可以直接按模型能力生成准确的代理列表，避免长期保留推荐再补禁令。

来源：正常快照 Agent-description.txt；`pi-extensions/extensions/filter-image-reader.ts`。

### 3. 监督策略跨工具反复注入

Agent、get_subagent_activity、get_subagent_activity_detail、watch_subagent、steer_subagent 的 promptGuidelines 多次重复：显式请求才监督、单次错误不干预、让子代理自愈、只纠正方向/范围/验收、避免微观指导。

这些 guidelines 被汇总进主 system rules；部分策略还出现在工具 description，watch 通知正文也再带一份。正常 Sol 开启子代理后，rules 从约 2,816 字符增至 9,502 字符。

这些约束单独都有用处，但叠加后，“不干扰子代理”得到远多于“主代理应继续完成独立审查”的篇幅。它是合理的行为倾向假说，而非已证实因果。

来源：`pi-subagents/src/index.ts:1767`、`:3042`；`src/subagent-supervision.ts:891`。实际文本以快照为准。

### 4. 不常用 workflow 的整份手册常驻

当前 full 模式下，Agent description 为 6,879 字符，SubagentWorkflow description 为 20,874 字符，后者包含大量 API、脚本用法和恢复说明。两项约 2.8 万字符，还未计算参数 schema。

常见单 worker 任务也承担这份成本。应优先缩短工具入口，把完整说明按调用需要读取；避免再追加更多“注意事项”抵消已有复杂度。现有 toolDescriptionMode=compact 是可测的配置选项，但本轮没有切换日常设置，也不能假定它同时缩短 workflow。

来源：快照 Agent-description.txt、SubagentWorkflow-description.txt；`pi-subagents/src/workflow/tool-description.ts`。

### 5. 通用子代理继承父提示，再追加相反工具偏好

general-purpose 默认 promptMode=append；buildAgentPrompt 把父提示词完整置于前缀，再追加 sub_agent_context。父提示要求 bash 执行 rg/find，用户全局偏好 rg/fd；追加桥段却要求 grep/find 工具，明确不使用 bash grep/rg/find/ls。

这是已确认的文本冲突。完整继承还可能把父角色工具说明带入不同工具集的 child，但本轮没有新建真实 child 截取最终请求，后一点仍需验证。Explore/Plan 使用 replace，不应混为一谈。

来源：`pi-subagents/src/prompts.ts:106`、`src/default-agents.ts:24`；全局 AGENTS。

## 不能误归因的部分

| Package | 本轮实际影响 |
|---|---|
| pi-subagents | 替换 bash；开启后提供代理/监督/任务工具；是常驻策略文本的主要来源 |
| pi-extensions | web_reader、Z.AI web_search、图片交付与视觉模型禁用 image-reader；大量其他入口属于 UI、计费、provider 实现 |
| pi-goal | 即使没有活动目标，create_goal 工具及约 1,234 字符 guidelines 仍可见；活动目标持续执行文本是条件分支 |
| pi-openai-toolkit | 注册 context/search/image 等能力，再按模型与配置决定暴露；本轮 Sol 的相关 hook 没有追加 system 文本，也没有重写提示内容 |
| pi-smart-compact | 当前 provider gate 明确关闭 openai-codex，因此不能解释本轮正常 Sol 的工作倾向；默认 DeepSeek 会注册 smart_* 工具，压力/压缩提示另有条件 |
| pi-btw | 本轮 context hook 未改变 system；侧聊用的独立系统提示不能当成主模型常驻指令 |

没有发现 toolkit 给当前 Sol 换成一整份 Codex CLI 角色提示。settings 中旧 codexContextModels 字段不能据此推断运行行为。

Toolkit web-search 的 removeToolkitWebSearchPrompt 会从自身 marker 截断到全文结尾，静态上可能误删其他插件追加段。但本轮该 route 未启用，没有发生截断；这是潜在组合风险，不能列为已发生故障。

## 与四个移植实验的关系

四个 Pi 移植实验显式禁止默认扩展发现，只加载 subagents、指定 provider 与审计扩展，并关闭普通 context/skill/template 加载。因此，日常图片、goal、btw、smart-compact、toolkit 叠加不是那四次等待的直接解释。

另做了冻结历史的 Sol/low 下一步重放，保留历史与工具不变，仅修改 system rules，不执行生成的工具调用：

| 提示变体 | 首轮等待 | 重复轮等待 | 合计 |
|---|---:|---:|---:|
| 原规则 A | 4/4 | 2/4 | 6/8 |
| 删除十条重复限制 B | 2/4 | 4/4 | 6/8 |
| B 加一句主动监督职责 C | 2/4 | 未重复 | 2/4 |

总计 20 次请求。A/B 首轮效果在重复轮反转，不能声称删除这十条就能解决等待。该实验只测一个决策点，也没有证明整个任务执行表现会改善。

原始证据：`sol-supervision-prompt-audit/results/summary.json`、`results-repeat/summary.json` 及对应 controls.json，位于上述 cache 根目录。

## 建议的处理顺序

1. 修正实际工具与提示不一致：关闭子代理时的 jobs/monitor 引用；按视觉能力生成 image-reader 列表。
2. 把监督政策集中到一处，其他工具只保留各自用途与必要操作条件，删除重复限制。
3. 缩短 Agent/workflow 常驻说明；父子提示分别按实际角色与工具构建，减少整份父提示无条件继承。
4. 先验证最终请求的一致性，再用同一历史重复采样；最后重跑端到端监督任务。评价是否继续做独立工作、能否发现验收问题、是否最终完成，而不是只看有没有调用 watch。

本轮没有应用这些变更。目标应是删掉冲突和重复，而不是为每个潜在例外再增加禁令。

## 后续落实（用户授权后）

用户要求移除 image-reader 及其拦截插件，修正其余问题，暂缓 Agent/workflow 说明长度优化。随后确认全局 Pi 与插件开发 SDK 已升级至 1.4.0，后续验证使用实际 1.4.0。

- 删除用户级 agents/image-reader.md，删除 pi-extensions 的 filter-image-reader.ts 及 package 注册项和 README 条目；保留 image-view 的图片显示能力。
- bash_bg/jobs/monitor 从子代理可见性开关中分离，随 owned shell 一起可用，关闭 Agent 不再让 bash 引用隐藏工具。
- 监督政策集中到 Agent guidelines：继续有用的独立工作并审查现有改动/证据，无可做工作时再等待。其他监督工具只保留操作说明，watch 明确说明阻塞 result wait 会延后通知。
- 删除 append bridge 中覆盖搜索偏好的 grep/find 两行。父提示继承方式保持原设计；本轮没有把 append 改成 replace。
- Web Search 仅删除自身完整已知提示段，保留后续其他插件文字，覆盖切路由和关闭搜索两种情形。
- Agent 保留 full 模式与 Writing the prompt 等内容，workflow 的 20,874 字符说明保留。没有进行整份说明瘦身。

修后实际请求（只截取、不执行模型生成）存于 `/home/wangyu/.cache/mcdebug-port-skill-eval/pi-effective-prompt-after-fix/`。正常 Sol 开/关两例均无 hook 错误、无 image-reader，shell 配套工具均可见；开例 system 中独立工作和方向/范围/验收政策各出现一次。检查脚本 `verify-pi-prompt-fix.py` 与 verification.json 同存于该 cache 上下文。修前/修后跨越 Pi 1.2→1.4 升级，不将字符差异解释成单一提示修改的行为效果。

验证结果：

- pi-subagents：lint、类型检查、bundle 一致性检查通过；87 项定向检查通过。默认高并发全量运行出现一次 workflow 纯字面量求值 100ms 超时，其余 2,455 项通过；该示例独立复核 24/24 通过。以 `npm run test -- --maxWorkers=8` 完整重跑后，135 个文件通过、1 个文件跳过，2,456 项通过、15 项跳过，耗时 91.59 秒。原有 Vite 配置未来兼容性提示仍会输出，本轮没有改测试配置。
- pi-openai-toolkit：完整 711 项测试及类型检查通过；Web Search 定向 5 项复核通过。
- pi-extensions：安装 bundle 一致性检查通过；正常 Pi 的最终请求截取确认拦截插件与 image-reader 代理均已消失。
- 10 个反向检查均检出预期断言失败：独立工作提示、集中监督政策、重复政策、watch 阻塞说明、activity ID 来源、隐藏 shell 配套工具、find/grep 追加禁令各一项、watch 证据边界，以及 Web Search 误删后续文本。源码逐项恢复，最终 bundle 与恢复后的源码再次核对一致。

日志为 `/home/wangyu/.cache/pi-prompt-fix-*.log`；反向检查详单为 `/home/wangyu/.cache/mcdebug-port-skill-eval/pi-prompt-fix-mutations/results.json`。这些验证确认提示构成和实现一致性，尚未重跑端到端移植任务来测量 Sol 行为改善。现有四个隔离 Pi 宿主保持冻结，不热加载本轮修改。

版本路径更正：随后发现 `pi` 命令实际指向 `~/.local/share/pi/releases/1.4.0/bin/pi`，旧 npm 目录仍留有 1.2.0 SDK。最初的修后请求截取脚本引用了旧 SDK；核心插件测试使用的开发 SDK 始终为 1.4.0。已把截取脚本切换至实际 1.4.0 release 内的 SDK，重跑开/关两个场景，结果存于 `pi-effective-prompt-after-fix-1.4/`，manifest 均记录 sdk_version=1.4.0。两例无 hook 错误，原修复断言均再次通过；system 字符分别为 66,992 和 69,236。前述旧快照保留作历史证据，不再作为 1.4.0 请求依据。
