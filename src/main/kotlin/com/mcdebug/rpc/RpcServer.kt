package com.mcdebug.rpc

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.McDebugMod
import com.mcdebug.wait.WaitOps
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP server bound to 0.0.0.0 (all network interfaces).
 * Wire format: NDJSON — one JSON object per line, '\n' as the delimiter.
 * Maximum frame size: 64 MiB (lines longer than this are rejected as parse errors).
 */
class RpcServer(
    private val dispatcher: RpcDispatcher,
    private val portFilePath: () -> Path?,
    private val protocolVersion: Int = 1
) {
    private val log = LoggerFactory.getLogger("mcdebug-rpc")
    private var serverSocket: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap<Int, Socket>()
    private val connectionIdGen = AtomicInteger(0)
    private val acceptExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcdebug-rpc-accept").apply { isDaemon = true }
    }
    private val connectionExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "mcdebug-rpc-conn-${connectionIdGen.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    var boundPort: Int = -1
        private set

    fun start(minecraftServer: MinecraftServer): Int {
        if (serverSocket != null) error("already started")
        // Port resolution order:
        //   1. JVM system property `-Dmcdebug.port=...`
        //   2. `MCDEBUG_PORT` env var
        //   3. `<gameDir>/config/mcdebug.json` field "port"
        //   4. Default 25580
        val requestedPort = resolveRequestedPort()
        log.info("mcdebug requested port: {} (sysprop={}, env={})", requestedPort,
            System.getProperty("mcdebug.port"), System.getenv("MCDEBUG_PORT"))
        // backlog：测试运行器每个并发用例会建一个独立 RPC 连接（128 并行 + trace
        // 额外请求，瞬时并发远超旧的 50），backlog 太小会导致超出部分的连接被
        // 内核 RST → 客户端 ECONNREFUSED。提到 1024 给 accept loop 足够缓冲
        // （实际上限由 OS SOMAXCONN 裁剪，Windows/Linux 现代值都远大于此）。
        val backlog = 1024
        val ss = try {
            ServerSocket(requestedPort, backlog, InetAddress.getByName(DEFAULT_BIND_ADDRESS))
        } catch (e: java.net.BindException) {
            log.warn("port {} busy, falling back to OS-assigned", requestedPort)
            ServerSocket(0, backlog, InetAddress.getByName(DEFAULT_BIND_ADDRESS))
        }
        serverSocket = ss
        boundPort = ss.localPort
        ss.soTimeout = 1000  // allow periodic accept-loop checks for shutdown
        log.info("mcdebug RPC server listening on {}:{}", DEFAULT_BIND_ADDRESS, boundPort)

        // Write port file (best effort)
        try {
            portFilePath()?.let { p ->
                p.parent?.let { Files.createDirectories(it) }
                Files.writeString(p, boundPort.toString())
            }
        } catch (e: Exception) {
            log.warn("could not write port file: {}", e.message)
        }

        acceptExecutor.submit { acceptLoop(ss, minecraftServer) }
        return boundPort
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        clientSockets.values.forEach { runCatching { it.close() } }
        clientSockets.clear()
        acceptExecutor.shutdownNow()
        connectionExecutor.shutdownNow()
        boundPort = -1
    }

    private fun acceptLoop(ss: ServerSocket, minecraftServer: MinecraftServer) {
        while (!ss.isClosed) {
            try {
                val client = ss.accept()
                val id = connectionIdGen.incrementAndGet()
                clientSockets[id] = client
                connectionExecutor.submit { handleConnection(client, id, minecraftServer) }
            } catch (e: Exception) {
                if (ss.isClosed) break
                // swallow timeouts so we can re-check isClosed
            }
        }
    }

    private fun handleConnection(socket: Socket, connId: Int, minecraftServer: MinecraftServer) {
        val peer = "${socket.remoteSocketAddress}"
        log.info("client connected: {}", peer)
        RpcContext.currentConnectionId.set(connId)
        try {
            socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            sendNotification(writer, JsonRpc.Notification.SERVER_READY, com.google.gson.JsonObject().apply {
                addProperty("port", boundPort)
                addProperty("protocolVersion", protocolVersion)
                addProperty("modVersion", McDebugMod.MOD_VERSION)
            })

            while (!socket.isClosed) {
                val line = readLineBounded(reader) ?: break
                if (line.isBlank()) continue
                val response = processLine(line, minecraftServer)
                writer.write(response.toString())
                writer.write("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            if (!socket.isClosed) log.debug("connection {} ended: {}", peer, e.message)
        } finally {
            // Cancel any long-running jobs (e.g. wait.until) owned by this connection
            // so they don't keep evaluating predicates and holding threads after the
            // client is gone.
            runCatching { WaitOps.cancelConnection(connId) }
            RpcContext.currentConnectionId.remove()
            clientSockets.remove(connId)
            runCatching { socket.close() }
            log.info("client disconnected: {}", peer)
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
     * Read `<gameDir>/config/mcdebug.json` if it exists and parse the `port` field.
     * Returns null if file missing, malformed, or doesn't have a numeric `port` field.
     */
    private fun readConfigPort(): Int? {
        try {
            val gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir
            val cfg = gameDir.resolve("config").resolve("mcdebug.json")
            if (!Files.exists(cfg)) return null
            val txt = Files.readString(cfg).trim()
            if (txt.isEmpty()) return null
            val json = com.google.gson.JsonParser.parseString(txt).asJsonObject
            if (json.has("port") && json.get("port").isJsonPrimitive &&
                json.get("port").asJsonPrimitive.isNumber) {
                val n = json.get("port").asInt
                if (n in 1..65535) return n
            }
            log.warn("config/mcdebug.json present but no valid 'port' field")
        } catch (e: Exception) {
            log.warn("failed to read config/mcdebug.json: {}", e.message)
        }
        return null
    }

    private fun resolveRequestedPort(): Int {
        System.getProperty("mcdebug.port")?.toIntOrNull()?.let { return it }
        System.getenv("MCDEBUG_PORT")?.toIntOrNull()?.let { return it }
        readConfigPort()?.let { return it }
        return DEFAULT_PORT
    }

    companion object {
        const val MAX_FRAME_BYTES = 64 * 1024 * 1024  // 64 MiB
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        const val DEFAULT_PORT = 25580
    }
}
