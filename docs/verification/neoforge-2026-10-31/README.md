# 黑盒验收原始报告（NeoForge 21.1.x 移植，2026-10-30/31）

这四个文件是**只拿到 CLI jar + `-h` + `skill --print`、在隔离目录里工作**的子代理交回的原始报告
（批次定义见 `docs/plan-neoforge-verification.md` §2，结论汇总见该文件 §5）。

| 文件 | 批次 | 结果 |
|---|---|---|
| `b1.md` | 服务端平面（NeoForge 专用服，发布 jar + KotlinForForge） | 17 PASS / 0 FAIL |
| `b2.md` | 客户端平面（同一 jar 的客户端，进世界） | 16 PASS / 1 PARTIAL / 1 FAIL（FAIL 的定性见 §5：模式/环境问题，非移植缺陷） |
| `b3.md` | 跨平面闭环（摆方块 → 像素 → 动作 → 服务端复核） | 7 PASS / 0 FAIL |
| `b4.md` | managed session（`session start` 自起客户端、双平面路由、stop） | 7 PASS / 0 FAIL |

报告里保留了对 agent 而言"文档没讲清"的部分（"帮助/skill 缺口"节）——那正是这批任务的产出之一，
已按 §5 的表逐条修进 `skills/mcdebug/SKILL.md` 与 CLI 帮助。
