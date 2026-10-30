package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.google.gson.JsonObject
import com.mcdebug.cli.session.StartRequest
import java.nio.file.Path

class SessionCommands : CliktCommand(
    name = "session",
    help = "manage named local game clients; no implicit current session, no remote server management",
    epilog = "Example: mcdebug session start dev --project . --world TestWorld",
) {
    override fun run() = Unit
}

/** Operation injection keeps CLI flag/output tests independent of game and process launch. */
internal class SessionStartCmd(
    private val start: (McDebugCli, StartRequest) -> JsonObject = { cli, request -> cli.sessionManager().start(request) },
) : CliktCommand(
    name = "start",
    help = "launch the project's Gradle client and wait for client/world readiness; failure or timeout exits 1 (game data is never deleted)",
    epilog = "Example: mcdebug session start dev --project . --task runClient --world TestWorld --render demand",
) {
    private val name by argument("NAME", help = "local session name")
    private val project by option("--project", metavar = "PATH", help = "Gradle project directory (default: current directory)").default(".")
    private val task by option("--task", help = "Gradle runClient task (e.g. :core:runClient)").default("runClient")
    private val gameDir by option("--game-dir", metavar = "PATH", help = "game directory (default: project/run)")
    private val world by option("--world", metavar = "SAVE", help = "existing saves/ directory name, not display name; omitted = main menu")
    private val render by option("--render", help = "client render mode").choice("continuous", "demand", "gui-only").default("demand")
    private val display by option("--display", help = "DISPLAY for the child client; omitted = inherited")
    private val width by option("--width", help = "window width (default 854; the project's loom.runs.client programArgs win, and the record repeats what was requested)").int().default(854)
    private val height by option("--height", help = "window height (default 480; see --width)").int().default(480)
    private val waitSeconds by option("--wait-seconds", help = "finite startup/readiness deadline in seconds").long().default(180L)

    override fun run() {
        if (width <= 0 || height <= 0) throw UsageError("--width and --height must be positive")
        if (waitSeconds < 0) throw UsageError("--wait-seconds must not be negative")
        val result = start(rootCli(), StartRequest(
            name = name, project = Path.of(project), task = task, gameDir = gameDir?.let { Path.of(it) },
            world = world, render = render, display = display, width = width, height = height, waitSeconds = waitSeconds,
        ))
        printJson(result)
        val state = result.get("state")?.asString
        if (state !in setOf("client_ready", "world_ready")) {
            throw CliExit(1, result.get("lastError")?.takeUnless { it.isJsonNull }?.asString ?: "session start: $state")
        }
    }
}

internal class SessionAttachCmd : CliktCommand(
    name = "attach",
    help = "register an existing local client after identity verification; attached sessions cannot be stopped (even with --force)",
    epilog = "Example: mcdebug session attach desktop --game-dir ./run",
) {
    private val name by argument("NAME", help = "local session name")
    private val gameDir by option("--game-dir", metavar = "PATH", help = "existing client's game directory").required()
    override fun run() = printJson(rootCli().sessionManager().attach(name, Path.of(gameDir)))
}

internal class SessionListCmd : CliktCommand(
    name = "list", help = "list registered sessions and their current states",
    epilog = "Example: mcdebug session list",
) {
    override fun run() = printJson(rootCli().sessionManager().list())
}

internal class SessionStatusCmd : CliktCommand(
    name = "status", help = "inspect session state and identity; client readiness is not world readiness",
    epilog = "Example: mcdebug session status dev",
) {
    private val name by argument("NAME", help = "local session name")
    override fun run() = printJson(rootCli().sessionManager().status(name))
}

internal class SessionLogsCmd : CliktCommand(
    name = "logs", help = "print a finite UTF-8 log tail (does not follow)",
    epilog = "Example: mcdebug session logs dev --lines 80",
) {
    private val name by argument("NAME", help = "local session name")
    private val lines by option("--lines", help = "number of trailing lines").int().default(80)
    override fun run() {
        if (lines < 0) throw UsageError("--lines must not be negative")
        echo(rootCli().sessionManager().logs(name, lines), trailingNewline = false)
    }
}

internal class SessionStopCmd(
    private val stop: (McDebugCli, String, Boolean, Long) -> JsonObject = { cli, name, force, wait ->
        cli.sessionManager().stop(name, force, wait)
    },
) : CliktCommand(
    name = "stop", help = "request normal shutdown of an owned client; --force only terminates an identity-matching owned client, never attached clients or shared Gradle daemons",
    epilog = "Example: mcdebug session stop dev --wait-seconds 30",
) {
    private val name by argument("NAME", help = "owned session name")
    private val force by option("--force", help = "allow forced termination of the verified owned client only").flag()
    private val waitSeconds by option("--wait-seconds", help = "shutdown deadline in seconds").long().default(30L)
    override fun run() {
        if (waitSeconds < 0) throw UsageError("--wait-seconds must not be negative")
        val result = stop(rootCli(), name, force, waitSeconds)
        printJson(result)
        val state = result.get("state")?.asString
        if (state != "stopped") {
            throw CliExit(1, result.get("lastError")?.takeUnless { it.isJsonNull }?.asString ?: "session stop: $state")
        }
    }
}

internal class SessionRemoveCmd : CliktCommand(
    name = "remove", help = "forget a registry record: attached ones any time (the game keeps running), owned ones once stopped/failed/dead",
    epilog = "Example: mcdebug session remove old-attach",
) {
    private val name by argument("NAME", help = "local session name")
    override fun run() = printJson(rootCli().sessionManager().remove(name))
}

fun sessionSubcommands(): List<CliktCommand> = listOf(
    SessionStartCmd(), SessionAttachCmd(), SessionListCmd(), SessionStatusCmd(), SessionLogsCmd(), SessionStopCmd(),
    SessionRemoveCmd(),
)
