/**
 * Shared help text constants for CLI commands.
 * Used by addHelpText('after', ...) on each Commander command.
 * All text is designed for ~78 char terminal width.
 */

// ---- Global program help (appended after auto-generated --help) ----

export const REPO_URL = 'https://github.com/yu1745/mcdebug';

export const GLOBAL_HELP_AFTER = `
Examples:
  mcdebug status
  mcdebug get --x 0 --y 64 --z 0
  mcdebug place --x 0 --y 64 --z 0 --block minecraft:furnace --state lit=true
  mcdebug place-as-player --x 0 --y 65 --z 0 --block minecraft:furnace --face down
  mcdebug inv insert --x 0 --y 64 --z 0 --item minecraft:coal --count 8
  mcdebug wait-until --expr 'block[0,64,0].id == "minecraft:water"'
  mcdebug raw world.getBlock '{"pos":[0,64,0]}'
  mcdebug craft do --grid @grid.json --recipe-id ic2_120:advanced_batpack
  mcdebug use --x 0 --y 64 --z 0 --face up                    toggle lever (empty hand)
  mcdebug use --x 0 --y 64 --z 0 --face north --item minecraft:bucket  pick up water
  mcdebug jar                        download mod JAR (same version as CLI)
  mcdebug jar --latest               download latest release

All commands output JSON to stdout. Errors go to stderr with exit code 1 (or 2 for server errors).
Use "mcdebug guide" for conceptual documentation (connection, port, error codes, conventions).

Source / issues: ${REPO_URL}
If the help above is unclear, read the source — each command is a single file
in mcdebug-client/src/commands/, the mod side is com.mcdebug.api.*Ops.kt
in src/main/kotlin/ of the repo.`;

// ---- Per-command help text ----

export const STATUS_HELP = `
Examples:
  mcdebug status

Output:
  { "mcVersion":"1.20.1", "modVersion":"...", "dims":[...],
    "players":1, "dayTime":6000, "tick":12345 }`;

export const DIMS_HELP = `
Examples:
  mcdebug dims

Output:
  { "dims": ["minecraft:overworld","minecraft:the_nether","minecraft:the_end"] }`;

export const PLACE_HELP = `
Examples:
  mcdebug place --x 0 --y 64 --z 0 --block minecraft:stone
  mcdebug place --x 0 --y 64 --z 0 --block minecraft:furnace --state lit=true
  mcdebug place --x 0 --y 64 --z 0 --block minecraft:chest --dim minecraft:the_nether
  mcdebug place --x 0 --y 64 --z 0 --block minecraft:oak_door --state facing=east --state half=lower --state hinge=right

Notes:
  --state is repeatable; common keys: facing, horizontal_facing, axis,
  rotation, half, hinge, type, lit, powered, waterlogged.
  For directional blocks (furnace, chest, dispenser, stairs, ...),
  you MUST pass facing/axis explicitly — otherwise you get the block's
  defaultState (usually facing=north), not the face a player would
  have clicked. There is no BlockPlaceContext (no neighbor/face/placer),
  so context-sensitive blocks (redstone wire, attachments) may not
  place the same way a player would.

  For a real player-like placement (full BlockItem pipeline, stable fake
  player, fires Criteria / onPlaced / sounds), use 'place-as-player' instead.
  Trade-off: place-as-player is slower and may fail (ok: false) if the block
  can't be placed there (e.g. replacing a solid block, vanilla door
  context-sensitive rules). Use 'place' for raw, fast, no-questions-asked
  state replacement.

Output:
  true`;

export const REMOVE_HELP = `
Examples:
  mcdebug remove --x 0 --y 64 --z 0
  mcdebug remove --x 0 --y 64 --z 0 --dim minecraft:the_nether

Output:
  true`;

export const PLACE_AS_PLAYER_HELP = `
Examples:
  # Furnace whose facing=SOUTH (player looks north, furnace faces the opposite)
  mcdebug place-as-player --block minecraft:furnace --x 0 --y 65 --z 0 \
    --face down --player-facing north

  # Stairs whose facing=EAST (stairs take playerFacing directly, not its opposite)
  mcdebug place-as-player --block minecraft:oak_stairs --x 0 --y 64 --z 0 \
    --face west --player-facing east

  # Door, lower half, hinge on the right, facing east
  mcdebug place-as-player --block minecraft:oak_door --x 0 --y 64 --z 0 \
    --face west --player-facing east

  # Chest whose facing=WEST (chest, like furnace, uses playerFacing.opposite)
  mcdebug place-as-player --block minecraft:chest --x 5 --y 64 --z 0 \
    --face west --player-facing east

Output:
  {
    "ok": true,
    "pos": [0, 65, 0],
    "neighbor": [0, 64, 0],
    "face": "down",
    "playerFacing": "north",
    "placer": "[mcdebug_fake_player]",
    "placerUuid": "8c0a4d6e-3f2b-4a1e-9d8c-5e7f0a1b2c3d",
    "previous": { "name": "minecraft:air", "props": {} },
    "state":   { "name": "minecraft:furnace", "props": { "facing": "south", "lit": "false" } }
  }

Notes:
  Goes through the full BlockItem / ItemPlacementContext pipeline — directional
  blocks (furnace, chest, dispenser, stairs, door, bed, ...) derive their facing
  from --face and --playerFacing instead of returning defaultState.

  The placer is a STABLE fake ServerPlayerEntity (UUID 8c0a4d6e-...,
  name "[mcdebug_fake_player]"), reused across all calls and all dims. It is
  in creative + invulnerable + flying mode, so the placed stack is not
  decremented and survival-mode interceptions don't fire. This means:
    - Criteria.PLACED_BLOCK DOES fire (on the fake player's tracker; not visible
      to any real player since the player is never registered with the player
      manager, but mods that check "did anyone just place this" see a hit).
    - block.onPlaced(...) receives the fake player as placer; mods that read
      the placer's UUID or name see "[mcdebug_fake_player]".
    - Sound falloff uses the fake player's pos (set to the placement pos each
      call), so audio behaves like a real player standing on the block.

  --face is REQUIRED: it is the side of the existing block that the player
  clicked. The new block is placed on the opposite side (pos = neighbor + face).
  Default --neighbor = pos - face, so for the common case just give --face.

  --player-facing is the direction the player is LOOKING, NOT the direction
  you want the block to face. Vanilla blocks consume it differently:
    stairs / doors / beds / banners / item frames : block.facing = playerFacing
    furnace / chest / dispenser / glazed terracotta: block.facing = playerFacing.opposite
    glass / stone / other non-directional        : ignored
  So for a furnace with facing=south, pass --player-facing north. For stairs
  with facing=east, pass --player-facing east. Default = face.opposite (if
  horizontal) or north.

  Fails (ok: false) if pos is non-replaceable (e.g. trying to replace a solid
  block). Use 'remove' first. Vanilla doors / redstone / few other blocks have
  extra context-sensitive rules and may also fail (ok: false) — this is the
  same behavior a real player would get.`;

export const GET_HELP = `
Examples:
  mcdebug get --x 0 --y 64 --z 0
  mcdebug get --x 0 --y 64 --z 0 --nbt

Output:
  {
    "pos": [0, 64, 0],
    "dim": "minecraft:overworld",
    "state": { "name": "minecraft:stone", "props": {} },
    "hasBlockEntity": false
  }

Use --nbt to include block-entity NBT (adds "nbt": {...} if the block has one).`;

export const FIND_HELP = `
Examples:
  mcdebug find --from 0,60,0 --to 10,70,10 --block minecraft:furnace
  mcdebug find --from 0,60,0 --to 10,70,10 --block minecraft:chest --count

Output (without --count):
  { "positions": [[0,64,0],[5,64,0]] }
Output (with --count):
  { "positions": [[0,64,0],[5,64,0]], "count": 2 }`;

export const COUNT_HELP = `
Examples:
  mcdebug count --from 0,60,0 --to 10,70,10

Output:
  { "counts": { "minecraft:stone": 1200, "minecraft:dirt": 340 } }`;

export const FORCELOAD_HELP = `
Examples:
  mcdebug forceload --cx 1 --cz 2
  mcdebug unforceload --cx 1 --cz 2

Output:
  { "chunk": [1, 2], "forced": true, "changed": true, "dim": "minecraft:overworld" }

Note: --cx and --cz are chunk coordinates (blockX >> 4, integer division by 16).`;

export const UNFORCELOAD_HELP = `
Examples:
  mcdebug unforceload --cx 1 --cz 2

Output:
  { "chunk": [1, 2], "forced": false, "changed": true, "dim": "minecraft:overworld" }

Note: --cx and --cz are chunk coordinates (blockX >> 4, integer division by 16).`;

export const BE_GET_NBT_HELP = `
Examples:
  mcdebug get-nbt --x 0 --y 64 --z 0

Output:
  { "nbt": { "id":"minecraft:furnace", "Items":[...], "BurnTime":0, ... } }

Returns error [-32004] if the block at the position has no block entity.`;

export const BE_SET_NBT_HELP = `
Examples:
  mcdebug set-nbt --x 0 --y 64 --z 0 --nbt '{"Items":[]}'
  mcdebug set-nbt --x 0 --y 64 --z 0 --nbt @furnace.json

Output:
  true

Use @file.json to read NBT from a file instead of inline JSON.`;

export const BE_GET_FIELD_HELP = `
Examples:
  mcdebug get-field --x 0 --y 64 --z 0 --path BurnTime
  mcdebug get-field --x 0 --y 64 --z 0 --path Items.0.Count

Output:
  { "value": 0 }

Path uses dot notation: top-level keys directly (BurnTime), nested via dots
(Items.0.Count), array indices with dots (Items.0).`;

export const BE_SET_FIELD_HELP = `
Examples:
  mcdebug set-field --x 0 --y 64 --z 0 --path BurnTime --value 200
  mcdebug set-field --x 0 --y 64 --z 0 --path Items.0.Count --value 32

Output:
  true

--value is a JSON literal (number, string, boolean, null) or @file.`;

export const INV_GET_HELP = `
Examples:
  mcdebug inv get --x 0 --y 64 --z 0 --slot 0

Output:
  { "item": "minecraft:coal", "count": 8, "nbt": null, "maxCount": 64 }

Slot indexing is 0-based. Empty slots return { "item": null, "count": 0, ... }.`;

export const INV_SET_HELP = `
Examples:
  mcdebug inv set --x 0 --y 64 --z 0 --slot 0 --item minecraft:iron_ore --count 16
  mcdebug inv set --x 0 --y 64 --z 0 --slot 0 --item minecraft:air --count 0

Output:
  true

To clear a slot: --item minecraft:air --count 0 (or --item "" --count 0).`;

export const INV_INSERT_HELP = `
Examples:
  mcdebug inv insert --x 0 --y 64 --z 0 --item minecraft:coal --count 8
  mcdebug inv insert --x 0 --y 64 --z 0 --item minecraft:diamond --slot 1 --count 4
  mcdebug inv insert --x 0 --y 64 --z 0 --item minecraft:coal --count 64 --simulate

Output:
  { "inserted": 8, "remaining": 0 }

Without --slot: auto-distributes across slots. --simulate tests without modifying.`;

export const INV_EXTRACT_HELP = `
Examples:
  mcdebug inv extract --x 0 --y 64 --z 0 --item minecraft:coal --count 8
  mcdebug inv extract --x 0 --y 64 --z 0 --item minecraft:coal --count 8 --simulate

Output:
  { "extracted": 8, "remaining": 0 }

--simulate tests without modifying. Without --slot: searches all slots.`;

export const INV_GROUP_HELP = `
Slot indexing is 0-based. All inv commands require --x --y --z to identify the block entity.
Use "mcdebug inv <command> --help" for command-specific examples and output format.`;

export const FLUID_GROUP_HELP = `
Fluid operations target the Fabric Transfer API Storage<FluidVariant> of a block.
All fluid commands require --x --y --z to identify the block position.
Use --side to query a specific face (default null = "complete inventory" path).
--index targets a specific tank when a storage has multiple parts (rare).

ALL fluid amounts are in DROPLETS (Fabric Transfer API native unit).
  81000 droplets = 1 bucket
  Example: --amount 81000 (one bucket), --amount 162000 (two buckets)

Use "mcdebug fluid <command> --help" for command-specific examples.`;

export const FLUID_INFO_HELP = `
Examples:
  mcdebug fluid info --x 0 --y 64 --z 0
  mcdebug fluid info --x 0 --y 64 --z 0 --side north

Output:
  {
    "side": "null",
    "type": "SingleVariantStorage",
    "supportsInsertion": true,
    "supportsExtraction": false,
    "parts": [
      {"fluid": null, "amount": 0, "capacity": 648000}
    ]
  }

amount and capacity are in DROPLETS (81000 = 1 bucket).
type: SingleVariantStorage | CombinedStorage | Other
parts: always 1 for SingleVariantStorage (even if empty); N for CombinedStorage.`;

export const FLUID_GET_HELP = `
Examples:
  mcdebug fluid get --x 0 --y 64 --z 0                    # single tank → default index 0
  mcdebug fluid get --x 0 --y 64 --z 0 --index 1          # multi-tank storage → must specify

Output:
  { "index": 0, "fluid": "ic2_120:biofuel", "amount": 81000, "capacity": 648000 }

amount and capacity are in DROPLETS (81000 = 1 bucket).
If the storage has multiple tanks and --index is omitted, an error is raised.`;

export const FLUID_INSERT_HELP = `
Examples:
  mcdebug fluid insert --x 0 --y 64 --z 0 --fluid ic2_120:biofuel --amount 81000
  mcdebug fluid insert --x 0 --y 64 --z 0 --fluid minecraft:water --amount 162000 --index 0

--amount is in DROPLETS (81000 = 1 bucket). Inserting 81000 fills one bucket of fluid.

Output:
  { "index": 0, "requested": 81000, "inserted": 81000, "remaining": 0 }

Insert goes through the storage's canInsert/insert (validates fluid type, capacity).
Precise insert: --index 0 goes to parts[0] directly, bypassing auto-distribution.`;

export const FLUID_EXTRACT_HELP = `
Examples:
  mcdebug fluid extract --x 0 --y 64 --z 0 --amount 81000
  mcdebug fluid extract --x 0 --y 64 --z 0 --amount 81000 --index 0

--amount is in DROPLETS (81000 = 1 bucket). Extracting 81000 removes one bucket.

Output:
  { "index": 0, "fluid": "ic2_120:biofuel", "requested": 81000, "extracted": 81000, "remaining": 0 }

Extract uses the tank's current fluid variant (cannot extract a different fluid type than what's stored).
If the tank is empty or extraction is disabled (canExtract=false), extracted will be 0.`;

export const WAIT_UNTIL_HELP = `
Predicate grammar:
  tick                          <op> <value>
  block[x,y,z].id               <op> <value>
  block[x,y,z].prop.<name>      <op> <value>
  be[x,y,z].<jsonPointer>       <op> <value>
  inv[x,y,z].size               <op> <value>
  inv[x,y,z].<slot>.item        <op> <value>
  inv[x,y,z].<slot>.count       <op> <value>
  inv[x,y,z].<slot>.maxCount    <op> <value>
  inv[x,y,z].<slot>.nbt.<path>  <op> <value>

  <op>   := == | != | < | <= | > | >=
  <value>:= number | "string" | true | false | null

Examples:
  mcdebug wait-until --expr 'tick == 12400'
  mcdebug wait-until --expr 'block[0,64,0].id == "minecraft:water"'
  mcdebug wait-until --expr 'block[0,64,0].prop.lit == true'
  mcdebug wait-until --expr 'be[0,64,0].BurnTime > 0' --poll 20
  mcdebug wait-until --expr 'inv[0,64,0].0.item == "minecraft:iron_ore"'

In TS tests, build predicates with the test-runner helpers (blockId / blockNotId /
beFieldEquals / invItem / ...) instead of hand-writing strings — see the README
"Test runner helpers" section.

Output (success):
  { "matched": true, "ranTicks": 145 }
Output (timeout):
  { "matched": false, "ranTicks": 24000 }
  (exits with code 2 and stderr: error [-32006] wait.until timeout ...)`;

export const REPL_HELP = `
The REPL exposes a DebugApi instance as the global variable 'dbg'.

Available methods:
  dbg.server.status()              dbg.server.listDimensions()
  dbg.server.runCommand(cmd, opts?)
  dbg.world.getBlock(pos, opts?)   dbg.world.setBlock(pos, block, state?, opts?)
  dbg.world.setBlocks(ops, opts?)  dbg.world.placeAsPlayer(pos, block, face, opts?)
  dbg.world.useItem(item, opts?)   dbg.world.useOnBlock(pos, face, opts?)
  dbg.world.attackBlock(pos, face, opts?)
  dbg.world.getRegion(box, opts?)
  dbg.world.selectBlocks(box, pred, opts?)
  dbg.world.forceloadChunk(cx, cz, opts?)   dbg.world.unforceloadChunk(cx, cz, opts?)
  dbg.be.getNbt(pos, dim?)         dbg.be.setNbt(pos, nbt, dim?)
  dbg.be.getField(pos, path, dim?) dbg.be.setField(pos, path, value, dim?)
  dbg.inv.getSize(pos, dim?)        dbg.inv.getSlot(pos, slot, dim?)
  dbg.inv.setSlot(pos, slot, item, count, nbt?, dim?)
  dbg.inv.insert(pos, item, count, opts?)   dbg.inv.extract(pos, item, count, opts?)
  dbg.fluid.info(pos, opts?)       dbg.fluid.get(pos, opts?)
  dbg.fluid.insert(pos, fluid, amount, opts?)   dbg.fluid.extract(pos, amount, opts?)
  dbg.wait.until(predicate, opts?)
  dbg.craft.craft(grid, opts?)     dbg.craft.find(grid, opts?)
  dbg.scan.findBlocks(box, block, opts?)     dbg.scan.countByBlock(box, dim?)
  dbg.scan.findEntities(box, opts?)

pos = [x, y, z] (integer tuple). opts can include { dim: "minecraft:the_nether" }.
All methods return promises — use await.

Type .exit or Ctrl+C to quit.

Source: ${REPO_URL}`;

export const RAW_HELP = `
Available RPC methods (namespace.method):

  server.status              server.listDimensions
  world.getBlock              world.setBlock               world.setBlocks
  world.placeAsPlayer        (uses a stable fake player — see 'mcdebug place-as-player --help')
  world.useItem              (right-click an item in air — see 'mcdebug use-item --help')
  world.useOnBlock           (right-click a block — see 'mcdebug use --help')
  world.attackBlock          (left-click / break a block — see 'mcdebug attack --help')
  world.getRegion             world.selectBlocks
  world.forceloadChunk        world.unforceloadChunk
  be.getNbt                   be.setNbt                    be.getField
  be.setField
  inv.getSize                 inv.getSlot                  inv.setSlot
  inv.insert                  inv.extract
  scan.findBlocks             scan.countByBlock
  scan.findEntities
  wait.until
  fluid.info                  fluid.get                    fluid.insert
  fluid.extract
  craft.craft                 craft.find
  server.runCommand

Methods with no dedicated CLI command (use raw to call):
  world.setBlocks   world.getRegion   world.selectBlocks   inv.getSize

Examples:
  mcdebug raw world.getBlock '{"pos":[0,64,0]}'
  mcdebug raw inv.getSize '{"pos":[0,64,0]}'
  mcdebug raw world.setBlocks '{"ops":[{"pos":[0,64,0],"block":"minecraft:stone"}]}'

jsonParams is a JSON object string or @file.json reference.

Source: ${REPO_URL}`;

export const CMD_HELP = `
Run a Minecraft server command as the console.

The command string can be passed as a single argument or split into multiple words:
  mcdebug cmd time set day        # equivalent to /time set day
  mcdebug cmd "/time set day"    # quoted if you prefer

A leading "/" is added automatically if missing.

Output:
  { "success": true, "result": 1, "output": "Set the time to 1000" }

--dim lets you root the executor in a specific dimension (default overworld).
Note: commands like /time set operate globally regardless of --dim.

Examples:
  mcdebug cmd time set day
  mcdebug cmd weather clear
  mcdebug cmd "give @p minecraft:diamond 1"
  mcdebug cmd setblock 0 100 0 minecraft:stone`;

export const JAR_HELP = `
Download the mcdebug Fabric mod JAR from GitHub Releases.

The downloaded JAR can be placed in your Minecraft instance's mods/ folder.

By default, downloads the JAR matching this CLI's version. Use --latest
to grab the newest release (then upgrade your CLI to match before using it).
Cross-version JAR+CLI pairs are not supported — the JSON-RPC protocol
versions must agree.

Examples:
  mcdebug jar                      download mcdebug-0.4.9.jar (CLI version)
  mcdebug jar --latest             download the latest release
  mcdebug jar --output mods/mcdebug.jar  save to a custom path`;

export const CRAFT_GROUP_HELP = `
Simulate crafting without placing a crafting table. Goes through the server's
full RecipeManager — vanilla shaped/shapeless AND modded types all work.

The 3x3 grid (--grid) is a 9-element JSON array in row-major order:
  index 0 = top-left,  1 = top-middle,  2 = top-right
  index 3 = middle-left, 4 = center,    5 = middle-right
  index 6 = bottom-left,7 = bottom-mid,  8 = bottom-right

Each slot is either null (empty) or {"item":id, "count":N?, "nbt":{...}?}.
nbt is merged into the stack's NBT compound. Common keys: "Damage" for tools,
mod-specific keys vary. Use @file.json instead of inline JSON to avoid
shell escaping.

Workflow: if you're unsure which recipe will match, run "craft find" first,
then pass --recipe-id to "craft do" to lock onto the right one.

Use "mcdebug craft <subcommand> --help" for command-specific examples.`;

export const CRAFT_DO_HELP = `
Examples:
  # Shapeless: 2 oak_planks -> 4 sticks
  mcdebug craft do --grid '[
    {"item":"minecraft:oak_planks","count":1},
    {"item":"minecraft:oak_planks","count":1},
    null, null, null, null, null, null, null
  ]'

  # Shaped 2x2: oak_log -> 4 oak_planks (must match 2x2 pattern)
  mcdebug craft do --grid '[
    {"item":"minecraft:oak_log","count":1}, {"item":"minecraft:oak_log","count":1}, null,
    {"item":"minecraft:oak_log","count":1}, {"item":"minecraft:oak_log","count":1}, null,
    null, null, null
  ]'

  # Shaped 3x3: chest
  mcdebug craft do --grid '[
    {"item":"minecraft:oak_planks"}, {"item":"minecraft:oak_planks"}, {"item":"minecraft:oak_planks"},
    null,                           {"item":"minecraft:oak_stairs"},    null,
    {"item":"minecraft:oak_planks"}, {"item":"minecraft:oak_planks"}, {"item":"minecraft:oak_planks"}
  ]'

  # Shaped 3x3 with NBT: written book with custom title
  mcdebug craft do --grid @book-crafting.json

  # Force a specific recipe when multiple match
  mcdebug craft find --grid @grid.json
  mcdebug craft do --grid @grid.json --recipe-id minecraft:chest

Output (matched):
  {
    "matched": true,
    "recipeId": "minecraft:oak_planks",
    "recipeType": "minecraft:crafting",
    "result": { "item": "minecraft:oak_planks", "count": 4, "nbt": null },
    "remainder": [
      { "item": null, "count": 0, "nbt": null },
      { "item": null, "count": 0, "nbt": null },
      ...
    ]
  }

Output (no match):
  { "matched": false, "candidates": [] }

Notes:
  --grid must be exactly 9 elements. Use null for empty slots.
  --recipe-id is optional; if omitted, the first matching recipe wins.
    Run "craft find" first if multiple recipes might match.
  Shaped recipes do a sliding-window match across the 3x3 grid, so a 2x2
    recipe matches at any 2x2 sub-region (e.g. slots [0,1,3,4] or [1,2,4,5]).
  Empty remainder slots show { "item": null } (not "minecraft:air").
  result.nbt contains the output's full NBT compound, if any. Modded recipe
    types may write custom NBT to the result (e.g. charge, durability).`;

export const FIND_ENTITIES_HELP = `
List all entities in a box. Returns type, UUID, position, and health
(for living entities). Use --type to filter by entity type.

Examples:
  mcdebug find-entities --from 0,63,0 --to 10,70,10
  mcdebug find-entities --from -5,63,-5 --to 5,70,5 --type minecraft:chicken
  mcdebug find-entities --from 0,100,0 --to 5,105,5 --nbt

Output:
  {
    "count": 3,
    "entities": [
      {"type":"minecraft:chicken","uuid":"...","x":2.5,"y":100.0,"z":3.5,"health":4.0,"maxHealth":4.0},
      ...
    ]
  }

Notes:
  Coordinates are double (entity center position, not block pos).
  The box has a +1 block margin on each side to catch entities on edges.
  Use --nbt to include full entity NBT (large output for complex entities).`;

export const CRAFT_FIND_HELP = `
Examples:
  mcdebug craft find --grid '[
    {"item":"minecraft:oak_planks","count":1},
    {"item":"minecraft:oak_planks","count":1},
    null, null, null, null, null, null, null
  ]'

Output:
  { "matches": [
    { "recipeId": "minecraft:oak_planks", "recipeType": "minecraft:crafting",
      "output": "minecraft:oak_planks x4" },
    { "recipeId": "minecraft:oak_pressure_plate", "recipeType": "minecraft:crafting",
      "output": "minecraft:oak_pressure_plate x1" }
  ] }

Returns every crafting recipe whose matches() returns true for the grid.
When multiple recipes match, pass the chosen recipeId to "craft do
--recipe-id" to disambiguate. Empty { "matches": [] } means no recipe
matches this grid.`;

export const USE_HELP = `
Simulate right-clicking (using) a block. Goes through the full vanilla
two-phase pipeline:
  1. BlockState.onUse — the block handles the interaction
     (lever toggles, button presses, doors open, chests open GUI, etc.)
  2. ItemStack.useOnBlock — if the block returned PASS, the item handles it
     (bucket picks up water, flint-and-steel ignites, wrench rotates,
     fluid cell injects fluid, etc.)

The fake player is in creative mode, so the held item is NOT consumed
(count does not decrement). This matches the automation use case.

Examples:
  # Toggle a lever (empty hand — lever handles Block.onUse itself)
  mcdebug use --x 0 --y 64 --z 0 --face up
  # Press a button
  mcdebug use --x 0 --y 65 --z 0 --face north
  # Open a chest / furnace (empty hand opens GUI-like interaction)
  mcdebug use --x 5 --y 64 --z 0 --face south
  # Use flint-and-steel on a block to ignite
  mcdebug use --x 0 --y 64 --z 0 --face north --item minecraft:flint_and_steel
  # Use bucket to pick up water source block
  mcdebug use --x 3 --y 62 --z 5 --face up --item minecraft:bucket
  # Use a modded wrench to rotate a machine
  mcdebug use --x 10 --y 64 --z 5 --face north --item modid:wrench
  # Shift+right-click wrench to disassemble a machine
  mcdebug use --x 10 --y 64 --z 5 --face north --item modid:wrench --sneaking

Output:
  {
    "success": true,
    "action": "success",
    "pos": [0, 64, 0],
    "face": "up",
    "blockConsumed": true,
    "itemConsumed": false,
    "itemBefore": { "item": null, "count": 0 },
    "itemAfter":  { "item": null, "count": 0 },
    "blockState": { "name": "minecraft:lever", "props": { "face": "ceiling", "powered": "true" } }
  }

Notes:
  --item is optional. Omit it for empty-hand interactions (lever, button,
    door, chest, etc.) where the block itself handles the right-click.
  --sneaking simulates shift+right-click. Many blocks/items change behavior:
    vanilla beds/doors return PASS when sneaking (letting item use fire);
    modded wrenches often use shift+click for disassemble vs rotate.
  --face is the face of the target block being clicked, not the player's
    facing direction.
  blockConsumed=true means BlockState.onUse returned accepted (lever
    toggled, door opened, etc.).
  itemConsumed=true means ItemStack.useOnBlock returned accepted (bucket
    picked up fluid, wrench rotated, etc.).
  itemAfter shows the stack after interaction — check for durability changes
    (Damage field) or NBT changes if the item was modified.
  If neither blockConsumed nor itemConsumed, action will be "pass" and
  success will be false — the interaction had no effect.`;

export const USE_ITEM_HELP = `
Simulate right-clicking (using) the held item in air, with no block or entity
target. This triggers Item.use(world, player, hand).

Use this for items whose right-click behavior is not tied to a target:
  - toggle tools such as ic2_120:nano_saber
  - bows, food, potions, scrolls, scanners, or other mod items that act on use

Examples:
  # Toggle a full IC2 nano saber on
  mcdebug use-item --item ic2_120:nano_saber --nbt '{"Energy":160000}'
  # Toggle the same saber off
  mcdebug use-item --item ic2_120:nano_saber --nbt '{"Energy":160000,"NanoSaberActive":1}'
  # Use a mod item while sneaking
  mcdebug use-item --item modid:tool --sneaking

Output:
  {
    "success": true,
    "action": "consume",
    "sneaking": false,
    "itemBefore": { "item": "ic2_120:nano_saber", "count": 1, "nbt": { "Energy": 160000 } },
    "itemAfter":  { "item": "ic2_120:nano_saber", "count": 1, "nbt": { "Energy": 160000, "NanoSaberActive": 1 } }
  }

Notes:
  This is different from 'mcdebug use', which right-clicks a block face, and
  from 'mcdebug interact-entity', which right-clicks an entity.
  itemAfter shows changed NBT, transformed stacks, or consumed items.`;

export const ATTACK_HELP = `
Simulate left-clicking (attacking) a block. Triggers Block.onBlockBreakStart
(the "start mining" event), and in creative mode immediately breaks the block.

This fires block-break events, loot drops, and onBroken callbacks — unlike
'remove' which is a raw setBlockState to air with no side effects.

Examples:
  # Break a block (creative mode — instant break with drops)
  mcdebug attack --x 0 --y 64 --z 0 --face north
  # Left-click with a sword (triggers sweep attack particles, etc.)
  mcdebug attack --x 5 --y 64 --z 3 --face up --item minecraft:diamond_sword

Output:
  {
    "broken": true,
    "pos": [0, 64, 0],
    "face": "north",
    "itemBefore": { "item": null, "count": 0 },
    "itemAfter":  { "item": null, "count": 0 },
    "blockState": { "name": "minecraft:air", "props": {} }
  }

Notes:
  The fake player is in creative mode, so blocks break instantly.
  broken=true means the block was destroyed (creative mode break).
  itemAfter may show durability changes if the held item was affected.`;

export const INTERACT_ENTITY_HELP = `
Simulate right-clicking (using) an entity. Mirrors the vanilla entity
interaction pipeline (PlayerEntity.interact):
  1. Fabric API UseEntityCallback (mod handlers)
  2. entity.interact(player, hand) — entity handles interaction
  3. item.useOnEntity(player, entity, hand) — item handles interaction
     (e.g. bucket milks cow, shears shear sheep, food feeds animal)

Examples:
  # Milk a cow (find UUID via find-entities first)
  mcdebug interact-entity --uuid <cow-uuid> --item minecraft:bucket
  # Shear a sheep
  mcdebug interact-entity --uuid <sheep-uuid> --item minecraft:shears
  # Feed a horse (shift+right-click with golden carrot)
  mcdebug interact-entity --uuid <horse-uuid> --item minecraft:golden_carrot --sneaking

Output:
  {
    "success": true,
    "entityType": "minecraft:cow",
    "entityUuid": "...",
    "eventConsumed": false,
    "entityConsumed": false,
    "itemConsumed": true,
    "itemBefore": { "item": "minecraft:bucket", "count": 1 },
    "itemAfter":  { "item": "minecraft:milk_bucket", "count": 1 }
  }

Notes:
  Use 'find-entities' to get the UUID of the target entity.
  itemConsumed=true means the item handled the interaction (e.g. bucket → milk bucket).
  The fake player is in creative mode, so item count is preserved.`;

export const ATTACK_ENTITY_HELP = `
Simulate left-clicking (attacking) an entity. Mirrors PlayerEntity.attack():
  1. Fabric API AttackEntityCallback (mod handlers)
  2. PlayerEntity.attack(entity) — damage, knockback, sweep attack, etc.

Examples:
  # Punch a cow (empty hand, 1 damage in survival)
  mcdebug attack-entity --uuid <cow-uuid>
  # Attack with a diamond sword (sweep + extra damage)
  mcdebug attack-entity --uuid <zombie-uuid> --item minecraft:diamond_sword

Output:
  {
    "entityType": "minecraft:cow",
    "entityUuid": "...",
    "eventConsumed": false,
    "entityHealth": 9,
    "entityMaxHealth": 10,
    "entityDead": false
  }

Notes:
  Use 'find-entities' to get the UUID of the target entity.
  The fake player temporarily switches to survival mode with full attack
  cooldown, so weapon damage should match a normal fully charged attack.
  entityDead=true means the entity was killed by the attack.`;

// ---- Guide sections ----

const GUIDE_CONNECTION = `
=== Connection ===

The mcdebug server is a Fabric mod running inside Minecraft. The CLI connects over
TCP/JSON-RPC to localhost. The server must be running (world loaded) before CLI
commands work.

Default host: 127.0.0.1 (localhost only, no remote access in v1).`;

const GUIDE_PORT_DISCOVERY = `
=== Port Discovery ===

The CLI finds the server port in this order:
  1. --port <N>        CLI flag (highest priority)
  2. MCDEBUG_PORT      environment variable
  3. mcdebug/port      file (searched in cwd and parent dirs)
  4. 25580             default`;

const GUIDE_FILE_SYNTAX = `
=== @file Syntax ===

Any argument accepting JSON (--nbt, --value, raw jsonParams) supports reading from
a file instead of inline JSON:
  --nbt @furnace.json    reads furnace.json and parses as JSON
  --value @data.json     reads data.json and parses as JSON
  mcdebug raw be.getNbt @params.json

Path is relative to cwd. Use absolute paths if needed: --nbt @/tmp/data.json`;

const GUIDE_ERRORS = `
=== Error Codes ===

Errors print to stderr: error [code] message
Exit code 1 for client errors, 2 for server-side errors (-32xxx).

Custom server error codes:
  -32001  Invalid position         -32002  Block not found
  -32003  Invalid block state      -32004  Block entity missing
  -32005  Slot out of range        -32006  Wait timeout
  -32007  NBT parse error          -32008  Chunk not loaded
  -32009  Permission denied        -32010  World read-only
  -32011  Dimension not found      -32012  Invalid predicate

Standard JSON-RPC codes:
  -32700  Parse error              -32600  Invalid request
  -32601  Method not found         -32602  Invalid params
  -32603  Internal error`;

const GUIDE_WAIT_GRAMMAR = WAIT_UNTIL_HELP;

const GUIDE_CONVENTIONS = `
=== Conventions ===

Coordinates:     Block positions are integer triples [x, y, z].
                Chunk coordinates are blockX >> 4 (divide by 16, truncate).
                Y=0 is bedrock in overworld; Y=-64 is the bottom in 1.18+.

Dimensions:      Default is minecraft:overworld.
                Pass --dim minecraft:the_nether or --dim minecraft:the_end.

Block IDs:       Always use full namespaced IDs: minecraft:furnace, minecraft:chest.

Slot indexing:   Inventory slots are 0-based. Slot 0 is the first slot.
                Empty slots return item: null, count: 0.

Block entities:  Blocks like furnaces, chests, hoppers have block entities (NBT data).
                Use get-nbt / set-nbt / get-field / set-field to read/write them.
                Use --nbt flag with "get" to include NBT in block query response.

JSON output:     All commands return JSON to stdout on success.
                Errors go to stderr with a numeric exit code.`;

const GUIDE_PLACEMENT = `
=== Placement: place vs place-as-player ===

Two ways to put a block at a position; pick based on what you need:

  place             raw setBlockState. Fast, no questions, no BlockItem pipeline.
                    The placed state is exactly what you pass in --state / --flags.
                    No fake player, no Criteria trigger, no onPlaced callback,
                    no sound, no GameEvent.
                    Use when: you want to assert an exact final state in tests,
                    or the block has no business with a placer.

  place-as-player   Full BlockItem / ItemPlacementContext pipeline with a stable
                    fake ServerPlayerEntity ([mcdebug_fake_player],
                    8c0a4d6e-3f2b-4a1e-9d8c-5e7f0a1b2c3d) as the placer.
                    Fires Criteria.PLACED_BLOCK, onPlaced, sounds, GameEvent.
                    Block state is derived from --face + --playerFacing via the
                    block's own getPlacementState (stairs / doors / chests / etc.
                    compute their facing the way they would for a real player).
                    Use when: testing mod code that branches on context.getPlayer()
                    (e.g. IC2 MachineBlock writes OwnerUUID from placer.uuid),
                    or when you want the block's vanilla state-derivation behavior
                    rather than a hand-written state.

  remove            Sets the position to minecraft:air. Always succeeds if the
                    position is loaded.

Workflow tip: after a place-as-player that you want to verify, use
  mcdebug get-nbt --x ... --y ... --z ...
to inspect the resulting block entity NBT — the OwnerUUID (or whatever
mod-specific placer field) should be the fake player's UUID if the mod
recorded it.
`;

const SECTIONS: Record<string, string> = {
  connection: GUIDE_CONNECTION,
  'port-discovery': GUIDE_PORT_DISCOVERY,
  'file-syntax': GUIDE_FILE_SYNTAX,
  errors: GUIDE_ERRORS,
  'wait-grammar': GUIDE_WAIT_GRAMMAR,
  conventions: GUIDE_CONVENTIONS,
  placement: GUIDE_PLACEMENT,
};

/**
 * Return the guide text for a specific section, or all sections joined.
 */
export function getGuideText(section?: string): string {
  if (section) {
    if (SECTIONS[section]) return SECTIONS[section];
    const available = Object.keys(SECTIONS).join(', ');
    return `Unknown section: "${section}". Available: ${available}\n`;
  }
  return Object.values(SECTIONS).join('\n');
}
