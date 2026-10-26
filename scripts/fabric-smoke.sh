#!/usr/bin/env bash
# Fabric 端冒烟：把 build/libs 的 remapped jar 放进一次性 1.21.1 专用服，
# 用 mcdebug-cli.jar 走 unix socket 做几组 RPC 断言（含 1.21 数据组件改制后
# 的 item nbt / be nbt 往返），最后做契约一致性检查（Methods.kt 的每个方法
# 都必须已注册，不允许 -32601）。
#   用法: scripts/fabric-smoke.sh
#   依赖: java 21、网络（launcher/fabric-api 缓存在 ~/.cache/mcdebug-fabric-smoke）
set -euo pipefail
cd "$(dirname "$0")/.."

MOD_JAR=$(find build/libs -maxdepth 1 -name 'mcdebug-*.jar' ! -name '*-sources.jar' 2>/dev/null | head -1)
[[ -n "$MOD_JAR" ]] || { echo "fabric-smoke: build first (./gradlew build)" >&2; exit 2; }

CACHE_DIR=${MCDEBUG_SMOKE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/mcdebug-fabric-smoke}
MC_VERSION=1.21.1
LOADER_VERSION=0.19.5
API_VERSION=0.116.17+1.21.1
FKL_VERSION=1.13.9+kotlin.2.3.10
CLI_JAR=${CLI_JAR:-../mcdebug/mcdebug-cli.jar}
[[ -f "$CLI_JAR" ]] || CLI_JAR=mcdebug-cli.jar
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

(cd "$SERVER_DIR" && java -Xms512M -Xmx1G -jar fabric-server-launch.jar nogui > server.log 2>&1) &
SERVER_PID=$!
kill_server() { touch "$SERVER_DIR/.stop" 2>/dev/null; kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; }
cleanup_with_kill() { kill_server; cleanup; }
trap cleanup_with_kill EXIT INT TERM

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

check "status modLoader=fabric"    '"modLoader":"fabric"'              status
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
check "wait.until"                 '"matched":true'                     raw wait.until '{"predicate":"inv[2,200,0].0.count == 5","timeoutTicks":50}'
check "place lever"                '"ok":true'                          raw world.setBlock '{"pos":[4,200,0],"block":"minecraft:lever"}'
check "redstone setLever"          '"powered":true'                     raw redstone.setLever '{"pos":[4,200,0],"powered":true}'
check "runCommand"                 '"success":true'                     raw server.runCommand '{"command":"/time set day"}'

# 契约一致性：Methods.kt 的每个方法都必须已注册（不能是 -32601 method not found）
if python3 - "$SOCK" contract/src/main/kotlin/com/mcdebug/contract/Methods.kt <<'PYEOF'
import json, re, socket, sys
sock_path, methods_kt = sys.argv[1:3]
names = sorted(set(re.findall(r'"([a-z]+\.[A-Za-z]+)"', open(methods_kt).read())))
s = socket.socket(socket.AF_UNIX); s.connect(sock_path); f = s.makefile("rwb")
missing = []
for i, m in enumerate(names):
    f.write((json.dumps({"jsonrpc": "2.0", "id": i, "method": m, "params": {}}) + "\n").encode()); f.flush()
    r = json.loads(f.readline())
    if r.get("error", {}).get("code") == -32601: missing.append(m)
print(f"contract parity: {len(names) - len(missing)}/{len(names)} methods registered" + (f", MISSING {missing}" if missing else ""))
sys.exit(1 if missing else 0)
PYEOF
then :; else fail=1; fi

kill_server
[ "$fail" = 0 ] && echo "fabric smoke: ALL OK" || { echo "fabric smoke: FAILED"; exit 1; }
