package com.mcdebug.rpc

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.McDebugMod
import com.mcdebug.wait.WaitOps
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * JSON-RPC server with two parallel transports sharing one dispatcher:
 *
 *   - unix domain socket (AF_UNIX, SOCK_STREAM) — **primary** channel, local
 *     access only (default `<gameDir>/mcdebug/socket`);
 *   - TCP — **secondary** channel for cross-machine access (default port 25580,
 *     the port the pre-0.5.0 server used, so existing production port mappings
 *     keep working). Bound to the wildcard address (`0.0.0.0`): restrict
 *     network exposure via port publishing rules / firewall, there is no auth.
 *
 * Wire format: NDJSON — one JSON object per line, '\n' as the delimiter.
 * Maximum frame size: 64 MiB (lines longer than this are rejected as parse errors).
 * Multi-client: any number of clients may connect to either transport; each
 * accepted connection is an independent stream, so per-connection state
 * (wait.until cancellation on disconnect, etc.) is identical to the old behavior.
 *
 * Fault tolerance (0.5.1+): the two listeners bind **independently**. A
 * single-side bind failure (typically: TCP port already in use, or a stale
 * unix socket path) only logs a WARN and the server keeps serving on the other
 * transport. Only if **both** listeners fail does start() throw. The unix
 * socket is the primary channel (local); TCP is auxiliary (cross-machine).
 *
 * Socket path resolution order:
 *   1. JVM system property `-Dmcdebug.socket=<path>`
 *   2. `MCDEBUG_SOCKET` env var
 *   3. `<gameDir>/config/mcdebug.json` field "socket" (relative paths resolve against gameDir)
 *   4. Default `<gameDir>/mcdebug/socket`
 *
 * TCP config resolution order (port and enabled flag resolve independently):
 *   1. JVM system properties `-Dmcdebug.tcpPort=<port>` / `-Dmcdebug.tcpEnabled=<true|false>`
 *   2. `MCDEBUG_TCP_PORT` / `MCDEBUG_TCP_ENABLED` env vars
 *   3. `<gameDir>/config/mcdebug.json` fields "tcpPort" / "tcpEnabled"
 *   4. Defaults: enabled, port 25580. `tcpPort=0` means ephemeral (OS picks a
 *      free port; the actual port is reported in the log and discovery file).
 *
 * Discovery files (best effort):
 *   - `<gameDir>/mcdebug/port` — the unix socket path (same file the old TCP
 *     server used for its port; the filename stays "port" for compatibility).
 *   - `<gameDir>/mcdebug/tcpPort` — the TCP port number (new; only written when
 *     the TCP listener actually bound).
 *
 * Pitfalls handled here:
 *   - stale socket files from a crashed server make bind() fail with
 *     EADDRINUSE, so start() deletes any pre-existing file at the target path
 *     before binding;
 *   - stop() removes the socket file so no stale file is left behind.
 */
class RpcServer(
    private val dispatcher: RpcDispatcher,
    private val socketFilePath: () -> Path?,
    private val tcpPortFilePath: () -> Path?,
    private val protocolVersion: Int = 1
) {
    private val log = LoggerFactory.getLogger("mcdebug-rpc")
    private var unixChannel: ServerSocketChannel? = null
    private var tcpChannel: ServerSocketChannel? = null
    private val clientChannels = ConcurrentHashMap<Int, SocketChannel>()
    private val connectionIdGen = AtomicInteger(0)
    private val unixAcceptExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcdebug-rpc-accept-unix").apply { isDaemon = true }
    }
    private val tcpAcceptExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcdebug-rpc-accept-tcp").apply { isDaemon = true }
    }
    private val connectionExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "mcdebug-rpc-conn-${connectionIdGen.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Bound unix socket path, or null if the unix listener failed to bind. */
    @Volatile
    var socketPath: Path? = null
        private set

    /** Actually bound TCP port, or null if TCP is disabled or failed to bind. */
    @Volatile
    var tcpPort: Int? = null
        private set

    /**
     * Bind both listeners independently and start their accept loops.
     * Throws IllegalStateException only if BOTH listeners fail to bind.
     *
     * @param minecraftServer the running server, used to dispatch requests;
     *                        may be null in transport-level tests (no requests
     *                        can be served then).
     * @return the bound unix socket path, or null if the unix listener failed.
     */
    fun start(minecraftServer: MinecraftServer?): Path? {
        if (unixChannel != null || tcpChannel != null) error("already started")

        var unixOk = false
        var tcpOk = false

        // --- unix socket (primary channel) ---
        val path = resolveSocketPath()
        try {
            // Remove a stale socket file left by a crashed server, otherwise bind()
            // fails with EADDRINUSE even though nothing is listening.
            try {
                Files.deleteIfExists(path)
            } catch (e: Exception) {
                log.warn("could not remove stale socket {}: {}", path, e.message)
            }
            try {
                path.parent?.let { Files.createDirectories(it) }
            } catch (e: Exception) {
                log.warn("could not create socket directory {}: {}", path.parent, e.message)
            }

            // backlog：测试运行器每个并发用例会建一个独立 RPC 连接（128 并行 + trace
            // 额外请求，瞬时并发远超旧的 50），backlog 太小会导致超出部分的连接被
            // 内核拒绝。提到 1024 给 accept loop 足够缓冲
            // （实际上限由 OS SOMAXCONN 裁剪，Windows/Linux 现代值都远大于此）。
            val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                ch.bind(UnixDomainSocketAddress.of(path), BACKLOG)
            } catch (e: Exception) {
                ch.close()
                throw e
            }
            unixChannel = ch
            socketPath = path
            unixOk = true
            log.info("mcdebug RPC server listening on unix socket {}", path)
            writeDiscoveryFile(socketFilePath, path.toString(), "socket")
        } catch (e: Exception) {
            log.warn("mcdebug RPC failed to bind unix socket {}: {} — continuing without it", path, e.message)
        }

        // --- TCP (secondary channel, cross-machine) ---
        val tcp = resolveTcpConfig()
        if (tcp.enabled) {
            try {
                val ch = ServerSocketChannel.open()
                try {
                    ch.bind(InetSocketAddress(tcp.port), BACKLOG)
                } catch (e: Exception) {
                    ch.close()
                    throw e
                }
                val actualPort = (ch.localAddress as InetSocketAddress).port
                tcpChannel = ch
                tcpPort = actualPort
                tcpOk = true
                log.info("mcdebug RPC server listening on TCP port {} (cross-machine)", actualPort)
                writeDiscoveryFile(tcpPortFilePath, actualPort.toString(), "tcp port")
            } catch (e: Exception) {
                log.warn(
                    "mcdebug RPC failed to bind TCP port {}: {} — continuing without it",
                    tcp.port,
                    e.message,
                )
            }
        } else {
            log.info("mcdebug RPC TCP listener disabled by configuration")
        }

        if (!unixOk && !tcpOk) {
            error("mcdebug RPC could not start: both unix socket and TCP listeners failed to bind")
        }

        unixChannel?.let { ch -> unixAcceptExecutor.submit { acceptLoop(ch, "unix", minecraftServer) } }
        tcpChannel?.let { ch -> tcpAcceptExecutor.submit { acceptLoop(ch, "tcp", minecraftServer) } }
        return socketPath
    }

    fun stop() {
        try {
            unixChannel?.close()
        } catch (_: Exception) {}
        try {
            tcpChannel?.close()
        } catch (_: Exception) {}
        unixChannel = null
        tcpChannel = null
        clientChannels.values.forEach { runCatching { it.close() } }
        clientChannels.clear()
        unixAcceptExecutor.shutdownNow()
        tcpAcceptExecutor.shutdownNow()
        connectionExecutor.shutdownNow()
        socketPath?.let { p -> runCatching { Files.deleteIfExists(p) } }
        socketPath = null
        tcpPort = null
    }

    private fun acceptLoop(ch: ServerSocketChannel, via: String, minecraftServer: MinecraftServer?) {
        while (ch.isOpen) {
            try {
                val client = ch.accept()
                val id = connectionIdGen.incrementAndGet()
                clientChannels[id] = client
                connectionExecutor.submit { handleConnection(client, id, via, minecraftServer) }
            } catch (e: AsynchronousCloseException) {
                break  // stop() closed the channel from another thread
            } catch (e: Exception) {
                if (!ch.isOpen) break
                // transient accept errors: keep serving
                log.debug("accept error: {}", e.message)
            }
        }
    }

    private fun handleConnection(
        channel: SocketChannel,
        connId: Int,
        via: String,
        minecraftServer: MinecraftServer?,
    ) {
        log.info("client connected: #{} ({})", connId, via)
        RpcContext.currentConnectionId.set(connId)
        try {
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8))
            sendNotification(writer, JsonRpc.Notification.SERVER_READY, JsonObject().apply {
                addProperty("socket", socketPath?.toString() ?: "")
                addProperty("tcpPort", tcpPort)
                addProperty("protocolVersion", protocolVersion)
                addProperty("modVersion", McDebugMod.MOD_VERSION)
            })

            while (channel.isOpen) {
                val line = readLineBounded(reader) ?: break
                if (line.isBlank()) continue
                val response = processLine(line, minecraftServer)
                writer.write(response.toString())
                writer.write("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            if (channel.isOpen) log.debug("connection {} ended: {}", connId, e.message)
        } finally {
            // Cancel any long-running jobs (e.g. wait.until) owned by this connection
            // so they don't keep evaluating predicates and holding threads after the
            // client is gone.
            runCatching { WaitOps.cancelConnection(connId) }
            RpcContext.currentConnectionId.remove()
            clientChannels.remove(connId)
            runCatching { channel.close() }
            log.info("client disconnected: #{} ({})", connId, via)
        }
    }

    private fun processLine(line: String, minecraftServer: MinecraftServer?): JsonElement {
        val rawObj: JsonObject = try {
            JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            return errorResponse(null, RpcException(RpcErrors.PARSE_ERROR, "invalid JSON: ${e.message}"))
        }

        val request = try {
            JsonRpc.Request.fromJsonObject(rawObj)
        } catch (e: RpcException) {
            return errorResponse(rawObj["id"]?.takeIf { !it.isJsonNull }, e)
        }

        if (request.method.startsWith("rpc.")) {
            return errorResponse(request.id, RpcException(RpcErrors.METHOD_NOT_FOUND, "method name reserved"))
        }

        val params = try {
            RpcContext.paramsObj(request.params)
        } catch (e: RpcException) {
            return errorResponse(request.id, e)
        }

        val server = minecraftServer
            ?: return errorResponse(request.id, RpcException(RpcErrors.INTERNAL_ERROR, "server not initialized"))

        return try {
            val future = dispatcher.dispatch(request.method, params, server)
            val result = future.get()  // block this connection thread until handler completes
            successResponse(request.id, result)
        } catch (e: java.util.concurrent.ExecutionException) {
            // Unwrap: the handler completed the future exceptionally
            val cause = e.cause
            if (cause is RpcException) {
                errorResponse(request.id, cause)
            } else {
                log.error("dispatch error for {}", request.method, cause)
                errorResponse(request.id, RpcException(RpcErrors.INTERNAL_ERROR, cause?.message ?: "internal error", null, cause))
            }
        } catch (e: RpcException) {
            errorResponse(request.id, e)
        } catch (e: Exception) {
            log.error("dispatch error for {}", request.method, e)
            errorResponse(request.id, RpcException(RpcErrors.INTERNAL_ERROR, e.message ?: "internal error", null, e))
        }
    }

    private fun readLineBounded(reader: BufferedReader): String? {
        val sb = StringBuilder()
        var c: Int
        while (true) {
            c = reader.read()
            if (c == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            if (sb.length >= MAX_FRAME_BYTES) {
                throw RpcException(RpcErrors.PARSE_ERROR, "frame exceeds $MAX_FRAME_BYTES bytes")
            }
            sb.append(c.toChar())
        }
    }

    private fun successResponse(id: Any?, result: JsonElement): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", JsonRpc.VERSION)
        add("id", idToJson(id))
        add("result", result)
    }

    private fun errorResponse(id: Any?, e: RpcException): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", JsonRpc.VERSION)
        add("id", idToJson(id))
        add("error", JsonObject().apply {
            addProperty("code", e.rpcCode)
            addProperty("message", e.message ?: "error")
            if (e.data != null) add("data", e.data)
        })
    }

    private fun idToJson(id: Any?): JsonElement = when (id) {
        null -> com.google.gson.JsonNull.INSTANCE
        is Number -> com.google.gson.JsonPrimitive(id)
        is String -> com.google.gson.JsonPrimitive(id)
        else -> com.google.gson.JsonPrimitive(id.toString())
    }

    /** Send a server-initiated notification to one specific socket. */
    private fun sendNotification(writer: BufferedWriter, method: String, params: JsonObject) {
        val msg = JsonObject().apply {
            addProperty("jsonrpc", JsonRpc.VERSION)
            addProperty("method", method)
            add("params", params)
        }
        writer.write(msg.toString())
        writer.write("\n")
        writer.flush()
    }

    /** Best-effort write of a discovery file (failure is logged, never fatal). */
    private fun writeDiscoveryFile(fileProvider: () -> Path?, content: String, what: String) {
        try {
            fileProvider()?.let { p ->
                p.parent?.let { Files.createDirectories(it) }
                Files.writeString(p, content)
            }
        } catch (e: Exception) {
            log.warn("could not write {} discovery file: {}", what, e.message)
        }
    }

    /**
     * Read `<gameDir>/config/mcdebug.json` once, if it exists. Returns the
     * parsed object, or null if the file is missing, empty, or malformed.
     */
    private fun readConfigJson(): JsonObject? {
        try {
            val gameDir = FabricLoader.getInstance().gameDir
            val cfg = gameDir.resolve("config").resolve("mcdebug.json")
            if (!Files.exists(cfg)) return null
            val txt = Files.readString(cfg).trim()
            if (txt.isEmpty()) return null
            val el = JsonParser.parseString(txt)
            if (!el.isJsonObject) {
                log.warn("config/mcdebug.json is not a JSON object")
                return null
            }
            return el.asJsonObject
        } catch (e: Exception) {
            log.warn("failed to read config/mcdebug.json: {}", e.message)
        }
        return null
    }

    private fun resolveSocketPath(): Path {
        System.getProperty("mcdebug.socket")?.let { return Paths.get(it).toAbsolutePath().normalize() }
        System.getenv("MCDEBUG_SOCKET")?.let { return Paths.get(it).toAbsolutePath().normalize() }
        readConfigJson()?.let { json ->
            val el = json.get("socket")
            if (el != null && el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                val s = el.asString.trim()
                if (s.isNotEmpty()) {
                    val gameDir = FabricLoader.getInstance().gameDir
                    val p = Paths.get(s)
                    return if (p.isAbsolute) p.normalize() else gameDir.resolve(p).normalize()
                }
            }
            log.warn("config/mcdebug.json present but no valid 'socket' field")
        }
        return FabricLoader.getInstance().gameDir.resolve("mcdebug").resolve("socket")
    }

    private data class TcpConfig(val enabled: Boolean, val port: Int)

    private fun resolveTcpConfig(): TcpConfig {
        val propEnabled = System.getProperty("mcdebug.tcpEnabled")
        val envEnabled = System.getenv("MCDEBUG_TCP_ENABLED")
        val propPort = System.getProperty("mcdebug.tcpPort")
        val envPort = System.getenv("MCDEBUG_TCP_PORT")

        // Only touch FabricLoader (config file) when no property/env override is
        // present — keeps this code path unit-testable without a Fabric env.
        val json = if (propEnabled == null && envEnabled == null && propPort == null && envPort == null) {
            readConfigJson()
        } else {
            null
        }

        val enabled = propEnabled?.let { parseBool(it, "-Dmcdebug.tcpEnabled") }
            ?: envEnabled?.let { parseBool(it, "MCDEBUG_TCP_ENABLED") }
            ?: json?.get("tcpEnabled")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isBoolean }?.asBoolean
            ?: true

        val port = propPort?.let { parsePort(it, "-Dmcdebug.tcpPort") }
            ?: envPort?.let { parsePort(it, "MCDEBUG_TCP_PORT") }
            ?: json?.get("tcpPort")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isNumber }?.asInt?.takeIf { it in 0..65535 }
            ?: DEFAULT_TCP_PORT

        return TcpConfig(enabled, port)
    }

    private fun parseBool(raw: String, source: String): Boolean = when (raw.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> {
            log.warn("invalid boolean '{}' in {}; treating as enabled", raw, source)
            true
        }
    }

    private fun parsePort(raw: String, source: String): Int = raw.trim().toIntOrNull()?.let { p ->
        if (p in 0..65535) p else {
            log.warn("invalid port {} in {}; using default {}", raw, source, DEFAULT_TCP_PORT)
            DEFAULT_TCP_PORT
        }
    } ?: run {
        log.warn("invalid port '{}' in {}; using default {}", raw, source, DEFAULT_TCP_PORT)
        DEFAULT_TCP_PORT
    }

    companion object {
        const val MAX_FRAME_BYTES = 64 * 1024 * 1024  // 64 MiB
        const val DEFAULT_TCP_PORT = 25580
        private const val BACKLOG = 1024
    }
}
