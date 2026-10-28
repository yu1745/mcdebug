package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

class GuiActCmd : CliktCommand(
    name = "act",
    help = "one round trip: run a gui action, wait until the GUI settled, optionally return gui screen + a screenshot + new problems",
    epilog = """
**The iteration step in one call** (`gui.act`). Instead of click → screen → screenshot → logs as four commands, ask for all of it at once.

**Options**
- `--action NAME` - `click|key|type|drag|close|openBlock|reinit|none` (default none: just settle and look)
- `--params JSON` - the action's parameters, exactly as for `raw gui.<action>` (or `@file.json`)
- `--shot gui|window` / `--annotate` / `--draw-log` / `--screen` - what to return afterwards
- `--settle-ticks N` - upper bound for the settle wait (default 40); `--no-settle` skips it

**What comes back**
- `ok`, `result` (or `error` with code/message/data - the views are still returned), `settle`, `screen`, `screenshot`, `problems` (WARN/ERROR logged meanwhile), `screenFault`

**Examples**
- `mcdebug gui act --action click --params '{"slot":0}' --shot gui --annotate`
- `mcdebug gui act --action type --params '{"text":"64","clear":true}' --screen`
- `mcdebug gui act --shot gui --draw-log` (no action: settle, then look)
""".trimIndent(),
) {
    private val action by option("--action", help = "click|key|type|drag|close|openBlock|reinit|none").default("none")
    private val params by option("--params", metavar = "JSON", help = "action parameters as JSON or @file")
    private val noSettle by option("--no-settle", help = "do not wait for the GUI to settle").flag()
    private val settleTicks by option("--settle-ticks", help = "upper bound for settling (default 40)").int()
    private val follow by FollowUpOptions()

    override fun run() {
        follow.validate()
        val text = params?.let { if (it.startsWith("@")) Files.readString(Path.of(it.drop(1))) else it } ?: "{}"
        val parsed = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            throw UsageError("--params must be a JSON object: ${e.message}")
        }
        val request = JsonObject().apply {
            addProperty("action", action)
            add("params", parsed)
            if (noSettle) addProperty("settle", false) else settleTicks?.let { add("settle", JsonObject().apply { addProperty("maxTicks", it) }) }
            addProperty("screen", follow.screen)
            follow.shot?.let { add("screenshot", ScreenshotOptions(mode = it, annotate = follow.annotate, drawLog = follow.drawLog).toJson()) }
        }
        withGuiApi { api -> printJson(api.rpc.call("gui.act", request)) }
    }
}

class GuiSettleCmd : CliktCommand(
    name = "settle",
    help = "wait until the server answered everything sent so far and slots/carried/widgets stayed unchanged (passive)",
    epilog = """
**Replaces fixed sleeps.** Sends a server barrier (a command-suggestion round trip, handled after every earlier packet), then waits until the GUI fingerprint (slots, carried stack, Screen, widgets) is unchanged for `--quiet-ticks` natural ticks. Container data slots (progress/energy) are not part of the fingerprint, so a running machine still settles.

- `--max-ticks N` - upper bound (default 40); `settled:false` when reached
- `--quiet-ticks N` - required stable ticks after the barrier (default 2)
- use it after server-plane writes (`be.setNbt`, `inv.setSlot`) before looking at the GUI
""".trimIndent(),
) {
    private val maxTicks by option("--max-ticks", help = "upper bound in client ticks (default 40)").int()
    private val quietTicks by option("--quiet-ticks", help = "stable ticks required after the barrier (default 2)").int()
    override fun run() = withGuiApi { api -> printJson(api.gui.settle(maxTicks, quietTicks)) }
}

class GuiReinitCmd : CliktCommand(
    name = "reinit",
    help = "re-run the open Screen's init() (layout code after a hotswap) and clear its render fault; keeps Screen and menu",
) {
    override fun run() = withGuiApi { api -> printJson(api.gui.reinit()) }
}

class GuiProblemsCmd : CliktCommand(
    name = "problems",
    help = "WARN/ERROR log entries of the client JVM (vanilla, mods, integrated server) and caught render faults",
    epilog = """
**Instead of grepping latest.log.** Every entry has a `seq`; pass the returned `next` as `--since` to read only what is new.

- `--level warn|error` (default warn = WARN and above)
- `--limit N` (default 100, max 500); `truncated:true` means older matching entries were skipped
- `gui act` already returns the problems logged during its own call
""".trimIndent(),
) {
    private val since by option("--since", help = "first seq to return (default 1 = everything retained)").long()
    private val level by option("--level", help = "warn | error").choice("warn", "error").default("warn")
    private val limit by option("--limit", help = "max entries (1..500, default 100)").int()
    private val brief by option("--brief", help = "one line per entry").flag()
    override fun run() = withGuiApi { api ->
        val r = api.gui.problems(since, level, limit)
        if (!brief) {
            printJson(r)
            return@withGuiApi
        }
        val o = r.asJsonObject
        o.getAsJsonArray("entries").forEach { e ->
            val x = e.asJsonObject
            println("#${x.get("seq")} t${x.get("clientTick")} ${x.get("level").asString} ${x.get("source").asString}: ${x.get("message").asString}")
        }
        println("next=${o.get("next")} truncated=${o.get("truncated")}")
    }
}

class GuiHotswapCmd : CliktCommand(
    name = "hotswap",
    help = "redefine changed classes in the running client from compiled output dirs (no restart), then re-init the open Screen",
    epilog = """
**Code → pixels in seconds.** After editing GUI code: `./gradlew classes` (or `compileClientKotlin`), then

- `mcdebug gui hotswap --classes build/classes/kotlin/client --classes build/classes/java/client`

**How it works**
- the first call attaches a one-class JDK agent to the client JVM (same machine and user); later calls reuse it
- classes changed since the last successful hotswap (or since the client started) are redefined through Fabric's and Mixin's transformers; classes that were never loaded need nothing
- the open Screen is re-initialised (`--no-reinit` to skip), so changed `init()` layout takes effect

**Limits**
- standard HotSpot accepts method-body changes only; added/removed fields or methods fail with a hint, unless the client runs on a JetBrains Runtime with `-XX:+AllowEnhancedClassRedefinition`
- existing objects keep their field values; reopen the GUI when constructor logic changed
- resources (textures, lang) still go through `gui reload-resources`
""".trimIndent(),
) {
    private val classes by option("--classes", metavar = "DIR", help = "class output directory (repeatable)").multiple(required = true)
    private val all by option("--all", help = "consider every class in the dirs, not only recently changed ones").flag()
    private val since by option("--since", help = "epoch millis: only class files modified after this").long()
    private val noReinit by option("--no-reinit", help = "do not re-run the open Screen's init()").flag()
    private val attachOnly by option("--attach-only", help = "only attach the agent, redefine nothing").flag()

    override fun run() = withGuiApi { api ->
        val status = api.gui.status().asJsonObject
        val available = status.getAsJsonObject("hotswap")?.get("available")?.asBoolean
            ?: throw CliExit(3, "the client's mcdebug predates gui.hotswap; deploy the current jar")
        var attached = false
        if (!available) {
            val pid = status.getAsJsonObject("process")?.get("pid")?.asLong
                ?: throw CliExit(2, "gui.status reports no client pid")
            try {
                AgentAttach.attach(pid)
            } catch (e: IllegalStateException) {
                throw CliExit(2, e.message ?: "agent attach failed")
            }
            attached = true
        }
        if (attachOnly) {
            printJson(JsonObject().apply { addProperty("ok", true); addProperty("attached", attached) })
            return@withGuiApi
        }
        val dirs = classes.map { Path.of(it).toAbsolutePath().normalize().toString() }
        val r = api.gui.hotswap(dirs, since, if (all) true else null, if (noReinit) false else null).asJsonObject
        r.addProperty("attached", attached)
        printJson(r)
        if (r.get("ok")?.asBoolean == false) throw CliExit(2, "some classes could not be redefined (see failed)")
    }
}
