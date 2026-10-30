#!/usr/bin/env bash
# Fabric 端冒烟：把 build/libs 的 remapped jar 放进一次性 1.21.1 专用服，
# 用 mcdebug-cli.jar 走 unix socket 做几组 RPC 断言（含 1.21 数据组件改制后
# 的 item nbt / be nbt 往返），最后做契约一致性检查（Methods.kt 的每个方法
# 都必须已注册，不允许 -32601）。
#   用法: scripts/fabric-smoke.sh
#   依赖: java 21、网络（launcher/fabric-api 缓存在 ~/.cache/mcdebug-fabric-smoke）
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)          # cleanup 里要绝对路径调 scripts/stop-server.py（set -u：别再漏定义）

MOD_VERSION=$(sed -n 's/^mod_version=//p' gradle.properties)
MOD_JAR="build/libs/mcdebug-${MOD_VERSION}.jar"   # build/libs keeps older versions around
[[ -f "$MOD_JAR" ]] || { echo "fabric-smoke: $MOD_JAR missing, build first (./gradlew build)" >&2; exit 2; }

CACHE_DIR=${MCDEBUG_SMOKE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/mcdebug-fabric-smoke}
MC_VERSION=1.21.1
LOADER_VERSION=0.19.5
API_VERSION=0.116.17+1.21.1
FKL_VERSION=1.13.9+kotlin.2.3.10
CLI_JAR=${CLI_JAR:-mcdebug-cli.jar}
[[ -f "$CLI_JAR" ]] || { echo "fabric-smoke: mcdebug-cli.jar not found" >&2; exit 2; }

fetch() {
  local url=$1 dest=$2 temporary="${2}.part.$$"
  if [[ -s "$dest" ]] && (jar tf "$dest" >/dev/null 2>&1 || [[ "$dest" == *.jar ]]); then return; fi
  mkdir -p "$CACHE_DIR"
  rm -f "$temporary"
  curl --fail --location --retry 3 --retry-all-errors --connect-timeout 15 --max-time 300 \
    --output "$temporary" "$url" || { rm -f "$temporary"; echo "fabric-smoke: download failed: $url" >&2; exit 2; }
  mv "$temporary" "$dest"
}

mkdir -p "$CACHE_DIR"
LAUNCHER="$CACHE_DIR/fabric-server-${MC_VERSION}-${LOADER_VERSION}-1.1.2.jar"
API_JAR="$CACHE_DIR/fabric-api-${API_VERSION}.jar"
FKL_JAR="$CACHE_DIR/fabric-language-kotlin-${FKL_VERSION}.jar"
fetch "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER_VERSION}/1.1.2/server/jar" "$LAUNCHER"
fetch "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${API_VERSION}/fabric-api-${API_VERSION}.jar" "$API_JAR"
fetch "https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/${FKL_VERSION}/fabric-language-kotlin-${FKL_VERSION}.jar" "$FKL_JAR"

SERVER_DIR=$(mktemp -d "${TMPDIR:-/tmp}/mcdebug-fabric-smoke.XXXXXX")
LOG="$SERVER_DIR/server.log"
SOCK="$SERVER_DIR/mcdebug/socket"
KEEP=${MCDEBUG_SMOKE_KEEP:-0}
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  # `(cd … && java …) &` 的 $! 是子 shell，setsid 又另开会话：按名字/PID 猜都会漏（曾攒下 7 个
  # 1GB 专用服）。改成按 unix socket 的 inode 定位真正持有它的游戏 JVM，cwd 兜底，从不碰 Gradle daemon。
  python3 "$ROOT/scripts/stop-server.py" --socket "$SOCK" --dir "$SERVER_DIR" >&2 || true
  if [[ "$KEEP" == 1 ]]; then printf 'fabric-smoke: retained server: %s\n' "$SERVER_DIR" >&2
  else rm -rf "$SERVER_DIR"; fi
  exit "$status"
}
trap cleanup EXIT INT TERM

mkdir -p "$SERVER_DIR/mods"
cp "$LAUNCHER" "$SERVER_DIR/fabric-server-launch.jar"
cp "$API_JAR" "$FKL_JAR" "$MOD_JAR" "$SERVER_DIR/mods/"
printf 'eula=true\n' > "$SERVER_DIR/eula.txt"
printf 'online-mode=false\nserver-port=0\nspawn-protection=0\nmax-players=1\nview-distance=4\nsync-chunk-writes=false\n' > "$SERVER_DIR/server.properties"

(cd "$SERVER_DIR" && setsid java -Xms512M -Xmx1G -jar fabric-server-launch.jar nogui > server.log 2>&1) &
SERVER_PID=$!            # 只用于“启动早期就退出”的探测，收尾走 stop-server.py

for _ in $(seq 1 180); do
  [[ -S "$SOCK" ]] && break
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "fabric-smoke: server exited early, see $LOG" >&2; tail -20 "$LOG" >&2; exit 1; }
  sleep 1
done
[[ -S "$SOCK" ]] || { echo "fabric-smoke: RPC socket not ready after 180s, see $LOG" >&2; exit 1; }
sleep 2

fail=0
cli() { java -jar "$CLI_JAR" --socket "$SOCK" "$@" 2>&1 | grep -v '^warning: server'; }
check() {
  local desc="$1" want="$2"; shift 2
  local out; out="$(cli "$@")" || true
  if [[ "$out" == *"$want"* ]]; then echo "ok   $desc"; else echo "FAIL $desc: want '$want' got: ${out:0:300}"; fail=1; fi
}

# 实体/方块进入世界往往要到下一拍才可见（机器忙时更久）：这类断言允许有限重试，
# 否则慢机器上会出现与代码无关的假失败（真实踩到过：spawn 之后立刻 scan 拿到 0）。
check_retry() {
  local tries="$1" desc="$2" want="$3"; shift 3
  local out=""
  for _ in $(seq 1 "$tries"); do
    out="$(cli "$@")" || true
    if [[ "$out" == *"$want"* ]]; then echo "ok   $desc"; return 0; fi
    sleep 0.4
  done
  echo "FAIL $desc: want '$want' got: ${out:0:300}"; fail=1
}

check "status modLoader=fabric"    '"modLoader":"fabric"'              status
check "status fluidUnit=droplet"   '"fluidUnit":"droplet"'         status
check "status energyUnit=E"        '"energyUnit":"E"'              status
check "setBlock chest"             '"ok":true'                          raw world.setBlock '{"pos":[2,200,0],"block":"minecraft:chest"}'
check "getBlock chest"             'minecraft:chest'                    raw world.getBlock '{"pos":[2,200,0]}'
check "inv setSlot"                '"ok":true'                          raw inv.setSlot '{"pos":[2,200,0],"slot":"0","item":"minecraft:diamond","count":5}'
check "inv getSlot"                '"count":5'                          raw inv.getSlot '{"pos":[2,200,0],"slot":"0"}'
# 1.21 数据组件改制：item nbt 经 minecraft:custom_data 组件无损往返
#nbt 布尔经 NbtByte 序列化为 JSON 数字 1（#nbt 哨兵可精确控制类型，这里验证默认路径）
check "item nbt set"               '"ok":true'                          raw inv.setSlot '{"pos":[2,200,0],"slot":"1","item":"minecraft:paper","count":1,"nbt":{"customFlag":true}}'
check "item nbt read back"         '"customFlag":1'                     raw inv.getSlot '{"pos":[2,200,0],"slot":"1"}'
check "be nbt carries component"   'custom_data'                        raw be.getNbt '{"pos":[2,200,0]}'
check "storage lists fabric:item"  '"handle":"fabric:item"'             raw storage.list '{"target":{"kind":"block","pos":[2,200,0]}}'
# loader 接缝：item 插入/读取/转移/模拟，以及物品承载流体（桶）——Fabric 走 Transfer API，
# NeoForge 走 capabilities，两条实现必须给出同一份 JSON
check "storage insert fabric:item" '"inserted":3'                      raw storage.insert '{"target":{"kind":"block","pos":[2,200,0]},"handle":"fabric:item","resource":{"kind":"item","item":"minecraft:redstone"},"amount":3}'
check "storage get fabric:item"    '"supportsInsertion":true'          raw storage.get '{"target":{"kind":"block","pos":[2,200,0]},"handle":"fabric:item"}'
check "second chest"               '"ok":true'                         raw world.setBlock '{"pos":[6,200,0],"block":"minecraft:chest"}'
check "storage transfer item"      '"transferred":3'                   raw storage.transfer '{"from":{"kind":"block","pos":[2,200,0]},"to":{"kind":"block","pos":[6,200,0]},"resource":{"kind":"item","item":"minecraft:redstone"},"amount":3}'
check "storage insert simulate"    '"simulated":true'                  raw storage.insert '{"target":{"kind":"block","pos":[6,200,0]},"handle":"fabric:item","resource":{"kind":"item","item":"minecraft:redstone"},"amount":3,"simulate":true}'
# 流体单位：Fabric = droplet（1 桶 = 81000），Forge/NeoForge = mB（1 桶 = 1000），见 server.status.fluidUnit。
# 物品承载的流体存储是整桶交换（FullItemFluidStorage 要求 maxAmount >= 整桶），所以这里请求 81000。
check "fluid extract from bucket"  '"extracted":81000'                 raw storage.extract '{"target":{"kind":"item","stack":{"item":"minecraft:water_bucket","count":1}},"handle":"fabric:fluid","resource":{"kind":"fluid","fluid":"minecraft:water"},"amount":81000}'
check "fluid targetAfter emptied"  '"targetAfter":{"kind":"item","stack":{"item":"minecraft:bucket"' raw storage.extract '{"target":{"kind":"item","stack":{"item":"minecraft:water_bucket","count":1}},"handle":"fabric:fluid","resource":{"kind":"fluid","fluid":"minecraft:water"},"amount":81000}'
check "fluid partial bucket = 0"   '"extracted":0'                     raw storage.extract '{"target":{"kind":"item","stack":{"item":"minecraft:water_bucket","count":1}},"handle":"fabric:fluid","resource":{"kind":"fluid","fluid":"minecraft:water"},"amount":1000}'
check "wait.until"                 '"matched":true'                     raw wait.until '{"predicate":"inv[2,200,0].0.count == 5","timeoutTicks":50}'
# world.setBlock 走原版 /setblock 的形状更新：悬空拉杆会被 updateFromNeighbourShapes 拿掉（ok:false），
# 所以先垫一块支撑方块，并用显式 props 跳过形状推导（face=floor 需要下方是固体）
check "lever support"              '"ok":true'                          raw world.setBlock '{"pos":[4,199,0],"block":"minecraft:stone"}'
check "place lever"                '"ok":true'                          raw world.setBlock '{"pos":[4,200,0],"block":"minecraft:lever","state":{"face":"floor","facing":"north"}}'
check "redstone setLever"          '"powered":true'                     raw redstone.setLever '{"pos":[4,200,0],"powered":true}'
check "runCommand"                 '"success":true'                     raw server.runCommand '{"command":"/time set day"}'

# ---- 参数校验：缺参/类型错必须是 -32602 并点名参数，不能是 -32603 ----
check "missing pos -> -32602"      'error [-32602] missing param: pos'  raw inv.setSlot '{"slot":0,"item":"minecraft:stone"}'
check "bad pos -> -32001"          'error [-32001] param pos'           raw world.getBlock '{"pos":[1,2]}'
check "bad int -> -32602"          'error [-32602] param slot'          raw inv.getSlot '{"pos":[2,200,0],"slot":"x"}'
check "numeric slot accepted"      '"count":5'                          raw inv.getSlot '{"pos":[2,200,0],"slot":0}'

# ---- reflect：专用服是 intermediary 运行时，映射表必须对上 1.21.1 ----
check "reflect simple name"        '"runtimeClass":"net.minecraft.class_2586"' raw reflect.resolve '{"class":"BlockEntity"}'
check "reflect mojangClass"        '"mojangClass":"net.minecraft.world.level.block.entity.BlockEntity"' raw reflect.resolve '{"class":"net.minecraft.world.level.block.entity.BlockEntity"}'
check "reflect static field"       '"resolvedTo":"field_'               raw reflect.get '{"class":"net.minecraft.world.level.block.Blocks","field":"STONE"}'

# ---- storage / transfer（[6,200,0] 的箱子已在上面 storage 段放好）----
check "storage insert"             '"inserted":3'                          raw storage.insert '{"target":{"kind":"block","pos":[6,200,0]},"handle":"fabric:item","resource":{"kind":"item","item":"minecraft:iron_ingot"},"amount":3}'
check "storage transfer"           '"transferred":2'                          raw storage.transfer '{"from":{"kind":"block","pos":[6,200,0]},"to":{"kind":"block","pos":[2,200,0]},"resource":{"kind":"item","item":"minecraft:iron_ingot"},"amount":2}'
check "transfer arrived"           '"matched":true'                          raw wait.until '{"predicate":"sum(inv[2,200,0].*.count) == 8","timeoutTicks":5}'
# ---- 拒绝型 transfer 必须“什么都不动”（跨 loader 回归护栏）----
# 预移植的 Fabric 实现在一个 Transaction 里做整笔搬运：目标收不下 → 整体不提交。移植后共享层
# 改成 dry-run + 尽力回滚，这里用**专用坐标**（不受其它断言影响）把语义钉死：
# 目标箱子填满 1728 → 转移必须 moved 0，且来源槽位原封不动。
check "guard: source chest"              '"ok":true'                     raw world.setBlock '{"pos":[2,202,0],"block":"minecraft:chest"}'
check "guard: destination chest"         '"ok":true'                     raw world.setBlock '{"pos":[6,202,0],"block":"minecraft:chest"}'
check "guard: fill destination"          '"inserted":1728'               raw storage.insert '{"target":{"kind":"block","pos":[6,202,0]},"handle":"fabric:item","resource":{"kind":"item","item":"minecraft:stone"},"amount":1728}'
check "guard: source has 3 redstone"     '"ok":true'                     raw inv.setSlot '{"pos":[2,202,0],"slot":0,"item":"minecraft:redstone","count":3}'
check "guard: transfer into full = 0"    '"transferred":0'               raw storage.transfer '{"from":{"kind":"block","pos":[2,202,0]},"to":{"kind":"block","pos":[6,202,0]},"resource":{"kind":"item","item":"minecraft:redstone"},"amount":3}'
check "guard: source slot untouched"     '"item":"minecraft:redstone","count":3' raw inv.getSlot '{"pos":[2,202,0],"slot":0}'
# ---- be.tick：有上限 ----
check "furnace"                    '"ok":true'                          raw world.setBlock '{"pos":[8,200,0],"block":"minecraft:furnace"}'
check "be.tick ok"                 '"ticked":3'                         raw be.tick '{"pos":[8,200,0],"ticks":3}'
check "be.tick bounded"            'error [-32602] ticks must be'       raw be.tick '{"pos":[8,200,0],"ticks":20000}'

# ---- craft ----
check "craft planks"               'minecraft:oak_planks'               raw craft.craft '{"grid":[{"item":"minecraft:oak_log","count":1},null,null,null,null,null,null,null,null]}'
check "craft grid cell type"       '[-32602]'                            raw craft.craft '{"grid":["minecraft:oak_log",null,null,null,null,null,null,null,null]}'

# ---- scan.findEntities：整块包含，不再四周扩 1 格 ----
# (0,0) 不一定在出生点常驻区块里：不 forceload 的话区块随时卸载，猪跟着存盘消失
check "forceload chunk 0,0"        '"forced":true'                      raw world.forceloadChunk '{"chunk":[0,0]}'
check "spawn pig"                  '"spawned":true'                     raw entity.spawn '{"type":"minecraft:pig","pos":[10.5,201,0.5],"nbt":{"NoAI":true,"NoGravity":true}}'
check_retry 10 "pig in its block"  '"count":1'                          raw scan.findEntities '{"box":{"from":[10,201,0],"to":[10,201,0]},"type":"minecraft:pig"}'
check "pig not in neighbour"       '"count":0'                          raw scan.findEntities '{"box":{"from":[12,201,0],"to":[12,201,0]},"type":"minecraft:pig"}'

# ---- snapshot.diff 默认忽略 /tick /time ----
check "diff ignores meta"          '"equal":true'                       raw snapshot.diff '{"before":{"tick":1,"time":5,"blocks":[]},"after":{"tick":9,"time":7,"blocks":[]}}'
check "diff includes meta on ask"  '"equal":false'                      raw snapshot.diff '{"before":{"tick":1},"after":{"tick":9},"ignoreMeta":false}'

# 契约一致性 + 平面隔离：见 scripts/contract-parity.py（按 id 匹配响应，排除客户端平面 gui.*）
python3 scripts/contract-parity.py "$SOCK" contract/src/main/kotlin/com/mcdebug/contract/Methods.kt \
  || { echo "fabric smoke: FAILED (contract parity)" >&2; exit 1; }

[ "$fail" = 0 ] && echo "fabric smoke: ALL OK" || { echo "fabric smoke: FAILED"; exit 1; }
