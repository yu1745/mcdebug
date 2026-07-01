# mcdebug — Minecraft Debug Server Mod

Fabric 1.20.1 + Kotlin mod that exposes a localhost JSON-RPC server, plus a
TypeScript CLI / test runner, so mod developers can automate tests of their
machine blocks by reading/writing the world, block entities, inventories,
resource storages, snapshots, traces, and screen handlers from an external TS
process.

```
┌─────────────────┐  JSON-RPC 2.0   ┌──────────────────┐
│  mcdebug (TS)   │  NDJSON over    │  DebugServerMod  │
│  CLI / runner   │  TCP 127.0.0.1  │  (Kotlin/Fabric) │
└─────────────────┘  default 25580  └──────────────────┘
```

See **CLAUDE.md** for the full contributor guide (architecture, naming, adding
new RPC methods, version bumps). This README covers consumer-facing usage.

## Setup

```bash
# mod (Kotlin side)
./gradlew.bat build

# client (TS CLI + test runner)
cd mcdebug-client
pnpm install
pnpm build            # tsc → dist/
node dist/cli.js --help
```

For setup of the Fabric project itself, see the
[Fabric Documentation](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up).

## CLI and npx usage

The CLI talks to a running mcdebug Fabric server over localhost JSON-RPC. It
finds the port from `--port`, `MCDEBUG_PORT`, `mcdebug/port`, then falls back to
`25580`.

```bash
# local checkout
node mcdebug-client/dist/cli.js status
node mcdebug-client/dist/cli.js raw world.getBlock '{"pos":[0,64,0]}'

# npx, once the package version is published
npx -y @yu1745/mcdebug status
npx -y @yu1745/mcdebug storage list --x 0 --y 64 --z 0

# npx directly from GitHub, useful before an npm release
npx -y github:yu1745/mcdebug status
npx -y github:yu1745/mcdebug snapshot capture --from 0,64,0 --to 2,66,2 --include block,inventory
```

Most machine testing APIs have dedicated top-level CLI commands. Use `mcdebug
raw <namespace.method> <json>` for low-level or rarely used RPCs.

| namespace | purpose |
|---|---|
| `storage.*` | unified item/fluid/energy access for blocks, entities, and item stacks |
| `snapshot.*` | capture and structurally diff world/resource/entity state |
| `trace.*` | capture snapshots on natural server ticks for failure diagnosis |
| `screen.*` | open and drive real server `ScreenHandler`s with a fake player |

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
```

The server still never exposes `tick.run` or `tick.runUntil`. `trace.*` and
`wait.until` observe natural Minecraft ticks from the real Fabric server.

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

TypeScript usage:

```ts
import { DebugApi, RpcClient } from "@yu1745/mcdebug";

const api = new DebugApi(new RpcClient());
const target = { kind: "block", pos: [0, 64, 0] as const };

const handles = await api.storage.list(target, { side: "north" });
const before = await api.snapshot.capture({
  box: { from: [0, 64, 0], to: [0, 64, 0] },
  include: ["block", "inventory", "energy"],
});
```

## Test runner helpers

The test runner (`mcdebug-client/src/test-runner.ts`) exports helpers that wrap
the raw `DebugApi` for the common test patterns. Each test gets a `TestContext`
with an isolated `origin` and cleared area. Import them in your `*.test.ts`:

```ts
import { defineTest, defineTests, place, setBlocks, waitUntil,
         beFieldGreaterThan, invItemEquals, assertBlockId } from "@yu1745/mcdebug";
```

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
