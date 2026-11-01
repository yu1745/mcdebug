# source-workspace 独立Luna行为测试

**最终结果：第4轮通过外层审计，测试与验证临时环境全部清理。** 本报告测试的冻结文档SHA256=f504ee048dd33da846854939d1c0fabf1995b287aad3e735fc1e367144739c4c。后续Pi对比见source-workspace-model-comparison.md。用户指出非必要概念和限制会分散注意力后，当前工作副本已精简至7101字节，SHA256=1dc46675e04280c10ff3d547f75a3d67eaec8cd6122c2c6cbf7ae1b739ef6086；格式和示例脚本语法校验通过，精简版另有DeepSeek V4.1 Flash none独立实测，四类主要源码通过，附加Distmarker源未取得；见source-workspace-model-comparison.md。

用户明确要求：环境准备阶段生成两边API和两边MC源码到工程旁../作为参考；独立Luna验证，失败只针对source-workspace.md改进，干净上下文重测，最终清理环境。

证据根 `/home/wangyu/.cache/mcdebug-port-skill-eval/source-workspace-luna-test-evidence`。所有agent均显式gpt-6-luna/low/fork_turns=none，不再委派。原始源输入仅固定reference commit41fde020636b79154e1c454a893962c639d89e1b；不提供已整理源码树或既有移植解法，不跑游戏/业务编译/原版基线。

## 第1轮：PARTIAL，完整门禁失败，环境已清理

文档SHA256 `468edea63def05f133db53d1bfb6ef2ea164ddd85e538ed73da4ce40d245b412`，冻结副本见source-workspace-tested.md。

- 生成目标MC5363 Java、提取Fabric模块API1103 Java、NeoForge API951 Java。
- 未生成完整NeoForge patched MC：其目录只有NeoForm注入Start.java和patch/config；另缺当前使用的FML/EventBus。agent如实标缺，但最终开头“已完成准备”不作为通过。
- 外层实际计算发现Fabric API归档在SOURCE.json里的手写SHA不匹配（实际3cf879c77604cba8cebd2486a904a5fd3b09c7d59008a8c6a67886962f90b0f7）。原始SOURCE/报告不覆写，audit另存实际值。
- target wrapper JAR缺失，agent尝试拼wrapper-main/shared失败后用完整Gradle8.12成功；文档原先缺清楚fallback。

外层单独临时副本验证固定ModDevGradle2.0.89的tasks与createMinecraftArtifacts，产物build/moddev/artifacts/neoforge-21.1.209-sources.jar，实际MC Java5363/NeoForge API951；不是Maven纯API sources.jar。实际生成/任务日志与archive审计已存证。

同时从userdev/config.json核出FML loader4.0.41与bus8.0.5，官方Maven sources实际可下载（179/21 Java，各含ModContainer/IEventBus）。不把缓存未命中当不可获取。

仅修订source-workspace.md：明确任务/产物、四类归档区别、Fabric总包无Java时需模块、完整Gradle而非伪wrapper、精确传递API坐标、程序计算SHA/计数与READY门禁。验证quick_validate通过。第1轮隔离根source-workspace-luna-6hz6e1zq与外层验证根source-workspace-research-2rv0m2uc均在确认无相关活进程后删除；标准共享Gradle缓存未删除。

## 第2轮：PARTIAL，已清理

agent `source_workspace_luna_v2`，文档SHA256 `31210ce236e3aaa0aec6b2892ba467009a479c7d46f908b8fa07a445cad47ab9`。新根 `/home/wangyu/.cache/source-workspace-luna-v2-olr6kmwr`，空target；只给修订文档和相同原始版本/权限输入，不说第1轮失败位置，不传外层已生成树或具体命令答案。完成后外层核实真实源码/版本/哈希，再清理隔离根并记录。

最终PARTIAL：两棵MC5363 Java齐，NF API/FML/Bus齐；Fabric选择0.115.1，Loom其实已在build/loom-cache/remapped_working生成正确named模块（50归档/1075 Java，公开API无class_N引用），但agent交付另下载的intermediary源码（828/1075 Java含class_N）。外层判产物选择错误，不用生成成功替代正确交付。已保留raw报告/清单/日志与两套产物审计后，确认无进程并清理隔离根。

文档仅补充当前工程Loom产物定位与内容验证、区分聚合wrapper缺sources和真实API缺失。没有在原上下文告知正确路径让它补作成功，而是新上下文重测。

## 第3轮：PARTIAL，已清理

agent `source_workspace_luna_v3`，文档SHA256 `965ff2badc59ac244e61fa71087a4eee8365e6d0b7ac7a6d8d7251cdb637feb4`。源生成成功，但提取仅net/minecraft（5168 Java），漏com/mojang的195类；Fabric选择0.115.6，找到remapped模块，但FabricItemGroupEntries公开API方法体仍有class_1761.class_7705，不能归类为仅注释/内部mixin遗留。该版本出版/源重映射质量问题与agent误分类分开记录。MC/Fabric原始归档在source-prep，agent的root-only审计未为它们逐个登记哈希。

外层保留三轮SOURCE/REPORT/token清单与归档集合覆盖审计，确认无进程后删除source-workspace-luna-v3-l8razlzi。文档增加可执行提取/哈希/集合覆盖脚本，明确两个MC包前缀、逐源归档记录，优先使用已实查公开API无遗留类名的0.115.1组合（不偷偷更改有既定约束的目标）。当前仅源工作区文档迭代，其它skill/移植快照不变。

## 第4轮：PASS，已清理

agent `source_workspace_luna_v4`，文档SHA256 `f504ee048dd33da846854939d1c0fabf1995b287aad3e735fc1e367144739c4c`。新根 `/home/wangyu/.cache/source-workspace-luna-v4-trgfqh_3`；只给新版文档和相同原始输入，未给上一轮根因/产物路径。已自行生成和完整提取NeoForge MC5363（含com/mojang195）与API951，真实归档SHA256 cacb0bb68edb788aecba66ad4ff32ef26e1af0096658a3e3077dbfc2d1774839。此为生成中间检查点；最终交付、审计和清理结果见下文。

最终交付：两端MC各5363 Java、NF API951、FML179、bus21、Fabric API48个含Java模块共1075 Java；2个无Java的包装归档已记作元数据，不算缺源码。53份SOURCE-ARCHIVE记录由外层重新计算真实归档SHA、过滤后应有Java集合与目标文件集合，全部匹配，四类源码及传递API均存在。公开Fabric API没有class_N残留；唯一命中是内部EntityRenderersMixin的一行合成lambda注释，按已冻结文档保留原文及备注，不是假转换，也不妨碍参考用途。

agent报告PARTIAL仅为等主代理复核及保留注释备注，外层按实际内容批准PASS，没有降低“缺树/错版本/漏文件/公开API未映射不得通过”的门禁。没有向该新上下文补任何命令或版本答案；直接执行文档独立完成。最终保存REPORT/SOURCE/53份记录/构建配置和原始生成日志后，检查无本轮活进程，删除第4轮根。final-cleanup.json确认四次测试根及外层命令验证根均不存在。无游戏、业务移植构建、原版基线重跑、commit/push或全局配置更改。

B1模型移植测试仍读取原冻结r2，未在运行中加入这份r3修订，因此B1结果不用于声称source-workspace准备已验证。
