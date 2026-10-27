package com.mcdebug.cli

import com.github.ajalt.clikt.core.UsageError
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

@Isolated
@Timeout(10)
class GuiInputCommandsTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `input commands send explicit parameters on the client plane`() {
        val cases = listOf(
            listOf("key", "--key", "A", "--ctrl", "--shift") to "gui.key",
            listOf("key", "--code", "259") to "gui.key",
            listOf("type", "--text", "机器 😀", "--clear") to "gui.type",
            listOf("drag", "--from", "1,2", "--to", "5,6", "--space", "png", "--steps", "4", "--step-ticks", "2", "--ctrl") to "gui.drag",
            listOf("disconnect", "--wait-ticks", "10") to "gui.disconnect",
        )
        for ((args, method) in cases) {
            LoopbackRpcServer("client") { socket ->
                val request = readRequest(socket)
                assertEquals(method, request.get("method").asString)
                val p = request.getAsJsonObject("params")
                when (args[0]) {
                    "key" -> if (args.contains("--code")) assertEquals(259, p.get("key").asInt) else {
                        assertEquals("A", p.get("key").asString)
                        assertEquals(3, p.get("modifiers").asInt)
                    }
                    "type" -> { assertEquals("机器 😀", p.get("text").asString); assertTrue(p.get("clear").asBoolean) }
                    "drag" -> { assertEquals(4, p.get("steps").asInt); assertEquals(2, p.get("stepTicks").asInt); assertEquals("png", p.get("space").asString) }
                    "disconnect" -> assertEquals(10, p.get("waitTicks").asInt)
                }
                reply(socket, request, JsonObject().apply { addProperty("ok", true) })
            }.use { server ->
                buildCli().parse(listOf("--client-tcp", server.options().tcp!!, "gui") + args)
                server.await()
            }
        }
    }

    @Test
    fun `invalid CLI gesture and key selections fail before any connection`() {
        for (args in listOf(
            listOf("key"), listOf("key", "--key", "A", "--code", "65"),
            listOf("drag", "--from", "NaN,2", "--to", "1,2"),
            listOf("drag", "--from", "1,2", "--to", "3,4", "--steps", "128", "--step-ticks", "20"),
            listOf("disconnect", "--wait-ticks", "0"),
        )) assertThrows(UsageError::class.java) { buildCli().parse(listOf("gui") + args) }
    }

    @Test
    fun `SDK golden assertion compares remote inline PNG without reading remote path`() {
        val png = PngCodec.encode(PngCodec.Image(1, 1, byteArrayOf(12, 34, 56, -1)))
        val expected = temp.resolve("expected.png")
        Files.write(expected, png)
        LoopbackRpcServer("client") { socket ->
            val request = readRequest(socket)
            assertEquals("gui.screenshot", request.get("method").asString)
            val p = request.getAsJsonObject("params")
            assertTrue(p.get("inline").asBoolean)
            assertEquals(0, p.get("delayTicks").asInt)
            reply(socket, request, JsonObject().apply {
                addProperty("path", "/this/remote/path/is/not/local.png")
                addProperty("png", Base64.getEncoder().encodeToString(png))
            })
        }.use { server ->
            RpcClient(server.options(plane = "client")).use { client ->
                assertTrue(DebugApi(client).gui.assertScreenshot(expected, mode = "window").matches)
            }
            server.await()
        }
        assertArrayEquals(png, Files.readAllBytes(expected))
    }
}
