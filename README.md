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
./gradlew build              # mod + contract + self-contained jar check
./gradlew :cli:copyCliJar    # CLI fat jar -> ./mcdebug-cli.jar (repo root, not in git)

java -jar mcdebug-cli.jar --help
```

For coding agents there is a Claude Code skill in `skills/mcdebug/` (workflow and pitfalls; the
CLI's own `--help` stays the command reference): `ln -s "$PWD/skills/mcdebug" ~/.claude/skills/mcdebug`.

Below, `mcdebug` stands for `java -jar mcdebug-cli.jar` or the native binary (`mdb`, see
[Native-image distribution](#native-image-distribution)). Tagged releases attach
`mcdebug-cli.jar` and the mod jar to the GitHub Release; the CLI also carries the mod jar of its
own build (`mcdebug jar`).

Fabric project setup itself: <https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up>.

## Deploy

The build product is a **normal mod jar** — copy it into the target environment's `mods/`
and restart. Server and client use the *same* jar (`environment: "*"`, both entrypoints);
the consumer project needs **zero changes** (no dependency, no plugin, no extra script).
Remove the old jar first — two mcdebug jars in one `mods/` fail to load. The CLI carries the
mod jar of its own build, so this does both (same version as the CLI, no download):

```bash
mcdebug jar --output path/to/consumer/run/mods   # writes mcdebug-<version>.jar, removes older mcdebug-*.jar
```

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
mcdebug status                                   # server.status (unix socket)
mcdebug raw world.getBlock '{"pos":[0,64,0]}'
mcdebug --tcp 192.168.5.102:25580 status          # cross-machine
mcdebug -c 'status' -c 'world get-block --x 0 --y 64 --z 0' --batch   # one connection, fail fast
mcdebug snapshot capture --from 0,64,0 --to 2,66,2 --include block,inventory
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
| `be.*` | block-entity NBT and fields; `be.tick` drives one BE ticker N times (0..12000 per call) |
| `inv.*` | slot-level access to a vanilla `Container` (`getSlot`/`setSlot`); for moving items prefer `storage.*` |
| `storage.*` | unified item/fluid/energy access for blocks, entities, item stacks |
| `fluid.*` / `craft.*` / `scan.*` / `entity.*` / `fixture.*` | per-tank fluids (generic form: `storage.*` with `fabric:fluid`), recipes, searches, entities, region fixtures |
| `snapshot.*` / `trace.*` | capture + diff state; record frames on natural ticks |
| `screen.*` | open/drive server-side menus (`AbstractContainerMenu`) with a fake player (**slot JSON, no pixels**) |
| `gui.*` | **client plane**: real GUI screenshots + Screen clicks (see below) |
| `redstone.*` | read power, set/pulse levers, neighbor updates |
| `wait.until` | wait for a predicate on natural server ticks (never drives ticks) |
| `reflect.*` | resolve/get/call/new any class at runtime; Mojang or intermediary names work in dev and production (table generated for this Minecraft version) |

`storage.*` resolves handles in order: vanilla `Inventory` → Fabric `ItemStorage` → Fabric
`FluidStorage` → Team Reborn `EnergyStorage`; targets are `{kind:"block"|"entity"|"item", ...}`
and results carry ids like `vanilla:inventory`, `fabric:item`, `fabric:fluid`,
`teamreborn:energy`. Prefer these generic APIs (and `be.tick` for machine progress) over
mod-specific NBT pokes.

The server never exposes `tick.run`/`tick.runUntil`: `trace.*` and `wait.until` observe natural
ticks only (`be.tick` is the one bounded exception: it runs a single block entity's ticker).

Params: a box is `{"from":[x,y,z],"to":[x,y,z]}` (corners in any order, both inclusive; for entity
queries the whole blocks). A missing or mistyped param is `-32602` naming the param; an unexpected
server exception is `-32603` with `data.exception` and `data.at` (top stack frames).
`snapshot.diff` leaves out the always-changing `/tick` and `/time` unless `"ignoreMeta":false`:

```bash
mcdebug raw snapshot.diff '{"before":{"tick":1,"blocks":[]},"after":{"tick":9,"blocks":[]}}'   # equal:true
mcdebug raw scan.findEntities '{"box":{"from":[0,64,0],"to":[0,64,0]}}'   # entities in that one block
mcdebug raw reflect.resolve '{"class":"BlockEntity"}'   # runtimeClass + mojangClass
``` The predicate DSL (`be[x,y,z].<path> <op> <value>`, `AND/OR/NOT`, `sum(...)`,
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
mcdebug --session demo raw gui.stop '{"instanceId":"INSTANCE_FROM_GUI_STATUS_PROCESS","sessionToken":"TOKEN_FROM_GUI_STATUS_PROCESS"}'
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
| `gui.screen` | the open screen: container slots in GUI units, hovered slot + its tooltip, widgets (nested ones too) with index/text/x/y/size/value/focus/tooltip |
| `gui.screenshot` | real PNG — `--mode window` (framebuffer) or `--mode gui` (just the container GUI) + a transform mapping PNG pixels back to GUI units; optional `annotate` (numbered slots/widgets), `drawLog` (the frame's draw calls), `format:"none"` (pixel hash only) |
| `gui.click` | click by container slot, **widget index**, widget text (falls back to text the frame *drew*), GUI coordinates, or **PNG pixels from the previous screenshot**; returns once the GUI has settled |
| `gui.hover` | move the cursor onto a target, render one frame, return the **tooltips that frame drew** (custom `renderBg` tooltips too), hovered slot/widget |
| `gui.scroll` | mouse wheel (`Screen.mouseScrolled`) at a target, then settle |
| `gui.openScreen` | open `inventory`, `pkg.Class` or `pkg.Class#staticFactory`: a Screen fixture without a block |
| `gui.act` | one round trip: click/hover/scroll/key/type/drag/close/openBlock/openScreen/reinit/reloadScreen + settle + optional `gui.screen` + screenshot + problems logged meanwhile |
| `gui.settle` | wait until the server answered everything sent so far and the GUI stayed quiet (passive) |
| `gui.problems` | WARN/ERROR log entries of the client JVM and caught Screen render faults, paged by cursor |
| `gui.hotswap` / `gui.reinit` | redefine changed classes from compiled output dirs (all or nothing) / re-run the open Screen's `init()` |
| `gui.reloadScreen` | rebuild the open Screen from compiled output in a fresh class loader: **any** change to its class, same container menu |
| `gui.openBlock` | walk the client's own use-on-block path so a machine GUI opens |
| `gui.close` / `gui.connect` / `gui.reloadResources` | close the screen (Esc) / join `host[:port]` / client-side F3+T |
| `gui.key` / `gui.type` | one current-Screen key tap / Unicode char events, with scoped logical modifiers |
| `gui.drag` | bounded press → mouseDragged steps → release on the same Screen, with passive natural-tick delays |
| `gui.disconnect` | save/leave the world and return to title without stopping MC or client RPC |
| CLI-only `gui compare` | local PNG golden regression comparison; no RPC/client required |

```bash
# The agent loop: set the machine up on the server plane, look at it on the client plane.
mcdebug raw world.setBlock '{"pos":[100,64,100],"block":"minecraft:chest"}'
mcdebug raw inv.setSlot   '{"pos":[100,64,100],"slot":0,"item":"minecraft:diamond","count":5}'
mcdebug gui open-block --x 100 --y 64 --z 100
mcdebug gui screenshot --mode gui --annotate         # only the machine GUI, plus *.annotated.png with numbered slots/widgets
mcdebug gui click --slot 0 --shot gui                # server-authoritative slot click, settle, new screenshot: one round trip
mcdebug gui click --widget 2                         # widget index from gui screen / the annotated PNG
mcdebug gui click --png 48,78                        # click by PNG pixel of the last screenshot
mcdebug gui hover --slot 0                           # tooltips really drawn, hovered slot/widget
mcdebug gui screenshot --mode gui --hide-cursor      # golden without hover/tooltip/carried stack
mcdebug gui problems --brief                         # WARN/ERROR lines and render faults since the client started
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
* Java/Kotlin method-body changes can be hot-swapped (`gui hotswap`, below); structural changes
  (new fields/methods/lambdas) in the open Screen's own class use `gui reload-screen`; elsewhere
  they need a restart unless the client runs on a JetBrains Runtime with
  `-XX:+AllowEnhancedClassRedefinition`. Textures/lang/models use `gui reload-resources`.
  Check `gui.status.jarPath` when deployment looks stale.

### Agent iteration loop

The pieces an agent needs to change GUI code and see the result without a human, or a restart:

* **Settle instead of sleeping.** `gui.click` used to wait a fixed 20 ticks (1 s). It now sends a
  server barrier (a command-suggestion round trip, which the server handles on its main thread after
  every earlier packet), then waits until slots, carried stack, Screen and widgets stay unchanged
  for 2 ticks. `waitTicks` is only the upper bound; `settle:false` / `--no-settle` restores the
  fixed wait. Container data slots (progress, energy) are excluded, so a running machine still
  settles. `gui settle` is the same wait on its own, e.g. after `be.setNbt`.
* **One round trip per step.** `gui act` (or `--shot gui|window`, `--screen`, `--annotate`,
  `--draw-log` on `click`/`key`/`type`/`drag`) returns the action result, settle report,
  screenshot, screen JSON and the problems logged during the call. Action errors are returned as
  `error` together with the views, so the agent still sees what happened.
* **Pictures a model can act on.** `--annotate` writes `<name>.annotated.png` next to the clean PNG:
  cyan numbers are slot indices (`--slot N`), magenta numbers widget indices (`--widget N`); the
  legend with PNG rectangles is in `annotations`. The clean PNG is never modified.
* **Draw log.** `--draw-log` returns every blit (texture, normalised UV, texel UV and texture size
  for `blit`, sprite name for `blitSprite`), fill, string, item and tooltip of the captured frame,
  with its on-screen rectangle in GUI units. Progress arrows and energy bars drawn in `renderBg` are
  not widgets; this is how an agent checks them as data. At most 5000 entries per frame.
* **Cheap change detection.** `--hash-only` (`format:"none"`) skips PNG encoding and file writes and
  returns `pixelHash` (SHA-256 of the RGBA pixels). Every capture reads back only its crop rectangle.
* **Render faults don't kill the client.** If the open Screen's `render` throws, the error is logged
  at ERROR, shown in `gui problems` / `gui.status.screenGuard.fault` / `act.screenFault`, and only that
  Screen stops drawing. Fix, hotswap, `gui reinit`. `clientScreenGuard=false` restores vanilla's crash.
* **Hotswap.** `./gradlew classes`, then `mcdebug gui hotswap --classes build/classes/kotlin/client`.
  The first call attaches a one-class JDK agent to the local client (same user); classes changed since
  the last swap go through Fabric's and Mixin's transformers and are redefined, then the open Screen
  is re-initialised (`--no-reinit` to skip). It is all or nothing: schema changes on stock HotSpot are
  listed per class (`added method init$lambda$0(...)`, `added field ...`) before anything is applied,
  exit 2, and the client keeps the old code — a partial swap would let new code call missing members.
* **Reload the Screen class itself.** `mcdebug gui reload-screen --classes build/classes/kotlin/client`
  loads the open Screen's class (and nested classes; `--also PREFIX` for more) from the output dirs
  into a fresh child-first class loader and swaps a new instance in. Any change works — new buttons,
  fields, lambdas. A container screen is rebuilt around the **same menu** without closing it, so the
  server container, slots and carried stack survive; a plain screen gets the old one's parent. Limits:
  the new instance starts with fresh fields; other classes stay as loaded; Java package-private access
  into the rest of the package fails; reopening the GUI the normal way uses the originally loaded class.
* **Screen fixtures.** `gui open-screen inventory | pkg.Class | pkg.Class#factory` opens any client
  Screen. Constructors/factories may take `Screen` (parent), `Player`, `Minecraft`, `Options`; put a
  dev-only factory that fills a Screen with test data in your mod's dev source set (Kotlin: `@JvmStatic`).
  Container screens need a server menu: use `gui open-block`, or a factory built on `player.inventoryMenu`.
* **Hover, scroll, drawn text.** `gui hover` returns the tooltips the next frame actually drew (energy
  bars, tank levels included); `gui scroll --dy N` scrolls lists. `--text` matches widget labels first
  and otherwise the strings the frame drew, so hand-drawn buttons and list entries are reachable
  (`mode:"drawnText"`).
* **Deterministic goldens.** `--hide-cursor` renders the captured frame with the cursor off-screen (no
  hover highlight, tooltip or carried stack); the following frame is normal again. Entity previews
  (the inventory player) still animate: mask them with `gui compare --ignore`.
* **Lite modes answer immediately.** In `demand`/`gui-only` the throttled frame limiter is woken by an
  incoming RPC, so a hop no longer waits out the rest of a 1/loopFps sleep.

```bash
mcdebug --session demo raw gui.act '{"action":"click","params":{"slot":0},"screen":true,"screenshot":{"mode":"gui","annotate":true}}'
mcdebug --session demo raw gui.settle '{"maxTicks":40,"quietTicks":2}'
mcdebug --session demo raw gui.screenshot '{"mode":"gui","drawLog":true,"format":"none"}'
mcdebug --session demo raw gui.click '{"widget":2}'
mcdebug --session demo raw gui.problems '{"since":1,"level":"error","limit":50}'
mcdebug --session demo raw gui.reinit '{}'
mcdebug --session demo gui hotswap --classes build/classes/kotlin/client   # raw gui.hotswap needs the agent attached first
mcdebug --session demo raw gui.reloadScreen '{"dirs":["build/classes/kotlin/client"]}'
mcdebug --session demo raw gui.hover '{"slot":0}'
mcdebug --session demo raw gui.scroll '{"gui":[100,80],"dy":-3}'
mcdebug --session demo raw gui.openScreen '{"screen":"com.example.dev.Fixtures#machineScreen"}'
mcdebug --session demo raw gui.screenshot '{"mode":"gui","cursor":"hide"}'
```

Measured on one machine (llvmpipe software GL, 854×480, `demand` mode, same world; RPC over the Unix
socket without CLI startup; previous jar vs this one):

| | previous | now |
|---|---:|---:|
| one-hop call in world view (`gui.status`, p50) | 37.1 ms | 0.8 ms |
| `gui.screen` with a chest open (p50) | 37.0 ms | 0.7 ms |
| `gui.screenshot --mode gui` (p50 / p95) | 37.0 / 48.4 ms | 21.5 / 24.0 ms |
| `gui.click --slot` (p50) | 988 ms | 102 ms |
| code change visible (Screen method body) | client restart (≈25–30 s) | `classes` 9 s + `gui hotswap` 1.2 s |
| code change visible (new field/method/lambda in the Screen) | client restart (≈25–30 s) | `classes` + `gui reload-screen`, no restart |

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
mcdebug --session demo raw gui.key '{"key":"A","modifiers":2}'
mcdebug --session demo raw gui.type '{"text":"机器 GUI","clear":true}'
mcdebug --session demo raw gui.drag '{"from":[100,80],"to":[220,80],"steps":8,"stepTicks":1}'
mcdebug --session demo raw gui.disconnect '{"waitTicks":200}'
```

Only one input operation is active at once. Drag has a 30-second total cap, 1..128 segments,
0..20 passive ticks per segment and <=400 total ticks. Screen/size changes abort remaining steps
and release the original target; `ok:false`/screenChanged may mean a key successfully navigated to
another Screen, not that it had no effect. Errors/cancellation do not roll back partial edits.
Text is limited to valid Unicode <=4096 UTF-16 units; newline/control keys use `gui key`.

Local golden comparisons never update a baseline implicitly, align or resize images:

```bash
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --diff artifacts/diff.png
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --channel-tolerance 2 --max-different-ratio 0.001
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --ignore 0,333,352,3 --report artifacts/review.html
mcdebug gui compare --expected golden/chest.png --actual artifacts/chest.png --accept   # explicit: replace (or create) the golden
```

`--ignore x,y,w,h` (repeatable) masks PNG rectangles such as the cursor, an animated arrow or a
fractional crop edge; the ratio is computed over compared pixels and the diff shows masks in blue.
`--report` writes a self-contained HTML page with expected/actual/diff for review. Only `--accept`
writes the baseline, and then exits 0.

Mismatch exits **1** with JSON pixel counts/ratio/max channel delta/bounds; dimensions must match.
Default is exact RGBA including alpha and hidden RGB; tolerance and allowed different-pixel ratio
are explicit. Diff output cannot overwrite either input or their aliases. The bounded pure-Java PNG
codec supports non-interlaced RGB8/RGBA8 without AWT/ImageIO; palette/grayscale/16-bit/interlaced PNG
and tRNS are rejected explicitly. Fix GUI scale, dimensions, render mode, pointer/tooltips/animation
and target GL/font environment yourself; comparison does not promise cross-GPU deterministic pixels.

SDK/JUnit consumers can use a local compare, or request a new **inline** PNG and assert remotely:

```kotlin
ScreenshotComparison.compare(expected, actual, diff = diff).requireMatch()
api.gui.assertScreenshot(expected, diff = diff, mode = "gui", channelTolerance = 0,
    ignore = listOf(ScreenshotRegion(0, 333, 352, 3)), hideCursor = true)
api.gui.act("click", mapOf("slot" to 0), screenshot = ScreenshotOptions(mode = "gui", annotate = true))
api.gui.hover(mapOf("slot" to 0))           // tooltips drawn in that frame
api.gui.openScreen("com.example.dev.Fixtures#machineScreen")
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
mcdebug raw gui.status '{}'
mcdebug raw gui.screenshot '{"mode":"gui","delayTicks":0}'
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

## Native-image distribution

JVM cold start is ~790 ms; the GraalVM native binary starts in **~11 ms** with no runtime
dependencies — the recommended way to call mcdebug from scripts on a server.

```bash
./build.sh                                                # -> dist/mcdebug-cli.jar
GRAALVM_HOME=/path/to/graalvm ./scripts/build-native.sh    # -> dist/mdb (~42 MB)
./dist/mdb --socket <sock> status                          # local unix socket
./dist/mdb --tcp <host[:port]> status                      # cross-machine TCP
```

Server distribution is described in the server repo's `registry/scripts.yaml`.

## License

MIT.
