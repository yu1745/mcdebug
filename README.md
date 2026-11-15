# mcdebug Maven 仓库

本分支按标准 Maven 目录存放预编译制品；源码和使用说明见 [26.3 分支](https://github.com/yu1745/mcdebug/tree/26.3)。

```groovy
repositories {
    mavenCentral()
    maven {
        url = 'https://raw.githubusercontent.com/yu1745/mcdebug/maven'
        content { includeGroup 'com.mcdebug' }
    }
}
dependencies {
    testImplementation 'com.mcdebug:mcdebug-testkit:0.8.0-mc26.3'
}
```

`mcdebug-testkit` 自动引入 `mcdebug-sdk` 和 `mcdebug-contract`。
`mcdebug-neoforge` 是同版本的游戏 agent，须由独立配置下载到测试游戏的 `mods/`，不要放入宿主测试 classpath。
完整接入步骤见 [JUnit / Gradle 测试指南](https://github.com/yu1745/mcdebug/blob/26.3/docs/testkit.md)。

公开下载不需要 GitHub 凭据，也不使用 JitPack。已发布版本不可覆盖；`releases/<版本>.txt` 记录对应源码提交。
