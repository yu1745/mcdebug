#!/usr/bin/env bash
# NeoForge 端冒烟：起 neoforge/ 嵌套构建的 dev 专用服（ModDevGradle 注入 classes，不用拷 jar），
# 用 mcdebug-cli.jar 走 unix socket 做服务端平面断言，最后跑契约一致性 + 平面隔离检查。
#
# 与 fabric-smoke.sh 的差异（都在这里断言，防止回归）：
#   - server.status.modLoader=neoforge、fluidUnit=mb（Fabric 是 droplet）
#   - reflect.* 运行在 Mojang 官方名上：BlockEntity 的 runtimeClass 就是 net.minecraft...（没有 class_ 前缀）
#   - 存储句柄仍叫 fabric:item / fabric:fluid（CLI 与契约按名字硬编码，两个 loader 保持一致），
#     但实现是 NeoForge capabilities：箱子能同时列出 vanilla:inventory 与 fabric:item
#
#   用法: scripts/neoforge-smoke.sh
#   依赖: java 21、网络（首次跑 Gradle/MDG 会下 NeoForge 与 assets，缓存在 ~/.gradle）
#   保留现场: MCDEBUG_SMOKE_KEEP=1 scripts/neoforge-smoke.sh

set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

NEO_DIR="$ROOT/neoforge"
RUN_DIR="$NEO_DIR/run"
SOCK="$RUN_DIR/mcdebug/socket"
LOG="$RUN_DIR/server-smoke.log"
CLI_JAR="$ROOT/mcdebug-cli.jar"
KEEP=${MCDEBUG_SMOKE_KEEP:-0}

[[ -f "$CLI_JAR" ]] || { echo "neoforge-smoke: missing $CLI_JAR (run ./gradlew :cli:copyCliJar)" >&2; exit 1; }
[[ -x "$NEO_DIR/gradlew" ]] || { echo "neoforge-smoke: missing $NEO_DIR/gradlew" >&2; exit 1; }

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  # 见 fabric-smoke.sh：按 socket inode / cwd 定位游戏 JVM，wrapper 由 Gradle 自己收尾，
  # Gradle daemon 是共享的，绝不按名字杀。
  python3 "$ROOT/scripts/stop-server.py" --socket "$SOCK" --dir "$RUN_DIR" --dir "$NEO_DIR" >&2 || true
  if [[ "$KEEP" == 1 ]]; then printf 'neoforge-smoke: retained %s (log %s)\n' "$RUN_DIR" "$LOG" >&2
  else rm -rf "$RUN_DIR/world" "$RUN_DIR/mcdebug/socket" "$RUN_DIR/mcdebug/clientSocket"; fi
  exit "$status"
}
trap cleanup EXIT INT TERM

# 干净的现场：世界与旧 socket 都清掉（坐标断言依赖固定地形）；配置只写冒烟需要的
mkdir -p "$RUN_DIR" "$RUN_DIR/mcdebug"
printf 'eula=true\n' > "$RUN_DIR/eula.txt"
printf 'online-mode=false\nserver-port=0\nspawn-protection=0\nmax-players=1\nview-distance=4\nsync-chunk-writes=false\nlevel-type=minecraft\\:flat\n' > "$RUN_DIR/server.properties"
rm -rf "$RUN_DIR/world" "$RUN_DIR/mcdebug/socket" "$RUN_DIR/mcdebug/port" "$RUN_DIR/mcdebug/tcpPort"

echo "neoforge-smoke: starting dev server (first run downloads NeoForge + assets)..." >&2
# setsid 建进程组：Gradle wrapper + 游戏 JVM 一起收掉，绝不 pkill 按名字匹配（会打到本脚本自己）
( cd "$NEO_DIR" && setsid ./gradlew runServer --console=plain > "$LOG" 2>&1 ) &
SERVER_PGID=$!           # 仅用于“构建/启动早期失败”的探测；收尾走 stop-server.py

for _ in $(seq 1 240); do
  [[ -S "$SOCK" ]] && break
  kill -0 "$SERVER_PGID" 2>/dev/null || { echo "neoforge-smoke: build/server exited early, see $LOG" >&2; tail -30 "$LOG" >&2; exit 1; }
  sleep 1
done
[[ -S "$SOCK" ]] || { echo "neoforge-smoke: RPC socket not ready after 240s, see $LOG" >&2; tail -30 "$LOG" >&2; exit 1; }
sleep 2

fail=0
cli() { java -jar "$CLI_JAR" --socket "$SOCK" "$@" 2>&1 | grep -v '^warning: server'; }
check() {
  local desc="$1" want="$2"; shift 2
  local out; out="$(cli "$@")" || true
  if [[ "$out" == *"$want"* ]]; then echo "ok   $desc"; else echo "FAIL $desc: want '$want' got: ${out:0:300}"; fail=1; fi
}

# 实体进入世界要到下一拍才可见（忙时更久），这类断言允许有限重试：见 fabric-smoke.sh 的同名函数
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

check "status modLoader=neoforge"  '"modLoader":"neoforge"'              status
check "status fluidUnit=mb"        '"fluidUnit":"mb"'                    status
check "status energyUnit=FE"       '"energyUnit":"FE"'                   status
check "world flat + setBlock"      '"ok":true'                           raw world.setBlock '{"pos":[2,200,0],"block":"minecraft:chest"}'
check "getBlock chest"             'minecraft:chest'                     raw world.getBlock '{"pos":[2,200,0]}'
check "inv setSlot"                '"ok":true'                           raw inv.setSlot '{"pos":[2,200,0],"slot":"0","item":"minecraft:diamond","count":5}'
check "inv getSlot"                '"count":5'                           raw inv.getSlot '{"pos":[2,200,0],"slot":"0"}'
# 1.21 数据组件：item nbt 经 minecraft:custom_data 组件无损往返
check "item nbt set"               '"ok":true'                           raw inv.setSlot '{"pos":[2,200,0],"slot":"1","item":"minecraft:paper","count":1,"nbt":{"customFlag":true}}'
check "item nbt read back"         '"customFlag":1'                      raw inv.getSlot '{"pos":[2,200,0],"slot":"1"}'
check "be nbt carries component"   'custom_data'                         raw be.getNbt '{"pos":[2,200,0]}'

# ---- 存储接缝：NeoForge capabilities（箱子同时给 vanilla:inventory 与 fabric:item）----
check "storage vanilla handle"     '"handle":"vanilla:inventory"'        raw storage.list '{"target":{"kind":"block","pos":[2,200,0]}}'
check "storage capability handle"  '"handle":"fabric:item"'              raw storage.list '{"target":{"kind":"block","pos":[2,200,0]}}'
check "storage insert"             '"inserted":3'                        raw storage.insert '{"target":{"kind":"block","pos":[2,200,0]},"handle":"fabric:item","resource":{"kind":"item","item":"minecraft:redstone"},"amount":3}'
check "storage get"                '"supportsInsertion":true'            raw storage.get '{"target":{"kind":"block","pos":[2,200,0]},"handle":"fabric:item"}'
check "storage simulate no-op"     '"simulated":true'                    raw storage.insert '{"target":{"kind":"block","pos":[2,200,0]},"handle":"fabric:item","resource":{"kind":"item","item":"minecraft:redstone"},"amount":3,"simulate":true}'
check "second chest"               '"ok":true'                           raw world.setBlock '{"pos":[6,200,0],"block":"minecraft:chest"}'
check "storage transfer item"      '"transferred":3'                     raw storage.transfer '{"from":{"kind":"block","pos":[2,200,0]},"to":{"kind":"block","pos":[6,200,0]},"resource":{"kind":"item","item":"minecraft:redstone"},"amount":3}'
check "transfer arrived"           '"matched":true'                      raw wait.until '{"predicate":"inv[6,200,0].0.count == 3","timeoutTicks":50}'
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
# ---- 快进（时间压缩）：原版 /tick 机制直通 + wait.until sprint:true + health.tps 加速读数 ----
# 与 fabric-smoke.sh 同一套断言：sprint 是原版机制，两个 loader 行为必须一致
# 与 fabric-smoke.sh 同一套断言：sprint 是原版机制，两个 loader 行为必须一致。
# health 断言用持续 rate（无到期）：快机上 sprint ~76k TPS，预算式断言不可满足（详见 fabric-smoke.sh）。
check "rate set 10000"            '"success":true'                    raw server.runCommand '{"command":"/tick rate 10000"}'
_tps=""
for _ in 1 2 3; do
  _tps=$(cli raw server.health | grep -o '"tps":[0-9.]*' | head -1 | cut -d: -f2)
  if [[ -n "$_tps" ]] && python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) > 20.5 else 1)" "$_tps" 2>/dev/null; then break; fi
  _tps=""; sleep 0.3

done
if [[ -n "$_tps" ]]; then echo "ok   rate 10000: health tps > 20 (${_tps})"
else echo "FAIL rate 10000: health tps never exceeded 20"; fail=1; fi
check "rate restore 20"           '"success":true'                    raw server.runCommand '{"command":"/tick rate 20"}'
_tick0=$(cli status | grep -o '"tick":[0-9]*' | head -1 | cut -d: -f2)
_target=$((_tick0 + 1200))
_t0=$(date +%s.%N)
check "wait.until sprint matched"  '"matched":true'                     raw wait.until "{\"predicate\":\"tick >= ${_target}\",\"timeoutTicks\":2400,\"sprint\":true}"
_t1=$(date +%s.%N)
_dur=$(python3 -c "print(f'{${_t1} - ${_t0}:.1f}')")
if python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) < 30 else 1)" "$_dur" 2>/dev/null; then
  echo "ok   wait.until sprint fast (${_dur}s for 1200 ticks)"
else
  echo "FAIL wait.until sprint not fast: ${_dur}s"; fail=1
fi
check "wait.until sprint engine off" '"success":false'                  raw server.runCommand '{"command":"/tick sprint stop"}'

# ---- 参数校验：缺参/类型错必须 -32602 并点名参数 ----
check "missing pos -> -32602"      'error [-32602] missing param: pos'   raw inv.setSlot '{"slot":0,"item":"minecraft:stone"}'
check "bad pos -> -32001"          'error [-32001] param pos'            raw world.getBlock '{"pos":[1,2]}'
check "bad int -> -32602"          'error [-32602] param slot'           raw inv.getSlot '{"pos":[2,200,0],"slot":"x"}'
check "numeric slot accepted"      '"count":5'                           raw inv.getSlot '{"pos":[2,200,0],"slot":0}'

# ---- reflect：NeoForge 的 dev 与生产都跑 Mojang 官方名，运行时名 == Mojang 名（没有映射表），
# 所以响应里没有 mojangClass 字段，短名也不再解析（Fabric 的短名来自它打包的映射表）：用 FQN。
check "reflect runtime = mojang"   '"runtimeClass":"net.minecraft.world.level.block.entity.BlockEntity"' raw reflect.resolve '{"class":"net.minecraft.world.level.block.entity.BlockEntity"}'
check "reflect loader class"       '"runtimeClass":"net.neoforged.neoforge.items.wrapper.InvWrapper"'   raw reflect.resolve '{"class":"net.neoforged.neoforge.items.wrapper.InvWrapper"}'
check "reflect static field"       '"resolvedTo":"STONE"'                raw reflect.get '{"class":"net.minecraft.world.level.block.Blocks","field":"STONE"}'

# ---- tick 只能被动观察；be.tick 是唯一例外且有上限 ----
# 见 fabric-smoke.sh：悬空拉杆会被形状更新拿掉，先垫支撑块 + 显式 props
check "lever support"              '"ok":true'                           raw world.setBlock '{"pos":[4,199,0],"block":"minecraft:stone"}'
check "place lever"                '"ok":true'                           raw world.setBlock '{"pos":[4,200,0],"block":"minecraft:lever","state":{"face":"floor","facing":"north"}}'
check "redstone setLever"          '"powered":true'                      raw redstone.setLever '{"pos":[4,200,0],"powered":true}'
check "runCommand"                 '"success":true'                      raw server.runCommand '{"command":"/time set day"}'
check "furnace"                    '"ok":true'                           raw world.setBlock '{"pos":[8,200,0],"block":"minecraft:furnace"}'
check "be.tick ok"                 '"ticked":3'                          raw be.tick '{"pos":[8,200,0],"ticks":3}'
check "be.tick bounded"            'error [-32602] ticks must be'        raw be.tick '{"pos":[8,200,0],"ticks":20000}'
check "craft planks"               'minecraft:oak_planks'                raw craft.craft '{"grid":[{"item":"minecraft:oak_log","count":1},null,null,null,null,null,null,null,null]}'
check "craft grid cell type"      '[-32602]'                            raw craft.craft '{"grid":["minecraft:oak_log",null,null,null,null,null,null,null,null]}'

# ---- 实体与快照 ----
check "forceload chunk 0,0"        '"forced":true'                       raw world.forceloadChunk '{"chunk":[0,0]}'
check "spawn pig"                  '"spawned":true'                      raw entity.spawn '{"type":"minecraft:pig","pos":[10.5,201,0.5],"nbt":{"NoAI":true,"NoGravity":true}}'
check_retry 10 "pig in its block"  '"count":1'                           raw scan.findEntities '{"box":{"from":[10,201,0],"to":[10,201,0]},"type":"minecraft:pig"}'
check "diff ignores meta"          '"equal":true'                        raw snapshot.diff '{"before":{"tick":1,"time":5,"blocks":[]},"after":{"tick":9,"time":7,"blocks":[]}}'
check "diff includes meta on ask"  '"equal":false'                       raw snapshot.diff '{"before":{"tick":1},"after":{"tick":9},"ignoreMeta":false}'

# 契约一致性 + 平面隔离：见 scripts/contract-parity.py（按 id 匹配响应，排除客户端平面 gui.*）
python3 scripts/contract-parity.py "$SOCK" contract/src/main/kotlin/com/mcdebug/contract/Methods.kt \
  || { echo "neoforge smoke: FAILED (contract parity)" >&2; exit 1; }

[ "$fail" = 0 ] && echo "neoforge smoke: ALL OK" || { echo "neoforge smoke: FAILED"; exit 1; }
