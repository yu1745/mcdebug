# 干净上下文控制实验 C1：配置 owner 闭包

## 有界诊断

宿主可见：此前P0调用max_turns=160；外层提供耗时25.58s/132.03s/35.06s。工具正常返回未完成交接，公开摘要未出现明确turn/context exhaustion；worker公开声明没有语义阻塞。不能据时长证明主动提前结束，也不能据max_turns认定已用满。旧8d输出只覆盖第一次baseline，不能作为后续resume全文。A本轮调用history读取公开记录返回remote-context-inactive，无可用续会话公共消息细节。因此终止机制**未知**：主动交接/上下文或运行时限制均未被充分证实。不读取隐藏thinking，不归罪模型总体能力，不改插件。控制变量：新建一个干净上下文terra/low，短单一owner闭包；保留baseline/target补丁，不重跑baseline。

## C1 主代理已读与决定

A完整读取当前Config.java、FurnaceConfigFile.java、IronFurnaces.java。
- Config当前使用builder push类别，定义49个整数与1个布尔（数量最终用源提取核对），保留**实际定义**而不是注释默认（如diamond tier实际2）。有效键需含类别前缀，misc中定义misc.lightupdates导致有效键misc.misc.lightupdates，不能擅自去重。
- showErrors仅声明从未定义，源缺省null且仅FurnaceSettings异常路径使用；不发明新配置键/默认，不在本闭包纠正该上游缺陷，明确留为已知风险。
- helper已有Value<T>.get、integer/bool、JSON加载/范围校验；复用而不是再造builder框架。JSON替代TOML是已允许的配置存储平台差异，本轮不承诺旧TOML迁移，键路径用含类别的扁平字符串保持有效键。
- init调用点当前NeoForge构造器registerConfig：此次替换为Config.load()且在任何注册/BE构造前执行。入口整体尚待下一闭包Fabric化，不能宣称runtime已启动，但不能仅新增无调用helper。
- 14个getCookTimeConfig类型声明显式返回ModConfigSpec.IntValue（base与各炉子类）；机械改为FurnaceConfigFile.Value<Integer>，不动算法，消费者.get()保持。
- helper增加load(Path)便于隔离无游戏最小验证，默认load仍用Fabric config dir。最小验证仅覆盖实际定义集合/默认范围等价、单个有效override和拒绝越界/错类型，不扩框架或矩阵。

C1验收：实际Config替换并init调用、上述ModConfigSpec依赖消失、consumer类型一致、最小配置验证通过，compileJava保留原始非配置首因。整个工程仍可因Registration/能量等失败，此闭包通过不等于P0/S1通过。

worker只能改cache目标Config/helper/入口配置调用点及炉BE配置返回类型，必要最小验证脚本与evidence；其它现有改动保留。不提交/不运行游戏/不改参考/全局/邻近工程。
