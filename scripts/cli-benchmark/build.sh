#!/usr/bin/env bash
# 构建完整 CLI、最小 Native Image 和最小 Kotlin/Native 探针。
set -euo pipefail
benchmark_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$benchmark_root"
: "${GRAALVM_HOME:?请指定 GraalVM 目录}"
: "${KONAN_HOME:?请指定 Kotlin/Native 目录}"
benchmark_out="$benchmark_root/build/cli-benchmark"
mkdir -p "$benchmark_out"
./gradlew :cli:copyCliJar --console=plain
"$GRAALVM_HOME/bin/native-image" -jar mcdebug-cli.jar --no-fallback \
  --initialize-at-build-time=com.github.ajalt.mordant.internal.nativeimage.NativeImagePosixMppImpls \
  -o "$benchmark_out/mdb"
"$GRAALVM_HOME/bin/javac" -d "$benchmark_out" scripts/cli-benchmark/Minimal.java
"$GRAALVM_HOME/bin/native-image" -cp "$benchmark_out" Minimal --no-fallback -o "$benchmark_out/minimal"
"$KONAN_HOME/bin/konanc" scripts/cli-benchmark/Probe.kt -opt -o "$benchmark_out/probe"
