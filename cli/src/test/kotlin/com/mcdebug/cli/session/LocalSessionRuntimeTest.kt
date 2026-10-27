package com.mcdebug.cli.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.cli.RpcClientOptions
import com.mcdebug.cli.RpcClient
import com.mcdebug.cli.RpcProcessIdentity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real local transports, but no Minecraft, Gradle invocation, or display. */
@Timeout(15)
class LocalSessionRuntimeTest {
    @TempDir lateinit var temp: Path

    private fun identity(game: Path, instance: String = "instance", token: String = ""): RpcProcessIdentity {
        val process = ProcessHandle.current()
        return RpcProcessIdentity(process.pid(), process.info().startInstant().get().toString(), instance, token, game.toString())
    }
    private fun discovery(game: Path, process: RpcProcessIdentity, socket: Path) {
        Files.createDirectories(game.resolve("mcdebug"))
        Files.writeString(game.resolve("mcdebug/client.json"), JsonObject().apply {
            add("process", process.toJson()); addProperty("clientSocket", socket.toString())
        }.toString())
    }

    @Test fun `real Unix attach and dual plane resolution handshake the same process`() {
        val game = Files.createDirectories(temp.resolve("game")).toRealPath()
        val process = identity(game)
        val clientSocket = temp.resolve("c.sock")
        val serverSocket = temp.resolve("s.sock")
        val serverIdentity = AtomicReference(process)
        UnixPeer(clientSocket, "client") { process }.use { client ->
            UnixPeer(serverSocket, "server") { serverIdentity.get() }.use {
                discovery(game, process, clientSocket)
                Files.writeString(game.resolve("mcdebug/port"), serverSocket.toString())
                val manager = SessionManager(temp.resolve("registry"))
                assertEquals("world_ready", manager.attach("external", game).get("state").asString)
                assertEquals(process, manager.resolve("external", "client").expectedProcess)
                assertEquals(process, manager.resolve("external", "server").expectedProcess)
                serverIdentity.set(process.copy(instanceId = "other-jvm"))
                val replacedOptions = manager.resolve("external", "server")
                assertThrows(IllegalArgumentException::class.java) { RpcClient(replacedOptions).use { it.handshake() } }
                assertTrue(client.requests.all { it.get("method").asString == "gui.status" })
                assertThrows(IllegalStateException::class.java) { manager.stop("external", true, 0) }
            }
        }
    }

    @Test fun `normal stop carries exact instance and token over verified Unix connection`() {
        val game = Files.createDirectories(temp.resolve("game")).toRealPath()
        val process = identity(game, token = "owned-token")
        val socket = temp.resolve("c.sock")
        UnixPeer(socket, "client") { process }.use { peer ->
            val options = RpcClientOptions(socket = socket.toString(), plane = "client", expectedProcess = process)
            LocalSessionRuntime().requestStop(options)
            val request = peer.requests.single()
            assertEquals("gui.stop", request.get("method").asString)
            assertEquals(process.instanceId, request.getAsJsonObject("params").get("instanceId").asString)
            assertEquals(process.sessionToken, request.getAsJsonObject("params").get("sessionToken").asString)
            assertThrows(IllegalArgumentException::class.java) {
                LocalSessionRuntime().requestStop(RpcClientOptions(socket = socket.toString(), plane = "client",
                    expectedProcess = process.copy(instanceId = "wrong")))
            }
            assertEquals(1, peer.requests.size)
        }
    }

    @Test fun `identity-only verification also rejects mismatched ready over loopback TCP`() {
        val game = Files.createDirectories(temp.resolve("game")).toRealPath()
        val process = identity(game)
        java.net.ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val serving = executor.submit {
                    repeat(2) {
                        server.accept().use { socket ->
                            val ready = JsonObject().apply {
                                addProperty("jsonrpc", "2.0"); addProperty("method", "server.ready")
                                add("params", JsonObject().apply {
                                    addProperty("plane", "client"); addProperty("protocolVersion", 1); addProperty("modVersion", "test")
                                    add("process", process.toJson())
                                })
                            }
                            socket.getOutputStream().write((ready.toString() + "\n").toByteArray(Charsets.UTF_8))
                            assertEquals(-1, socket.getInputStream().read(), "verify must not send gui.status")
                        }
                    }
                }
                fun options(expected: RpcProcessIdentity) = RpcClientOptions(tcp = "127.0.0.1:${server.localPort}",
                    plane = "client", expectedProcess = expected)
                assertEquals(process, LocalSessionRuntime().verify(options(process)))
                assertThrows(IllegalArgumentException::class.java) {
                    LocalSessionRuntime().verify(options(process.copy(instanceId = "other")))
                }
                serving.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `setsid launches only a target fake shell wrapper with redirected UTF8 log`() {
        val project = Files.createDirectories(temp.resolve("fake project"))
        // This is a shell fixture named gradlew, not Gradle. It cannot launch MC/daemons.
        val wrapper = project.resolve("gradlew")
        Files.writeString(wrapper, "#!/bin/sh\nprintf '中文😀 token=%s arg=%s\\n' \"\$MCDEBUG_SESSION_TOKEN\" \"\$1\"\nexec /bin/sleep 1\n")
        val log = temp.resolve("fake.log")
        val runtime = LocalSessionRuntime()
        val process = runtime.launch(SessionLaunch(listOf("setsid", "--wait", "/bin/sh", wrapper.toString(), "space arg"),
            project, mapOf("MCDEBUG_SESSION_TOKEN" to "token"), log))
        ProcessHandle.of(process.pid).ifPresent { handle -> handle.onExit().get(5, TimeUnit.SECONDS) }
        assertEquals("中文😀 token=token arg=space arg\n", Files.readString(log))
        assertFalse(runtime.isAlive(process))
        val self = runtime.currentProcess()
        assertFalse(runtime.isAlive(self.copy(startTime = "2000-01-01T00:00:00Z")))
        assertThrows(IllegalStateException::class.java) { runtime.terminate(self.copy(startTime = "2000-01-01T00:00:00Z"), true) }
    }
}

private class UnixPeer(path: Path, private val plane: String, private val process: () -> RpcProcessIdentity) : AutoCloseable {
    private val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(path)) }
    private val executor = Executors.newCachedThreadPool()
    private val channels = Collections.synchronizedList(mutableListOf<SocketChannel>())
    val requests: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private val accepting = executor.submit {
        while (listener.isOpen) {
            val channel = try { listener.accept() } catch (_: Exception) { break }
            channels.add(channel)
            executor.submit { serve(channel) }
        }
    }
    private fun serve(channel: SocketChannel) {
        channel.use {
            fun write(json: JsonObject) {
                val bytes = ByteBuffer.wrap((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
            }
            write(JsonObject().apply {
                addProperty("jsonrpc", "2.0"); addProperty("method", "server.ready")
                add("params", JsonObject().apply {
                    addProperty("plane", plane); addProperty("protocolVersion", 1); addProperty("modVersion", "test")
                    add("process", process().toJson())
                })
            })
            val input = Channels.newInputStream(channel).bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = input.readLine() ?: break
                val request = JsonParser.parseString(line).asJsonObject
                requests.add(request)
                write(JsonObject().apply {
                    addProperty("jsonrpc", "2.0"); add("id", request.get("id"))
                    add("result", JsonObject().apply {
                        addProperty("inWorld", true); addProperty("singleplayer", true); addProperty("ok", true)
                    })
                })
            }
        }
    }
    override fun close() {
        listener.close()
        accepting.get(3, TimeUnit.SECONDS)
        synchronized(channels) { channels.forEach { runCatching { it.close() } } }
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
    }
}
