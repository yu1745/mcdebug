# mcdebug — AGENTS.md（容器测试环境重建手册）

本文件记录 mcdebug 的**本地隔离测试容器**重建步骤。该容器用于在 Kilt + TConstruct + IC2 环境下验证 mcdebug 的 RPC 能力（`shoot` 远程武器、`reflect` 任意反射、挖矿/掉落测试等），与生产服务器完全隔离。

## 1. 为什么需要这个容器

- mcdebug 是 Fabric 服务端调试工具，但生产环境是 **Kilt（Forge 兼容层）+ Forge mod**（TConstruct/Mantle/IC2）。
- 纯 Fabric 最小环境无法验证 Forge mod 交互；生产服务器禁止直接操作。
- 本容器在**本机 Docker** 运行（用户明确要求测试容器建在本地，不隔 SSH 操作远程），复刻生产依赖版本。

## 2. 容器概况

| 项 | 值 |
|---|---|
| 容器名 | `mcdebug-tconstruct-luck-local` |
| 镜像 | `servers-fabric-btrfs:jdk17`（本机已有） |
| 工作目录 | `/server`（挂载宿主机目录） |
| Minecraft 端口 | 25665 |
| mcdebug RPC 端口 | 25680 |
| 宿主机数据目录 | `/home/wangyu/mcdebug-tconstruct-luck-local` |
| JVM | `java -Xmx5G -XX:+UseG1GC -jar fabric-server-launch.jar nogui` |

## 3. 重建步骤

### 3.1 准备数据目录

```bash
# 数据目录位于本机磁盘（非 NFS），删除重建即可
rm -rf /home/wangyu/mcdebug-tconstruct-luck-local
mkdir -p /home/wangyu/mcdebug-tconstruct-luck-local/{mods,config,logs,mcdebug}
```

### 3.2 收集 mods（版本锁定，逐个核对 sha256）

| jar | 来源 | sha256 |
|---|---|---|
| `fabric-server-launch.jar` | 旧目录保留 / 远程测试目录 `/tmp/mcdebug-tconstruct-luck-20260803/` | — |
| `fabric-api-0.92.11+1.20.1.jar` | 旧目录 / 生产 `fabric1.20.1-1/mods/` | `3278787c04ad...` |
| `fabric-language-kotlin-1.13.9+kotlin.2.3.10.jar` | 同上 | `fecd5e6dd6ae...` |
| `architectury-9.2.14-fabric.jar` | 同上 | `bd7a7032bedb...` |
| `ForgeConfigAPIPort-v8.0.3-1.20.1-Fabric.jar` | 同上 | `627d28ae4f5c...` |
| `Kilt-20.1.14+build.639304-local.jar` | **本地构建** `develop/Kilt/build/libs/` | `69b129191369...` |
| `Mantle-1.20.1-1.11.104.jar` | 生产 mods | `6052e47c3981...` |
| `TConstruct-1.20.1-3.11.2.166.jar` | 生产 mods | `653b49d73481...` |
| `ic2_120-0.4.jar` | **本地构建** `develop/ic2-fabric/core/build/libs/` | `d751882acdab...` |
| `base-2.3.15+1.20.1.jar` | 生产 `kaleidoscope_cookery` jar 内嵌 `META-INF/jars/` 提取 | `886a8ca7809a...` |
| `loot-2.3.15+1.20.1.jar` | 同上 | `4cf639ebfb0e...` |
| `transfer-2.3.15+1.20.1.jar` | 同上 | `e71ab765097f...` |
| `mcdebug-0.4.15.jar` | **本地构建** `develop/mcdebug/build/libs/` | `4ade2bb6623d...` |

要点：
- **Porting Lib 2.3.15 三个 jar**：从生产 `kaleidoscope_cookery-1.3.0-hotifix-fabric+mc1.20.1.jar` 的 `META-INF/jars/` 提取（生产环境由它提供 2.3.15；Kilt 内嵌的是 2.3.9）。生产环境依赖解析为 2.3.15，测试容器必须复刻。
- **不要放独立 JEI**：ic2 0.4 已修复无 JEI 启动（`Ic2_120.kt` 的 `UuCostIndex.onRebuild` 回调加了 `isModLoaded("jei")` 保护，commit `8989177d`）。若用旧 ic2 jar 则需 JEI 或会启动崩溃。
- 配置目录 `config/`：`fml.toml`、`forge-server.toml`、`mantle-server.toml`、`tconstruct-common.toml`、`mcdebug.json`（RPC 端口 25680）、`ic2_120.json` 从旧目录复制。
- `server.properties`：`server-port=25665`、`online-mode=false`、`enable-command-block=true`、`level-type=minecraft:normal`。
- `eula.txt`：`eula=true`。
- `libraries/`、`versions/`：Fabric loader 首次启动自动下载，无需手工准备。

### 3.3 启动容器

```bash
docker rm -f mcdebug-tconstruct-luck-local
docker run -d --name mcdebug-tconstruct-luck-local \
  -p 25665:25665 -p 25680:25680 \
  -v /home/wangyu/mcdebug-tconstruct-luck-local:/server \
  -w /server \
  --memory=6g \
  servers-fabric-btrfs:jdk17 \
  java -Xmx5G -XX:+UseG1GC -jar fabric-server-launch.jar nogui
```

等待就绪（脚本在数据目录下，轮询日志不阻塞）：

```bash
/home/wangyu/mcdebug-tconstruct-luck-local/wait-done.sh \
  /home/wangyu/mcdebug-tconstruct-luck-local/logs/latest.log 300
```

验证：
```bash
docker ps --filter name=mcdebug-tconstruct --format "{{.Names}} {{.Status}}"
nc -z 127.0.0.1 25680 && echo "RPC OK"
cd /home/wangyu/server/develop/mcdebug/mcdebug-client
node dist/cli.js --port 25680 status
```

### 3.4 部署新构建

```bash
# 服务端 jar（改完 Kotlin 后）
cd /home/wangyu/server/develop/mcdebug && ./gradlew build
cp build/libs/mcdebug-0.4.15.jar /home/wangyu/mcdebug-tconstruct-luck-local/mods/

# CLI（改完 TS 后）
cd mcdebug-client && corepack pnpm run build

# 重启容器加载新 jar
docker restart mcdebug-tconstruct-luck-local
# 等待 Done + RPC ready（见 wait-done.sh）
```

## 4. 常用测试命令

```bash
# 通用
mcdebug status / find-entities / entity spawn/list-items/collect-items

# 远程武器（shoot，服务端 RPC world.useItemHold）
mcdebug shoot --item minecraft:bow --ammo minecraft:arrow --target <uuid> --hold 20
mcdebug shoot --item minecraft:crossbow --ammo minecraft:arrow --target <uuid> --hold 30 --repeat 2
mcdebug shoot --item ic2_120:mining_laser --nbt '{"Energy":200000}' --target <uuid> --hold 0
mcdebug shoot --item tconstruct:shuriken --nbt @shuriken.json --target <uuid> --hold 0

# 任意反射（reflect，服务端 RPC reflect.*）
mcdebug reflect 'net.minecraft.entity.LivingEntity' --members
mcdebug reflect 'net.minecraft.world.item.Items' --field DIAMOND        # 静态字段 → $ref
mcdebug new 'net.minecraft.world.item.ItemStack' --args '[{"$ref":1},3]' # 带参构造
mcdebug reflect --ref 2 --method setCount --args '[64]'                  # 实例方法
mcdebug refs / raw reflect.release '{"ref":1}'

# 挖矿 / 掉落（attack，服务端 RPC world.attackBlock）
mcdebug attack --x 100 --y 65 --z 100 --face up --item tconstruct:pickaxe \
  --nbt @pickaxe-luck3.json --gamemode survival
```

## 5. 已知注意点

- **假玩家热键栏 slot 0 = 主手**（`selectedSlot` 默认 0）。`shoot` 的弹药放副手 + slot 8，**绝不能写 slot 0**（会覆盖手持武器）。
- **运行时是 intermediary 映射**（Kilt）：反射字符串必须用运行时名；`reflect` 通过内置 yarn/mojang 映射表自动转换（打包在 jar 资源 `mappings/*.gz`，jar 约 2MB）。
- **`tickActiveItemStack` 反射名**：yarn 名在运行时不存在，须用 `method_6076`（Mappings 已处理）。
- 测试生物（鸡）会自行移动、受击面积小——目标设近（2-5 格）或生成多只；IC2 镭射直线无下坠但射程有限。
- 无 JEI 环境：ic2 0.4 已修复，其他 mod 若报 `ClassNotFoundException mezz.jei` 检查是否用旧 ic2 jar。
- 容器数据目录在本机磁盘，可整体 `rm -rf` 重建；生产服务器不受任何影响。

## 6. 版本升级路径

- **Kilt**：`develop/Kilt` 构建后替换 `Kilt-*.jar`（sha256 变化）。
- **ic2**：`develop/ic2-fabric/core/build/libs/ic2_120-0.4.jar`。
- **mcdebug**：见 3.4。版本号在 `gradle.properties` 的 `mod_version`，升版后 jar 名变化需同步 CLI 版本匹配。
