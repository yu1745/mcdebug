# 移植 skill 与模型层级实测计划

## 持续独立重放循环（用户最新要求）

不在每轮验收后等待用户催办。外层维护通用skill并审原始证据；干净上下文 Sol/low 主代理只接冻结skill、用户验收任务和必要原始源码/工具/基线输入，自行派 Terra/low worker实施，反馈skill改进。外层不提供旧补丁、已完成roadmap或修复答案。每轮完整保存skill哈希、身份调用参数、干预、结果及反馈；只把可泛化证据转成skill修改，再独立重放。强配置达到可接受闭环后冻结，再用干净上下文与目标测Terra/low→Luna/low；不将单次成功描述为统计稳定性。

当前 A2：`/home/wangyu/.cache/mcdebug-port-skill-eval/round-a2-sol-terra`，冻结skill及哈希在manifest.json；只提供37份原版baseline材料，未提供旧Fabric源码/证据。正式派发 `a2_sol_main`，参数gpt-6.1-sol/low/fork_turns=none；其worker应显式gpt-6.1-terra/low且不再递归。A2独占本轮target/evidence/consumer/session/gameDir，原目标保留不动。外层已创建持续goal，不能把中间报告或上下文交接当任务完成。

## Codex 继续执行（2026-11-01，当前状态入口）

用户确认原 Pi 已停止，指定 Sol 主代理同时负责 Terra worker 派发及通用 skill 反馈优化。当前实验进度、派单和验收见 `iron-furnaces-roadmap.md` 顶部 Codex 检查点；下文 Pi 插件未提交/scope阻塞/目标停止于Config等均为历史，插件最终状态以 CODEX-HANDOFF.md 为准。

调用记录：Codex `spawn_agent(task_name=s1_resume, model=gpt-6.1-terra, reasoning_effort=low, fork_turns=none)` 已接受；当前主代理身份由用户明确指定。Codex 工具未附独立 provider 解析元数据，不伪造 Pi 风格的实际模型回显。worker 不再递归，唯一写 target；主代理仅写 skill/实验文档并审查原始工件。

当前 skill 改动来自 C1–C11 的实测编排结果：完整纵向契约保持，逐轮单 owner 闭包，区分局部交付/全工程编译/游戏/发布物证据。不能将其解释成已隔离证明的上下文长度效应。V2 验证 S1R，随后 J1 验证最终发布物；阶段 A 尚未冻结，B 不启动。

状态：插件两项修复已完成并核对实际 diff；完整检查 2443 passed / 15 skipped，主会话独立复跑关键回归 52 passed，bundle 与 diff 检查通过。等待用户 `/reload` 并开启代理工具及递归，再开始移植/模型能力试验。插件改动尚未提交。

## 用户要求与前置顺序

1. 先完成子代理插件的两项修复：删除 `agent_bg` 并集中控制递归；永久保留顶层 agent 历史/结果，在原父会话跨 Pi 重启可查询及 resume。
2. 验证插件后通知用户 `/reload`，随后执行 `/subagent-tools on`、`/subagent-recursion on`、`/subagent-recursion depth 2`，等待用户确认；不得在旧插件会话中用外部进程代理绕过递归门控。
3. 先以 **gpt-6.1-sol 主代理 → gpt-6.1-terra worker** 实际执行移植 skill，基于失败证据优化 skill。
4. 冻结 skill 和任务后，以 **gpt-6.1-terra 主代理 → gpt-6-luna worker** 进行较低成本能力测试；若无法稳定通过，再考虑上移一级。
5. 用户指定的所有 GPT 模型均限 **openai-codex** 渠道，禁止 fuzzy 匹配导致跨 provider 回退。记录调用参数与宿主模型解析元数据；宿主若未附解析元数据则注明未提供，不让LLM自证身份，也不因此重复预检。

外层会话负责实验设计和最终验收；子代理 A 读取 skill 负责 roadmap/审查，并通过正式嵌套 `Agent` 派 worker。外层 main=0，A=1，worker=2，递归上限 2 足够；worker 不得继续派生。用户最新要求：后续派单统一 `thinking: low`（包括 A 和它派出的 worker），只新增直接覆盖修复的必要测试，不扩展测试矩阵、不额外做 mutation 测试；仓库要求的已有完整检查仍执行。

## 最新阶段A结果

白名单更新后的正式 `Agent(openai-codex/gpt-6.1-terra, thinking=low)` 已成功，递归预检通过。身份依据调用参数/宿主解析元数据，不要求LLM自证；宿主未提供额外元数据不是阻塞。早期scope拒绝保留为历史，已解决；短预检turn-limit包装不视为能力失败。

同一worker `8d1eb4a4-d251-47c` 完成原版构建、真实放置/GUI加工/取出、满槽及保存重启，A已直接打开关键PNG。Fabric实施多次纠偏后仍仅有构建/元数据及未接线配置辅助类，compileJava失败于未迁移Config，尚无目标运行证据。按连续无新证据规则停止盲目resume，分类本次执行未收敛，不降低切片标准或由A接管代码，也不宣称技术路线不可行/模型总体不合格。

持久材料：`iron-furnaces-roadmap.md`、`iron-furnaces-worker-task.md`、`iron-furnaces-review.md`；原始证据 `/home/wangyu/.cache/mcdebug-port-skill-eval/evidence/`。skill仅补用户纠正的模型身份取证规则及实际放置误判重放支持的工具可发现性规则。未达到阶段A切片通过/独立重放冻结条件，不开始阶段B。详细风险与清理见review。

## 本轮 A 的正式递归预检：初次阻塞（历史，后已解决）

A 在读取通用 skill 后，使用实际暴露的 `functions.Agent` 发起极小预检：`subagent_type=general-purpose`、`model=openai-codex/gpt-6.1-terra`、`thinking=low`、`max_turns=1`；任务仅要求报告实际 provider/model 和 read 工具可用性，禁止写文件、启动进程或继续递归。

工具原始结果：`Model not in scope: "openai-codex/gpt-6.1-terra".` 当前返回的 enabledModels 仅包含：`antigravity/gemini-3.8-flash`、`codebuddy/deepseek-v4.1-flash (x0.11)`、`deepseek/deepseek-flash`、`minimax-cn/minimax-m3.1-flash`、`openai-codex/gpt-6-astra`、`openai-codex/gpt-6.1-sol`、`opengateway/deepseek/deepseek-v4.1-flash-ultrafast`、`river/deepseek-ai/deepseek-v4.1-flash-1017`、`xcode/claude-opus-5-5`、`zai-coding-cn/glm-5.3`。

结论：正式工具存在，但指定 worker 在本次工具作用域不可用；未创建 worker，实际 worker model 无，depth2 运行与视觉能力尚未验证。A 的主模型请求由外层指定为 `openai-codex/gpt-6.1-sol` / low，本轮没有独立运行时身份回显，不能把请求当额外验证。分类为模型作用域/环境阻塞，不是移植失败，也没有可支持 skill 修改的重放证据。

已按用户要求立即停止：未替换模型/渠道、未绕过递归、未修改配置或插件、未克隆/构建/修改 port、未启动游戏，无 owned session 需要清理。下一步由外层让正式 Agent 的 enabledModels 包含指定 terra，并在新作用域重做同一个极小预检；通过后才继续源码 baseline、roadmap 与实现。此前模型注册记录不代表本轮 Agent 作用域可用。

## 当前模型注册核实

通过 `pi --list-models` 查看 `openai-codex` 全部条目：

| 请求模型 | 当前注册 | 图像能力声明 |
|---|---|---|
| `openai-codex/gpt-6.1-sol` | 有 | yes |
| `openai-codex/gpt-6.1-terra` | 已按官方在线索引临时补入 `models.json` | yes |
| `openai-codex/gpt-6-luna` | 有 | yes |

`gpt-5.6-terra` 不是用户指定的 `gpt-6.1-terra`。其他 provider 的同名模型不能代替。用户已授权临时补入 `~/.pi/agent/models.json`，不更新 Pi：元数据依据 <https://pi.dev/models/openai-codex/gpt-6-1-terra> 及 Pi 上游 catalog；按用户要求将 contextWindow 从官方 500000 下调为 **272000**，maxTokens 保持 128000。已通过本机 schema 校验，`pi --list-models gpt-6.1-terra` 显示 Codex 渠道、272K、图片与推理均支持。模型注册声明不等同于实际运行证据，首轮还需完成一个小型读图/工具调用预检。

## 样本筛选

目标是能同时暴露服务端逻辑、加载器接缝、网络/menu 与真实 GUI 的项目，而不是改几行版本号就能过的示例，也不是把大型整合生态一次搬完。源代码与资源都应有明确可用许可。

### 首选：Iron Furnaces NeoForge 4.3.2

- 仓库：<https://github.com/Qelifern/IronFurnacesNeoForge>
- 分支：`4.3.2`；固定 commit：`41fde020636b79154e1c454a893962c639d89e1b`。
- `gradle.properties`：Minecraft `1.21.1`、NeoForge `21.1.209`；Java 21，ModDevGradle `2.0.89`。
- 该 commit 的 `README-LICENSE.md` 声明仓库及所有组成部分 Apache-2.0，附 `LICENSE.txt`。
- GitHub tree 查询未截断，Java 文件 142 个，有 Gradle wrapper。
- 真实菜单与 Screen、方块实体库存、加工进度、不同炉型及配置，适合数据与像素交叉反馈。
- 构建有配方查看器 API 编译依赖及运行时依赖；已有兼容功能不能通过默默删除来获得通过。
- **不要取默认分支 `4.4.2`**：其 `minecraft_version=26.1.2`，不属于本次目标。

源码检查点：

```text
src/main/java/ironfurnaces/container/furnaces/BlockIronFurnaceContainerBase.java
src/main/java/ironfurnaces/gui/furnaces/BlockIronFurnaceScreenBase.java
src/main/java/ironfurnaces/tileentity/TileEntityInventory.java
src/main/templates/META-INF/neoforge.mods.toml
```

这些只是预定位，不是提前代写路线。完整调用链、真实 owner 和首轮纵向切片由强模型主代理读源码后确定。

### 备选与排除原因

- <https://github.com/Sycraxe/burden>：Minecraft 1.21.1 / NeoForge 21.1.197；28 个 Java 文件，有 BackpackMenu/Screen 和组件库存，规模更小。但代码 MIT、assets **All Rights Reserved**，还带配饰 API 依赖且仓库树没有 gradlew，不作为首选，避免把许可与构建修复混入能力测量。
- <https://github.com/gottsch/gottsch-minecraft-neoforge-Furnace-Ring>：默认分支 `1.21.1-main`，便携加工功能。GitHub 许可证只识别为 Other，尚未核实完整资源授权和 GUI 覆盖；候选而非已批准样本。
- <https://github.com/SammySemicolon/MinersDelight>：需要依赖生态，GitHub 未识别许可证；当前不优先继续投入筛选。

以上是远程源码元数据筛选，**未克隆、未构建、未证明可启动**。参考下载/检出与测试世界不得写进生产目录；若纳入本仓库上级的长期 upstream 检出，遵守其来源注册规则。

## 先做环境基线，避免把基础设施故障当模型能力不足

- 固定源 commit、JDK、加载器、映射、工具版本与构建缓存条件。
- 用隔离 gameDir 和离线存档副本，先验证 NeoForge 参考端能启动、双平面 mdb 可达、截图可读取。
- 确认显示服务、GL、实际窗口/GUI scale、模型直接读图能力和 session 生命周期。
- 原版基线操作、输入夹具和可观察结果落盘，不把目标端生成的第一帧当 golden。
- 原版无法启动、依赖下载失败、模型未注册等单列为环境阻塞，不计作 worker 移植失败。
- 不安装测试模组到生产服；不发布上游 PR/Release，不自行 push。

## 阶段 A：强层级优化 skill

模型：`openai-codex/gpt-6.1-sol` → `openai-codex/gpt-6.1-terra`。

给 A：通用 skill、固定源 checkout、目标工作目录、构建/工具入口和验收契约。A 自己写 roadmap，再派 worker 实现。外层只审查范围、证据与 skill 缺口，不替 worker 偷偷修实现。

首轮建议是一个完整的炉型行为闭包，而非整个模组宣称完成：注册与正常加载 → 真实放置/打开 → 输入与燃料 → 自然 tick 加工 → menu 同步与进度渲染 → 取出产物 → 保存重启。是否纳入侧配置/能量变体由源码依赖与试验切片决定，提前列非目标；必要主干不能以排除源码伪造通过。

每次失败先分类：

- skill 缺失或歧义：执行者合理理解下出现错误路线/漏验。
- 模型执行失败：任务与规则清晰，仍未遵守或无法完成。
- 工具缺陷：mdb/代理运行时行为与契约不一致。
- 环境或上游问题：下载、版本、显示、源构建失败等。
- 正常工程返工：有新证据且在收敛，不把任何首次失败都算能力不足。

只有可复现、可泛化的问题才改 skill；具体注册 ID、类名、坐标和特殊适配方案留在实验夹具/roadmap，不加入通用 skill。改动记录“失败证据→规则变化→重放结果”。优化后的说明须让新的执行者从干净目标重放，不只是让原上下文里已获提示的 worker 继续成功。

冻结条件：既定纵向切片通过，强模型审查无未关闭阻断；独立重放能依靠 skill 与任务契约完成；已知未覆盖项明确。未达到条件，不开始给弱层级下能力结论。

## 阶段 B：冻结 skill 后测较低成本层级

模型：`openai-codex/gpt-6.1-terra` → `openai-codex/gpt-6-luna`。

- 使用同一源 commit、冻结 skill、验收、夹具和等价缓存环境。
- 全新目标 checkout / 会话 / 模型上下文；不提供阶段 A 的目标补丁、已完成路线或逐条修复答案。
- 原版观察基线与工具说明可以共享；之前模型的解法不能作为隐性提示。
- 执行同一核心切片及预先固定的边界用例。若资源只允许一次试跑，标明单次结果，不宣称统计上的稳定性。
- 外层介入、补充提示、模型实际回退、重试和失败全部记录；不能在失败后换强模型补完仍算低成本层级通过。
- 若发现新的 skill 缺陷，修订后双方基于同版重新评估，不拿不同 skill 版本直接比模型。
- 连续失败无新证据时按 skill 阻塞规则暂停；区分主代理路线/审查能力与 worker 实现能力，再决定是否整体上移一级。

## 验收与记录

每轮记录：源/目标 commit、skill 哈希、主/子实际 provider/model、请求/实际 thinking、任务与干预、工具调用、构建退出码、RPC 原始结果、日志、干净截图与读图观察、审查 R-ID、重启结果、清理状态。

必须观察：

1. 真正使用已修改代码，而不是旧 jar 或热加载遗留实例。
2. 加工输入/产出与燃料变化正确，拒绝/满槽等相关边界无丢失或复制。
3. GUI 点击、进度、槽位和 tooltip 与服务端一致；agent 实际打开图片检查。
4. 保存后重启仍成立；里程碑检查独立消费者的发布 jar。
5. 所有 owned sessions 与测试进程有明确清理或交接。

成本报告包含整个 A→worker 后代的实际用量、墙钟、重试与外层修复成本。若 provider 没有可信定价，只报告 tokens/用量，不把 `$0` 当免费。区分一次性环境准备和每次移植执行成本。

不能用“编译绿”“拿到图片”“worker 说完成”代替移植验收；样本切片成功也不代表整个模组移植成功。
