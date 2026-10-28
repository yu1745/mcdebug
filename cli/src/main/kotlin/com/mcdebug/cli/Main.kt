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
import com.mcdebug.cli.session.SessionManager
import com.mcdebug.cli.session.defaultRegistryDir
import java.nio.file.Path
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
            GuiCommands().subcommands(*guiSubcommands().toTypedArray()),
            RedstoneCommands().subcommands(*redstoneSubcommands().toTypedArray()),
            ReflectCommands().subcommands(*reflectSubcommands().toTypedArray()),
            WatchCommands().subcommands(*watchSubcommands().toTypedArray()),
            SessionCommands().subcommands(*sessionSubcommands().toTypedArray()),
            JarCmd(),
            SkillCmd(),
        )

class McDebugCli : CliktCommand(
    name = "mcdebug",
    help = "CLI for the mcdebug Minecraft debug mod (JSON-RPC over unix socket or TCP)\n\n" +
        "两个平面（Planes）:\n" +
        "  server  world.*/be.*/inv.*/... 走服务端平面（发现文件 mcdebug/port，TCP 25580）\n" +
        "  client  gui.* 走客户端平面——必须有运行中的游戏客户端（发现文件 mcdebug/clientPort，TCP 25581\n" +
        "          默认关闭）；gui 子命令与 raw gui.* 会自动选客户端平面\n\n" +
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

    val session by option("--session", metavar = "NAME", help = "route both planes through a named local session; excludes explicit transport options; e.g. --session dev gui status")
    val sessionsDir by option(
        "--sessions-dir", metavar = "PATH", envvar = "MCDEBUG_SESSIONS_DIR",
        help = "session registry directory (default: XDG_STATE_HOME or ~/.local/state, then mcdebug/sessions)",
    )

    fun sessionManager(): SessionManager = SessionManager(sessionsDir?.let { Path.of(it) } ?: defaultRegistryDir())

    private fun validateSessionOptions() {
        if (session != null && listOf(socket, tcp, host, port, portFile, clientSocket, clientTcp).any { it != null }) {
            throw UsageError("--session cannot be combined with --socket/--tcp/--host/--port/--port-file/--client-socket/--client-tcp")
        }
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
    val clientSocket by option(
        "--client-socket",
        help = "client-plane unix socket path for gui.* (overrides MCDEBUG_CLIENT_SOCKET and mcdebug/clientPort)",
    )
    val clientTcp by option(
        "--client-tcp",
        metavar = "HOST[:PORT]",
        help = "connect to the client plane over TCP (default port 25581) — must be enabled on the client side",
    )
    val timeout by option("--timeout", help = "TCP connect / server.ready handshake timeout in ms (each; default 5000)").int().default(5000)
    val requestTimeout by option(
        "--request-timeout",
        metavar = "SECONDS",
        help = "request deadline in seconds, including writes; 0 = unlimited; default: 30, except wait.until = unlimited " +
            "(server timeoutTicks still applies). Explicit value overrides ALL methods; expiry closes the connection, not a remote rollback",
    ).int()
    val commandStrings by option(
        "-c", "--command",
        metavar = "CMD",
        help = "run one command string (e.g. -c \"world get-block --x 1 --y 2 --z 3\"), repeatable; " +
            "all -c commands share ONE connection and run in order",
    ).multiple()
    val batchMode by option("--batch", help = "batch mode: run all -c commands, print per-command status, stop at first failure (exit code = that failure's code)").flag()

    fun clientOptions(): RpcClientOptions {
        validateSessionOptions()
        try {
            session?.let {
                return sessionManager().resolve(it, RpcClientOptions.PLANE_SERVER, timeout, requestTimeout?.toLong()?.times(1000))
            }
            return RpcClientOptions(socket, tcp, host, port, portFile, timeout, requestTimeoutMs = requestTimeout?.toLong()?.times(1000))
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message ?: "invalid options")
        }
    }

    /**
     * 客户端平面（`gui.*`）连接参数。刻意**不**继承 `--socket`/`--tcp`：那是服务端
     * 平面的地址，两个平面在单机客户端里是同一进程的两条不同 socket。
     */
    fun clientPlaneOptions(): RpcClientOptions {
        validateSessionOptions()
        try {
            session?.let {
                return sessionManager().resolve(it, RpcClientOptions.PLANE_CLIENT, timeout, requestTimeout?.toLong()?.times(1000))
            }
            return RpcClientOptions(
                socket = clientSocket,
                tcp = clientTcp,
                timeoutMs = timeout,
                plane = RpcClientOptions.PLANE_CLIENT,
                requestTimeoutMs = requestTimeout?.toLong()?.times(1000),
            )
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message ?: "invalid options")
        }
    }

    override fun run() {
        // Only validate syntax here: session management/help must not resolve or connect.
        validateSessionOptions()
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

    /** 每条 -c 的新命令树必须继承根连接/deadline选项，而不是退回默认地址/超时。 */
    private fun connectionArgs(): List<String> = buildList {
        fun forward(name: String, value: Any?) {
            if (value != null) { add(name); add(value.toString()) }
        }
        forward("--session", session)
        forward("--sessions-dir", sessionsDir)
        forward("--socket", socket)
        forward("--tcp", tcp)
        forward("--host", host)
        forward("--port", port)
        forward("--port-file", portFile)
        forward("--client-socket", clientSocket)
        forward("--client-tcp", clientTcp)
        forward("--timeout", timeout)
        forward("--request-timeout", requestTimeout)
    }

    /** 批处理：连一次，顺序执行每条 -c 命令；任一失败即停，退出码 = 该失败的退出码。 */
    private fun runBatch() {
        // 连接按需建立：一批命令可能只碰其中一个平面（甚至只有服务端平面）。
        batchActive = true
        try {
            commandStrings.forEachIndexed { i, cmd ->
                val tokens = tokenizeCommandLine(cmd)
                if (tokens.isEmpty()) throw UsageError("empty command: $cmd")
                println("[${i + 1}/${commandStrings.size}] $cmd")
                try {
                    buildCli().parse(connectionArgs() + tokens)
                    println("[${i + 1}/${commandStrings.size}] ✓ $cmd")
                } catch (e: Exception) {
                    System.err.println("[${i + 1}/${commandStrings.size}] ✗ $cmd (stopping batch)")
                    throw e
                }
            }
        } finally {
            batchActive = false
            runCatching { batchClient?.close() }
            runCatching { batchClientPlane?.close() }
            batchClient = null
            batchClientPlane = null
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

class RawCmd : CliktCommand(name = "raw", help = "call an arbitrary JSON-RPC method; jsonParams is JSON or @file; params follow the SERVER schema, e.g. {\"pos\":[x,y,z]} (pos is an array, there is no --x/--y/--z here) — check the equivalent subcommand's --help for the schema. gui.stop requires instanceId and sessionToken identity; prefer session stop for normal managed shutdown") {
    val method by argument("method", help = "method name, e.g. world.getBlock")
    val jsonParams by argument("jsonParams", help = "JSON params object, or @file to read from file").optional()

    override fun run() {
        val cli = requireNotNull(currentContext.parent?.command as? McDebugCli)
        // gui.* 只存在于客户端平面；raw 自动选平面，省掉一层开关。
        withPlaneFor(cli, method) { client ->
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
