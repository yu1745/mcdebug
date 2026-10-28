#!/usr/bin/env bash
# Forge 端冒烟：起 forge/ 的 GameTest 服务器（不走 dedicated 服务端，因此没有 EULA 提示；
# devtest 里的 RpcHold 测试让它保持存活），用现有 mcdebug-cli.jar 对它做几组 RPC 断言，
# 然后释放（touch flag）让服务器正常停机。
#   用法: scripts/forge-smoke.sh            （需要 mcdebug-cli.jar；无需 root、无需网络之外的资源）
#   日志: forge/run-gametest/smoke.log
#
# 契约一致性只看 **服务端平面**（`Methods.SERVER_METHODS`）：`gui.*` 由客户端 entrypoint
# 注册，Forge 服务端（乃至 Fabric 专用服务端）上本来就不存在，报 -32601 是正确行为。
set -euo pipefail
cd "$(dirname "$0")/.."
CLI_JAR="${CLI_JAR:-mcdebug-cli.jar}"
SOCK="$PWD/forge/run-gametest/mcdebug/socket"
LOG="$PWD/forge/run-gametest/smoke.log"
METHODS_KT="contract/src/main/kotlin/com/mcdebug/contract/Methods.kt"
FLAG="$(mktemp -u /tmp/mcdebug-forge-smoke.XXXXXX)"
mkdir -p forge/run-gametest
rm -f "$SOCK"
rm -rf forge/run-gametest/gametestworld   # 开发用一次性世界，保证断言可重复

# 服务端平面契约校验 + 平面隔离：与 fabric-smoke.sh 共用 scripts/contract-parity.py，
# 避免两个冒烟脚本各自解析 Methods.kt 而漂移（历史教训：fabric 侧漏掉"排除 gui.*"与
# "按 id 匹配响应"，报出假的 inv.extract 缺失）。
contract_parity() {
  python3 scripts/contract-parity.py "$SOCK" "$METHODS_KT"
}


# Gradle 8.1.1 in forge/ must run on JDK 17 (no machine path is pinned in forge/gradle.properties).
FORGE_JAVA_HOME="${JAVA17_HOME:-${JAVA_HOME_17_X64:-/usr/lib/jvm/java-17-openjdk-amd64}}"
(cd forge && JAVA_HOME="$FORGE_JAVA_HOME" ./gradlew runGameTestServer -PdevFlag="$FLAG" --console=plain >"$LOG" 2>&1) &
SERVER_PID=$!

fail=0
cleanup() {
  if ! contract_parity; then fail=1; fi
  touch "$FLAG"; wait "$SERVER_PID" 2>/dev/null || true; rm -f "$FLAG"
}
trap cleanup EXIT

# 轮询等 RPC ready（有界：最多 180s，服务器进程提前退出立即失败，避免模式写错时无限等）
for _ in $(seq 1 90); do
  grep -q "mcdebug RPC ready" "$LOG" 2>/dev/null && break
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "server exited early, see $LOG" >&2; exit 1; }
  sleep 2
done
grep -q "mcdebug RPC ready" "$LOG" || { echo "RPC not ready after 180s, see $LOG" >&2; exit 1; }

fail=0
cli() { java -jar "$CLI_JAR" --socket "$SOCK" "$@" 2>&1 | grep -v '^warning: server'; }
check() { # check <desc> <expected-substring> <cli args...>
  local desc="$1" want="$2"; shift 2
  local out; out="$(cli "$@")" || true
  if [[ "$out" == *"$want"* ]]; then echo "ok   $desc"; else echo "FAIL $desc: want '$want' got: ${out:0:300}"; fail=1; fi
}

check "status modLoader=forge"      '"modLoader":"forge"'           status
check "setBlock stone"              '"ok":true'                      raw world.setBlock '{"pos":[2,200,0],"block":"minecraft:chest"}'
check "getBlock chest"              'minecraft:chest'                raw world.getBlock '{"pos":[2,200,0]}'
check "inv setSlot"                 '"ok":true'                      raw inv.setSlot '{"pos":[2,200,0],"slot":"0","item":"minecraft:diamond","count":5}'
check "inv getSlot"                 '"count":5'                      raw inv.getSlot '{"pos":[2,200,0],"slot":"0"}'
check "storage list (capability)"   'fabric:item'                    raw storage.list '{"target":{"kind":"block","pos":[2,200,0]}}'
check "bucket fluid via capability" '"extracted":1000'               raw storage.extract '{"target":{"kind":"item","stack":{"item":"minecraft:water_bucket","count":1}},"handle":"fabric:fluid","resource":{"kind":"fluid","fluid":"minecraft:water"},"amount":1000}'
check "wait.until"                  '"matched":true'                 raw wait.until '{"predicate":"inv[2,200,0].0.count == 5","timeoutTicks":50}'
check "bow release spawns arrow"    'minecraft:arrow'                raw world.useItemHold '{"item":"minecraft:bow","ammo":"minecraft:arrow","direction":"north","holdTicks":20,"playerPos":[2,220,10]}'
check "runCommand"                  '"success":true'                 raw server.runCommand '{"command":"/time set day"}'

# 服务端平面契约校验（同上；cleanup 里还会再跑一次用于真实失败状态）
if contract_parity; then :; else fail=1; fi

touch "$FLAG"; wait "$SERVER_PID" || true; trap - EXIT; rm -f "$FLAG"
[ "$fail" = 0 ] && echo "forge smoke: ALL OK" || { echo "forge smoke: FAILED"; exit 1; }
