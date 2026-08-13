package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class WorldCommands : CliktCommand(
    name = "world",
    help = "world block / region / player-simulation operations; coordinate styles: single point = --x/--y/--z, box = --from/--to \"x,y,z\" (comma string), chunk = --cx/--cz (chunk coordinates = blockX >> 4, NOT block coordinates)",
) {
    override fun run() = Unit
}

class GetBlockCmd : CliktCommand(name = "get-block", help = "read a block state (+optional NBT) at a position") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")
    private val includeNbt by option("--include-nbt").flag()

    override fun run() = withApi { api ->
        printJson(api.world.getBlock(listOf(x, y, z), dim, includeNbt))
    }
}

class SetBlockCmd : CliktCommand(name = "set-block", help = "set a block state (raw setBlockState, no BlockItem pipeline); ok:false (out-of-bounds / same block already set) is a NORMAL response, not an error: exit code stays 0, check the ok field") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val block by option("--block").required()
    private val state by option("--state", help = "state property k=v, repeatable").multiple()
    private val flags by option("--flags").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        val props = state.takeIf { it.isNotEmpty() }?.let { parseStateProps(it) }
        printJson(api.world.setBlock(listOf(x, y, z), block, props, flags, dim))
    }
}

class PlaceCmd : CliktCommand(name = "place", help = "alias of set-block: raw setBlockState, NOT the player placement pipeline (see place-as-player); ok:false is a normal response, not an error") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val block by option("--block").required()
    private val state by option("--state", help = "state property k=v, repeatable").multiple()
    private val flags by option("--flags").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        val props = state.takeIf { it.isNotEmpty() }?.let { parseStateProps(it) }
        printJson(api.world.setBlock(listOf(x, y, z), block, props, flags, dim))
    }
}

class RemoveCmd : CliktCommand(name = "remove", help = "remove a block (sets it to minecraft:air); ok:false (out-of-bounds / already air) is a NORMAL response, not an error: exit code 0") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.setBlock(listOf(x, y, z), "minecraft:air", null, null, dim))
    }
}

class FillBoxCmd : CliktCommand(name = "fill-box", help = "fill a box with one block state; chunks must ALREADY be loaded (unloaded chunk -> -32008), unlike get-region which auto-loads") {
    private val from by option("--from", help = "box corner \"x,y,z\" (comma string)").required()
    private val to by option("--to", help = "box corner \"x,y,z\" (comma string)").required()
    private val block by option("--block").required()
    private val state by option("--state", help = "state property k=v, repeatable").multiple()
    private val flags by option("--flags").int()
    private val maxBlocks by option("--max-blocks", help = "confirmation THRESHOLD, not a cap: if the box exceeds it, the call fails with -32602 and writes NOTHING (default 32768)").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        val props = state.takeIf { it.isNotEmpty() }?.let { parseStateProps(it) }
        printJson(api.world.fillBox(parseBox(from, to), block, props, flags, dim, maxBlocks))
    }
}

class ClearBoxCmd : CliktCommand(name = "clear-box", help = "fill a box with air; chunks must ALREADY be loaded (unloaded chunk -> -32008), unlike get-region which auto-loads") {
    private val from by option("--from", help = "box corner \"x,y,z\" (comma string)").required()
    private val to by option("--to", help = "box corner \"x,y,z\" (comma string)").required()
    private val flags by option("--flags").int()
    private val maxBlocks by option("--max-blocks", help = "confirmation THRESHOLD, not a cap: if the box exceeds it, the call fails with -32602 and writes NOTHING (default 32768)").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.clearBox(parseBox(from, to), flags, dim, maxBlocks))
    }
}

class PlaceAsPlayerCmd : CliktCommand(name = "place-as-player", help = "place a block through the full BlockItem placement pipeline with a fake player (fake player defaults: gamemode=survival, player-facing = looking at the clicked face)") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val block by option("--block").required()
    private val face by option("--face", help = "up|down|north|south|east|west").required()
    private val neighbor by option("--neighbor", help = "x,y,z of the block the face belongs to")
    private val playerFacing by option("--player-facing", help = "up|down|north|south|east|west (default: looking at the clicked face)")
    private val nbt by option("--nbt", help = "block entity NBT JSON or @file — NOT IMPLEMENTED YET: currently ignored by the server (no error, no effect)")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.placeAsPlayer(
            listOf(x, y, z), block, face,
            neighbor = neighbor?.let { parseTriplet(it, "neighbor") },
            playerFacing = playerFacing,
            nbt = nbt?.let { parseJsonArg(it) },
            dim = dim,
        ))
    }
}

class GetRegionCmd : CliktCommand(name = "get-region", help = "read all blocks in a box; AUTO-LOADS unloaded chunks (opposite of fill-box/clear-box, which fail with -32008 on unloaded chunks)") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val includeNbt by option("--include-nbt").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.getRegion(parseBox(from, to), includeNbt, dim))
    }
}

class SelectBlocksCmd : CliktCommand(name = "select-blocks", help = "find blocks matching a predicate in a box") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val block by option("--block")
    private val includeNbt by option("--include-nbt").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.selectBlocks(parseBox(from, to), block, includeNbt, dim))
    }
}

class ReplaceBoxCmd : CliktCommand(
    name = "replace-box",
    help = "find & replace blocks inside a box: all positions matching --match get --replace; " +
        "implemented CLI-side as select-blocks + ONE setBlocks call, so state properties of the replaced " +
        "block are NOT preserved (only plain block ids); large boxes are slow — it scans every position",
) {
    private val from by option("--from", help = "box corner \"x,y,z\" (comma string)").required()
    private val to by option("--to", help = "box corner \"x,y,z\" (comma string)").required()
    private val match by option("--match", help = "block id to look for, e.g. minecraft:stone").required()
    private val replace by option("--replace", help = "block id to write, e.g. minecraft:air").required()
    private val state by option("--state", help = "state property k=v for the replacement block, repeatable").multiple()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        val box = parseBox(from, to)
        val sel = api.world.selectBlocks(box, match, false, dim)
        val matches = sel.asJsonObject.getAsJsonArray("matches")
        val props = state.takeIf { it.isNotEmpty() }?.let { parseStateProps(it) }
        val ops = matches.map { el ->
            val pos = el.asJsonObject.getAsJsonArray("pos").map { it.asInt }
            mapOf<String, Any?>("pos" to pos, "block" to replace, "stateProps" to props)
        }
        val replaced = if (ops.isEmpty()) 0 else api.world.setBlocks(ops, null, dim).asJsonObject.get("count").asInt
        com.google.gson.JsonObject().apply {
            addProperty("matched", matches.size())
            addProperty("replaced", replaced)
        }.let { printJson(it) }
    }
}

class ForceloadCmd : CliktCommand(name = "forceload", help = "force-load a chunk; takes CHUNK coordinates (--cx = blockX >> 4), NOT block coordinates") {
    private val cx by option("--cx", help = "chunk X (blockX >> 4)").int().required()
    private val cz by option("--cz", help = "chunk Z (blockZ >> 4)").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.forceloadChunk(cx, cz, dim))
    }
}

class UnforceloadCmd : CliktCommand(name = "unforceload", help = "unforce-load a chunk; takes CHUNK coordinates (--cx = blockX >> 4); after a REAL unload, writes into that chunk fail with -32008 (reads auto-load it again, writes do not)") {
    private val cx by option("--cx", help = "chunk X (blockX >> 4)").int().required()
    private val cz by option("--cz", help = "chunk Z (blockZ >> 4)").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.unforceloadChunk(cx, cz, dim))
    }
}

class UseOnBlockCmd : CliktCommand(name = "use-on-block", help = "simulate right-clicking a block with an item (full interactBlock pipeline); fake player stands just outside the clicked face, gamemode defaults to survival") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val face by option("--face", help = "REQUIRED: up|down|north|south|east|west (which face was clicked)").required()
    private val item by option("--item")
    private val count by option("--count").int()
    private val nbt by option("--nbt", help = "item NBT JSON or @file")
    private val sneaking by option("--sneaking").flag()
    private val playerFacing by option("--player-facing", help = "up|down|north|south|east|west (default: looking at the clicked face)")
    private val gamemode by option("--gamemode", help = "survival|creative (default: survival)")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.useOnBlock(listOf(x, y, z), face, item, count, nbt?.let { parseJsonArg(it) }, sneaking, playerFacing, gamemode, dim))
    }
}

class UseItemCmd : CliktCommand(name = "use-item", help = "simulate right-clicking with an item in air (Item.use); fake player defaults: gamemode=survival, position=world spawn (there is no position option)") {
    private val item by option("--item").required()
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val sneaking by option("--sneaking").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.useItem(item, count, nbt?.let { parseJsonArg(it) }, sneaking, dim))
    }
}

class UseItemHoldCmd : CliktCommand(name = "use-item-hold", help = "right-click a ranged weapon and hold it for holdTicks (bows, crossbows, modded weapons); gamemode defaults to survival") {
    private val item by option("--item").required()
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val ammo by option("--ammo")
    private val ammoCount by option("--ammo-count").int()
    private val targetUuid by option("--target-uuid", help = "aim at this entity (then --player-pos is optional: the fake player stands 5 blocks in front of it)")
    private val direction by option("--direction", help = "north|south|east|west|up|down (fixed facing when no target)")
    private val holdTicks by option("--hold-ticks").int()
    private val repeat by option("--repeat").int()
    private val playerPos by option("--player-pos", help = "x,y,z of the fake player — REQUIRED when using --direction: without it the fake player would spawn at world spawn, usually far from the origin, and shots land far away")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        val pos = playerPos?.let { parseTriplet(it, "player-pos") }
        if (targetUuid == null && pos == null) {
            throw IllegalArgumentException(
                "--player-pos is required when aiming with --direction: without --target-uuid the fake player would spawn at world spawn, far from the origin, and shots would land far away"
            )
        }
        printJson(api.world.useItemHold(
            item, count, nbt?.let { parseJsonArg(it) }, ammo, ammoCount, targetUuid,
            direction, holdTicks, repeat, pos, dim,
        ))
    }
}

class AttackBlockCmd : CliktCommand(name = "attack-block", help = "simulate left-clicking a block (processBlockBreakingAction pipeline); ONE call performs the whole break (start->hold->stop): the block is fully broken in a single invocation; gamemode defaults to survival") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val face by option("--face").required()
    private val item by option("--item")
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val armor by option("--armor", help = "armor JSON ({\"head\":{\"item\":\"...\"}}) or @file")
    private val gamemode by option("--gamemode", help = "survival|creative")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.attackBlock(listOf(x, y, z), face, item, count, nbt?.let { parseJsonArg(it) }, armor?.let { parseJsonArg(it) }, gamemode, dim))
    }
}

class InteractEntityCmd : CliktCommand(name = "interact-entity", help = "simulate right-clicking an entity with an item; fake player defaults: gamemode=survival, player-facing=south, standing next to the entity") {
    private val uuid by option("--uuid").required()
    private val item by option("--item")
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val sneaking by option("--sneaking").flag()
    private val playerFacing by option("--player-facing", help = "up|down|north|south|east|west (default: south)")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.interactEntity(uuid, item, count, nbt?.let { parseJsonArg(it) }, sneaking, playerFacing, dim))
    }
}

class AttackEntityCmd : CliktCommand(name = "attack-entity", help = "simulate left-clicking (attacking) an entity; fake player defaults: gamemode=survival, player-facing=south, standing 1.5 blocks behind the entity") {
    private val uuid by option("--uuid").required()
    private val item by option("--item")
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val armor by option("--armor", help = "armor JSON or @file")
    private val playerFacing by option("--player-facing", help = "up|down|north|south|east|west (default: south)")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.attackEntity(uuid, item, count, nbt?.let { parseJsonArg(it) }, armor?.let { parseJsonArg(it) }, playerFacing, dim))
    }
}

fun worldSubcommands() = listOf(
    GetBlockCmd(), SetBlockCmd(), PlaceCmd(), RemoveCmd(), FillBoxCmd(), ClearBoxCmd(),
    PlaceAsPlayerCmd(), GetRegionCmd(), SelectBlocksCmd(), ReplaceBoxCmd(), ForceloadCmd(), UnforceloadCmd(),
    UseOnBlockCmd(), UseItemCmd(), UseItemHoldCmd(), AttackBlockCmd(), InteractEntityCmd(), AttackEntityCmd(),
)
