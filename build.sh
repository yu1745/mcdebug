#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
# build.sh — 统一构建契约（通用 Gradle 实现）
#
# 契约（所有 T0/T1 仓库一致，外部调用方式不得更改）：
#   用法:   ./build.sh [--clean] [--out <dir>]      # 默认 out=dist
#   退出码: 0=成功；非 0=失败（原因写 stderr）
#   产出:   <out>/*.jar  +  <out>/build-info.json
#           build-info.json = { repo, version, source_commit, jars:[{file,sha256}], built_at }
#
# 内部实现可自由改写（gradle 目标、模块过滤、重命名、多产物、外部前置任务）。
# 可选仓库级覆盖：
#   - build-tasks.txt  存在则其内容（一行一个任务）替代默认 `build` 任务
#   - build-jars.txt   存在则只收集其中列出的 jar 文件名模式（每行一个 glob）
#   - MCDEBUG_BUILD_VERSIONS=26.3,1.21.1,1.20.1 或 all
#       根构建（纯 JVM 模块）之后，再用各自的 JDK 构建 versions/ 下的 Minecraft 版本，
#       然后重新打包 CLI 把这些模组 JAR 内嵌进去。各版本需要的 JDK：26.3→25，1.21.1→21，
#       1.20.1→17；依次取 JAVA25_HOME / JAVA21_HOME / JAVA17_HOME，未设置时回退
#       /usr/lib/jvm/java-<N>-openjdk-amd64。不设置时只构建根模块，不触发任何 Minecraft 构建。
#       收集到 <out>/ 的模组 JAR 改名为 mcdebug-<loader>-<mc>-<version>.jar（与 Release 一致）。
# ═══════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")"

OUT=dist
CLEAN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --clean) CLEAN=1 ;;
    --out) shift; OUT="$1" ;;
    *) echo "usage: $0 [--clean] [--out <dir>]" >&2; exit 2 ;;
  esac
  shift
done

if [ ! -x ./gradlew ]; then
  echo "build.sh: 本仓库无 ./gradlew，请改写本脚本适配（或检查目录）" >&2
  exit 1
fi

TASKS=build
[ -f build-tasks.txt ] && TASKS="$(tr '\n' ' ' < build-tasks.txt)"

if [ "$CLEAN" = 1 ]; then
  ./gradlew clean --console=plain
fi
./gradlew $TASKS --console=plain

build_mc_version() {  # $1=versions/ 下的目录名  $2=Gradle 守护进程使用的 JDK 主版本
  local jdk_var="JAVA$2_HOME"
  local jdk="${!jdk_var:-/usr/lib/jvm/java-$2-openjdk-amd64}"
  if [ ! -x "$jdk/bin/java" ]; then
    echo "build.sh: versions/$1 需要 JDK $2：请设置 JAVA$2_HOME（当前候选：$jdk）" >&2
    exit 1
  fi
  echo "build.sh: building versions/$1 with $jdk"
  ( cd "versions/$1" && JAVA_HOME="$jdk" ./gradlew build --console=plain )
}

if [ -n "${MCDEBUG_BUILD_VERSIONS:-}" ]; then
  requested="${MCDEBUG_BUILD_VERSIONS/all/26.3,1.21.1,1.20.1}"
  IFS=',' read -r -a mc_lines <<< "$requested"
  for line in "${mc_lines[@]}"; do
    case "$line" in
      26.3)   build_mc_version mc26.3 25 ;;
      1.21.1) build_mc_version mc1.21.1 21 ;;
      1.20.1) build_mc_version mc1.20.1 17 ;;
      *) echo "build.sh: MCDEBUG_BUILD_VERSIONS 只接受 26.3,1.21.1,1.20.1 或 all（得到 '$line'）" >&2; exit 2 ;;
    esac
  done
  # 模组 JAR 已在 versions/*/build/libs：重新打包 CLI，把它们内嵌进 mcdebug-cli.jar
  ./gradlew :cli:copyCliJar --console=plain
fi

rm -rf "$OUT"
mkdir -p "$OUT"

# 收集产物 jar：排除 sources/dev/slim 件（slim 是 Forge 未打 MixinExtras 的中间件）；其余（含 shadow/-all）都收。
# 26.3 与 1.21.1 的模组 JAR 同名（mcdebug-<v>.jar / mcdebug-neoforge-<v>.jar），直接 cp 会互相覆盖：
# versions/ 下的模组 JAR 按 Release 的命名改名为 mcdebug-<loader>-<mc>-<version>.jar。
# 只收本次构建的版本，避免把以前构建遗留在 versions/*/build/libs 的旧 JAR 混进产物。
built_lines=",${requested:-},"
find . -path '*/build/libs/*.jar' ! -name '*-sources.jar' ! -name '*-dev.jar' ! -name '*-slim.jar' -print0 \
  | while IFS= read -r -d '' j; do
      case "$j" in
        ./versions/mc*/build/libs/*)
          rel="${j#./versions/mc}"; line="${rel%%/*}"
          [[ "$built_lines" == *",$line,"* ]] || continue
          name="$(basename "$j" .jar)"
          case "$name" in
            mcdebug-neoforge-*) loader=neoforge; ver="${name#mcdebug-neoforge-}" ;;
            mcdebug-forge-*)    loader=forge;    ver="${name#mcdebug-forge-}" ;;
            *)                  loader=fabric;   ver="${name#mcdebug-}" ;;
          esac
          cp "$j" "$OUT/mcdebug-$loader-$line-$ver.jar" ;;
        *) cp "$j" "$OUT/" ;;
      esac
    done

# 可选过滤：build-jars.txt 每行一个 glob，只保留匹配的 jar
if [ -f build-jars.txt ]; then
  python3 - "$OUT" <<'PYEOF'
import sys, glob, pathlib
out = pathlib.Path(sys.argv[1])
keep = [p.strip() for p in pathlib.Path("build-jars.txt").read_text().splitlines() if p.strip()]
for j in list(out.glob("*.jar")):
    if not any(j.match(k) for k in keep):
        j.unlink()
PYEOF
fi

if ! ls "$OUT"/*.jar >/dev/null 2>&1; then
  echo "build.sh: 构建完成但未收集到任何产物 jar（检查 build 输出或 build-jars.txt 过滤）" >&2
  exit 1
fi

python3 - "$OUT" <<'PYEOF'
import hashlib, json, subprocess, sys, datetime, pathlib
out = pathlib.Path(sys.argv[1])

def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()

def git_head():
    r = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None

def git_remote():
    r = subprocess.run(["git", "remote", "get-url", "origin"], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None

# version：gradle.properties 的 mod_version/version，否则 git describe
version = None
gp = pathlib.Path("gradle.properties")
if gp.exists():
    for line in gp.read_text().splitlines():
        line = line.strip()
        if line.startswith("mod_version=") or line.startswith("version="):
            version = line.split("=", 1)[1].strip()
            break
if not version:
    r = subprocess.run(["git", "describe", "--tags", "--always"], capture_output=True, text=True)
    version = r.stdout.strip() or "unknown"

jars = [{"file": j.name, "sha256": sha256(j)} for j in sorted(out.glob("*.jar"))]
info = {
    "repo": git_remote(),
    "version": version,
    "source_commit": git_head(),
    "jars": jars,
    "built_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
(out / "build-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
print(f"build.sh: {len(jars)} jar(s) -> {out}/  version={version} commit={info['source_commit']}")
PYEOF
