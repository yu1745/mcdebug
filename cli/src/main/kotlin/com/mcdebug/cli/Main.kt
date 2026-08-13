package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.int
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        McDebugCli()
            .subcommands(
                StatusCmd(), RawCmd(), ReplCmd(),
                WorldCommands().subcommands(*worldSubcommands().toTypedArray()),
                BeCommands().subcommands(*beSubcommands().toTypedArray()),
                InvCommands().subcommands(*invSubcommands().toTypedArray()),
                WaitCommands().subcommands(*waitSubcommands().toTypedArray()),
                StorageCommands().subcommands(*storageSubcommands().toTypedArray()),
                FluidCommands().subcommands(*fluidSubcommands().toTypedArray()),
                CraftCommands().subcommands(*craftSubcommands().toTypedArray()),
                ScanCommands().subcommands(*scanSubcommands().toTypedArray()),
                SnapshotCommands().subcommands(*snapshotSubcommands().toTypedArray()),
                TraceCommands().subcommands(*traceSubcommands().toTypedArray()),
                EntityCommands().subcommands(*entitySubcommands().toTypedArray()),
                FixtureCommands().subcommands(*fixtureSubcommands().toTypedArray()),
                ScreenCommands().subcommands(*screenSubcommands().toTypedArray()),
                RedstoneCommands().subcommands(*redstoneSubcommands().toTypedArray()),
                ReflectCommands().subcommands(*reflectSubcommands().toTypedArray()),
                JarCmd(),
            )
            .main(args)
    } catch (e: RpcException) {
        val data = e.data?.let { " data=${it}" } ?: ""
        System.err.println("error [${e.rpcCode}] ${e.message}$data")
        exitProcess(if (e.rpcCode in -32099..-32000) 2 else 1)
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

class McDebugCli : CliktCommand(
    name = "mcdebug",
    help = "CLI for the mcdebug Minecraft debug server mod (JSON-RPC over unix socket or TCP)",
) {
    init {
        versionOption(McDebugCli::class.java.`package`.implementationVersion ?: "dev")
    }

    val socket by option("--socket", help = "unix socket path (overrides MCDEBUG_SOCKET and discovery file)")
    val tcp by option(
        "--tcp",
        metavar = "HOST[:PORT]",
        help = "connect over TCP to HOST[:PORT] (default port 25580) — cross-machine access",
    )
    val host by option("--host", help = "TCP host (must be used together with --port)")
    val port by option("--port", help = "TCP port (must be used together with --host)").int()
    val portFile by option("--port-file", help = "socket discovery file path")
    val timeout by option("--timeout", help = "connection timeout in ms (TCP connect)").int().default(5000)

    fun clientOptions(): RpcClientOptions {
        try {
            return RpcClientOptions(socket, tcp, host, port, portFile, timeout)
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message ?: "invalid options")
        }
    }

    override fun run() {
        // 子命令链中父 run 也会被调用；直接调用（无子命令）时打印帮助。
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
        }
    }
}

class StatusCmd : CliktCommand(name = "status", help = "show server status") {
    override fun run() {
        val cli = requireNotNull(currentContext.parent?.command as? McDebugCli)
        RpcClient(cli.clientOptions()).use { client ->
            printJson(DebugApi(client).server.status())
        }
    }
}

class RawCmd : CliktCommand(name = "raw", help = "call an arbitrary JSON-RPC method; jsonParams is JSON or @file") {
    val method by argument("method", help = "method name, e.g. world.getBlock")
    val jsonParams by argument("jsonParams", help = "JSON params object, or @file to read from file").optional()

    override fun run() {
        val cli = requireNotNull(currentContext.parent?.command as? McDebugCli)
        RpcClient(cli.clientOptions()).use { client ->
            val params = jsonParams?.let { s ->
                val txt = if (s.startsWith("@")) java.nio.file.Files.readString(java.nio.file.Path.of(s.substring(1))) else s
                parseJson(txt)
            }
            val r = client.call(method, params)
            printJson(r)
        }
    }
}

class ReplCmd : CliktCommand(
    name = "repl",
    help = "interactive shell: each line is a command (status | raw <method> [json] | help | exit)",
) {
    override fun run() {
        val cli = requireNotNull(currentContext.parent?.command as? McDebugCli)
        RpcClient(cli.clientOptions()).use { client ->
            val api = DebugApi(client)
            echo("mcdebug repl — type 'help' for commands, 'exit' to quit")
            val reader = System.`in`.bufferedReader()
            while (true) {
                print("mcdebug> ")
                System.out.flush()
                val line = reader.readLine() ?: break
                val cmd = line.trim()
                if (cmd.isEmpty()) continue
                when {
                    cmd == "exit" || cmd == "quit" -> break
                    cmd == "help" -> echo("commands: status | raw <method> [json] | help | exit")
                    cmd == "status" -> printJson(api.server.status())
                    cmd.startsWith("raw ") -> {
                        val parts = cmd.removePrefix("raw ").split(Regex("\\s+"), limit = 2)
                        val method = parts[0]
                        val params = parts.getOrNull(1)?.let { parseJson(it) }
                        printJson(client.call(method, params))
                    }
                    else -> echo("unknown command: $cmd (try 'help')")
                }
            }
        }
    }
}
