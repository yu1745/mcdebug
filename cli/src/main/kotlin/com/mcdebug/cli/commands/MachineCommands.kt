package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
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

/**
 * Grid args → the 9 grid entries (row-major, slot 0 = top-left). `item@count` / `null` / `-`
 * fill the next slot; `N=item@count` puts it in slot N (0..8) and continues after it.
 */
internal fun parseGridSpec(specs: List<String>): List<Any?> {
    val grid = arrayOfNulls<Any?>(9).toMutableList()
    val used = BooleanArray(9)
    var next = 0
    for (s in specs) {
        val eq = s.indexOf('=')
        val slot = if (eq >= 0) {
            s.substring(0, eq).toIntOrNull()?.takeIf { it in 0..8 }
                ?: throw UsageError("grid arg '$s': slot before '=' must be 0..8")
        } else next
        if (slot > 8) throw UsageError("more than 9 grid args")
        if (used[slot]) throw UsageError("grid slot $slot given twice")
        used[slot] = true
        next = slot + 1
        val body = if (eq >= 0) s.substring(eq + 1) else s
        grid[slot] = if (body == "null" || body == "-") null else {
            val at = body.lastIndexOf('@')
            val item = if (at >= 0) body.substring(0, at) else body
            val count = if (at >= 0) body.substring(at + 1).toIntOrNull()
                ?: throw UsageError("grid arg '$s': count after '@' must be an integer") else 1
            mapOf("item" to item, "count" to count)
        }
    }
    return grid
}

class CraftDoCmd : CliktCommand(name = "do", help = "craft a 3x3 grid; args fill slots left-to-right, top-to-bottom (item@count, or null/- for empty); N=item@count puts it in slot N (0..8); --recipe-id bypasses grid matching and forces a specific recipe") {
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

class ScanCommands : CliktCommand(name = "scan", help = "box scanning; --from/--to are block corners, both inclusive. Results: find-blocks {positions:[[x,y,z]...], count?}; count-by-block {counts:{id:n}}; find-entities {entities:[...], count}") {
    override fun run() = Unit
}

class ScanFindBlocksCmd : CliktCommand(name = "find-blocks", help = "find all positions of a block id in a box; returns {positions:[[x,y,z]...]}, plus count with --count") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val block by option("--block").required()
    private val count by option("--count", help = "also return the number of matches").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.scan.findBlocks(parseBox(from, to), block, count, dim))
    }
}

class ScanCountByBlockCmd : CliktCommand(name = "count-by-block", help = "count blocks by id in a box; returns {counts:{blockId: n}}") {
    private val from by option("--from").required()
    private val to by option("--to").required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.scan.countByBlock(parseBox(from, to), dim))
    }
}

class ScanFindEntitiesCmd : CliktCommand(name = "find-entities", help = "find entities touching the blocks of a box (same box semantics as entity list); returns {entities:[...], count}") {
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

class SnapshotDiffCmd : CliktCommand(name = "diff", help = "structural diff between two snapshots; the /tick and /time metadata (different in every capture) are left out unless --include-meta") {
    private val before by option("--before", help = "snapshot JSON or @file").required()
    private val after by option("--after", help = "snapshot JSON or @file").required()
    private val includeMeta by option("--include-meta", help = "also diff /tick and /time").flag()
    private val ignoreMeta by option("--ignore-meta", hidden = true, help = "default since 0.6.3; kept for old scripts").flag()

    override fun run() = withApi { api ->
        // Always explicit: servers before 0.6.3 compared metadata unless told otherwise.
        printJson(api.snapshot.diff(parseJsonArg(before), parseJsonArg(after), ignoreMeta || !includeMeta))
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
