# 准备可检索的参考源码

移植前准备四类源码，放在目标工程旁，供主代理和worker查询。

| 参考树 | 来源 |
|---|---|
| NeoForge API | 源工程使用的精确NeoForge版本 |
| 源端MC | 源工程工具链生成的完整Minecraft源码 |
| Fabric API | 目标工程使用的精确Fabric API版本，映射与目标一致 |
| 目标端MC | 目标Loom工程运行genSources生成的Minecraft源码 |

已有版本和映射匹配的参考树可直接复用。缺失部分按下面的流程准备。

## 1. 目录与工具链

所有准备目录放在本次授权工作区内。`../`相对目标工程：

```text
workspace/
  target/
  source-prep/
    neoforge/
    fabric/
  port-reference/                 # target看到的 ../port-reference/
    neoforge-api-<version>/
    mc-neoforge-<mc>-<loader>/
    fabric-api-<version>/
    mc-fabric-<mc>-<mappings>/
    SOURCE.json
```

从源工程和目标工程核对MC、loader/API、映射、构建插件、Gradle与JDK版本。

Gradle wrapper需有脚本、properties和gradle-wrapper.jar。缺jar时使用匹配版本的完整Gradle发行版；下文的`sh gradlew`相应替换为该Gradle可执行文件。

## 2. NeoForge：生成源端MC与API

将原始源工程复制到`source-prep/neoforge/`，保留原构建配置，排除旧build、.gradle、run和存档。在准备副本中运行任务。

ModDevGradle 2.x可先查询，再生成：

```bash
sh gradlew tasks --all --console=plain --no-daemon > tasks-all.log 2>&1
sh gradlew createMinecraftArtifacts --console=plain --no-daemon > create-artifacts.log 2>&1
```

多项目使用实际限定任务名；其它构建插件按其任务输出定位产物。

MC 1.21.1 / NeoForge 21.1.209 / ModDevGradle 2.0.89的实测产物：

```text
build/moddev/artifacts/neoforge-21.1.209-sources.jar
```

该生成归档同时包含MC和NeoForge类，可按包分离：

- MC：`net/minecraft/`与`com/mojang/`。
- NeoForge API：`net/neoforged/neoforge/`。

确认归档含完整的`BlockEntity.java`、`ItemStack.java`等MC类。官方Maven的NeoForge sources包可能只有API；NeoForm配置和userdev补丁是生成输入，不能代替MC源码树。以本工程任务输出绑定版本和产物。

## 3. Fabric：生成目标MC与已映射API

使用目标工程，或在`source-prep/fabric/`建立不接业务源码的准备工程，跟随目标的版本和映射。

目标尚无其它约束时，MC 1.21.1可使用已验证组合：

```text
Java 21
Gradle 8.12
Loom 1.10.5
Fabric Loader 0.16.10
Fabric API 0.115.1+1.21.1
loom.officialMojangMappings()
```

运行：

```bash
sh gradlew genSources --console=plain --no-daemon > gen-sources.log 2>&1
```

### MC源码

在当前工程的以下目录定位本次MC/映射对应的sources.jar：

```text
.gradle/loom-cache/minecraftMaven/net/minecraft/
```

提取`net/minecraft/`和`com/mojang/`两组包。Loom可能输出merged归档，也可能拆成common与clientOnly；拆分时保留两者。

### Fabric API源码

Fabric API聚合sources包常只有元数据，实际Java源码在各模块中。优先使用当前Loom工程已经映射好的模块sources。

Loom 1.10.5的实测位置：

```text
build/loom-cache/remapped_working/
  loom_mappings_<key>.net.fabricmc.fabric-api-<module>-<version>-sources.jar
```

其它版本也可能位于`.gradle/loom-cache/remapped_mods/`。可在当前准备工程搜索：

```bash
rg --files --hidden --no-ignore build/loom-cache .gradle/loom-cache \
  -g '*fabric-*-sources.jar' > api-source-candidates.txt
```

先检查候选内容，再决定是否需要下载和重映射。例如Mojang命名的`StorageUtil.java`引用`net.minecraft.world.Container`，`AttachmentTarget.java`引用`net.minecraft.server.level.ServerLevel`。

将当前依赖中含Java的模块分别提取到：

```text
fabric-api-<version>/modules/<artifact-version>/
```

在交付树中检查MC类名是否仍为intermediary：

```bash
rg -n -g '*.java' 'net\.minecraft\.class_[0-9]+|\bclass_[0-9]+' \
  "$fabric_api_tree/modules"
```

抽查实际要用的 API 签名与实现方法体；`class_NNN` 检索阴性不代表映射完整，import 与方法体也可能混用 Mojang/Yarn 名称。影响当前调用链时，对照同版本原始源码与映射补齐或核实，并标明未闭合项及影响。注释、mixin 目标字符串和 API 自有名称按用途判断。

无Java的POM、聚合包装和资源模块记为元数据，不算缺源码模块。

## 4. 补充实际使用的传递API

源工程调用FML/EventBus时，从对应NeoForge userdev/config.json或实际依赖解析结果取得精确坐标，到官方Maven获取sources。

NeoForge 21.1.209示例：

```text
net.neoforged.fancymodloader:loader:4.0.41
net.neoforged:bus:8.0.5
```

其源码不在MC树或NeoForge API树中，分别提取。其它依赖按移植中实际用到的API补充。

## 5. 提取与记录

使用下面的脚本提取Java、检查覆盖并自动记录归档哈希和文件数，避免手填。保存为`extract_reference.py`：

```python
from pathlib import Path
from zipfile import ZipFile
import hashlib, json, sys

archive, destination = map(Path, sys.argv[1:3])
prefixes = sys.argv[3:]

with ZipFile(archive) as z:
    expected = {n for n in z.namelist() if n.endswith('.java')
                and (not prefixes or any(n.startswith(p) for p in prefixes))}
    if not expected:
        raise SystemExit('No matching Java')
    for name in sorted(expected):
        if '..' in Path(name).parts or Path(name).is_absolute():
            raise SystemExit('Unsafe archive member')
        z.extract(name, destination)

actual = {str(p.relative_to(destination))
          for p in destination.rglob('*.java')}
missing = sorted(expected - actual)
if missing:
    raise SystemExit('Incomplete extraction: ' + repr(missing[:10]))

record = {
    'archive': str(archive.resolve()),
    'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
    'prefixes': prefixes,
    'java_files': len(expected),
    'missing': missing,
}
(destination / 'SOURCE-ARCHIVE.json').write_text(
    json.dumps(record, indent=2) + '\n')
```

替换成实际归档和目录：

```bash
python3 extract_reference.py "$nf_source_archive" "$nf_mc_tree" net/minecraft/ com/mojang/
python3 extract_reference.py "$nf_source_archive" "$nf_api_tree" net/neoforged/neoforge/
python3 extract_reference.py "$fabric_mc_archive" "$fabric_mc_tree" net/minecraft/ com/mojang/
python3 extract_reference.py "$mapped_api_module_archive" "$api_module_tree"
```

对含Java的API模块循环提取；FML/EventBus分别调用。拆分的MC归档分别提取到common/client目录。

在`port-reference/SOURCE.json`汇总各树路径、版本/映射、生成命令/日志和逐归档记录，供后续agent直接使用。

## 6. 完成检查

确认四类源码可检索、版本与映射匹配，两端MC包含归档中`net/minecraft/`和`com/mojang/`的全部Java，实际使用的传递API已补齐，即可标记READY。缺项标PARTIAL并说明影响。

参考源码不参与目标mod编译，也不提交到仓库。签名或行为差异在具体移植owner时结合两端源码核实。
