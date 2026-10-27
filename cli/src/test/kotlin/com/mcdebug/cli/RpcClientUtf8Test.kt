package com.mcdebug.cli

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** TCP end-to-end UTF-8 regression tests, using the mandatory server.ready handshake. */
@Timeout(10)
class RpcClientUtf8Test {
    @Test
    fun `non-ascii response decodes as utf8 and request is valid utf8`() {
        LoopbackRpcServer { socket ->
            val request = readRequest(socket)
            assertEquals("world.getBlock", request.get("method").asString)
            reply(socket, request, JsonObject().apply { addProperty("text", "中文 café 😀") })
        }.use { server ->
            RpcClient(server.options()).use { client ->
                val result = client.call("world.getBlock", JsonObject())
                assertEquals("中文 café 😀", result.asJsonObject.get("text").asString)
            }
            server.await()
        }
    }

    @Test
    fun `writeLine sends utf8 bytes (non-ascii params survive)`() {
        LoopbackRpcServer { socket ->
            val request = readRequest(socket)
            assertEquals("中文😀", request.getAsJsonObject("params").get("name").asString)
            reply(socket, request, JsonObject())
        }.use { server ->
            RpcClient(server.options()).use { client ->
                val params = JsonObject().apply { addProperty("name", "中文😀") }
                client.call("be.setNbt", params)
            }
            server.await()
        }
    }
}
