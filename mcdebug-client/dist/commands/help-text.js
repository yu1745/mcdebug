/**
 * Shared help text constants for CLI commands.
 * Used by addHelpText('after', ...) on each Commander command.
 * All text is designed for ~78 char terminal width.
 */
// ---- Global program help (appended after auto-generated --help) ----
export const GLOBAL_HELP_AFTER = `
Examples:
  mcdebug status
  mcdebug get --x 0 --y 64 --z 0
  mcdebug place --x 0 --y 64 --z 0 --block minecraft:furnace --state lit=true
  mcdebug inv insert --x 0 --y 64 --z 0 --item minecraft:coal --count 8
  mcdebug wait-until --expr 'block[0,64,0].id == "minecraft:water"'
  mcdebug raw world.getBlock '{"pos":[0,64,0]}'
  mcdebug jar                        download mod JAR (same version as CLI)
  mcdebug jar --latest               download latest release

All commands output JSON to stdout. Errors go to stderr with exit code 1 (or 2 for server errors).
Use "mcdebug guide" for conceptual documentation (connection, port, error codes, conventions).`;
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

Output:
  true`;
export const REMOVE_HELP = `
Examples:
  mcdebug remove --x 0 --y 64 --z 0
  mcdebug remove --x 0 --y 64 --z 0 --dim minecraft:the_nether

Output:
  true`;
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

Output (success):
  { "matched": true, "ranTicks": 145 }
Output (timeout):
  { "matched": false, "ranTicks": 24000 }
  (exits with code 2 and stderr: error [-32006] wait.until timeout ...)`;
export const REPL_HELP = `
The REPL exposes a DebugApi instance as the global variable 'dbg'.

Available methods:
  dbg.server.status()              dbg.server.listDimensions()
  dbg.world.getBlock(pos, opts?)   dbg.world.setBlock(pos, block, state?, opts?)
  dbg.world.setBlocks(ops, opts?)  dbg.world.getRegion(box, opts?)
  dbg.world.selectBlocks(box, pred, opts?)
  dbg.world.forceloadChunk(cx, cz, opts?)   dbg.world.unforceloadChunk(cx, cz, opts?)
  dbg.be.getNbt(pos, dim?)         dbg.be.setNbt(pos, nbt, dim?)
  dbg.be.getField(pos, path, dim?) dbg.be.setField(pos, path, value, dim?)
  dbg.inv.getSize(pos, dim?)        dbg.inv.getSlot(pos, slot, dim?)
  dbg.inv.setSlot(pos, slot, item, count, nbt?, dim?)
  dbg.inv.insert(pos, item, count, opts?)   dbg.inv.extract(pos, item, count, opts?)
  dbg.wait.until(predicate, opts?)
  dbg.scan.findBlocks(box, block, opts?)     dbg.scan.countByBlock(box, dim?)

pos = [x, y, z] (integer tuple). opts can include { dim: "minecraft:the_nether" }.
All methods return promises — use await.

Type .exit or Ctrl+C to quit.`;
export const RAW_HELP = `
Available RPC methods (namespace.method):

  server.status              server.listDimensions
  world.getBlock              world.setBlock               world.setBlocks
  world.getRegion             world.selectBlocks
  world.forceloadChunk        world.unforceloadChunk
  be.getNbt                   be.setNbt                    be.getField
  be.setField
  inv.getSize                 inv.getSlot                  inv.setSlot
  inv.insert                  inv.extract
  scan.findBlocks             scan.countByBlock
  wait.until

Methods with no dedicated CLI command (use raw to call):
  world.setBlocks   world.getRegion   world.selectBlocks   inv.getSize

Examples:
  mcdebug raw world.getBlock '{"pos":[0,64,0]}'
  mcdebug raw inv.getSize '{"pos":[0,64,0]}'
  mcdebug raw world.setBlocks '{"ops":[{"pos":[0,64,0],"block":"minecraft:stone"}]}'

jsonParams is a JSON object string or @file.json reference.`;
export const JAR_HELP = `
Download the mcdebug Fabric mod JAR from GitHub Releases.

The downloaded JAR can be placed in your Minecraft instance's mods/ folder.

By default, downloads the JAR matching this CLI's version (use --version for other versions).

Examples:
  mcdebug jar                      download mcdebug-0.1.0.jar (CLI version)
  mcdebug jar --version 0.2.0      download mcdebug-0.2.0.jar
  mcdebug jar --latest             download the latest release
  mcdebug jar --output mods/mcdebug.jar  save to a custom path`;
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
const SECTIONS = {
    connection: GUIDE_CONNECTION,
    'port-discovery': GUIDE_PORT_DISCOVERY,
    'file-syntax': GUIDE_FILE_SYNTAX,
    errors: GUIDE_ERRORS,
    'wait-grammar': GUIDE_WAIT_GRAMMAR,
    conventions: GUIDE_CONVENTIONS,
};
/**
 * Return the guide text for a specific section, or all sections joined.
 */
export function getGuideText(section) {
    if (section) {
        if (SECTIONS[section])
            return SECTIONS[section];
        const available = Object.keys(SECTIONS).join(', ');
        return `Unknown section: "${section}". Available: ${available}\n`;
    }
    return Object.values(SECTIONS).join('\n');
}
