# Isolated release-jar performance consumer

Template only: **copy this project to a new temporary directory before running it**.
It contains no mcdebug source sets/resources. The benchmark installs the remapped
release jar in each fresh gameDir/mods, so this tests consumer loading rather than
mcdebug's own Loom dev classes. It is still a Loom runtime, not an official-launcher
intermediary production installation.

From the mcdebug repository root:

```bash
./gradlew compileClientKotlin :cli:copyCliJar build --console=plain
REPO="$PWD"
F="$(mktemp -d /tmp/mcp-XXXXXXXX)"
mkdir -p "$F/consumer/gradle/wrapper"
cp scripts/perf-consumer/build.gradle scripts/perf-consumer/settings.gradle scripts/perf-consumer/gradle.properties "$F/consumer/"
cp gradlew "$F/consumer/gradlew"
cp gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties "$F/consumer/gradle/wrapper/"
```

Prepare dependencies separately and record its duration. Do not delete shared
Gradle/download/page/Mesa caches, kill shared daemons, or call this a cold-cache test.
Run `classes clientClasses` in `$F/consumer` with its copied wrapper.

Provide a dedicated, already-running X display (the measured run used Xvfb :108)
and a **stopped world snapshot**. Never use an active production world, existing
consumer run/mods or a symlink to saves. The controller copies the world, excluding
session.lock; it does not delete or bypass the source world's lock.

```bash
python3 scripts/client-performance.py --project "$F/consumer" --cli "$REPO/mcdebug-cli.jar" --mod-jar "$REPO/build/libs/mcdebug-0.6.1.jar" --source-world "$REPO/run/saves/New World" --out "$F/results" --display :108
python3 scripts/summarize-client-performance.py --manifest "$F/results/manifest.json" > "$F/summary.json"
python3 -m unittest discover -s scripts -p 'test_*performance.py' -v
```

The output directory must not exist. Default: 3 Latin-rotated rounds × 3 modes,
30-second warmup, 20-second world idle, 5-second GUI stabilization, 20-second GUI
idle, 20 sequential GUI screenshots at 1-second intervals. Fresh temporary gameDirs,
Session records, logs and PNGs are retained; the controller normally stops each
owned client before starting the next one. On failure, inspect the JSON cleanup
result before doing anything else. Never solve a failure by deleting session.lock
or killing shared Gradle processes.

Configuration: MC1.21.1/Loader0.19.5/FabricAPI0.116.17+1.21.1/FLK1.13.9+kotlin2.3.10;
854×480, GUI scale2, render distance2/simulation5, maxFps60/no-vsync, MC Xms256m/Xmx2G.
The controller explicitly selects llvmpipe software GL with four Mesa workers;
actual graphics strings are checked in every trial. This software-GL setup is not
hardware GPU utilization or a universal modpack benchmark.

CPU 100% means one logical core, not the whole machine. Resource statistics use
the actual /proc sampling subwindow recorded in each stage (about18–19 seconds),
not an exact full20-second CPU integral. RSS is not Java heap/VRAM; VmHWM is a
process-lifetime high-water mark. Screenshot timing includes new-frame scheduling,
readback, crop, PNG encoding, file writes/hash and persistent Unix RPC response;
it is not per-call Java CLI startup latency. See the full measurement plan in
`docs/plan-client-performance.md`.
