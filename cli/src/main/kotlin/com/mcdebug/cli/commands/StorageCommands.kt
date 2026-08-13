package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.google.gson.JsonElement

// ---- shared target/resource parsing (mirrors TS storage.ts) ----

fun parseBlockTarget(x: Int?, y: Int?, z: Int?, dim: String?, what: String): JsonElement {
    if (x == null || y == null || z == null) throw IllegalArgumentException("$what requires --x, --y, --z")
    return gson.toJsonTree(mapOf("kind" to "block", "pos" to listOf(x, y, z), "dim" to dim))
}

fun parseTargetArg(target: String?, x: Int?, y: Int?, z: Int?, dim: String?, what: String): JsonElement =
    if (target != null) parseJsonArg(target) else parseBlockTarget(x, y, z, dim, what)

fun parseResourceArg(resource: String?, item: String?, fluid: String?, energy: Boolean, nbt: String?): JsonElement {
    if (resource != null) return parseJsonArg(resource)
    val nbtEl = nbt?.let { parseJsonArg(it) }
    return when {
        item != null -> gson.toJsonTree(mapOf("kind" to "item", "item" to item, "nbt" to nbtEl))
        fluid != null -> gson.toJsonTree(mapOf("kind" to "fluid", "fluid" to fluid, "nbt" to nbtEl))
        energy -> gson.toJsonTree(mapOf("kind" to "energy"))
        else -> throw IllegalArgumentException("resource required: use --resource, --item, --fluid, or --energy")
    }
}

fun defaultHandleFor(resource: JsonElement): String = when (resource.asJsonObject.get("kind").asString) {
    "item" -> "fabric:item"
    "fluid" -> "fabric:fluid"
    else -> "teamreborn:energy"
}

private fun normalizeSide(side: String?): String? = if (side == "null") null else side

// ---- storage group ----

class StorageCommands : CliktCommand(name = "storage", help = "generic item/fluid/energy storage operations") {
    override fun run() = Unit
}

class StorageListCmd : CliktCommand(name = "list", help = "list storage handles at a target") {
    private val target by option("--target", help = "Target JSON or @file")
    private val x by option("--x").int()
    private val y by option("--y").int()
    private val z by option("--z").int()
    private val dim by option("--dim")
    private val side by option("--side", help = "up|down|north|south|east|west|null")

    override fun run() = withApi { api ->
        printJson(api.storage.list(parseTargetArg(target, x, y, z, dim, "storage list"), normalizeSide(side)))
    }
}

class StorageGetCmd : CliktCommand(name = "get", help = "read one storage handle") {
    private val handle by option("--handle").required()
    private val target by option("--target")
    private val x by option("--x").int()
    private val y by option("--y").int()
    private val z by option("--z").int()
    private val dim by option("--dim")
    private val side by option("--side")

    override fun run() = withApi { api ->
        printJson(api.storage.get(parseTargetArg(target, x, y, z, dim, "storage get"), handle, normalizeSide(side)))
    }
}

class StorageInsertCmd : CliktCommand(name = "insert", help = "insert item/fluid/energy into a target") {
    private val handle by option("--handle")
    private val amount by option("--amount").int().required()
    private val target by option("--target")
    private val x by option("--x").int()
    private val y by option("--y").int()
    private val z by option("--z").int()
    private val dim by option("--dim")
    private val side by option("--side")
    private val resource by option("--resource", help = "StorageResource JSON or @file")
    private val item by option("--item")
    private val fluid by option("--fluid")
    private val energy by option("--energy").flag()
    private val nbt by option("--nbt")
    private val simulate by option("--simulate").flag()

    override fun run() = withApi { api ->
        val res = parseResourceArg(resource, item, fluid, energy, nbt)
        printJson(api.storage.insert(
            parseTargetArg(target, x, y, z, dim, "storage insert"),
            handle ?: defaultHandleFor(res), res, amount.toLong(), normalizeSide(side), simulate,
        ))
    }
}

class StorageExtractCmd : CliktCommand(name = "extract", help = "extract item/fluid/energy from a target") {
    private val handle by option("--handle")
    private val amount by option("--amount").int().required()
    private val target by option("--target")
    private val x by option("--x").int()
    private val y by option("--y").int()
    private val z by option("--z").int()
    private val dim by option("--dim")
    private val side by option("--side")
    private val resource by option("--resource")
    private val item by option("--item")
    private val fluid by option("--fluid")
    private val energy by option("--energy").flag()
    private val nbt by option("--nbt")
    private val simulate by option("--simulate").flag()

    override fun run() = withApi { api ->
        val res = parseResourceArg(resource, item, fluid, energy, nbt)
        printJson(api.storage.extract(
            parseTargetArg(target, x, y, z, dim, "storage extract"),
            handle ?: defaultHandleFor(res), res, amount.toLong(), normalizeSide(side), simulate,
        ))
    }
}

class StorageTransferCmd : CliktCommand(name = "transfer", help = "move resource between two targets") {
    private val from by option("--from", help = "x,y,z").required()
    private val to by option("--to", help = "x,y,z").required()
    private val fromTarget by option("--from-target", help = "Target JSON or @file")
    private val toTarget by option("--to-target", help = "Target JSON or @file")
    private val fromDim by option("--from-dim")
    private val toDim by option("--to-dim")
    private val resource by option("--resource")
    private val item by option("--item")
    private val fluid by option("--fluid")
    private val energy by option("--energy").flag()
    private val nbt by option("--nbt")
    private val amount by option("--amount").int().required()
    private val fromSide by option("--from-side")
    private val toSide by option("--to-side")
    private val simulate by option("--simulate").flag()

    override fun run() = withApi { api ->
        val fromPos = parseTriplet(from, "from")
        val toPos = parseTriplet(to, "to")
        val fromEl = fromTarget?.let { parseJsonArg(it) } ?: gson.toJsonTree(mapOf("kind" to "block", "pos" to fromPos, "dim" to fromDim))
        val toEl = toTarget?.let { parseJsonArg(it) } ?: gson.toJsonTree(mapOf("kind" to "block", "pos" to toPos, "dim" to toDim))
        val res = parseResourceArg(resource, item, fluid, energy, nbt)
        printJson(api.storage.transfer(fromEl, toEl, res, amount.toLong(), normalizeSide(fromSide), normalizeSide(toSide), simulate))
    }
}

// ---- fluid group ----

class FluidCommands : CliktCommand(name = "fluid", help = "fluid tank operations") {
    override fun run() = Unit
}

class FluidInfoCmd : CliktCommand(name = "info", help = "tank info at a position") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val side by option("--side")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.fluid.info(listOf(x, y, z), side, dim))
    }
}

class FluidGetCmd : CliktCommand(name = "get", help = "read one tank part") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val side by option("--side")
    private val index by option("--index", help = "tank part index").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.fluid.get(listOf(x, y, z), side, index, dim))
    }
}

class FluidInsertCmd : CliktCommand(name = "insert", help = "insert fluid into a tank; amount unit is DROPLETS (81000 = 1 bucket = 3 bottles)") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val fluid by option("--fluid").required()
    private val amount by option("--amount").int().required()
    private val side by option("--side")
    private val index by option("--index", help = "tank part index (required when the storage has multiple tanks)").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.fluid.insert(listOf(x, y, z), fluid, amount.toLong(), side, index, dim))
    }
}

class FluidExtractCmd : CliktCommand(name = "extract", help = "extract fluid from a tank; amount unit is DROPLETS (81000 = 1 bucket = 3 bottles); extraction works in WHOLE-BOTTLE units (27000) — finer amounts are not extracted") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val amount by option("--amount").int().required()
    private val side by option("--side")
    private val index by option("--index", help = "tank part index (required when the storage has multiple tanks)").int()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.fluid.extract(listOf(x, y, z), amount.toLong(), side, index, dim))
    }
}

fun storageSubcommands() = listOf(
    StorageListCmd(), StorageGetCmd(), StorageInsertCmd(), StorageExtractCmd(), StorageTransferCmd(),
)

fun fluidSubcommands() = listOf(FluidInfoCmd(), FluidGetCmd(), FluidInsertCmd(), FluidExtractCmd())
