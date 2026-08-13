#!/usr/bin/env bash
# build-native.sh — 用 GraalVM native-image 构建 mcdebug CLI 单文件可执行
#
# 背景：JVM 冷启动 ~790ms，native-image 产物启动 ~11ms、无 JDK/node 运行时依赖，
#       适合在 server 上高频调用（status/清怪/排查）。
#       实测（GraalVM CE 21.0.2）：一次构建 58s、峰值内存 3.5GB、产物 ~42MB；
#       Gson 树模型 + JDK unix socket 零配置通过。
#
# 用法: ./scripts/build-native.sh [输出路径，默认 dist/mcdebug-native]
# 前置: ① ./build.sh 已跑过（需要 dist/mcdebug-cli-<版本>.jar）
#       ② native-image 可用：GRAALVM_HOME/bin 或 PATH（GraalVM CE 21+，含 native-image 组件）
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-dist/mcdebug-native}"
# CLI fat jar 是 dist/mcdebug-cli.jar（带 Main-Class manifest）；版本号后缀的 mcdebug-cli-<v>.jar 是无 manifest 的薄 jar
JAR="dist/mcdebug-cli.jar"
if [ ! -f "$JAR" ]; then
  JAR="$(ls dist/mcdebug-cli-*.jar 2>/dev/null | grep -v -- '-sources' | sort | tail -1 || true)"
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "build-native: 未找到 CLI fat jar，请先运行 ./build.sh" >&2
  exit 1
fi

NATIVE_IMAGE="${NATIVE_IMAGE:-native-image}"
if [ -n "${GRAALVM_HOME:-}" ] && [ -x "${GRAALVM_HOME}/bin/native-image" ]; then
  NATIVE_IMAGE="${GRAALVM_HOME}/bin/native-image"
fi
command -v "$NATIVE_IMAGE" >/dev/null 2>&1 || command -v "${NATIVE_IMAGE##*/}" >/dev/null 2>&1 || {
  echo "build-native: 找不到 native-image。安装 GraalVM CE 21+（含 native-image 组件）并设置 GRAALVM_HOME。" >&2
  exit 1
}

echo "build-native: jar=$JAR"
echo "build-native: 构建中（约 1 分钟，峰值内存 ~3.5GB）..."
"$NATIVE_IMAGE" -jar "$JAR" --no-fallback -o "$OUT"

echo "build-native: ✅ 完成 -> $OUT"
ls -la "$OUT"
