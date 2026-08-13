package com.mcdebug.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * 0.6.0 回归测试：RpcClient 读线程必须按 UTF-8 解码整行（0.5.x 逐字节 cast 成
 * Char 会把多字节序列拆成 mojibake，导致 set-nbt 写非 ASCII 文本后读回乱码）。
 * 用本机 TCP loopback 假服务端做端到端验证（覆盖 CLI 读写两个方向）。
 */
class RpcClientUtf8Test {

    @Test
    fun `non-ascii response decodes as utf8 and request is valid utf8`() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val serverRequest = AtomicReference<JsonObject>()
        val responder = Thread {
            try {
                val sock = server.accept()
                val input = sock.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                serverRequest.set(JsonParser.parseString(input.readLine()).asJsonObject)
                val resp = JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    addProperty("id", serverRequest.get().get("id").asLong)
                    add("result", JsonObject().apply { addProperty("text", "中文 café 😀") })
                }
                sock.getOutputStream().write((resp.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                sock.getOutputStream().flush()
                sock.close()
            } catch (_: Exception) {
                // 断言失败通过主线程超时/断言暴露
            }
        }
        responder.isDaemon = true
        responder.start()
        try {
            RpcClient(RpcClientOptions(tcp = "127.0.0.1:$port")).use { client ->
                val result = client.call("world.getBlock", JsonObject())
                assertEquals("中文 café 😀", result.asJsonObject.get("text").asString)
                // 请求方向：客户端发出去的行必须是合法 UTF-8（此处按 UTF-8 解析成功即证明）。
                val req = serverRequest.get()
                assertEquals("world.getBlock", req.get("method").asString)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `writeLine sends utf8 bytes (non-ascii params survive)`() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val raw = AtomicReference<String>()
        val responder = Thread {
            try {
                val sock = server.accept()
                // 只读一行（客户端请求恰好一行 NDJSON）；readBytes 会等 EOF，而客户端不主动断开会死锁。
                val line = sock.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                raw.set(line)
                sock.getOutputStream().write(
                    ("""{"jsonrpc":"2.0","id":0,"result":{}}""" + "\n").toByteArray(StandardCharsets.UTF_8)
                )
                sock.getOutputStream().flush()
                sock.close()
            } catch (_: Exception) {
            }
        }
        responder.isDaemon = true
        responder.start()
        try {
            RpcClient(RpcClientOptions(tcp = "127.0.0.1:$port")).use { client ->
                val params = JsonObject().apply { addProperty("name", "中文😀") }
                client.call("be.setNbt", params)
                val sent = JsonParser.parseString(raw.get().trim().substringBeforeLast('\n')).asJsonObject
                assertEquals("中文😀", sent.getAsJsonObject("params").get("name").asString)
            }
        } finally {
            server.close()
        }
    }
}
