# mcdebug — Minecraft Debug Mod

JSON-RPC debug agent for mod development automation. One jar, two planes: the
**server plane** (`world.*` / `be.*` / `inv.*` / `screen.*` …) inspects and mutates a live
server, the **client plane** (`gui.*`) drives a real game client — screenshots with pixel
coordinates and real Screen clicks, so an agent can close the loop on GUI work. The Kotlin
CLI (`mcdebug-cli.jar` / `mdb`) is the zero-install entry point.

```
┌─────────────────┐  JSON-RPC 2.0   ┌───────────────────────────────┐
│  mcdebug (Kotlin│  NDJSON over    │  mcdebug mod (Kotlin/Fabric)  │
│  CLI / runner)  │  unix socket    │  server plane + client plane  │
│                 │  <gameDir>/     │      ▲                        │
│                 │   mcdebug/*     │      │ TCP 25580 / 25581      │
│                 ├─────────────── ►│  (cross-machine access)       │
│                 │  NDJSON over    │                               │
│                 │  TCP host:port  │                               │
└─────────────────┘                 └───────────────────────────────┘
```

See **AGENTS.md** for the contributor guide (architecture, planes, naming, adding RPC
methods, verification, container manual). This README covers consumer-facing usage.

## Setup

```bash
./gradlew build              # mod + contract + Forge + self-contained jar check
./gradlew :cli:copyCliJar    # CLI fat jar -> ./mcdebug-cli.jar (repo root)

node bin/mcdebug.js --help          # via the npm shell (needs java on PATH/JAVA_HOME)
java -jar mcdebug-cli.jar --help    # directly
```

Fabric project setup itself: <https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up>.

## Deploy

The build product is a **normal mod jar** — copy it into the target environment's `mods/`
and restart. Server and client use the *same* jar (`environment: "*"`, both entrypoints);
the consumer project needs **zero changes** (no dependency, no plugin, no extra script).
Remove the old jar first — two mcdebug jars in one `mods/` fail to load.

A single-player client runs both planes in one process, so `world.*`/`be.*`/`inv.*` and
`gui.*` are both available against the same integrated server.

## Transports and discovery

The unix domain socket is the primary channel (local, concurrent, never exposed); TCP is
the auxiliary cross-machine channel. They bind independently: one side failing only logs a
WARN, only both failing is an error.

| | server plane | client plane (`gui.*`) |
|---|---|---|
| unix socket | `<gameDir>/mcdebug/socket` | `<gameDir>/mcdebug/clientSocket` |
| discovery file | `mcdebug/port` | `mcdebug/clientPort` |
| TCP | 25580, **enabled** | 25581, **disabled** (never expose a real player client) |
| json / property / env | `socket`, `tcpPort`, `tcpEnabled` / `-Dmcdebug.*` / `MCDEBUG_*` | same names with a `client` prefix (`clientSocket`, `-Dmcdebug.clientSocket`, `MCDEBUG_CLIENT_SOCKET`) |

Resolution order (both planes): CLI/JVM property → environment → `<gameDir>/config/mcdebug.json`
→ default. Discovery files are best effort: `port` holds the socket **path**, `tcpPort` the
actually bound port (written only when TCP bound; `tcpPort=0` = ephemeral).

The CLI discovers the socket via `--socket` / `MCDEBUG_SOCKET` / the discovery file and never
falls back between transports on its own — cross-machine access is explicit (`--tcp host:port`,
`--client-tcp host:port`). `gui.*` (including `raw gui.*`) automatically uses the client plane;
every other method uses the server plane.

## CLI usage

```bash
node bin/mcdebug.js status                                   # server.status (unix socket)
node bin/mcdebug.js raw world.getBlock '{"pos":[0,64,0]}'
node bin/mcdebug.js --tcp 192.168.5.102:25580 status          # cross-machine
node bin/mcdebug.js -c 'status' -c 'world get-block --x 0 --y 64 --z 0' --batch   # one connection, fail fast

pnpm dlx @yu1745/mcdebug status                               # published package
pnpm dlx 'github:yu1745/mcdebug#v0.5.0' snapshot capture --from 0,64,0 --to 2,66,2 --include block,inventory
```

Exit codes: `0` success (including business-level `ok:false` / `equal:false` — check the JSON),
`1` CLI usage/parse error, `2` server rejected the request (any JSON-RPC error incl. `-320xx`),
`3` method not found (usually a too-old mod jar). On connect the CLI compares the mod version
with its own and warns on stderr when they differ.

## RPC namespaces

Use `mcdebug raw <namespace.method> <json>` for anything without a dedicated command.

| namespace | purpose |
|---|---|
| `world.*` | blocks, regions, use/attack/interact, forceload |
| `be.*` | block-entity NBT and fields; `be.tick` drives a BE ticker N times |
| `inv.*` | item slots of a block entity |
| `storage.*` | unified item/fluid/energy access for blocks, entities, item stacks |
| `fluid.*` / `craft.*` / `scan.*` / `entity.*` / `fixture.*` | fluids, recipes, searches, entities, region fixtures |
| `snapshot.*` / `trace.*` | capture + diff state; record frames on natural ticks |
| `screen.*` | open/drive server-side `ScreenHandler`s with a fake player (**slot JSON, no pixels**) |
| `gui.*` | **client plane**: real GUI screenshots + Screen clicks (see below) |
| `redstone.*` | read power, set/pulse levers, neighbor updates |
| `wait.until` | wait for a predicate on natural server ticks (never drives ticks) |
| `reflect.*` | resolve/call/new/release any class at runtime |

`storage.*` resolves handles in order: vanilla `Inventory` → Fabric `ItemStorage` → Fabric
`FluidStorage` → Team Reborn `EnergyStorage`; targets are `{kind:"block"|"entity"|"item", ...}`
and results carry ids like `vanilla:inventory`, `fabric:item`, `fabric:fluid`,
`teamreborn:energy`. Prefer these generic APIs (and `be.tick` for machine progress) over
mod-specific NBT pokes.

The server never exposes `tick.run`/`tick.runUntil`: `trace.*` and `wait.until` observe natural
ticks only. The predicate DSL (`be[x,y,z].<path> <op> <value>`, `AND/OR/NOT`, `sum(...)`,
comparisons) is documented in `mcdebug --help` under `wait-until` and in AGENTS.md §7.

## Managed sessions (Linux, local clients)

A session manages a real client's lifecycle and both RPC planes. You no longer need to run
Gradle in another terminal: mdb starts the **target project's own** wrapper, waits for readiness,
then returns while the client stays running. There is no new daemon or alternate MC implementation.
Install the current mod jar in consumer projects first; mcdebug's own dev launch injects its classes
and must **not** have another mcdebug jar in its own `run/mods/`.

```bash
java -jar mcdebug-cli.jar session start demo --project . --world "New World" --render demand
java -jar mcdebug-cli.jar session status demo
java -jar mcdebug-cli.jar --session demo gui status
java -jar mcdebug-cli.jar --session demo raw server.status '{}'
java -jar mcdebug-cli.jar --session demo gui screenshot --mode window --out /tmp/window.png
java -jar mcdebug-cli.jar session logs demo --lines 80
java -jar mcdebug-cli.jar session stop demo
```

For a consumer with submodules, select its actual game directory explicitly (relative paths are
relative to your current working directory, not `--project`):

```bash
java -jar mcdebug-cli.jar session start ic2 --project ../ic2-fabric-1.21.1 --task :core:runClient --game-dir ../ic2-fabric-1.21.1/core/run --world "New World" --render demand
```

| command / option | behavior |
|---|---|
| `session start NAME` | project defaults to CWD, task `runClient`, gameDir `project/run`, render `demand`, window 854×480, wait 180s |
| `--world SAVE` | existing directory name under gameDir/saves; waits for single-player world readiness; omitted = client/main menu readiness |
| `--game-dir PATH` / `--task TASK` / `--display DISPLAY` | explicit run directory / qualified runClient task / child display |
| `--wait-seconds N` | bounded startup or shutdown wait; startup timeout is an error, not a stopped-process guarantee |
| `session attach NAME --game-dir PATH` | verify an existing local client; does **not** gain stop ownership |
| `session list` / `session status NAME` | report metadata, PID identity, endpoints, state and errors |
| `session logs NAME --lines N` | finite UTF-8 log tail; no follow loop |
| `session stop NAME [--force]` | normal owned-client shutdown; explicit force may kill only the exact owned MC process, never shared Gradle daemons or attached clients |
| `--session NAME` | route gui.* to the client and other methods to that client's integrated server; no global current session |
| `--sessions-dir PATH` | registry directory; otherwise MCDEBUG_SESSIONS_DIR, then XDG_STATE_HOME/mcdebug/sessions or ~/.local/state/mcdebug/sessions |

Batch inherits the selected session and registry. Once a plane's shared connection is open, changing
its session/transport/deadlines inside a `-c` command is rejected instead of silently reusing another
target; use separate invocations for different sessions. Managed launches keep both TCP listeners disabled
and pin Unix sockets inside the selected gameDir. Cross-machine/multiplayer-server pairing is not
part of Session v1; use the existing explicit transport options outside `--session`.

Sessions require a current identity-bearing `server.ready` and `mcdebug/client.json`: PID,
OS process startTime, per-JVM instanceId, launch token and canonical gameDir. PID alone, a socket
file alone, or a Gradle wrapper PID is **not** enough. Replaced endpoints fail rather than silently
routing commands into another game. `--session` cannot be combined with explicit socket/TCP overrides.

Same-name/same-config live starts reuse the session; another live instance/configuration or another
session on the same canonical gameDir is refused, including across registries. Session operations
never delete a world or Minecraft's session.lock. A stop during bootstrap can report `stopping`
(nonzero exit): the launch-token marker cancels a late MC startup, but mdb does not invent an exited
JVM identity. Inspect status/logs before retrying. The token is an ownership correlation value, **not
RPC authentication**.

`gui.stop` is the low-level identity-checked shutdown RPC (`instanceId`, `sessionToken`); normal use
should go through `session stop`. After deploying/publishing this build, raw access is also available:

```bash
pnpm dlx @yu1745/mcdebug --session demo raw gui.stop '{"instanceId":"INSTANCE_FROM_GUI_STATUS_PROCESS","sessionToken":"TOKEN_FROM_GUI_STATUS_PROCESS"}'
```

Raw RPC bypasses the CLI's owned/attached policy deliberately; it is not a security boundary.
See [Session design and safety rules](docs/plan-session-management.md).
Measured startup, CPU/RSS and screenshot latency (9 isolated consumer trials, with raw data and caveats):
[Client performance report](docs/client-performance.md).

## Client plane: `gui.*`

Start the client straight in a save instead of clicking through the menus (the value is the
folder name under `saves/`, not the display name):

```bash
DISPLAY=:0 ./gradlew :core:runClient --args="--width 1280 --height 720 --quickPlaySingleplayer \"New World\""
```

One client per game dir: a surviving instance keeps `saves/<level>/session.lock`, and the next
launch then logs `Failed to read level ... already locked` and falls back to the title screen.

| method | what it does |
|---|---|
| `gui.status` | client state: screen, window/gui scale, framebuffer, endpoint, **loaded jar path**, contract self-check |
| `gui.screen` | the open screen: container slots in GUI units + widget list (text/x/y/size/value) |
| `gui.screenshot` | real PNG — `--mode window` (framebuffer) or `--mode gui` (just the container GUI) + a transform mapping PNG pixels back to GUI units |
| `gui.click` | click by container slot, widget text, GUI coordinates, or **PNG pixels from the previous screenshot** |
| `gui.openBlock` | walk the client's own use-on-block path so a machine GUI opens |
| `gui.close` / `gui.connect` / `gui.reloadResources` | close the screen (Esc) / join `host[:port]` / client-side F3+T |
| `gui.key` / `gui.type` | one current-Screen key tap / Unicode char events, with scoped logical modifiers |
| `gui.drag` | bounded press → mouseDragged steps → release on the same Screen, with passive natural-tick delays |
| `gui.disconnect` | save/leave the world and return to title without stopping MC or client RPC |
| CLI-only `gui compare` | local PNG golden regression comparison; no RPC/client required |

```bash
# The agent loop: set the machine up on the server plane, look at it on the client plane.
mcdebug raw world.setBlock '{"pos":[100,64,100],"block":"minecraft:chest"}'
mcdebug raw inv.setSlot   '{"pos":[100,64,100],"slot":"0","item":"minecraft:diamond","count":5}'
mcdebug gui open-block --x 100 --y 64 --z 100
mcdebug gui screenshot --mode gui --out shot-1.png   # only the machine GUI
mcdebug gui click --slot 0                           # server-authoritative slot click
mcdebug gui screenshot --mode gui --out shot-2.png
mcdebug gui click --png 48,78                        # click by PNG pixel of the last screenshot
mcdebug gui close
```

Notes that save time:

* Screenshots wait for a **newly rendered frame**, not merely another tick. `delayTicks` is an
  optional passive delay before requesting that frame; `frameId` and `clientTick` describe the
  captured frame. This is not an acknowledgement of a previous server operation.
* `--mode gui` clips the container rectangle to the framebuffer. `transform.cropInGui` retains
  fractional boundaries, and `pixelsPerGuiXY=[sx,sy]` describes the actual rendering projection.
  PNG coordinates are `[guiX*sx-crop[0], guiY*sy-crop[1]]`. Do not derive scaling from the rounded
  GUI width. `gui click --png` performs the inverse for you and requires a preceding screenshot
  in this client process; no prior capture gives `reason:"NO_SCREENSHOT"`.
* Clicks use normal Screen events / server-authoritative slot packets. In continuous mode the
  default cursor warp needs focus for hover/carried-item visuals. Lightweight modes instead use
  a virtual GUI cursor, without warping the desktop pointer or requiring focus.
  `--no-cursor-move` disables cursor updates. Trust server state, not the pixels alone.
* Ordinary requests have a **30-second deadline**, over both transports. Use
  `mcdebug --request-timeout 10 gui status` for a short probe, or `--request-timeout 0` to disable
  it. Screenshots additionally have a client-side 30-second cap including passive delay and
  encoding; disabling the CLI deadline does not disable that cap. `wait.until` defaults to its
  server-side tick timeout without an extra wall-clock deadline;
  an explicit option overrides this. `--timeout` controls connection/handshake milliseconds.
  A request timeout closes the local connection, **not** a promise of remote cancellation/rollback.
* Version warnings use the `server.ready` handshake, without an extra status RPC. Endpoints must
  speak the current handshake protocol; there is no old-server fallback.
* `gui.connect` expects the client to be outside a world; use `gui disconnect` to save/leave first.
  Loading overlays and in-progress ConnectScreen cancellation are deliberately rejected by disconnect.
  Its wait is passive; a timeout does not undo a stop/save already requested.
* Java/Kotlin changes require a client restart; only textures/lang/models support
  `gui reload-resources`. Check `gui.status.jarPath` when deployment looks stale.

### Screen input and golden assertions

Focus a widget with `gui click` first. Key taps and text target only the current Screen, not system
keys or global movement/debug bindings. Ctrl/Shift/Alt/Super are logical event-scoped modifiers;
physical keyboard state is not changed. `--clear` sends select-all/backspace, not EditBox.setValue.
Input reports distinguish dispatched/handled events from actual inserted text: use `gui screen`
widget values or authoritative server data to verify state.

```bash
mcdebug --session demo gui key --key A --ctrl
mcdebug --session demo gui type --text '机器 GUI 😀' --clear
mcdebug --session demo gui key --key ENTER
mcdebug --session demo gui drag --from 100,80 --to 220,80 --steps 8 --step-ticks 1
mcdebug --session demo gui disconnect --wait-ticks 200
pnpm dlx @yu1745/mcdebug --session demo raw gui.key '{"key":"A","modifiers":2}'
pnpm dlx @yu1745/mcdebug --session demo raw gui.type '{"text":"机器 GUI","clear":true}'
pnpm dlx @yu1745/mcdebug --session demo raw gui.drag '{"from":[100,80],"to":[220,80],"steps":8,"stepTicks":1}'
pnpm dlx @yu1745/mcdebug --session demo raw gui.disconnect '{"waitTicks":200}'
```

Only one input operation is active at once. Drag has a 30-second total cap, 1..128 segments,
0..20 passive ticks per segment and <=400 total ticks. Screen/size changes abort remaining steps
and release the original target; `ok:false`/screenChanged may mean a key successfully navigated to
another Screen, not that it had no effect. Errors/cancellation do not roll back partial edits.
Text is limited to valid Unicode <=4096 UTF-16 units; newline/control keys use `gui key`.

Local golden comparisons never update a baseline, align or resize images:

```bash
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --diff artifacts/diff.png
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --channel-tolerance 2 --max-different-ratio 0.001
```

Mismatch exits **1** with JSON pixel counts/ratio/max channel delta/bounds; dimensions must match.
Default is exact RGBA including alpha and hidden RGB; tolerance and allowed different-pixel ratio
are explicit. Diff output cannot overwrite either input or their aliases. The bounded pure-Java PNG
codec supports non-interlaced RGB8/RGBA8 without AWT/ImageIO; palette/grayscale/16-bit/interlaced PNG
and tRNS are rejected explicitly. Fix GUI scale, dimensions, render mode, pointer/tooltips/animation
and target GL/font environment yourself; comparison does not promise cross-GPU deterministic pixels.

SDK/JUnit consumers can use a local compare, or request a new **inline** PNG and assert remotely:

```kotlin
ScreenshotComparison.compare(expected, actual, diff = diff).requireMatch()
api.gui.assertScreenshot(expected, diff = diff, mode = "gui", channelTolerance = 0)
```

`expected`, `actual`, `diff` are `Path`s. SDK assertion throws `AssertionError` on mismatch and protects
the baseline; it does not try to read a remote screenshot filesystem path. No new test DSL/plugin.

### Lightweight runtime (Fabric 1.21.1)

Keep the real MC client and its mod/resource/network lifecycle; do not fork MC. Put this in the
client's `<gameDir>/config/mcdebug.json`, then restart:

```json
{"clientRenderMode":"demand","clientLoopFps":20,"clientIdleFps":1}
```

| mode | rendering policy |
|---|---|
| `continuous` (default) | Normal continuous rendering; unchanged for regular player clients. |
| `demand` | Idle at 1 rendered frame/s; screenshots trigger a full frame, including the world background. |
| `gui-only` | Same scheduling, but omit world/HUD rendering. Real Screen rendering stays; world previews/backgrounds can differ. Opt-in only. |

`clientLoopFps` is 20..260 (the loop also services natural client ticks and RPC tasks).
`clientIdleFps` is 0..loopFps; 0 means render only on capture requests/screen changes. Throttling
applies only in a loaded world with no screen or a container screen. Startup, overlays/resource
reloads and other screens keep normal rendering so their completion callbacks cannot starve.
Lite mode prevents automatic lost-focus pausing, but explicit pause screens still follow MC rules.

Priority: `-Dmcdebug.clientRenderMode` / `MCDEBUG_CLIENT_RENDER_MODE` / JSON / default;
likewise `clientLoopFps` → `MCDEBUG_CLIENT_LOOP_FPS`, `clientIdleFps` → `MCDEBUG_CLIENT_IDLE_FPS`.
For example (use the consumer project's own task/save):

```bash
MCDEBUG_CLIENT_RENDER_MODE=demand MCDEBUG_CLIENT_IDLE_FPS=0 DISPLAY=:0 \
  ./gradlew runClient --args='--width 854 --height 480 --quickPlaySingleplayer "New World"'
pnpm dlx @yu1745/mcdebug raw gui.status '{}'
pnpm dlx @yu1745/mcdebug raw gui.screenshot '{"mode":"gui","delayTicks":0}'
```

`gui.status.render` reports policy, rendered/skipped counts and pending captures. This reduces
**steady-state drawing**, not the required GL context, initial resource/model loading or necessarily
resident memory. Some mods update state in render hooks: validate demand mode with your target mod,
or use continuous mode for those screens. See [design and verification](docs/plan-lightweight-client.md).

## JUnit 5 runner

`com.mcdebug.runner` (inside `mcdebug-cli`) integrates mcdebug with JUnit 5: annotate a class with
`@McDebugTest`, inject a `TestContext` (grid-allocated `origin`, per-test area cleanup, forceload,
method-level parallelism), and use the helpers that wrap `DebugApi`:

| group | helpers |
|---|---|
| placement / world | `place`, `placeAsPlayer`, `setBlocks`, `getBlockId`, `getBlockProp`, `assertBlockId`, `assertBlockNotId` |
| block entities | `getBeField`, `getBeNumber`, `setBeField` |
| inventory | `getSlot`, `getSlotItem`, `getSlotCount`, `setSlot`, `insertItem`, `assertSlotHas`, `assertSlotEmpty`, `assertSlotCount` |
| waiting / tracing | `waitUntil(ctx, predicate, timeoutTicks)`, `waitTicks`, `withTrace`, `traceBoxAround` |
| predicate builders | `blockId`, `blockProp`, `beField*`, `invItem*`, `invCount*`, `tickEquals`, `tickGreaterOrEqual` (→ the server predicate DSL string) |

```kotlin
@McDebugTest
class MaceratorTest {
    @Test fun grindsOre(ctx: TestContext) {
        place(ctx, ctx.origin, "<mod>:macerator")
        insertItem(ctx, ctx.origin, "minecraft:iron_ore", 1, 0)
        ctx.api.be.tick(ctx.origin, 20 * 20)     // drive the BE ticker; no 20 s of wall clock
        waitUntil(ctx, invItem(ctx.origin, 3, "minecraft:raw_iron"), 25 * 20)
        assertSlotHas(ctx, ctx.origin, 3, "minecraft:raw_iron")
    }
}
```

Or drive the raw SDK directly (same shape as the CLI; add `mcdebug-cli` from JitPack / mavenLocal
to the test classpath):

```kotlin
val api = DebugApi(RpcClient(RpcClientOptions()))
val before = api.snapshot.capture(mapOf(
    "box" to mapOf("from" to listOf(0, 64, 0), "to" to listOf(0, 64, 0)),
    "include" to listOf("block", "inventory", "energy"),
))
api.redstone.pulse(listOf(0, 65, 0), 4)
```

There is deliberately no `gui.*` binding in the runner: pixels need a real client, so GUI checks
belong in an agent/CLI loop, not in the headless JUnit grid.

## Forge 1.20.1 port

`forge/` is a native Forge port of the same server plane (same methods, parameters, result shapes
and error codes; the CLI works unchanged). Independent Gradle 8.1.1 + ForgeGradle 6 build, wired
into the root build as `:forge`:

```bash
./gradlew :forge:build        # -> forge/build/libs/mcdebug-forge-<version>.jar
./build.sh                    # dist/ gets the Fabric jars AND the Forge jar
scripts/forge-smoke.sh        # dev GameTest server + CLI assertions + contract parity (no EULA)
```

Differences: storage handles map to Forge capabilities (`fabric:item`=`ITEM_HANDLER`,
`fabric:fluid`=`FLUID_HANDLER`, `teamreborn:energy`=`ENERGY`/FE — ids kept for CLI compatibility),
**fluid amounts are mB** (1000/bucket) instead of Fabric droplets, the fake player is Forge's
`FakePlayer`, `reflect.*` uses Mojang class names, and there is **no `gui.*` client plane** (Forge
client agent not implemented). See AGENTS.md §13.

## Native-image distribution

JVM cold start is ~790 ms; the GraalVM native binary starts in **~11 ms** with no runtime
dependencies — the recommended way to call mcdebug from scripts on a server.

```bash
./build.sh                                                # -> dist/mcdebug-cli.jar
GRAALVM_HOME=/path/to/graalvm ./scripts/build-native.sh    # -> dist/mdb (~42 MB)
./dist/mdb --socket <sock> status                          # local unix socket
./dist/mdb --tcp <host[:port]> status                      # cross-machine TCP
```

Server distribution is described in the server repo's `registry/scripts.yaml`. The npm shell
(`bin/mcdebug.js` / `pnpm dlx`) still works for local use but is deprecated for distribution — the
native binary is too large for npm, and servers do not need pnpm.

## License

MIT.
