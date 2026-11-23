# JUnit 测试前端

最后更新时间：2026-11-15。状态：现行 API 与消费者接入说明。

[项目概览](../README.md) · [CLI 使用指南](usage.md)

`mcdebug-testkit` 在宿主 JVM 中运行普通 JUnit 5 测试，通过 `mcdebug-sdk` 直接连接
游戏的 JSON-RPC 端点，不执行 CLI，也不向测试工作进程加载 Minecraft、Fabric、NeoForge
或被测模组。Gradle 的标准 `Test` 任务负责选择测试、报告断言失败并生成 XML/HTML 报告。

## 模块

| 模块 | 职责 |
| --- | --- |
| `contract` | 方法名与通信协议契约 |
| `sdk` | RPC 传输、类型化 API、图像比较和托管会话生命周期 |
| `testkit` | JUnit 集成、等待、失败取证与原有服务端网格测试运行器 |
| `cli` | 命令解析与终端输出，使用同一份 SDK |

JVM 模块生成 Java 17 字节码，构建使用现有 JDK 25 工具链。
`frontend/` 是独立的 Gradle 构建入口，用于开发和发布 `contract`、`sdk` 与 `testkit`，
无需配置 Loom 或 ModDevGradle。游戏仍须满足自身加载器和 JDK 的要求。

## GitHub Maven 依赖

[yu1745/mcdebug](https://github.com/yu1745/mcdebug/tree/maven) 的公开 `maven` 分支
是标准 Maven 仓库。Gradle 直接从 GitHub Raw 下载预构建 JAR、POM 和模块元数据。
消费者无需 GitHub Token 或 mcdebug 源码副本，也不使用本地 Maven 仓库；JitPack 发布链路已弃用。

在消费者的 `build.gradle` 中配置：

```groovy
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = 'McdebugGitHub'
                url = 'https://raw.githubusercontent.com/yu1745/mcdebug/maven'
            }
        }
        filter { includeGroup 'com.mcdebug' }
    }
}
```

全部制品统一使用不可覆盖的版本号，当前为 `0.9.0`。纯 JVM 构件：

| 坐标 | 用途 |
| --- | --- |
| `com.mcdebug:mcdebug-testkit:0.9.0` | 宿主 JUnit 前端，传递引入 SDK 和协议模块 |
| `com.mcdebug:mcdebug-sdk:0.9.0` | 供其他前端直接使用的 RPC SDK |
| `com.mcdebug:mcdebug-contract:0.9.0` | 协议契约 |

预编译的游戏端模组 JAR，每个 `<mc, loader>` 一个构件，均自包含（Fabric / NeoForge 版内嵌 Kotlin 标准库；Forge 1.20.1 版需要 KotlinForForge 4.x）：

| 坐标 | 用途 |
| --- | --- |
| `com.mcdebug:mcdebug-mod-26.3-neoforge:0.9.0` | Minecraft 26.3 游戏端代理，要求 NeoForge 26.3.0.103+ |
| `com.mcdebug:mcdebug-mod-26.3-fabric:0.9.0` | Minecraft 26.3 游戏端代理（Fabric） |
| `com.mcdebug:mcdebug-mod-1.21.1-neoforge:0.9.0` | Minecraft 1.21.1 游戏端代理，要求 NeoForge 21.1.x |
| `com.mcdebug:mcdebug-mod-1.21.1-fabric:0.9.0` | Minecraft 1.21.1 游戏端代理（Fabric） |
| `com.mcdebug:mcdebug-mod-1.20.1-forge:0.9.0` | Minecraft 1.20.1 游戏端代理（Forge 47.x） |
| `com.mcdebug:mcdebug-neoforge:0.9.0` | 旧坐标，内容与 `mcdebug-mod-26.3-neoforge` 相同，仅为既有消费者保留 |

测试前端与游戏端代理必须使用一致的 `maven_version`；代理构件的 Minecraft 版本与加载器由构件 ID 表达，与被测工程一致。
同一个模组 JAR 也可以从 GitHub Release 直接下载（文件名 `mcdebug-<loader>-<mc>-<version>.jar`）。
通过**独立配置**解析游戏端代理，将其复制到专用游戏的 `mods/` 目录，
不要把它放入宿主测试的类路径：

```groovy
configurations {
    mcdebugAgent {
        canBeConsumed = false
        canBeResolved = true
        transitive = false
    }
}
dependencies { mcdebugAgent 'com.mcdebug:mcdebug-mod-26.3-neoforge:0.9.0' }   // 换成被测工程的 <mc>-<loader>
// 消费者自己的准备任务可将 configurations.mcdebugAgent 复制到测试游戏的 mods 目录。
```

## Gradle 任务

使用独立的宿主源集，其类路径不得继承模组的 `main` 运行时或加载器类路径：

```groovy
sourceSets { mcdebugTest }
dependencies {
    mcdebugTestImplementation 'com.mcdebug:mcdebug-testkit:0.9.0'
    mcdebugTestImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    mcdebugTestRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
tasks.register('mcdebugTest', Test) {
    testClassesDirs = sourceSets.mcdebugTest.output.classesDirs
    classpath = sourceSets.mcdebugTest.runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty 'junit.jupiter.execution.parallel.enabled', 'false'
    systemProperty 'mcdebug.gameDir', providers.gradleProperty('mcdebugGameDir').get()
    systemProperty 'mcdebug.artifactsDir', layout.buildDirectory.dir('reports/mcdebug').get().asFile.absolutePath
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
```

此示例连接所选游戏目录中已运行的客户端，不会停止被连接的客户端。
端点缺失、握手失败、世界未加载或 RPC 契约不匹配都会使执行失败，测试前端不会跳过这些情况。
该任务应由开发者显式运行，不要把它悄悄挂到没有游戏实例的普通构建上。

`@McDebugClientTest` 会在同一个 JUnit 工作进程中串行执行测试，即使消费者在其他地方
启用了 JUnit 并行执行也一样。同时保留 `maxParallelForks = 1`：使用同一客户端的测试
共享 Screen、光标和玩家背包。

## 测试示例

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.mcdebug.testkit.ClientTestContext;
import com.mcdebug.testkit.McDebugClientTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@McDebugClientTest
class TankClientTest {
  @Test
  void opensRealTankMenu(ClientTestContext ctx) {
    // 消费者的测试夹具先放置液罐，并将玩家移到交互距离内。
    ctx.act("gui.openBlock", Map.of("pos", List.of(14, 200, 0), "face", "south"));
    var screen = ctx.getGui().screen().getAsJsonObject();
    assertEquals("motive.client.gui.FluidStorageScreen", screen.get("class").getAsString());
    assertEquals(38, screen.getAsJsonArray("slots").size());
    ctx.capture("tank");
  }
}
```

`getServer()` 用于读取世界和库存的权威状态，`getGui()` 操作真实客户端 Screen，
`getClient()` 提供客户端平面 SDK。`call(method, params)` 根据协议契约路由并返回 JSON；
`act(method, params)` 还会在顶层明确返回 `ok:false` 或 `success:false` 时使测试失败。
对于刻意验证的拒绝路径，以及合法返回零的幂等原版命令，使用原始 `call`，
再用 JUnit 断言预期后置条件，不要把 RPC 成功返回直接当作测试通过。

`await(description, Duration, BooleanSupplier)` 轮询条件，在基于单调时钟的超时期限内
重试普通断言失败；RPC 或连接错误则立即失败。每个 RPC 也有独立、有限的超时时间。
轮询不会回滚或取消游戏已经执行的操作。`waitServerTicks(n)` 观察自然发生的服务端 tick，
也可通过 SDK 的 `wait.until` 观察服务端谓词。

在 `@BeforeEach` / `@AfterEach` 中准备和清理消费者自己的场景。
测试工具包不会猜测哪些世界方块属于测试，也不会随意清空区域。
测试客户端行为时使用真实的 `gui.*`；`screen.*` 仍是服务端假玩家菜单 API。
坐标点击可以验证 Screen 命中检测和自定义控件；槽位点击是通过库存点击路径执行的便捷操作。

## 托管客户端

测试工具包可使用 SDK 已有的托管会话生命周期。在 `Test` 任务上额外设置以下系统属性：

| 属性 | 含义 |
| --- | --- |
| `mcdebug.launchProject` | 包含 `runClient` 任务的独立加载器运行工程 |
| `mcdebug.testProject` | 执行测试的工程，用于防止递归启动同一个 Gradle 构建 |
| `mcdebug.world` | `saves/` 下已有的专用测试存档目录名 |
| `mcdebug.display` | 可选的子进程 `DISPLAY`，默认继承环境 |
| `mcdebug.width`, `mcdebug.height` | 请求的窗口尺寸，默认 1920×1080 |
| `mcdebug.sessionsDir` | 可选的隔离会话注册表目录，默认位于游戏目录旁 |
| `mcdebug.startTimeoutSeconds` | 启动超时，默认 180 秒 |
| `mcdebug.requestTimeoutMs` | 每次 RPC 的超时，必须为正数，默认 30000 毫秒 |

托管启动沿用 SDK 的 Linux / `setsid` 要求。使用独立运行工程，装入已构建的模组和
游戏端代理 JAR。若启动正在执行测试工作进程的同一个构建，可能因 Gradle 工程锁而死锁；
若让 `Test` 依赖长期运行的 `runClient` 任务，测试又会一直无法开始。

运行时在本次测试中共用一个客户端，校验进程身份，并在 JUnit 关闭根存储时，
只停止由它启动的客户端。它不会删除存档或终止共享 Gradle 守护进程。
`restartClient()` 保存并停止该自有客户端，等待包装进程退出，再重新启动并连接新的进程身份。
连接到已有客户端时不能使用此方法。

托管客户端的就绪检查还会等待加入世界时的 Screen 和覆盖层消失，再执行 GUI / 服务端屏障。
刚观察到 `inWorld:true` 时就发送加入或重启后的首次交互仍然过早。
各测试用例仍应等待自己的目标 Screen、槽位或数值，不要把任意 Screen 变化当作目标菜单已打开。

## 证据与失败处理

每次调用都有独立的产物目录，后续通过的运行不会复用或覆盖先前失败的证据。
每个测试都会把证据路径写入 JUnit 报告。

通过 `call` 和 `act` 执行的调用会将方法、参数、结果或错误记录到 `rpc.jsonl`。
仍可直接使用类型化 SDK，其状态也包含在失败快照中。

- `capture(name)` 使用 RPC 内联字节，在测试宿主上保存新的全窗口 PNG、标注 PNG 和绘制日志 JSON，
  不假设远端文件系统路径在本机存在。
- 测试方法执行后，新增 ERROR 日志会使测试失败，即使该测试自己的断言已通过。
  启动消息位于每项测试的日志游标之前；WARN 仅作诊断，错误日志被截断也会使验证失败。
- 断言或生命周期方法失败时，记录堆栈、客户端状态、Screen、权威服务端状态、新问题和截图。
  诊断过程中发生的错误会附加到原始失败上，随后仍执行场景清理。
- 标准报告仍位于 `build/test-results/<task>` 和 `build/reports/tests/<task>`。

优先对状态、槽位、文字和网络结果进行语义断言。需要像素证据时可使用 SDK 的图像比较；
必须显式确认基线，并有意识地处理动画、缩放和渲染后端差异。
仅保存截图不能证明模型或动画正确。

## 覆盖边界

此前端验证真实 GUI 行为、同步、存储效果和渲染。
按键与文字输入 API 目前只向打开的 Screen 派发事件，不包含完整的移动或战斗输入驱动。
游戏逻辑测试与客户端集成测试应互相补充。初始套件通过说明方案可行，
不代表模组已被完整覆盖，也不能据此给出源码行覆盖率。

## 发布

这是除 GitHub Release 之外唯一的分发通道（JitPack 已弃用）。维护者在根 `gradle.properties` 中设置
`maven_version`，先构建纯 JVM 模块与**全部五个**预编译模组 JAR（三个 Minecraft 版本各需自己的 JDK，
见[单仓多版本说明](monorepo.md)），再由 `frontend/` 暂存标准 Maven 仓库，包含 JAR、源码、POM、模块元数据和校验和：

```sh
./gradlew build
(cd versions/mc26.3 && ./gradlew build)                                      # JDK 25
(cd versions/mc1.21.1 && JAVA_HOME=<JDK 21> ./gradlew build)                  # 连带 :neoforge:build
(cd versions/mc1.20.1 && JAVA_HOME=<JDK 17> ./gradlew build)                  # 仅 Forge
./gradlew -p frontend stageMavenRepository
./gradlew -p frontend -q listPublicationArtifactIds                          # 本次发布的全部构件 ID
```

构件清单只在 `frontend/build.gradle` 维护（`mcdebug-mod-<mc>-<loader>` 与兼容坐标 `mcdebug-neoforge`）。
缺少某个模组 JAR 时 `stageMavenRepository` 会直接失败并指出先构建哪个版本。

默认暂存目录为 `frontend/build/github-maven`；`-PmavenRepoDir=/path/to/repository`
可改为已有 Maven 分支的检出目录。暂存更新时保留该分支的旧版本和元数据。
不得替换已发布版本，每次制品变化都要增加 `maven_version`。
`publish Maven repository` 工作流会在 `main` 分支的 `gradle.properties` / `frontend/` 变更时运行，
从 `frontend/build.gradle` 读取构件清单做不可变检查（已发布版本直接跳过；同版本缺件视为不完整并失败，
新增构件也必须提升 `maven_version`），然后构建三个版本并发布，
并在 `maven` 分支的 `releases/<version>.txt` 中记录源码提交。
