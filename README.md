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

* `gui.screenshot --mode gui` crops the rectangle reported by `gui.screen.gui`
  (`left/top/imageWidth/imageHeight`) and returns `transform.cropInGui`; both always agree.
* PNG pixel coordinates need a `gui.screenshot` **in the same client process** first, otherwise
  you get `-32602` with `reason:"NO_SCREENSHOT"`.
* Clicks dispatch ordinary `Screen` events — no GLFW synthesis, no window focus. The real cursor
  is warped to the click point by default (`--no-cursor-move` disables) because the carried stack,
  tooltips and hover highlight are drawn at the physical cursor; the OS pointer itself never
  appears in a screenshot.
* Every click report carries the landing point (`guiX`/`guiY` in GUI units; slot clicks report the
  slot centre), so slot ↔ GUI ↔ PNG coordinates are interchangeable with the screenshot `transform`.
* Cursor-dependent rendering (floating carried stack, tooltips, hover highlight) only follows the
  warp while the game window is focused — check `gui.status.windowFocused`. Game state is
  authoritative regardless: read `gui.screen`, the click report (`slotAfter`/`carried`) or the
  server plane, never the pixels alone.
* While the client main thread is busy (world load, `gui reload-resources`) `gui.*` calls block
  until it frees up — wrap polling probes in `timeout 15 java -jar mcdebug-cli.jar …`.
* In-world menus cannot be opened without key input (`gui.key` is future work), so `gui.connect`
  expects the client to be outside a world.
* Blank/stale PNG? The window must be visible and rendering (`gui.status.fps`), the client must be
  the jar you think it is (`gui.status.jarPath`), and Java/Kotlin changes need a client restart —
  only textures/lang/models can use `gui reload-resources`.

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
