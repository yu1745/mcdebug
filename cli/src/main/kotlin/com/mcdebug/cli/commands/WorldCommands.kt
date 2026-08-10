package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class WorldCommands : CliktCommand(name = "world", help = "world block / region / player-simulation operations") {
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

class SetBlockCmd : CliktCommand(name = "set-block", help = "set a block state (raw setBlockState, no BlockItem pipeline)") {
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

class PlaceCmd : CliktCommand(name = "place", help = "alias of set-block (raw setBlockState)") {
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

class RemoveCmd : CliktCommand(name = "remove", help = "remove a block (sets it to minecraft:air)") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.setBlock(listOf(x, y, z), "minecraft:air", null, null, dim))
    }
}

class FillBoxCmd : CliktCommand(name = "fill-box", help = "fill a loaded box with one block state") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val block by option("--block").required()
    private val state by option("--state", help = "state property k=v, repeatable").multiple()
    private val flags by option("--flags").int()
    private val maxBlocks by option("--max-blocks").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        val props = state.takeIf { it.isNotEmpty() }?.let { parseStateProps(it) }
        printJson(api.world.fillBox(parseBox(from, to), block, props, flags, dim, maxBlocks))
    }
}

class ClearBoxCmd : CliktCommand(name = "clear-box", help = "fill a box with air") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val flags by option("--flags").int()
    private val maxBlocks by option("--max-blocks").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.clearBox(parseBox(from, to), flags, dim, maxBlocks))
    }
}

class PlaceAsPlayerCmd : CliktCommand(name = "place-as-player", help = "place a block through the full BlockItem pipeline with a fake player") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val block by option("--block").required()
    private val face by option("--face", help = "up|down|north|south|east|west").required()
    private val neighbor by option("--neighbor", help = "x,y,z of the block the face belongs to")
    private val playerFacing by option("--player-facing", help = "up|down|north|south|east|west")
    private val nbt by option("--nbt", help = "block entity NBT JSON or @file")
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

class GetRegionCmd : CliktCommand(name = "get-region", help = "read all blocks in a box") {
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

class ForceloadCmd : CliktCommand(name = "forceload", help = "force-load a chunk") {
    private val cx by option("--cx").int().required()
    private val cz by option("--cz").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.forceloadChunk(cx, cz, dim))
    }
}

class UnforceloadCmd : CliktCommand(name = "unforceload", help = "unforce-load a chunk") {
    private val cx by option("--cx").int().required()
    private val cz by option("--cz").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.unforceloadChunk(cx, cz, dim))
    }
}

class UseOnBlockCmd : CliktCommand(name = "use-on-block", help = "simulate right-clicking a block with an item (full interactBlock pipeline)") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val face by option("--face").required()
    private val item by option("--item")
    private val count by option("--count").int()
    private val nbt by option("--nbt", help = "item NBT JSON or @file")
    private val sneaking by option("--sneaking").flag()
    private val playerFacing by option("--player-facing")
    private val gamemode by option("--gamemode", help = "survival|creative")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.useOnBlock(listOf(x, y, z), face, item, count, nbt?.let { parseJsonArg(it) }, sneaking, playerFacing, gamemode, dim))
    }
}

class UseItemCmd : CliktCommand(name = "use-item", help = "simulate right-clicking with an item in air (Item.use)") {
    private val item by option("--item").required()
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val sneaking by option("--sneaking").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.useItem(item, count, nbt?.let { parseJsonArg(it) }, sneaking, dim))
    }
}

class UseItemHoldCmd : CliktCommand(name = "use-item-hold", help = "right-click a ranged weapon and hold it for holdTicks (bows, crossbows, modded weapons)") {
    private val item by option("--item").required()
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val ammo by option("--ammo")
    private val ammoCount by option("--ammo-count").int()
    private val targetUuid by option("--target-uuid")
    private val direction by option("--direction", help = "north|south|east|west|up|down")
    private val holdTicks by option("--hold-ticks").int()
    private val repeat by option("--repeat").int()
    private val playerPos by option("--player-pos", help = "x,y,z")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.useItemHold(
            item, count, nbt?.let { parseJsonArg(it) }, ammo, ammoCount, targetUuid,
            direction, holdTicks, repeat, playerPos?.let { parseTriplet(it, "player-pos") }, dim,
        ))
    }
}

class AttackBlockCmd : CliktCommand(name = "attack-block", help = "simulate left-clicking a block (processBlockBreakingAction pipeline)") {
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

class InteractEntityCmd : CliktCommand(name = "interact-entity", help = "simulate right-clicking an entity with an item") {
    private val uuid by option("--uuid").required()
    private val item by option("--item")
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val sneaking by option("--sneaking").flag()
    private val playerFacing by option("--player-facing")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.interactEntity(uuid, item, count, nbt?.let { parseJsonArg(it) }, sneaking, playerFacing, dim))
    }
}

class AttackEntityCmd : CliktCommand(name = "attack-entity", help = "simulate left-clicking (attacking) an entity") {
    private val uuid by option("--uuid").required()
    private val item by option("--item")
    private val count by option("--count").int()
    private val nbt by option("--nbt")
    private val armor by option("--armor", help = "armor JSON or @file")
    private val playerFacing by option("--player-facing")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.world.attackEntity(uuid, item, count, nbt?.let { parseJsonArg(it) }, armor?.let { parseJsonArg(it) }, playerFacing, dim))
    }
}

fun worldSubcommands() = listOf(
    GetBlockCmd(), SetBlockCmd(), PlaceCmd(), RemoveCmd(), FillBoxCmd(), ClearBoxCmd(),
    PlaceAsPlayerCmd(), GetRegionCmd(), SelectBlocksCmd(), ForceloadCmd(), UnforceloadCmd(),
    UseOnBlockCmd(), UseItemCmd(), UseItemHoldCmd(), AttackBlockCmd(), InteractEntityCmd(), AttackEntityCmd(),
)
