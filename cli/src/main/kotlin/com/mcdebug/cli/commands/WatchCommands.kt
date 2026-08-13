package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.google.gson.JsonElement

/**
 * watch 组 —— 纯 CLI 层实现（无需服务端改动）：记录初值，按 --interval-ticks
 * 轮询（每 tick 按 50ms 估算），检测到变化即打印新值并退出 0；超时退出 1。
 * 一条命令只开一个连接，整个轮询期间复用。
 */
class WatchCommands : CliktCommand(
    name = "watch",
    help = "poll a value until it changes, then print the new value and exit 0; " +
        "polling-based (not event-driven): checks every --interval-ticks (default 20), " +
        "gives up after --timeout-ticks (default 1200 = 1 min @20tps; pass 0 for no timeout)",
) {
    override fun run() = Unit
}

class WatchBlockCmd : CliktCommand(name = "block", help = "watch a block's state at <x,y,z> until it changes") {
    private val pos by argument("pos", help = "x,y,z")
    private val timeoutTicks by option("--timeout-ticks", help = "give up after N ticks (default 1200; 0 = never)").int().default(1200)
    private val intervalTicks by option("--interval-ticks", help = "poll every N ticks (default 20)").int().default(20)
    private val dim by option("--dim")

    override fun run() {
        withClient(rootCli()) { c ->
            val api = DebugApi(c)
            val p = parseTriplet(pos, "pos")
            watchLoop(timeoutTicks, intervalTicks) { api.world.getBlock(p, dim, false) }
        }
    }
}

class WatchFieldCmd : CliktCommand(name = "field", help = "watch one NBT path inside the block entity at <x,y,z> (reuses be get-field)") {
    private val bePos by argument("be-pos", help = "x,y,z of the block entity")
    private val path by argument("path", help = "NBT path, e.g. Energy or Items[0].count")
    private val timeoutTicks by option("--timeout-ticks", help = "give up after N ticks (default 1200; 0 = never)").int().default(1200)
    private val intervalTicks by option("--interval-ticks", help = "poll every N ticks (default 20)").int().default(20)
    private val dim by option("--dim")

    override fun run() {
        withClient(rootCli()) { c ->
            val api = DebugApi(c)
            val p = parseTriplet(bePos, "be-pos")
            watchLoop(timeoutTicks, intervalTicks) {
                api.be.getField(p, path, dim).asJsonObject.get("value")
            }
        }
    }
}

class WatchEntityCmd : CliktCommand(name = "entity", help = "watch one NBT path inside entity <uuid> (fetches entity NBT and extracts the path client-side)") {
    private val uuid by argument("uuid")
    private val path by argument("path", help = "NBT path, e.g. Health or Pos[0]")
    private val timeoutTicks by option("--timeout-ticks", help = "give up after N ticks (default 1200; 0 = never)").int().default(1200)
    private val intervalTicks by option("--interval-ticks", help = "poll every N ticks (default 20)").int().default(20)
    private val dim by option("--dim")

    override fun run() {
        withClient(rootCli()) { c ->
            val api = DebugApi(c)
            watchLoop(timeoutTicks, intervalTicks) {
                val nbt = api.entity.getNbt(uuid, dim).asJsonObject.get("nbt")
                jsonGetPath(nbt, path)
                    ?: throw CliExit(1, "path '$path' not found in entity NBT")
            }
        }
    }
}

fun watchSubcommands() = listOf(WatchBlockCmd(), WatchFieldCmd(), WatchEntityCmd())

/** 通用轮询循环：打印初值后每 intervalTicks（50ms/tick）读一次，变化即打印并返回（退出 0）；超时抛 CliExit(1)。 */
private fun watchLoop(timeoutTicks: Int, intervalTicks: Int, read: () -> JsonElement) {
    require(intervalTicks > 0) { "--interval-ticks must be >= 1" }
    val initial = read()
    println("initial: ${gson.toJson(initial)}")
    var elapsed = 0
    while (true) {
        Thread.sleep(intervalTicks * 50L)
        elapsed += intervalTicks
        val cur = read()
        if (cur != initial) {
            println("changed after $elapsed ticks:")
            printJson(cur)
            return
        }
        if (timeoutTicks > 0 && elapsed >= timeoutTicks) {
            throw CliExit(1, "watch timed out after $elapsed ticks (no change)")
        }
    }
}
