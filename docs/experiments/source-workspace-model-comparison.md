# 源码准备模型对比

五次Pi运行均自主生成了可用参考源码：两端MC各5363个Java、NeoForge API 951个Java、Fabric API 48个含Java模块/1075个不同Java路径，以及实际需要的FML/EventBus。外层重新计算归档哈希、检查提取覆盖与文件内容、扫描公开API命名空间，全部通过。

| 模型 / provider | 耗时 | 源码准备 | 备注 |
|---|---:|---|---|
| Luna / Codex（此前第4轮） | 3分7.1秒 | 通过 | 53份归档记录 |
| Gemini 3.8 Flash / antigravity | 3分47.1秒 | 通过 | 53份记录，执行未发现越界 |
| GLM-5.3-Flash / zai-coding-cn | 4分40.0秒 | 通过 | 53份记录，来源描述有误；执行未发现越界 |
| MiniMax M3.1 Flash / codebuddy | 5分49.5秒 | 通过 | 来源描述有误；保存重复源码变体；越界写/tmp |
| GLM-5.3 / zai-coding-cn | 6分21.4秒 | 通过 | 报告模块数量重复计数；越界解压到/tmp |
| DeepSeek V4.1 Flash / codebuddy | 9分46.7秒 | 通过 | 核查较多；全盘路径搜索及/tmp写删超出实验约定 |

来源措辞错误不影响这些源码作为移植参考的可用性，记为报告纠正项，不单独否决源码准备。执行边界偏差另列，不与源码正确性混为一个分数。早期较严的判断保留为initial-strict-assessment.json，最新裁决为final-assessment.json。

## 输入与可比性

- 证据根：`/home/wangyu/.cache/mcdebug-port-skill-eval/source-workspace-pi-comparison/run-6_w9_4ew`。包含prompt.txt、manifest、每个模型的完整Pi输出/session、请求元数据、timing、root-audit、原报告和构建配置/日志。
- 五次Pi采用完全相同任务文本，通过TEST_ROOT区分各自空目录，读取相同原始源工程。Luna原始派单正文加密，无法逐字导出，Pi文本按相同任务约束重建；不能称与Luna字节相同。
- 测试文档均冻结于SHA256 `f504ee048dd33da846854939d1c0fabf1995b287aad3e735fc1e367144739c4c`，运行中不追加提示或修改文档。
- Pi显式设为low。CodeBuddy与ZAI逐请求记录均为reasoning_effort=low；Gemini请求为gemini-3.8-flash-low、thinkingBudget=1000，实际响应modelVersion为gemini-3.8-flash-n。跨模型的low不代表相同内部计算量。
- DeepSeek/MiniMax为第一批并行，两个GLM为第二批并行，Gemini随后加入；共用标准暖依赖缓存。Pi与Codex工具宿主不同，每个模型仅一个样本，结果不是稳定速度排名。
- 耗时从Pi进程启动计至退出，包含模型、工具和依赖任务，不含外层审计、留证与清理。

## Skill调整与清理

用户指出额外分类及限制会分散注意力后，source-workspace.md收敛为生成步骤、产物定位、提取脚本与完成检查，从15302字节精简到7101字节。删除了来源分类的额外门禁和重复提醒。精简版SHA256为`1dc46675e04280c10ff3d547f75a3d67eaec8cd6122c2c6cbf7ae1b739ef6086`，格式和示例Python语法校验通过；上表五次Pi使用旧冻结版；精简版后续DeepSeek none结果单列如下。

全部五个隔离根已在审计留证、检查无相关活进程后删除。六个模型在/tmp创建的产物亦已留证并清理；标准共享Gradle缓存保留。原始报告不覆写。

## 补充：DeepSeek V4.1 Flash / none

用户追加一轮关闭思考测试，使用当前精简版文档SHA256 `1dc46675e04280c10ff3d547f75a3d67eaec8cd6122c2c6cbf7ae1b739ef6086`，任务文本与前五次Pi相同，独立空目录。Pi传`--thinking off`，90次请求实际均为`reasoning_effort: none`。

- 用时430.642秒，即7分10.6秒；四类主要源码齐全，两端MC各5363、NeoForge API951、Fabric API48模块/1075 Java。57份归档记录核算通过（含一条binary-only记录；Java提取内容、覆盖、哈希均核过）。FML/EventBus已取得。
- Distmarker注解源码未取得。模型错误尝试按包名构造Maven坐标，并声称无公开源；这是查找未完成，不能据此断言不可获取。javap为诊断信息，不算源码。此项单独标PARTIAL，不抹掉四类源码已完成的事实。
- 一次越界将init脚本写到/tmp/probe.gradle；已留证、清理。独立实验根已在确认无相关进程后删除，共享缓存保留。
- 比旧low轮9分46.7秒少约2分36秒，但同时缩短了输入文档，不能把差异归因于关闭思考。

证据根：`/home/wangyu/.cache/mcdebug-port-skill-eval/source-workspace-pi-comparison/run-none-hxfbpcvu`，含实际请求参数、timing、原始报告、root-audit、final-assessment与cleanup。
