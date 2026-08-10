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
 * Unix domain socket server (AF_UNIX, SOCK_STREAM).
 * Wire format: NDJSON — one JSON object per line, '\n' as the delimiter.
 * Maximum frame size: 64 MiB (lines longer than this are rejected as parse errors).
 *
 * Socket path resolution order:
 *   1. JVM system property `-Dmcdebug.socket=<path>`
 *   2. `MCDEBUG_SOCKET` env var
 *   3. `<gameDir>/config/mcdebug.json` field "socket" (relative paths resolve against gameDir)
 *   4. Default `<gameDir>/mcdebug/socket`
 *
 * The resolved socket path is written to `<gameDir>/mcdebug/port` (the same
 * discovery file the old TCP server used) so clients only need to read that
 * file; the filename stays "port" for compatibility with existing consumers.
 *
 * AF_UNIX is multi-client: any number of clients may connect to the listening
 * socket simultaneously; each accepted connection is an independent stream,
 * so per-connection state (wait.until cancellation on disconnect, etc.) is
 * identical to the old TCP behavior. Unlike TCP there is no port namespace to
 * collide on — every server instance owns its gameDir-local socket path, and
 * the socket is unreachable from the network (no auth exposure).
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
    private val protocolVersion: Int = 1
) {
    private val log = LoggerFactory.getLogger("mcdebug-rpc")
    private var serverChannel: ServerSocketChannel? = null
    private val clientChannels = ConcurrentHashMap<Int, SocketChannel>()
    private val connectionIdGen = AtomicInteger(0)
    private val acceptExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcdebug-rpc-accept").apply { isDaemon = true }
    }
    private val connectionExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "mcdebug-rpc-conn-${connectionIdGen.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    var socketPath: Path? = null
        private set

    fun start(minecraftServer: MinecraftServer): Path {
        if (serverChannel != null) error("already started")
        val path = resolveSocketPath()

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
        val backlog = 1024
        val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            ch.bind(UnixDomainSocketAddress.of(path), backlog)
        } catch (e: Exception) {
            ch.close()
            throw e
        }
        serverChannel = ch
        socketPath = path
        log.info("mcdebug RPC server listening on unix socket {}", path)

        // Write socket discovery file (best effort). Content is the socket path,
        // not a port number.
        try {
            socketFilePath()?.let { p ->
                p.parent?.let { Files.createDirectories(it) }
                Files.writeString(p, path.toString())
            }
        } catch (e: Exception) {
            log.warn("could not write socket file: {}", e.message)
        }

        acceptExecutor.submit { acceptLoop(ch, minecraftServer) }
        return path
    }

    fun stop() {
        try {
            serverChannel?.close()
        } catch (_: Exception) {}
        serverChannel = null
        clientChannels.values.forEach { runCatching { it.close() } }
        clientChannels.clear()
        acceptExecutor.shutdownNow()
        connectionExecutor.shutdownNow()
        socketPath?.let { p -> runCatching { Files.deleteIfExists(p) } }
        socketPath = null
    }

    private fun acceptLoop(ch: ServerSocketChannel, minecraftServer: MinecraftServer) {
        while (ch.isOpen) {
            try {
                val client = ch.accept()
                val id = connectionIdGen.incrementAndGet()
                clientChannels[id] = client
                connectionExecutor.submit { handleConnection(client, id, minecraftServer) }
            } catch (e: AsynchronousCloseException) {
                break  // stop() closed the channel from another thread
            } catch (e: Exception) {
                if (!ch.isOpen) break
                // transient accept errors: keep serving
                log.debug("accept error: {}", e.message)
            }
        }
    }

    private fun handleConnection(channel: SocketChannel, connId: Int, minecraftServer: MinecraftServer) {
        log.info("client connected: #{}", connId)
        RpcContext.currentConnectionId.set(connId)
        try {
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8))
            sendNotification(writer, JsonRpc.Notification.SERVER_READY, JsonObject().apply {
                addProperty("socket", socketPath?.toString() ?: "")
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
            log.info("client disconnected: #{}", connId)
        }
    }

    private fun processLine(line: String, minecraftServer: MinecraftServer): JsonElement {
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

        return try {
            val future = dispatcher.dispatch(request.method, params, minecraftServer)
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

    /**
     * Read `<gameDir>/config/mcdebug.json` if it exists and parse the `socket`
     * field. Relative paths resolve against gameDir. Returns null if the file
     * is missing, malformed, or has no valid `socket` field.
     */
    private fun readConfigSocket(): Path? {
        try {
            val gameDir = FabricLoader.getInstance().gameDir
            val cfg = gameDir.resolve("config").resolve("mcdebug.json")
            if (!Files.exists(cfg)) return null
            val txt = Files.readString(cfg).trim()
            if (txt.isEmpty()) return null
            val json = com.google.gson.JsonParser.parseString(txt).asJsonObject
            if (json.has("socket") && json.get("socket").isJsonPrimitive &&
                json.get("socket").asJsonPrimitive.isString) {
                val s = json.get("socket").asString.trim()
                if (s.isNotEmpty()) {
                    val p = Paths.get(s)
                    return if (p.isAbsolute) p.normalize() else gameDir.resolve(p).normalize()
                }
            }
            log.warn("config/mcdebug.json present but no valid 'socket' field")
        } catch (e: Exception) {
            log.warn("failed to read config/mcdebug.json: {}", e.message)
        }
        return null
    }

    private fun resolveSocketPath(): Path {
        System.getProperty("mcdebug.socket")?.let { return Paths.get(it).toAbsolutePath().normalize() }
        System.getenv("MCDEBUG_SOCKET")?.let { return Paths.get(it).toAbsolutePath().normalize() }
        readConfigSocket()?.let { return it }
        return FabricLoader.getInstance().gameDir.resolve("mcdebug").resolve("socket")
    }

    companion object {
        const val MAX_FRAME_BYTES = 64 * 1024 * 1024  // 64 MiB
    }
}
