请接管这个从 Pi 迁来的工作，不要尝试用 Pi session/agent ID 做 Codex resume。

工作目录：/home/wangyu/server-v2/develop/mcdebug-wt-121
先完整阅读：
/home/wangyu/server-v2/develop/mcdebug-wt-121/docs/experiments/CODEX-HANDOFF.md
以及其中要求的 AGENTS.md、skill 与检查点文档。

首先落实交接文档§0的停止门禁：确认原 Pi 主代理/嵌套worker已停止，避免两个会话并发写同一target。若仍活动，先让我停止原Pi。不要通过杀共享Gradle daemon或无关Java进程处理。

然后简要复述：当前目标、最新验证事实、未完成项、准备继续的一个具体步骤。目标是实测完善通用 NeoForge→Fabric 移植skill；样本Iron Furnaces的原版基线已验，fabric-target截至C11已compileJava通过，但完整构建/游戏/S1/consumer状态必须读最新证据。保留所有未提交文件，不重做原版基线，不把编译通过当移植通过。

继续阶段A：强模型规划审查、Terra worker按单一owner闭包实施；全部thinking low，最少必要测试。先确认Codex宿主实际能否选择指定模型与派生子代理，无法支持则明确告诉我，不静默换模型/provider或冒充已切换。阶段A未通过不启动阶段B，不commit/push，不改生产、mcdebug本体、全局配置或插件。
