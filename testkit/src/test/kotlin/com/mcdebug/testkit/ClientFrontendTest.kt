package com.mcdebug.testkit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.sdk.PngCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier

/** Negative controls against real JSON-RPC peers; no Minecraft, CLI, Gradle child or display. */
@Timeout(10)
class ClientFrontendTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `test front end has no command line or game dependency`() {
        assertThrows(ClassNotFoundException::class.java) { Class.forName("com.mcdebug.cli.McDebugCli") }
        assertThrows(ClassNotFoundException::class.java) { Class.forName("net.minecraft.client.Minecraft") }
    }

    @Test
    fun `explicit execution fails when the selected client is missing`() = properties {
        assertThrows(IllegalStateException::class.java) { ClientTestRuntime() }
    }

    @Test
    fun `business rejection fails actions while raw calls support negative assertions`() = peers { ctx ->
        assertThrows(AssertionError::class.java) { ctx.act("gui.click", mapOf("slot" to 0)) }
        assertFalse(ctx.call("gui.click", mapOf("slot" to 0))["ok"].asBoolean)
    }

    @Test
    fun `unmet condition times out instead of being counted as a pass`() = peers { ctx ->
        assertThrows(AssertionError::class.java) {
            ctx.await("a deliberately false condition", Duration.ofMillis(20), BooleanSupplier { false })
        }
    }

    @Test
    fun `failure evidence is saved without swallowing the original assertion`() = peers { ctx ->
        val failure = AssertionError("the actual expected value did not arrive")
        ctx.collectFailure(failure)
        assertTrue(Files.readString(ctx.artifactsDir.resolve("failure.txt")).contains(failure.message!!))
        assertTrue(Files.size(ctx.artifactsDir.resolve("failure.png")) > 0)
        assertEquals(1, PngCodec.read(ctx.artifactsDir.resolve("failure.png")).width)
        assertTrue(Files.exists(ctx.artifactsDir.resolve("screen.json")))
        assertFalse(Files.readString(ctx.artifactsDir.resolve("failure.json")).contains("\"png\":"))
        assertEquals(0, failure.suppressed.size)
    }

    @Test
    fun `new render errors fail a test even when its own assertions passed`() = peers(error = true) { ctx ->
        assertThrows(AssertionError::class.java) { ctx.assertNoNewErrors() }
    }

    private fun properties(block: () -> Unit) {
        val settings = mapOf("mcdebug.gameDir" to temp.toString(), "mcdebug.artifactsDir" to temp.resolve("reports").toString())
        val old = settings.keys.associateWith(System::getProperty)
        try {
            settings.forEach(System::setProperty)
            block()
        } finally {
            old.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        }
    }

    private fun peers(error: Boolean = false, test: (ClientTestContext) -> Unit) = properties {
        FrontendPeer(temp, "server", error).use { server ->
            FrontendPeer(temp, "client", error).use { client ->
                val discovery = temp.resolve("mcdebug")
                Files.createDirectories(discovery)
                Files.writeString(discovery.resolve("port"), server.endpoint)
                Files.writeString(discovery.resolve("clientPort"), client.endpoint)
                ClientTestRuntime().use { runtime ->
                    test(ClientTestContext(runtime, temp.resolve("evidence")))
                }
            }
        }
    }
}

private class FrontendPeer(game: Path, plane: String, error: Boolean) : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val endpoint = "tcp:127.0.0.1:${listener.localPort}"
    private val executor = Executors.newCachedThreadPool()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val png = Base64.getEncoder().encodeToString(PngCodec.encode(PngCodec.Image(1, 1, byteArrayOf(0, 0, 0, -1))))

    init {
        executor.submit {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: java.io.IOException) { break }
                sockets.add(socket)
                executor.submit {
                    socket.use {
                        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        fun write(text: String) { writer.write(text); writer.newLine(); writer.flush() }
                        val self = ProcessHandle.current()
                        val process = """{"pid":${self.pid()},"startTime":"${self.info().startInstant().orElseThrow()}","instanceId":"test","sessionToken":"","gameDir":"${game.toString().replace("\\", "\\\\")}"}"""
                        write("""{"jsonrpc":"2.0","method":"server.ready","params":{"plane":"$plane","protocolVersion":1,"modVersion":"test","process":$process}}""")
                        socket.getInputStream().bufferedReader(Charsets.UTF_8).forEachLine { line ->
                            val request = JsonParser.parseString(line).asJsonObject
                            val method = request["method"].asString
                            val result = when (method) {
                                "gui.status" -> """{"inWorld":true,"contractMismatch":[]}"""
                                "gui.problems" -> {
                                    val params = request.getAsJsonObject("params")
                                    if (error && params?.has("since") == true) """{"available":true,"next":8,"truncated":false,"entries":[{"seq":7,"level":"ERROR","message":"render failed"}]}"""
                                    else """{"available":true,"next":7,"truncated":false,"entries":[]}"""
                                }
                                "gui.click" -> """{"ok":false,"reason":"deliberate rejection"}"""
                                "gui.screenshot" -> """{"width":1,"height":1,"png":"$png"}"""
                                else -> "{}"
                            }
                            write("""{"jsonrpc":"2.0","id":${request["id"]},"result":$result}""")
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { it.close() }
        executor.shutdownNow()
    }
}
