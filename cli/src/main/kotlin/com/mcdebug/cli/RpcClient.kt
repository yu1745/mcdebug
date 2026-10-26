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

/** JSON-RPC 错误（code -32700..-32603 标准 + -32099..-32000 自定义）。 */
class RpcException(val rpcCode: Int, message: String, val data: JsonElement? = null) :
    RuntimeException(message)

/**
 * 连接参数。`plane` 决定默认值：服务端平面（`world.*`…）默认 TCP 25580 + 发现文件
 * `mcdebug/port`；客户端平面（`gui.*`，需要运行中的游戏客户端）默认 TCP 25581 +
 * 发现文件 `mcdebug/clientPort`。两个平面的默认值必须不同——单机客户端在同一个
 * 进程、同一个 gameDir 里同时跑两个平面。
 */
class RpcClientOptions(
    val socket: String? = null,
    /** TCP 目标 "host:port"（port 可省略，用该平面的默认端口）；指定后优先于 socket。 */
    val tcp: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val portFile: String? = null,
    val timeoutMs: Int = 5000,
    /** [PLANE_SERVER]（默认）或 [PLANE_CLIENT]。 */
    val plane: String = PLANE_SERVER,
) {
    init {
        require(tcp == null || (host == null && port == null)) {
            "--tcp 与 --host/--port 不能同时使用"
        }
        require((host == null) == (port == null)) {
            "--host 与 --port 必须成对使用"
        }
        require(plane == PLANE_SERVER || plane == PLANE_CLIENT) { "unknown plane: $plane" }
    }

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
    private var channel: SocketChannel? = null
    private var readThread: Thread? = null
    private var nextId = 0L
    private val pending = ConcurrentHashMap<Any, CompletableFuture<JsonElement>>()

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

    private fun ensureConnected() {
        if (channel?.isOpen == true) return
        val tcp = tcpTarget()
        if (tcp != null) {
            connectTcp(tcp)
        } else {
            connectUnix()
        }
        readThread = Thread(::readLoop, "mcdebug-rpc-read").apply {
            isDaemon = true
            start()
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
    private fun readLoop() {
        val bb = ByteBuffer.allocate(64 * 1024)
        val lineBuf = java.io.ByteArrayOutputStream(64 * 1024)
        try {
            while (true) {
                bb.clear()
                val n = channel!!.read(bb)
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
        } catch (_: IOException) {
            // connection dropped
        } catch (_: Exception) {
            // channel closed
        } finally {
            val err = IllegalStateException("connection closed")
            pending.values.forEach { it.completeExceptionally(err) }
            pending.clear()
        }
    }

    private fun handleLine(line: String) {
        val msg = JsonParser.parseString(line).asJsonObject
        if (!msg.has("id") || msg.get("id").isJsonNull) return  // server notification
        val id: Any = msg.get("id").let {
            when {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber -> it.asLong
                it.isJsonPrimitive && it.asJsonPrimitive.isString -> it.asString
                else -> it.toString()
            }
        }
        val f = pending.remove(id) ?: return
        if (msg.has("error")) {
            val err = msg.getAsJsonObject("error")
            f.completeExceptionally(
                RpcException(err.get("code").asInt, err.get("message").asString, err.get("data")),
            )
        } else {
            f.complete(msg.get("result"))
        }
    }

    @Synchronized
    fun call(method: String, params: JsonElement? = null): JsonElement {
        ensureConnected()
        val id = nextId++
        val req = jsonObj {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val f = CompletableFuture<JsonElement>()
        pending[id] = f
        writeLine(req.toString())
        // 阻塞等待。wait.until 等长轮询方法会一直挂到条件满足，这里不做超时。
        return try {
            f.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    /** 整行写入（SocketChannel.write 直写，阻塞直到全部写出）。 */
    private fun writeLine(s: String) {
        val bytes = (s + "\n").toByteArray(StandardCharsets.UTF_8)
        var off = 0
        while (off < bytes.size) {
            val n = channel!!.write(ByteBuffer.wrap(bytes, off, bytes.size - off))
            if (n < 0) throw IllegalStateException("connection closed")
            off += n
        }
    }

    override fun close() {
        runCatching { channel?.close() }
        channel = null
    }

    companion object {
        private const val NL_BYTE: Byte = '\n'.code.toByte()
        private const val CR_BYTE: Byte = '\r'.code.toByte()
    }
}
