package com.mcdebug.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** JSON-RPC 错误（code -32700..-32603 标准 + -32099..-32000 自定义）。 */
class RpcException(val rpcCode: Int, message: String, val data: JsonElement? = null) :
    RuntimeException(message)

/** 本地墙钟 deadline，不是服务端 wait.until 的 tick 超时（RPC -32006）。 */
class RpcRequestTimeoutException(val method: String, val timeoutMs: Long) :
    IOException("request deadline exceeded for $method after ${timeoutMs}ms (connection closed)")

/** 连接首条 server.ready 通知；不依赖游戏主线程或额外 status RPC。 */
data class RpcProcessIdentity(
    val pid: Long,
    val startTime: String,
    val instanceId: String,
    val sessionToken: String,
    val gameDir: String,
) {
    init {
        require(pid > 0) { "invalid process PID" }
        java.time.Instant.parse(startTime)
        require(instanceId.isNotBlank()) { "missing process instanceId" }
        // Remote TCP peers may use another OS's path syntax. Only local
        // Session resolution interprets/canonicalizes this otherwise opaque path.
        require(gameDir.isNotBlank()) { "missing process gameDir" }
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("pid", pid)
        addProperty("startTime", startTime)
        addProperty("instanceId", instanceId)
        addProperty("sessionToken", sessionToken)
        addProperty("gameDir", gameDir)
    }

    companion object {
        fun fromJson(value: JsonObject): RpcProcessIdentity = RpcProcessIdentity(
            value.get("pid").asLong,
            value.get("startTime").asString,
            value.get("instanceId").asString,
            value.get("sessionToken").asString,
            value.get("gameDir").asString,
        )
    }
}

data class RpcHandshake(val plane: String, val protocolVersion: Int, val modVersion: String, val process: RpcProcessIdentity)

/**
 * 连接参数。`plane` 决定默认值：服务端平面（`world.*`…）默认 TCP 25580 + 发现文件
 * `mcdebug/port`；客户端平面（`gui.*`，需要运行中的游戏客户端）默认 TCP 25581 +
 * 发现文件 `mcdebug/clientPort`。两个平面的默认值必须不同——单机客户端在同一个
 * 进程、同一个 gameDir 里同时跑两个平面。
 */
data class RpcClientOptions(
    val socket: String? = null,
    /** TCP 目标 "host:port"（port 可省略，用该平面的默认端口）；指定后优先于 socket。 */
    val tcp: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val portFile: String? = null,
    /** TCP connect 以及连接后 server.ready 握手的等待上限（各自计时）。 */
    val timeoutMs: Int = 5000,
    /** [PLANE_SERVER]（默认）或 [PLANE_CLIENT]。 */
    val plane: String = PLANE_SERVER,
    /** null = 普通 RPC 30s / wait.until 无限；0 = 所有请求无限；正数覆盖所有 RPC。 */
    val requestTimeoutMs: Long? = null,
    /** Session connections must not follow a replaced endpoint into a different JVM. */
    val expectedProcess: RpcProcessIdentity? = null,
) {
    init {
        require(tcp == null || (host == null && port == null)) {
            "--tcp 与 --host/--port 不能同时使用"
        }
        require((host == null) == (port == null)) {
            "--host 与 --port 必须成对使用"
        }
        require(plane == PLANE_SERVER || plane == PLANE_CLIENT) { "unknown plane: $plane" }
        require(timeoutMs > 0) { "--timeout must be > 0 ms" }
        require(requestTimeoutMs == null || requestTimeoutMs >= 0) { "--request-timeout must be >= 0 seconds" }
    }

    internal fun requestTimeoutFor(method: String): Long =
        requestTimeoutMs ?: if (method == "wait.until") 0L else 30_000L

    val isClient: Boolean get() = plane == PLANE_CLIENT

    /** 该平面 TCP 默认端口（--tcp/--client-tcp 省略端口时用）。 */
    val defaultTcpPort: Int get() = if (isClient) CLIENT_TCP_PORT else SERVER_TCP_PORT

    /** 该平面的 socket 发现文件候选（相对 CWD，按序尝试）。 */
    val socketFileCandidates: List<String>
        get() = if (isClient) CLIENT_SOCKET_FILES else SERVER_SOCKET_FILES

    /** 该平面的 socket 覆盖环境变量名。 */
    val socketEnvVar: String get() = if (isClient) CLIENT_SOCKET_ENV else SERVER_SOCKET_ENV

    companion object {
        const val PLANE_SERVER = "server"
        const val PLANE_CLIENT = "client"

        const val SERVER_TCP_PORT = 25580
        const val CLIENT_TCP_PORT = 25581
        const val SERVER_SOCKET_ENV = "MCDEBUG_SOCKET"
        const val CLIENT_SOCKET_ENV = "MCDEBUG_CLIENT_SOCKET"

        val SERVER_SOCKET_FILES =
            listOf("mcdebug/port", "run/mcdebug/port", "../run/mcdebug/port", "../../run/mcdebug/port")
        val CLIENT_SOCKET_FILES = listOf(
            "mcdebug/clientPort",
            "run/mcdebug/clientPort",
            "../run/mcdebug/clientPort",
            "../../run/mcdebug/clientPort",
        )
    }
}

/**
 * JSON-RPC 2.0 客户端，unix socket / TCP + NDJSON，双传输自动选择：
 * `tcp`/`host+port` → TCP（跨机）；否则 unix socket（本机）。
 * 连接复用、请求并发（id 关联），与服务器的连接生命周期一一对应。
 *
 * 实现注意：直接操作 SocketChannel 的 ByteBuffer 读写，**不要**用
 * `Channels.newInputStream/newOutputStream` —— JDK 17 的 Channels 对
 * SocketChannel 的读写都 `synchronized(channel)`：读线程持锁阻塞读时，
 * 写线程永远拿不到锁（三方死锁：读线程等服务端、写线程等锁、服务端等请求）。
 * 已在 JUnit 并行测试中实测复现（JDK 21 的 Channels 无此问题，故 CLI 正常）。
 */
class RpcClient(private val opts: RpcClientOptions) : AutoCloseable {
    private val stateLock = Any()
    private val writeLock = Any()
    @Volatile private var channel: SocketChannel? = null
    private var terminalError: Throwable? = null
    private val ready = CompletableFuture<RpcHandshake>()
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Any, CompletableFuture<JsonElement>>()

    internal fun requireSameBatchOptions(options: RpcClientOptions) {
        require(opts == options) { "cannot change session, transport or deadlines inside a shared batch connection; use a separate invocation" }
    }

    private fun discoverSocket(): String {
        opts.socket?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv(opts.socketEnvVar)?.takeIf { it.isNotBlank() }?.let { return it }
        val candidates = listOfNotNull(opts.portFile) + opts.socketFileCandidates
        for (p in candidates) {
            try {
                val txt = Files.readString(Path.of(p)).trim()
                if (txt.isNotEmpty()) return txt
            } catch (_: Exception) {
                // try next
            }
        }
        throw IllegalStateException(
            "cannot discover mcdebug ${opts.plane} socket: pass " +
                (if (opts.isClient) "--client-socket" else "--socket") +
                ", set ${opts.socketEnvVar}, or run from a directory with a " +
                "${opts.socketFileCandidates.first()} discovery file",
        )
    }

    /** 解析 TCP 目标 "host[:port]"；port 省略时用该平面默认端口（支持 [v6] 括号）。 */
    private fun tcpTarget(): InetSocketAddress? {
        val raw = opts.tcp ?: return (opts.host ?: return null).let { h ->
            InetSocketAddress(h, opts.port ?: opts.defaultTcpPort)
        }
        val host: String
        val port: Int
        if (raw.startsWith("[")) {
            val close = raw.indexOf(']')
            require(close > 1) { "invalid --tcp address: $raw" }
            host = raw.substring(1, close)
            port = raw.substring(close + 1).removePrefix(":")
                .takeIf { it.isNotEmpty() }?.toIntOrNull() ?: opts.defaultTcpPort
        } else {
            val idx = raw.lastIndexOf(':')
            if (idx < 0) {
                host = raw
                port = opts.defaultTcpPort
            } else {
                host = raw.substring(0, idx)
                port = raw.substring(idx + 1).toIntOrNull()
                    ?: throw IllegalArgumentException("invalid --tcp port: $raw")
            }
        }
        return InetSocketAddress(host, port)
    }

    private fun ensureConnected() = synchronized(stateLock) {
        terminalError?.let { throw it }
        if (channel != null) return@synchronized
        val tcp = tcpTarget()
        if (tcp != null) connectTcp(tcp) else connectUnix()
        val ch = requireNotNull(channel)
        Thread({ readLoop(ch) }, "mcdebug-rpc-read").apply {
            isDaemon = true
            start()
        }
    }

    /** 首条通知就是握手；没有旧服务器 status fallback。握手等待不受请求无限选项影响。 */
    fun handshake(): RpcHandshake {
        ensureConnected()
        return try {
            ready.get(opts.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            val error = IOException("server.ready handshake timed out after ${opts.timeoutMs}ms", e)
            failConnection(error)
            throw error
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } catch (e: InterruptedException) {
            failConnection(e)
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /** TCP 连接（跨机访问）；connect 带 timeout（--timeout，默认 5s）。 */
    private fun connectTcp(addr: InetSocketAddress) {
        val ch = SocketChannel.open()
        try {
            ch.socket().connect(addr, opts.timeoutMs)
        } catch (e: Exception) {
            ch.close()
            throw IllegalStateException("cannot connect to mcdebug TCP $addr: ${e.message}", e)
        }
        channel = ch
    }

    /** unix socket 连接（本机访问，0.5.x 主通道）。 */
    private fun connectUnix() {
        val path = discoverSocket()
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            ch.connect(UnixDomainSocketAddress.of(path))
        } catch (e: Exception) {
            ch.close()
            throw IllegalStateException("cannot connect to mcdebug socket $path: ${e.message}", e)
        }
        channel = ch
    }

    /** 读线程：逐字节找行分隔，按 UTF-8 解码整行后分发响应/通知。
     *
     * 注意：不能逐字节 cast 成 Char（0.5.x 的做法）——那会把 UTF-8 多字节序列拆成
     * 单个字节的 mojibake，导致非 ASCII 文本（set-nbt 写中文/emoji 后读回）乱码。 */
    private fun readLoop(ch: SocketChannel) {
        val bb = ByteBuffer.allocate(64 * 1024)
        val lineBuf = java.io.ByteArrayOutputStream(64 * 1024)
        try {
            while (true) {
                bb.clear()
                val n = ch.read(bb)
                if (n < 0) break
                bb.flip()
                while (bb.hasRemaining()) {
                    val b = bb.get()
                    if (b == NL_BYTE) {
                        val bytes = lineBuf.toByteArray()
                        var len = bytes.size
                        if (len > 0 && bytes[len - 1] == CR_BYTE) len--
                        if (len > 0) handleLine(String(bytes, 0, len, StandardCharsets.UTF_8))
                        lineBuf.reset()
                    } else {
                        lineBuf.write(b.toInt())
                    }
                }
            }
        } catch (e: Exception) {
            failConnection(e)
        } finally {
            failConnection(IOException("connection closed"))
        }
    }

    private fun handleLine(line: String) {
        val msg = JsonParser.parseString(line).asJsonObject
        if (!ready.isDone) {
            require(msg.get("method")?.asString == "server.ready" && !msg.has("id")) {
                "expected server.ready as first message"
            }
            val p = msg.getAsJsonObject("params")
            val handshake = RpcHandshake(
                p.get("plane").asString, p.get("protocolVersion").asInt, p.get("modVersion").asString,
                RpcProcessIdentity.fromJson(requireNotNull(p.getAsJsonObject("process")) { "server.ready is missing process identity" }),
            )
            require(handshake.plane == opts.plane) { "expected ${opts.plane} plane, got ${handshake.plane}" }
            require(handshake.protocolVersion == 1) { "unsupported protocolVersion: ${handshake.protocolVersion}" }
            require(opts.expectedProcess == null || handshake.process == opts.expectedProcess) {
                "session process identity mismatch: endpoint belongs to another client/server instance"
            }
            ready.complete(handshake)
            return
        }
        if (!msg.has("id") || msg.get("id").isJsonNull) return  // other server notifications
        val id: Any = msg.get("id").let {
            when {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber -> it.asLong
                it.isJsonPrimitive && it.asJsonPrimitive.isString -> it.asString
                else -> it.toString()
            }
        }
        synchronized(stateLock) {
            val f = pending[id] ?: return
            if (msg.has("error")) {
                val err = msg.getAsJsonObject("error")
                f.completeExceptionally(
                    RpcException(err.get("code").asInt, err.get("message").asString, err.get("data")),
                )
            } else {
                f.complete(msg.get("result"))
            }
            pending.remove(id)
        }
    }

    /**
     * deadline 从握手完成后计时，包含写锁等待/写入/响应；0 = 无限。
     * wait.until 默认由服务端 timeoutTicks 控制，不能从 tick 数推断墙钟时长。
     * 超时关闭连接并终止所有 pending；不代表远端已经执行的操作已被取消或回滚。
     */
    fun call(
        method: String,
        params: JsonElement? = null,
        requestTimeoutMs: Long = opts.requestTimeoutFor(method),
    ): JsonElement {
        require(requestTimeoutMs >= 0) { "requestTimeoutMs must be >= 0" }
        handshake()
        val id = nextId.getAndIncrement()
        val req = jsonObj {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val f = CompletableFuture<JsonElement>()
        val ch = synchronized(stateLock) {
            terminalError?.let { throw it }
            pending[id] = f
            requireNotNull(channel)
        }
        val deadline = if (requestTimeoutMs == 0L) null else deadlines.schedule({
            // 与响应完成串行裁决，已经完成的请求不应关闭一条仍在复用的连接。
            synchronized(stateLock) {
                if (!f.isDone) failConnection(RpcRequestTimeoutException(method, requestTimeoutMs))
            }
        }, requestTimeoutMs, TimeUnit.MILLISECONDS)
        return try {
            try {
                synchronized(writeLock) {
                    if (!f.isDone) writeLine(ch, req.toString())
                }
            } catch (e: Exception) {
                failConnection(e)
            }
            f.get()
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } catch (e: InterruptedException) {
            failConnection(e)
            Thread.currentThread().interrupt()
            throw e
        } finally {
            deadline?.cancel(false)
            pending.remove(id, f)
        }
    }

    /** 写锁仅覆盖整行写入，不持有连接状态锁、不持锁等待响应。 */
    private fun writeLine(ch: SocketChannel, s: String) {
        val bytes = (s + "\n").toByteArray(StandardCharsets.UTF_8)
        var off = 0
        while (off < bytes.size) {
            val n = ch.write(ByteBuffer.wrap(bytes, off, bytes.size - off))
            if (n < 0) throw IOException("connection closed")
            off += n
        }
    }

    /** 终态：不自动重连，避免把有副作用的请求偷偷重发给新连接。 */
    private fun failConnection(error: Throwable) = synchronized(stateLock) {
        if (terminalError != null) return@synchronized
        terminalError = error
        runCatching { channel?.close() }
        channel = null
        ready.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        failConnection(IOException("connection closed"))
    }

    companion object {
        private const val NL_BYTE: Byte = '\n'.code.toByte()
        private const val CR_BYTE: Byte = '\r'.code.toByte()
        private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "mcdebug-rpc-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
