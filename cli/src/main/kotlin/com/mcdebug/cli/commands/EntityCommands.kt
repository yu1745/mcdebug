package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.float
import com.github.ajalt.clikt.parameters.types.int

// ---- entity group ----

class EntityCommands : CliktCommand(name = "entity", help = "server entity control") {
    override fun run() = Unit
}

class EntitySpawnCmd : CliktCommand(name = "spawn", help = "spawn an entity at a position") {
    private val type by option("--type").required()
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")
    private val yaw by option("--yaw").float()
    private val pitch by option("--pitch").float()
    private val nbt by option("--nbt", help = "entity NBT JSON or @file")
    private val stack by option("--stack", help = "item stack JSON (for ItemEntity)")
    private val includeNbt by option("--include-nbt").flag()

    override fun run() = withApi { api ->
        printJson(api.entity.spawn(type, listOf(x, y, z), dim, yaw, pitch, nbt?.let { parseJsonArg(it) }, stack?.let { parseJsonArg(it) }, includeNbt))
    }
}

class EntityGetNbtCmd : CliktCommand(name = "get-nbt", help = "read entity NBT") {
    private val uuid by option("--uuid").required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.entity.getNbt(uuid, dim))
    }
}

class EntitySetNbtCmd : CliktCommand(name = "set-nbt", help = "write entity NBT") {
    private val uuid by option("--uuid").required()
    private val nbt by option("--nbt").required()
    private val dim by option("--dim")
    private val replace by option("--replace").flag()

    override fun run() = withApi { api ->
        printJson(api.entity.setNbt(uuid, parseJsonArg(nbt), dim, replace))
    }
}

class EntityTeleportCmd : CliktCommand(name = "teleport", help = "teleport an entity") {
    private val uuid by option("--uuid").required()
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")
    private val toDim by option("--to-dim")
    private val yaw by option("--yaw").float()
    private val pitch by option("--pitch").float()
    private val includeNbt by option("--include-nbt").flag()

    override fun run() = withApi { api ->
        printJson(api.entity.teleport(uuid, listOf(x, y, z), dim, toDim, yaw, pitch, includeNbt))
    }
}

class EntityRemoveCmd : CliktCommand(name = "remove", help = "remove an entity") {
    private val uuid by option("--uuid").required()
    private val dim by option("--dim")
    private val includeNbt by option("--include-nbt").flag()

    override fun run() = withApi { api ->
        printJson(api.entity.remove(uuid, dim, includeNbt))
    }
}

class EntityListItemsCmd : CliktCommand(name = "list-items", help = "list dropped item entities in a box") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val dim by option("--dim")
    private val item by option("--item")
    private val includeNbt by option("--include-nbt").flag()

    override fun run() = withApi { api ->
        printJson(api.entity.listItems(parseBox(from, to), dim, item, includeNbt))
    }
}

class EntityCollectItemsCmd : CliktCommand(name = "collect-items", help = "collect dropped item entities in a box") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val dim by option("--dim")
    private val item by option("--item")
    private val remove by option("--remove").flag()
    private val includeNbt by option("--include-nbt").flag()

    override fun run() = withApi { api ->
        printJson(api.entity.collectItems(parseBox(from, to), dim, item, remove, includeNbt))
    }
}

// ---- fixture group ----

class FixtureCommands : CliktCommand(name = "fixture", help = "capture and load block-region fixtures as JSON") {
    override fun run() = Unit
}

class FixtureCaptureCmd : CliktCommand(name = "capture", help = "capture a block-region fixture") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val dim by option("--dim")
    private val includeNbt by option("--include-nbt").flag()

    override fun run() = withApi { api ->
        printJson(api.fixture.capture(parseBox(from, to), dim, includeNbt))
    }
}

class FixtureLoadCmd : CliktCommand(name = "load", help = "load a fixture JSON into the world") {
    private val fixture by option("--fixture", help = "fixture JSON or @file").required()
    private val origin by option("--origin", help = "x,y,z placement origin")
    private val dim by option("--dim")
    private val flags by option("--flags").int()

    override fun run() = withApi { api ->
        printJson(api.fixture.load(parseJsonArg(fixture), origin?.let { parseTriplet(it, "origin") }, dim, flags))
    }
}

fun entitySubcommands() = listOf(
    EntitySpawnCmd(), EntityGetNbtCmd(), EntitySetNbtCmd(), EntityTeleportCmd(),
    EntityRemoveCmd(), EntityListItemsCmd(), EntityCollectItemsCmd(),
)

fun fixtureSubcommands() = listOf(FixtureCaptureCmd(), FixtureLoadCmd())
