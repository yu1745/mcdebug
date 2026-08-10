package com.mcdebug.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
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
    val portFile: String? = null,
    val timeoutMs: Int = 5000,
)

/**
 * JSON-RPC 2.0 客户端，unix socket + NDJSON。
 * 连接复用、请求并发（id 关联），与服务器的连接生命周期一一对应。
 */
class RpcClient(private val opts: RpcClientOptions) : AutoCloseable {
    private var channel: SocketChannel? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
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

    private fun ensureConnected() {
        if (channel?.isOpen == true) return
        val path = discoverSocket()
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            ch.connect(UnixDomainSocketAddress.of(path))
        } catch (e: Exception) {
            ch.close()
            throw IllegalStateException("cannot connect to mcdebug socket $path: ${e.message}", e)
        }
        channel = ch
        reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))
        readThread = Thread(::readLoop, "mcdebug-rpc-read").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                val line = reader!!.readLine() ?: break
                if (line.isBlank()) continue
                val msg = JsonParser.parseString(line).asJsonObject
                if (msg.has("id") && !msg.get("id").isJsonNull) {
                    val id: Any = msg.get("id").let {
                        when {
                            it.isJsonPrimitive && it.asJsonPrimitive.isNumber -> it.asLong
                            it.isJsonPrimitive && it.asJsonPrimitive.isString -> it.asString
                            else -> it.toString()
                        }
                    }
                    val f = pending.remove(id) ?: continue
                    if (msg.has("error")) {
                        val err = msg.getAsJsonObject("error")
                        f.completeExceptionally(
                            RpcException(err.get("code").asInt, err.get("message").asString, err.get("data")),
                        )
                    } else {
                        f.complete(msg.get("result"))
                    }
                }
                // server notifications（server.ready 等）暂不订阅
            }
        } catch (_: Exception) {
            // connection dropped
        } finally {
            val err = IllegalStateException("connection closed")
            pending.values.forEach { it.completeExceptionally(err) }
            pending.clear()
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
        writer!!.write(req.toString())
        writer!!.write("\n")
        writer!!.flush()
        // 阻塞等待。wait.until 等长轮询方法会一直挂到条件满足，这里不做超时。
        return try {
            f.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    override fun close() {
        runCatching { channel?.close() }
        channel = null
    }
}
