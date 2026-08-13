package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.int
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        buildCli().main(args)
    } catch (e: CliExit) {
        System.err.println("error: ${e.message}")
        exitProcess(e.code)
    } catch (e: RpcException) {
        val data = e.data?.let { " data=${it}" } ?: ""
        System.err.println("error [${e.rpcCode}] ${e.message}$data")
        exitProcess(exitCodeFor(e))
    } catch (e: UsageError) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

/** 构造完整命令树（批处理每条 -c 命令各自用一棵新树，避免 clikt 选项状态残留）。 */
fun buildCli(): McDebugCli =
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
            WatchCommands().subcommands(*watchSubcommands().toTypedArray()),
            JarCmd(),
        )

class McDebugCli : CliktCommand(
    name = "mcdebug",
    help = "CLI for the mcdebug Minecraft debug server mod (JSON-RPC over unix socket or TCP)\n\n" +
        "退出码（Exit Codes）:\n" +
        "  0  成功（含业务性 ok:false——如 set-block 目标越界/已是目标方块、diff equal:false；\n" +
        "     这类不是协议错误，要看输出 JSON 的 ok/equal 字段）\n" +
        "  1  CLI 用法/解析错误（参数错、连不上、socket 发现失败等本地问题）\n" +
        "  2  服务端拒绝（JSON-RPC error，含 -320xx 自定义错误码，如 -32006 等待超时）\n" +
        "  3  服务端方法未实现（method not found, -32601——通常是服务端版本太旧）",
    // 无子命令时也要调用 run()：-c/--batch 批处理依赖 run() 分发。
    invokeWithoutSubcommand = true,
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
    val commandStrings by option(
        "-c", "--command",
        metavar = "CMD",
        help = "run one command string (e.g. -c \"world get-block --x 1 --y 2 --z 3\"), repeatable; " +
            "all -c commands share ONE connection and run in order",
    ).multiple()
    val batchMode by option("--batch", help = "batch mode: run all -c commands, print per-command status, stop at first failure (exit code = that failure's code)").flag()

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
            if (commandStrings.isNotEmpty()) {
                runBatch()
            } else {
                if (batchMode) throw UsageError("--batch 需要至少一个 -c <command>")
                echo(getFormattedHelp())
            }
        } else if (commandStrings.isNotEmpty()) {
            throw UsageError("-c/--command 不能与子命令同时使用")
        }
    }

    /** 批处理：连一次，顺序执行每条 -c 命令；任一失败即停，退出码 = 该失败的退出码。 */
    private fun runBatch() {
        batchClient = RpcClient(clientOptions())
        try {
            warnVersionMismatch(batchClient!!)
            commandStrings.forEachIndexed { i, cmd ->
                val tokens = tokenizeCommandLine(cmd)
                if (tokens.isEmpty()) throw UsageError("empty command: $cmd")
                println("[${i + 1}/${commandStrings.size}] $cmd")
                try {
                    buildCli().parse(tokens)
                    println("[${i + 1}/${commandStrings.size}] ✓ $cmd")
                } catch (e: Exception) {
                    System.err.println("[${i + 1}/${commandStrings.size}] ✗ $cmd (stopping batch)")
                    throw e
                }
            }
        } finally {
            runCatching { batchClient?.close() }
            batchClient = null
        }
    }
}

class StatusCmd : CliktCommand(
    name = "status",
    help = "show server status; --health adds TPS/MSPT + per-dimension entity/loaded-chunk counts (requires server mcdebug >= 0.6.0)",
) {
    private val health by option("--health", help = "aggregated health: TPS, MSPT, per-dim entities + loaded chunks").flag()

    override fun run() {
        val cli = requireNotNull(currentContext.parent?.command as? McDebugCli)
        withClient(cli) { client ->
            val api = DebugApi(client)
            if (health) {
                val h = try {
                    api.server.health()
                } catch (e: RpcException) {
                    if (e.rpcCode == -32601) {
                        throw CliExit(3, "status --health 需服务端 mcdebug >= 0.6.0（当前服务端没有 server.health 方法）")
                    }
                    throw e
                }
                printHealth(h)
            } else {
                printJson(api.server.status())
            }
        }
    }

    private fun printHealth(h: com.google.gson.JsonElement) {
        val o = h.asJsonObject
        val tps = o.get("tps").asDouble
        val mspt = o.get("mspt").asDouble
        println("tps: $tps")
        println("mspt: $mspt")
        o.get("players")?.let { println("players: ${it.asInt}") }
        println("dims:")
        o.getAsJsonArray("dims").forEach { d ->
            val dobj = d.asJsonObject
            println("  ${dobj.get("dim").asString}: entities=${dobj.get("entities").asInt}, loadedChunks=${dobj.get("loadedChunks").asInt}")
        }
    }
}

class RawCmd : CliktCommand(name = "raw", help = "call an arbitrary JSON-RPC method; jsonParams is JSON or @file; params follow the SERVER schema, e.g. {\"pos\":[x,y,z]} (pos is an array, there is no --x/--y/--z here) — check the equivalent subcommand's --help for the schema") {
    val method by argument("method", help = "method name, e.g. world.getBlock")
    val jsonParams by argument("jsonParams", help = "JSON params object, or @file to read from file").optional()

    override fun run() {
        val cli = requireNotNull(currentContext.parent?.command as? McDebugCli)
        withClient(cli) { client ->
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
    help = "interactive shell: each line is a command (status | raw <method> [json] | help | exit); " +
        "history persisted to ~/.mdb_history; variables: '1' prefixed with a dollar sign = last result JSON (verbatim), '?' prefixed with a dollar sign = last exit code; " +
        "end a line with \\ or leave JSON brackets unclosed to continue on the next line",
) {
    override fun run() {
        val cli = requireNotNull(currentContext.parent?.command as? McDebugCli)
        withClient(cli) { client ->
            val api = DebugApi(client)
            echo("mcdebug repl — type 'help' for commands, 'exit' to quit")
            val reader = System.`in`.bufferedReader()
            val history = ReplHistory.load()
            var lastResult: String? = null
            var lastStatus = 0
            while (true) {
                print("mcdebug> ")
                System.out.flush()
                val line = readInput(reader) ?: break
                val rawLine = line.trim()
                val cmd = substitute(rawLine, lastResult, lastStatus).trim()
                if (cmd.isEmpty()) continue
                history.add(rawLine)
                when {
                    cmd == "exit" || cmd == "quit" -> break
                    cmd == "help" -> println(
                        "commands: status | raw <method> [json] | help | exit\n" +
                            "history: persisted to ~/.mdb_history (arrow keys not supported)\n" +
                            "substitution: '1' + dollar sign = last result JSON (verbatim), " +
                            "'?' + dollar sign = last exit code (0/1/2/3)\n" +
                            "multi-line: end a line with backslash or leave JSON brackets unclosed",
                    )
                    cmd == "status" -> try {
                        val r = api.server.status()
                        lastResult = gson.toJson(r)
                        lastStatus = 0
                        printJson(r)
                    } catch (e: RpcException) {
                        lastStatus = exitCodeFor(e)
                        System.err.println("error [${e.rpcCode}] ${e.message}")
                    }
                    cmd.startsWith("raw ") -> {
                        val parts = cmd.removePrefix("raw ").split(Regex("\\s+"), limit = 2)
                        val method = parts[0]
                        val params = parts.getOrNull(1)?.let { parseJson(it) }
                        try {
                            val r = client.call(method, params)
                            lastResult = gson.toJson(r)
                            lastStatus = 0
                            printJson(r)
                        } catch (e: RpcException) {
                            lastStatus = exitCodeFor(e)
                            System.err.println("error [${e.rpcCode}] ${e.message}")
                        }
                    }
                    else -> echo("unknown command: $cmd (try 'help')")
                }
            }
        }
    }

    private fun substitute(line: String, lastResult: String?, lastStatus: Int): String =
        line.replace("$1", lastResult ?: "").replace("$?", lastStatus.toString())

    /** 多行输入：行尾 \\ 续行；JSON 括号不配平也续行。 */
    private fun readInput(reader: java.io.BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: return if (sb.isEmpty()) null else sb.toString()
            val trimmed = line.trimEnd()
            if (trimmed.endsWith("\\")) {
                sb.append(trimmed.dropLast(1)).append('\n')
                print("      > ")
                System.out.flush()
                continue
            }
            sb.append(line)
            if (unbalancedBrackets(sb.toString())) {
                sb.append('\n')
                print("      > ")
                System.out.flush()
                continue
            }
            return sb.toString()
        }
    }

    private fun unbalancedBrackets(s: String): Boolean {
        var braces = 0
        var brackets = 0
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && inString -> i++
                c == '"' -> inString = !inString
                !inString && c == '{' -> braces++
                !inString && c == '}' -> braces--
                !inString && c == '[' -> brackets++
                !inString && c == ']' -> brackets--
            }
            i++
        }
        return braces > 0 || brackets > 0
    }
}

/** 会话历史：持久化到 ~/.mdb_history（最多保留 200 条，连续重复去重）。 */
class ReplHistory(private val path: java.nio.file.Path) {
    private val entries = java.util.ArrayDeque<String>()

    fun add(cmd: String) {
        if (entries.peekLast() == cmd) return
        entries.addLast(cmd)
        while (entries.size > MAX) entries.removeFirst()
        try {
            java.nio.file.Files.writeString(path, entries.joinToString("\n") + "\n")
        } catch (_: Exception) {
            // history 写入失败不影响会话
        }
    }

    companion object {
        private const val MAX = 200

        fun load(): ReplHistory {
            val p = java.nio.file.Path.of(System.getProperty("user.home"), ".mdb_history")
            val h = ReplHistory(p)
            try {
                if (java.nio.file.Files.exists(p)) {
                    java.nio.file.Files.readAllLines(p).takeLast(MAX).forEach { h.entries.addLast(it) }
                }
            } catch (_: Exception) {
                // 读取失败则以空历史开始
            }
            return h
        }
    }
}
