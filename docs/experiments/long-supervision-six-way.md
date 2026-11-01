# 六组长移植监督实验（2026-11-01）

用户选择同时做运行环境/提示词对照及 Flash worker 对照，共六组；所有主代理 Sol/low，所有 worker low。

| 组 | 主代理环境 | worker | 提示词 |
|---|---|---|---|
| native-luna | Codex CLI 0.166.0 | Luna | 原生 Codex |
| pi-luna | Pi 1.4.0 | Luna | 优化后的 Pi |
| full-luna | Pi 1.4.0 | Luna | 完整适配 Codex 相关提示层 |
| pi-deepseek | Pi 1.4.0 | codebuddy/deepseek-v4.1-flash | 优化后的 Pi |
| pi-minimax | Pi 1.4.0 | codebuddy/minimax-m3.1-flash | 优化后的 Pi |
| pi-glm | Pi 1.4.0 | zai-coding-cn/glm-5.3-flash | 优化后的 Pi |

前三组比较环境和提示，后三组固定 Pi 提示比较 worker。完整 Codex 适配覆盖情况以实际生成的 provenance 为准；此前仅替换 Sol 基础提示的小实验不算本组结果。完整适配包含 worker 提示，因此本轮比较的是提示配置整体，不能单独归因主代理某一句话。

## 固定输入和隔离

根目录：`/home/wangyu/.cache/mcdebug-port-skill-eval/long-supervision-v2/`。每组独立新会话、空 target/consumer/runtime/evidence、私有凭据副本及会话目录；TMPDIR 指向本轮 runtime/tmp，mcdebug registry 指向本轮 runtime/sessions。凭据不写入报告，宿主退出后移除。

输入精确复制 C1 的冻结 r2 四份 skill 文件、37 份原版基线，逐文件 SHA256 校验；任务沿用相同 A–D 验收，仅替换本轮路径与实际宿主调用方式。不提供之前移植实现、补丁、roadmap 或修复提示。原版 source commit：`41fde020636b79154e1c454a893962c639d89e1b`。

启动器：`/home/wangyu/.cache/mcdebug-port-skill-eval/long-supervision-six.py`。原生 Codex 使用独立 CODEX_HOME、公共固定模型 catalog、Sol/low，V2 子代理默认 Luna/low、最多一个子代理，并允许显式模型参数。Pi 冻结同一份当前插件 bundle，项目配置只开放唯一 worker。完整适配组仅额外开放该实验 profile extension 给 worker，以适配子代理工具描述和压缩；保持实际工具 schema/实现。该 profile 已离线核验主子 prompt 组装、工具结构、压缩请求/结果形态，真实主代理请求已成功；压缩的真实运行效果仍待观察。来源与范围在 full-luna/host/profile/provenance.json 及 profile/README.md。

注意：这是工作目录和配置隔离，不是 OS 文件访问沙箱。共享标准 Gradle/Maven 缓存照原任务允许使用。原生 Codex 与 Pi 的工具机制本身不同，原生对照不是仅一个 system prompt 字符串不同的实验。

## 调度与观测

先跑 Luna 三方对照，再跑三个 Flash 组；准备完成不等于已启动。08:38:49–50 UTC 已启动 native-luna 与 pi-luna；full-luna 经离线 profile 校验后也已启动（精确时间见各组 host/status.json）。Flash 三组当前为 prepared。

只读记录首次派发前后工作、首次阻塞等待及后续阻塞时长、worker 运行期间独立读源码/diff/原始证据和打开图片的行为、A–D 真实进度、耗时及环境故障。阻塞率必须说明分母与时间口径，不能用历史 next-response 重放比例替代整轮等待时间。

若出现外层提示/救援，单独记录；不替主代理给出移植实现方案。任务交付按真实证据复核，不以摘要或 PASS 标签替代。旧三组停止与清理另见原实验记录。

## 开局观察（未完成结论）

优化 Pi 的 Sol 在首次派发 Luna 后开启 watch，继续核对玩家持久化 API、读原版基线截图和源码，并发送有源码依据的指导；当前没有派发后立刻阻塞的开局。native 实际 turn_context 确认为 Sol/low 与子代理 Luna/low。以上不代表长任务已经通过，也不能推出整轮等待率。

## 用户终止本轮

用户在 2026-11-01 10:05 UTC 左右明确要求停止全部测试并收尾，取消标记记录时间为 10:05:39.414 UTC。第二批三个 Flash 组取消，均未启动；周期报告停止。实验根已写 CANCELLED.json，启动器加入拒绝后续启动的检查。正在执行 owned 资源清理，最终状态以 cleanup 报告为准。

原生 Codex 组已在取消前自然结束：08:38:49.949—10:05:07.341 UTC，约 86 分 17 秒，exit 0。固定 A–D 功能验收通过，未测范围、环境返工和一次 /tmp 输出违规保留在本组 review.md / skill-feedback.md。最终 jar SHA256：521b9157a969d122847769435854c2e34af4afd35e291aff051be7c3c4dcd123。

两个 Pi 组属于用户中途取消，不能记为自主失败终态，也不能记全通过。取消时优化 Pi 已构建并进入游戏、功能验收尚未收敛；完整 Codex 提示 Pi 已执行 A 与 C 并推进后续，完整验收仍未收敛。未取得足够证据计算最终同口径等待率，本轮不补跑或据开局次数外推。

### 最终收尾核验

三个已启动宿主均 exit 0，owned 游戏、worker、Pi/Codex、Xvfb/wrapper 无残留；私有 auth/models 副本已移除。共享 Gradle daemon 保留，未清共享 /tmp 或锁。清理证据：long-supervision-v2/cleanup/final-summary.json 及同目录动作/进程审计。

原生 Codex 自然完成 86 分 17 秒；优化 Pi 运行 87 分 22 秒后按用户要求取消，完整提示适配 Pi 运行 84 分 28 秒后取消。后三个 Flash 从未启动。以上耗时为从启动到宿主退出，非净模型计算耗时。

Sol→Luna 计数：同一长移植任务共 4 轮（既往 C1、本轮 native、本轮 optimized Pi、本轮 full Pi）；两个 Codex 原生轮通过，两个 Pi 轮中途取消。另有 6 轮小型实时提示词测试；合计 10 轮实际协作运行。24 次历史单步重放、启动失败以及仅 Luna 单独做源码准备均不计入这 10 轮。
