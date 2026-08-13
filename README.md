# mcdebug — Minecraft Debug Server Mod

Fabric 1.20.1 + Kotlin mod that exposes a JSON-RPC server, plus a
Kotlin CLI / SDK and a JUnit 5 test runner, so mod developers can automate
tests of their machine blocks by reading/writing the world, block entities,
inventories, resource storages, snapshots, traces, screen handlers,
redstone controls, entities, and reusable fixtures from an external JVM
process (or from a JUnit test suite).

```
┌─────────────────┐  JSON-RPC 2.0   ┌──────────────────┐
│  mcdebug (Kotlin│  NDJSON over    │  DebugServerMod  │
│  CLI / runner)  │  unix socket    │  (Kotlin/Fabric) │
│                 │  <gameDir>/     │      ▲           │
│                 │   mcdebug/socket│      │ TCP       │
│                 ├─────────────── ►│  25580 (cross-  │
│                 │  NDJSON over    │  machine access)│
│                 │  TCP host:25580 │                  │
└─────────────────┘                 └──────────────────┘
```

Two transports, one RPC dispatcher (0.5.1+): the **unix socket** is the
primary channel (local, unreachable from the network) and **TCP** is the
auxiliary channel for cross-machine access. Either listener may fail to bind
(e.g. TCP port occupied) and the server still starts on the other one; only
if both fail does startup error out.

The CLI is a Kotlin fat jar (`mcdebug-cli.jar`, committed to the repo root).
The npm package at the repo root is a one-line shell that execs
`java -jar mcdebug-cli.jar`, so `pnpm dlx @yu1745/mcdebug` keeps working as
the zero-install entry point for scripts and humans.

See **CLAUDE.md** for the full contributor guide (architecture, naming, adding
new RPC methods, version bumps). This README covers consumer-facing usage.

## Setup

```bash
# build everything (mod + contract + CLI fat jar)
./gradlew build
./gradlew :cli:copyCliJar   # copies the fat jar to ./mcdebug-cli.jar (repo root)

# CLI usage (any of these)
node bin/mcdebug.js --help          # via the npm shell (JAVA_HOME or PATH java)
java -jar mcdebug-cli.jar --help    # directly

# install/run via pnpm dlx once published (pulls the jar inside the package)
pnpm dlx @yu1745/mcdebug status
```

For setup of the Fabric project itself, see the
[Fabric Documentation](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up).

## CLI and pnpm dlx usage

The CLI talks to a running mcdebug Fabric server over JSON-RPC. **Local
access** uses the unix domain socket (`<gameDir>/mcdebug/socket` by default);
**cross-machine access** uses TCP (`--tcp host:port`, default port 25580).

Unix socket discovery order: `--socket`, `MCDEBUG_SOCKET`, the `mcdebug/port`
discovery file (written by the server), in that order. TCP must be requested
explicitly (`--tcp` or `--host` + `--port`) — the CLI never falls back from one
transport to the other on its own.

```bash
# local checkout (unix socket)
node bin/mcdebug.js status
node bin/mcdebug.js raw world.getBlock '{"pos":[0,64,0]}'

# cross-machine (TCP): server must have its TCP listener bound (default port 25580)
node bin/mcdebug.js --tcp 192.168.5.102:25582 status
node bin/mcdebug.js --host 192.168.5.102 --port 25582 raw world.getBlock '{"pos":[0,64,0]}'

# pnpm dlx, once the package version is published (the jar is inside the package)
pnpm dlx @yu1745/mcdebug status
pnpm dlx @yu1745/mcdebug storage list --x 0 --y 64 --z 0
pnpm dlx @yu1745/mcdebug redstone get-power --x 0 --y 64 --z 0

# pnpm directly from the GitHub repo root, useful before a registry release
pnpm dlx 'github:yu1745/mcdebug#v0.5.0' status
pnpm dlx 'github:yu1745/mcdebug#v0.5.0' snapshot capture --from 0,64,0 --to 2,66,2 --include block,inventory
```

Most machine testing APIs have dedicated top-level CLI commands. Use `mcdebug
raw <namespace.method> <json>` for low-level or rarely used RPCs.

| namespace | purpose |
|---|---|
| `storage.*` | unified item/fluid/energy access for blocks, entities, and item stacks |
| `snapshot.*` | capture and structurally diff world/resource/entity state |
| `trace.*` | capture snapshots on natural server ticks for failure diagnosis |
| `screen.*` | open and drive real server `ScreenHandler`s with a fake player |
| `redstone.*` | read power, set/pulse vanilla levers, trigger neighbor updates |
| `entity.*` | spawn, inspect, teleport, remove, and collect item entities |
| `fixture.*` | capture and load block-region fixtures as JSON |
| `be.tick` | actively tick a block entity N times (same `BlockEntityTicker` path as natural ticks); turns 15s machine-test waits into milliseconds (neighbor/world ticks are NOT driven — use `wait.until` for natural-tick timing) |

Examples:

```bash
# List available storage adapters on a block.
mcdebug storage list --x 0 --y 64 --z 0 --side north

# Insert items through a storage handle.
mcdebug storage insert --x 0 --y 64 --z 0 --handle vanilla:inventory --item minecraft:coal --amount 8

# Read a water bucket as an item target and extract its Fabric fluid content.
mcdebug storage extract --target '{"kind":"item","stack":{"item":"minecraft:water_bucket","count":1}}' --handle fabric:fluid --fluid minecraft:water --amount 81000

# Start a trace, wait using normal server ticks, then stop it.
mcdebug trace start --from 0,64,0 --to 0,64,0 --include block,inventory,blockEntityNbt --interval-ticks 1
mcdebug wait-until --expr 'tick >= 200'
mcdebug trace stop --trace-id "<trace id from start>"

# Open a machine/chest/furnace screen and inspect its slots/properties.
mcdebug screen open-block --x 0 --y 64 --z 0
mcdebug screen set-player-slot --screen-id "<screen id>" --slot 0 --item minecraft:cobblestone --count 1
mcdebug screen quick-move --screen-id "<screen id>" --slot 34
mcdebug screen close --screen-id "<screen id>"

# Redstone and entity controls.
mcdebug redstone set-lever --x 0 --y 65 --z 0 --powered true
mcdebug redstone pulse --x 0 --y 65 --z 0 --ticks 4
mcdebug entity spawn --type minecraft:item --pos 0,65,0 --stack '{"item":"minecraft:diamond","count":3}'
mcdebug entity collect-items --from -2,63,-2 --to 2,67,2 --item minecraft:diamond

# Capture an area fixture and load it elsewhere.
mcdebug fixture capture --from 0,64,0 --to 2,66,2 > machine-fixture.json
mcdebug fixture load --fixture @machine-fixture.json --origin 10,64,10
```

The server still never exposes `tick.run` or `tick.runUntil`. `trace.*` and
`wait.until` observe natural Minecraft ticks from the real Fabric server.

## Transport configuration and discovery

Server side (in `config/mcdebug.json`, or JVM properties / env vars):

| channel | config key | JVM property | env var | default |
|---|---|---|---|---|
| unix socket path | `"socket"` | `-Dmcdebug.socket=<path>` | `MCDEBUG_SOCKET` | `<gameDir>/mcdebug/socket` |
| TCP enabled | `"tcpEnabled"` | `-Dmcdebug.tcpEnabled=<bool>` | `MCDEBUG_TCP_ENABLED` | `true` |
| TCP port | `"tcpPort"` | `-Dmcdebug.tcpPort=<port>` | `MCDEBUG_TCP_PORT` | `25580` (`0` = ephemeral) |

Example: `config/mcdebug.json` `{"socket": "mcdebug/alt.sock", "tcpPort": 25580}`.

Discovery files under `<gameDir>/mcdebug/` (best effort):

- `port` — the resolved **unix socket path** (filename kept from the old TCP
era for compatibility with existing consumers);
- `tcpPort` — the actually bound **TCP port number** (only written when the
TCP listener bound; e.g. the real port when `tcpPort=0`).

Fault tolerance: the two listeners bind independently. A single-side failure
(typically a TCP port conflict) logs a WARN and the server keeps running on
the other transport; startup only fails if **both** listeners fail.

### Version differences

| version | transport |
|---|---|
| 0.4.15 | pure TCP (container 25580) |
| 0.5.0 | pure unix socket |
| 0.5.1 | dual: unix socket (primary) + TCP 25580 (auxiliary) |

## Universal machine APIs

New tests should prefer the generic machine-level APIs over mod-specific NBT
when possible.

```ts
type Target =
  | { kind: "block"; pos: Pos; dim?: string }
  | { kind: "entity"; uuid: string; dim?: string }
  | { kind: "item"; stack: ItemStackJson };

type Side = "up" | "down" | "north" | "south" | "east" | "west" | null;
```

`storage.*` uses this adapter order:

1. Vanilla `Inventory`
2. Fabric Transfer `ItemStorage`
3. Fabric Transfer `FluidStorage`
4. Team Reborn `EnergyStorage`

Supported RPCs:

| RPC | summary |
|---|---|
| `storage.list(target, opts?)` | enumerate handles such as `vanilla:inventory`, `fabric:item`, `fabric:fluid`, `teamreborn:energy` |
| `storage.get(target, handle, opts?)` | read slots, tanks, or energy amount/capacity |
| `storage.insert/extract(...)` | move item/fluid/energy into or out of one target, with `simulate` support |
| `storage.transfer(...)` | transfer a resource between two targets |
| `snapshot.capture(...)` | capture `block`, `blockEntityNbt`, `inventory`, `fluid`, `energy`, and/or `entity` state |
| `snapshot.diff(before, after)` | structural JSON diff, intentionally business-logic agnostic |
| `trace.start/stop/get(...)` | record snapshot frames every N natural ticks |
| `screen.openBlock/snapshot/setPlayerSlot/clickSlot/quickMove/close(...)` | drive real server-side GUI handlers |
| `redstone.getPower/isPowered/setLever/pulse/notifyNeighbors(...)` | observe and drive vanilla redstone inputs without artificial ticks |
| `entity.spawn/getNbt/setNbt/teleport/remove/listItems/collectItems(...)` | control server entities and dropped item entities |
| `world.fillBox/clearBox(...)` | bulk edit loaded test regions with a safety block limit |
| `fixture.capture/load(...)` | serialize and restore block regions, including BE NBT |

Kotlin SDK usage (same API shape as the CLI; add `mcdebug-cli` to your
test classpath from JitPack / mavenLocal):

```kotlin
val api = DebugApi(RpcClient(RpcClientOptions()))
val handles = api.storage.list(mapOf("kind" to "block", "pos" to listOf(0, 64, 0)), side = "north")
val before = api.snapshot.capture(mapOf(
    "box" to mapOf("from" to listOf(0, 64, 0), "to" to listOf(0, 64, 0)),
    "include" to listOf("block", "inventory", "energy"),
))
api.redstone.pulse(listOf(0, 65, 0), 4)
val fixture = api.fixture.capture(mapOf("from" to listOf(0, 64, 0), "to" to listOf(2, 66, 2)))
```

## JUnit 5 test runner

The runner package (`com.mcdebug.runner`, inside `mcdebug-cli`) integrates
mcdebug with JUnit 5: annotate a test class with `@McDebugTest` and inject a
`TestContext` parameter (grid-allocated `origin`, per-test area cleanup,
forceload, method-level parallelism enabled by the bundled
`junit-platform.properties`). Helpers (`place`, `setBlocks`, `waitUntil`,
`assertBlockId`, predicate builders, `withTrace`, ...) wrap the raw `DebugApi`:

```kotlin
@McDebugTest
class MaceratorTest {
    @Test
    fun grindsOre(ctx: TestContext) {
        setupAdjacentBatbox(ctx, "ic2_120:macerator")
        insertItem(ctx, ctx.origin, "minecraft:iron_ore", 1, 0)
        ctx.api.be.tick(ctx.origin, 600)   // actively drive the machine — ms-level
        assertSlotHas(ctx, ctx.origin, 1, "ic2_120:crushed_iron")
    }
}
```

`be.tick` drives a block entity's ticker N times in one RPC (same
`BlockEntityTicker` path as natural ticks) — machine tests go from 15s
`wait.until` waits to milliseconds. Machines with per-tick energy budgets
(IC2's `TickLimitedSidedEnergyContainer`) need self-precharging:
`setBeField(ctx, pos, "EnergyStored", <capacity>)` bypasses the world-tick
input budget. Natural-tick timing tests still use `wait.until`.

### Generic storage / trace / screen helpers

| helper | description |
|---|---|
| `blockTarget(pos, dim?)` | build a block `Target` |
| `entityTarget(uuid, dim?)` | build an entity `Target` |
| `itemTarget(stack)` | build an item-stack `Target` |
| `expectStorageAmount(ctx, target, handle, amount, opts?)` | assert item/fluid/energy amount |
| `waitStorageAtLeast(ctx, target, handle, amount, opts?)` | poll storage via natural ticks until enough resource exists |
| `withTrace(ctx, options, fn)` | run `fn` and attach the stopped trace to failures |
| `openMachineScreen(ctx, pos, opts?)` | open a block screen with the fake player |
| `setScreenPlayerSlot(ctx, screenId, playerSlot, stack)` | seed fake-player inventory before `quickMove` / `clickSlot` |
| `snapshotDiff(ctx, before, after)` | call `snapshot.diff` |
| `traceBoxAround(pos, radius?)` | convenience box for trace/snapshot capture |

### Block read / assertions

| helper | description |
|---|---|
| `place(ctx, pos, block)` | set a block (raw setBlockState) |
| `setBlocks(ctx, ops[])` | set many blocks at once |
| `assertBlockId(ctx, pos, expected)` | assert block id equals `expected` (throws on mismatch) |
| `assertBlockNotId(ctx, pos, unexpected)` | assert block id is **not** `unexpected` (e.g. ore was mined away) |
| `getBlockId(ctx, pos)` | read the current block id (never throws — for conditional logic) |
| `getBlockProp(ctx, pos, name)` | read a block state property string, or null |

### Block-entity field read / write

| helper | description |
|---|---|
| `getBeField(ctx, pos, path)` | read a BE field as raw JSON (number/string/bool/object) |
| `getBeNumber(ctx, pos, path)` | read a BE field coerced to number (throws if non-numeric) |
| `setBeField(ctx, pos, path, value)` | write a BE field |

**Note** — IC2-style `SyncedData.int` fields are stored as two 16-bit NBT keys
`<Name>_High` / `<Name>_Low`. For a 0/1 flag like `Running`, read `Running_Low`.

### Inventory read / write / assertions

| helper | description |
|---|---|
| `insertItem(ctx, pos, item, count, slot)` | insert into a specific slot |
| `setSlot(ctx, pos, slot, item, count, nbt?)` | overwrite a slot |
| `getSlot(ctx, pos, slot)` | read a slot's ItemStackJson |
| `assertSlotHas(ctx, pos, slot, item)` | assert slot holds `item` |
| `assertSlotEmpty(ctx, pos, slot)` | assert slot is empty |
| `assertSlotCount(ctx, pos, slot, count)` | assert slot count equals `count` |

### `waitUntil` predicate builders

`waitUntil(ctx, predicate, timeoutTicks)` passively waits for a server-side
condition (evaluated each natural tick — it does NOT advance ticks). Build the
predicate string with these helpers instead of hand-writing the grammar:

**Block** — `blockId(pos, id)`, `blockNotId(pos, id)`, `blockProp(pos, name, value)`

**Block entity** (full op coverage): `beFieldEquals`, `beFieldNotEquals`,
`beFieldLessThan`, `beFieldLessOrEqual`, `beFieldGreaterThan`, `beFieldGreaterOrEqual`
— each `(pos, path, value)`.

**Inventory**: `invItem`, `invItemNot`, `invCountEquals`, `invCountLessThan`,
`invCountGreaterThan`, `invCountLessOrEqual`, `invCountGreaterOrEqual`
— each `(pos, slot, value)`.

**Tick**: `tickEquals(tick)`, `tickGreaterOrEqual(tick)`.

```ts
// Wait until an ore at origin.down() was mined away (block no longer iron_ore)
await waitUntil(ctx, blockNotId(ctx.origin.down(), "minecraft:iron_ore"), 20 * 20);
// Wait until slot 3 holds raw_iron, then assert it
await waitUntil(ctx, invItem(ctx.origin, 3, "minecraft:raw_iron"), 25 * 20);
await assertSlotHas(ctx, ctx.origin, 3, "minecraft:raw_iron");
```

The raw predicate grammar (for hand-written predicates) is documented in
`mcdebug --help` under `wait-until` and in CLAUDE.md §7.

## License

MIT.

## CLI 分发（native-image，推荐）

JVM 冷启动 ~790ms；GraalVM native-image 单文件产物启动 **~11ms**、零运行时依赖（无需 JDK/node/pnpm），适合在 server 上高频调用。

```bash
./build.sh                                  # 先构建（产出 dist/mcdebug-cli.jar）
GRAALVM_HOME=/path/to/graalvm ./scripts/build-native.sh   # 产物 dist/mcdebug-native（~42MB）
./dist/mcdebug-native --version             # 11ms
./dist/mcdebug-native --socket <sock> status          # 本机 unix socket 通道
./dist/mcdebug-native --tcp <host[:port]> status      # 跨机 TCP 通道（默认 25580）
```

> npm 壳（`bin/mcdebug.js` / pnpm dlx）已弃用：native 产物体积大不适合 npm 分发，且 server 上使用场景不需要 pnpm 一行式。
> 分发方式见 server 仓库 `registry/scripts.yaml` 的 mcdebug-cli 条目。
