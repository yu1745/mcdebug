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

class RpcClientOptions(
    val socket: String? = null,
    /** TCP 目标 "host:port"（port 可省略，默认 25580）；指定后优先于 socket。 */
    val tcp: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val portFile: String? = null,
    val timeoutMs: Int = 5000,
) {
    init {
        require(tcp == null || (host == null && port == null)) {
            "--tcp 与 --host/--port 不能同时使用"
        }
        require((host == null) == (port == null)) {
            "--host 与 --port 必须成对使用"
        }
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
        System.getenv("MCDEBUG_SOCKET")?.takeIf { it.isNotBlank() }?.let { return it }
        val candidates = listOfNotNull(
            opts.portFile,
            "mcdebug/port",
            "run/mcdebug/port",
            "../run/mcdebug/port",
            "../../run/mcdebug/port",
        )
        for (p in candidates) {
            try {
                val txt = Files.readString(Path.of(p)).trim()
                if (txt.isNotEmpty()) return txt
            } catch (_: Exception) {
                // try next
            }
        }
        throw IllegalStateException(
            "cannot discover mcdebug socket: pass --socket, set MCDEBUG_SOCKET, " +
                "or run from a directory with a mcdebug/port discovery file",
        )
    }

    /** 解析 --tcp 参数：host[:port]，默认端口 25580（支持 [v6] 括号）。 */
    private fun tcpTarget(): InetSocketAddress? {
        val raw = opts.tcp ?: return (opts.host ?: return null).let { h ->
            InetSocketAddress(h, opts.port ?: DEFAULT_TCP_PORT)
        }
        val host: String
        val port: Int
        if (raw.startsWith("[")) {
            val close = raw.indexOf(']')
            require(close > 1) { "invalid --tcp address: $raw" }
            host = raw.substring(1, close)
            port = raw.substring(close + 1).removePrefix(":")
                .takeIf { it.isNotEmpty() }?.toIntOrNull() ?: DEFAULT_TCP_PORT
        } else {
            val idx = raw.lastIndexOf(':')
            if (idx < 0) {
                host = raw
                port = DEFAULT_TCP_PORT
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

    /** 读线程：逐字节组行，按 id 分发响应/通知。 */
    private fun readLoop() {
        val bb = ByteBuffer.allocate(64 * 1024)
        val sb = StringBuilder()
        try {
            while (true) {
                bb.clear()
                val n = channel!!.read(bb)
                if (n < 0) break
                bb.flip()
                while (bb.hasRemaining()) {
                    val c = bb.get().toInt().toChar()
                    if (c == '\n') {
                        if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                        if (sb.isNotEmpty()) handleLine(sb.toString())
                        sb.clear()
                    } else {
                        sb.append(c)
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
        const val DEFAULT_TCP_PORT = 25580
    }
}
