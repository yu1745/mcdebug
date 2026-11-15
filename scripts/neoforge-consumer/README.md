# 隔离的 NeoForge 发布 JAR 消费者

此目录只是模板，**运行前必须复制到新的临时目录**，与 `scripts/perf-consumer/` 的规则相同。
模板不包含 mcdebug 源码集或资源，因此从 `run/mods` 加载的始终是发布 JAR。
[NeoForge 验证计划](../../docs/plan-neoforge-verification.md)和移植自身的消费者检查
均以此作为黑盒目标。

在 mcdebug 仓库根目录执行：

```bash
REPO="$PWD"
F="$(mktemp -d /tmp/mcneo-XXXXXXXX)"
mkdir -p "$F/consumer"
cp -r scripts/neoforge-consumer/. "$F/consumer/"
cp gradlew gradlew.bat "$F/consumer/"
mkdir -p "$F/consumer/gradle/wrapper"
cp gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties "$F/consumer/gradle/wrapper/"

# 被测 jar 已内嵌 Kotlin 标准库，无需安装 KotlinForForge。
mkdir -p "$F/consumer/run/mods"
java -jar "$REPO/mcdebug-cli.jar" jar --loader neoforge --output "$F/consumer/run/mods"

printf 'eula=true\nonline-mode=false\nserver-port=0\nspawn-protection=0\nmax-players=1\n' > "$F/consumer/run/eula.txt"
printf 'eula=true\nonline-mode=false\nserver-port=0\nspawn-protection=0\nmax-players=1\n' > "$F/consumer/run/server.properties"

cd "$F/consumer"
./gradlew runServer --console=plain                 # 服务端平面
DISPLAY=:0 ./gradlew runClient --console=plain      # 客户端平面（gui.*，需要显示设备）
```

说明：

- 可用于**服务端**实例，无需显示设备且开销较低；也可用于同时承载集成服务器平面的
  **客户端**实例，即单人游戏。通过工程的 `programArguments` 传入
  `--quickPlaySingleplayer <save>`，或使用 `mcdebug session start` 启动。
- 两个加载器的 JAR 均内嵌 Kotlin 标准库，无需 Kotlin 语言模组。
  同一 `mods/` 中存在两份 mcdebug JAR 会中止模组加载；
  `mcdebug jar --loader …` 会自动删除另一加载器的 JAR。
- 不要提交包含 `run/` 的目录副本，此模板用于创建全新的临时实例。
