package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

// ---- craft group ----

class CraftCommands : CliktCommand(name = "craft", help = "simulate crafting through the server RecipeManager") {
    override fun run() = Unit
}

/** "slot=item@count" 或 "item@count" 或 "null" → grid 项。 */
private fun parseGridSpec(specs: List<String>): List<Any?> = specs.map { s ->
    when {
        s == "null" || s == "-" -> null
        else -> {
            val slotIdx = s.indexOf('=')
            val body = if (slotIdx >= 0) s.substring(slotIdx + 1) else s
            val at = body.lastIndexOf('@')
            val item = if (at >= 0) body.substring(0, at) else body
            val count = if (at >= 0) body.substring(at + 1).toIntOrNull() ?: 1 else 1
            mapOf("item" to item, "count" to count)
        }
    }
}

class CraftDoCmd : CliktCommand(name = "do", help = "craft a 3x3 grid (up to 9 args); each arg = item@count or null, parsed BY POSITION (left-to-right, top-to-bottom) — a 'slot=' prefix is stripped and IGNORED, not interpreted as a slot; --recipe-id bypasses grid matching and forces a specific recipe") {
    private val grid by argument("grid", help = "grid slots left-to-right, top-to-bottom; use 'null' for empty").multiple()
    private val recipeId by option("--recipe-id")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.craft.craft(parseGridSpec(grid), recipeId, dim))
    }
}

class CraftFindCmd : CliktCommand(name = "find", help = "list recipes matching a grid") {
    private val grid by argument("grid", help = "grid slots; use 'null' for empty").multiple()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.craft.find(parseGridSpec(grid), dim))
    }
}

// ---- scan group ----

class ScanCommands : CliktCommand(name = "scan", help = "box scanning; return shapes differ: find-blocks = flat array of [x,y,z]; count-by-block = {\"id\":count} map; find-entities = array of entity objects (searched box expanded by +1 margin on each side)") {
    override fun run() = Unit
}

class ScanFindBlocksCmd : CliktCommand(name = "find-blocks", help = "find all positions of a block id in a box; returns a FLAT array of [x,y,z] positions (no per-block objects)") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val block by option("--block").required()
    private val count by option("--count", help = "return the total count instead of the position list").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.scan.findBlocks(parseBox(from, to), block, count, dim))
    }
}

class ScanCountByBlockCmd : CliktCommand(name = "count-by-block", help = "count blocks by id in a box; returns a {\"blockId\": count} map (not a list)") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.scan.countByBlock(parseBox(from, to), dim))
    }
}

class ScanFindEntitiesCmd : CliktCommand(name = "find-entities", help = "find entities in a box; the searched box is the given box EXPANDED by 1 block on each side; returns an array of entity objects") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val type by option("--type")
    private val includeNbt by option("--include-nbt").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.scan.findEntities(parseBox(from, to), type, includeNbt, dim))
    }
}

// ---- snapshot group ----

class SnapshotCommands : CliktCommand(name = "snapshot", help = "capture and diff world snapshots") {
    override fun run() = Unit
}

class SnapshotCaptureCmd : CliktCommand(name = "capture", help = "capture a snapshot of a box") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val include by option("--include", help = "comma list: block,inventory,entity,fluid,energy").default("block")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        val opts = mapOf(
            "box" to parseBox(from, to),
            "include" to include.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            "dim" to dim,
        )
        printJson(api.snapshot.capture(opts))
    }
}

class SnapshotDiffCmd : CliktCommand(name = "diff", help = "structural diff between two snapshots; by default /tick and /time ALWAYS change between captures, so equal:false is reported even when no real content changed — pass --ignore-meta to filter those metadata fields out") {
    private val before by option("--before", help = "snapshot JSON or @file").required()
    private val after by option("--after", help = "snapshot JSON or @file").required()
    private val ignoreMeta by option("--ignore-meta", help = "filter /tick, /time metadata changes from the diff (server >= 0.6.0)").flag()

    override fun run() = withApi { api ->
        printJson(api.snapshot.diff(parseJsonArg(before), parseJsonArg(after), ignoreMeta))
    }
}

// ---- trace group ----

class TraceCommands : CliktCommand(name = "trace", help = "capture snapshots on natural server ticks") {
    override fun run() = Unit
}

class TraceStartCmd : CliktCommand(name = "start", help = "start a tick trace on a box; --max-ticks auto-stops the trace after N ticks (server >= 0.6.0); without it the trace runs until 'trace stop'") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val intervalTicks by option("--interval-ticks").int().default(1)
    private val maxTicks by option("--max-ticks", help = "auto-stop after this many ticks; 0 / unset = run until 'trace stop' (no auto-stop)").int()
    private val include by option("--include").default("block")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        val opts = mapOf(
            "box" to parseBox(from, to),
            "intervalTicks" to intervalTicks,
            "maxTicks" to maxTicks,
            "include" to include.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            "dim" to dim,
        )
        printJson(api.trace.start(opts))
    }
}

class TraceGetCmd : CliktCommand(name = "get", help = "read a trace result") {
    private val traceId by option("--trace-id").required()

    override fun run() = withApi { api ->
        printJson(api.trace.get(traceId))
    }
}

class TraceStopCmd : CliktCommand(name = "stop", help = "stop and read a trace") {
    private val traceId by option("--trace-id").required()

    override fun run() = withApi { api ->
        printJson(api.trace.stop(traceId))
    }
}

fun craftSubcommands() = listOf(CraftDoCmd(), CraftFindCmd())
fun scanSubcommands() = listOf(ScanFindBlocksCmd(), ScanCountByBlockCmd(), ScanFindEntitiesCmd())
fun snapshotSubcommands() = listOf(SnapshotCaptureCmd(), SnapshotDiffCmd())
fun traceSubcommands() = listOf(TraceStartCmd(), TraceGetCmd(), TraceStopCmd())
