# mcdebug — Minecraft Debug Server Mod

Fabric 1.20.1 + Kotlin mod that exposes a localhost JSON-RPC server, plus a
TypeScript CLI / test runner, so mod developers can automate tests of their
machine blocks by reading/writing the world, block entities, and inventories
from an external TS process.

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

## Test runner helpers

The test runner (`mcdebug-client/src/test-runner.ts`) exports helpers that wrap
the raw `DebugApi` for the common test patterns. Each test gets a `TestContext`
with an isolated `origin` and cleared area. Import them in your `*.test.ts`:

```ts
import { defineTest, defineTests, place, setBlocks, waitUntil,
         beFieldGreaterThan, invItemEquals, assertBlockId } from "@yu1745/mcdebug";
```

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
