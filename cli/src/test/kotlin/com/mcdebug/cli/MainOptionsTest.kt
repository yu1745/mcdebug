package com.mcdebug.cli

import com.github.ajalt.clikt.core.UsageError
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.cli.session.SessionRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Isolated
import java.nio.charset.StandardCharsets.UTF_8
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.io.TempDir

/** Batch owns global shared clients; isolate these tests from other CLI invocations. */
@Isolated
@Timeout(10)
class MainOptionsTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `batch refuses per-command endpoint overrides instead of reusing the wrong session connection`() {
        for (plane in listOf("server", "client")) {
            val option = if (plane == "client") "--client-tcp" else "--tcp"
            val method = if (plane == "client") "gui.first" else "world.first"
            LoopbackRpcServer(plane) { socket ->
                val input = socket.getInputStream().bufferedReader(UTF_8)
                val first = JsonParser.parseString(input.readLine()).asJsonObject
                assertEquals(method, first.get("method").asString)
                reply(socket, first, JsonObject())
                assertNull(input.readLine(), "must not route the next command through the previous target")
            }.use { server ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    buildCli().parse(listOf(option, server.options().tcp!!, "--batch",
                        "-c", "raw $method {}", "-c", "$option 127.0.0.1:1 raw $method {}"))
                }
                assertTrue(error.message!!.contains("shared batch connection"))
                assertFalse(batchActive)
                assertNull(batchClient)
                assertNull(batchClientPlane)
                server.await()
            }
        }
    }

    @Test
    fun `session resolves both planes and batch inherits registry and session selection`() {
        val game = Files.createDirectories(temp.resolve("game")).toRealPath()
        val registry = Files.createDirectories(temp.resolve("registry with spaces")).toRealPath()
        val self = ProcessHandle.current()
        val identity = RpcProcessIdentity(self.pid(), self.info().startInstant().orElseThrow().toString(),
            "cli-test-instance", "cli-test-token", game.toString())
        SessionUnixPeer(temp.resolve("server.sock"), "server", identity).use { server ->
            SessionUnixPeer(temp.resolve("client.sock"), "client", identity).use { client ->
                val discovery = Files.createDirectories(game.resolve("mcdebug"))
                Files.writeString(discovery.resolve("port"), server.path.toString())
                Files.writeString(discovery.resolve("client.json"), JsonObject().apply {
                    add("process", identity.toJson())
                    addProperty("clientSocket", client.path.toString())
                }.toString())
                val record = SessionRecord(name = "dev", registryDir = registry.toString(), owned = false,
                    sessionToken = identity.sessionToken, gameDir = game.toString(), process = identity,
                    state = "world_ready", clientSocket = client.path.toString(), serverSocket = server.path.toString())
                Files.writeString(registry.resolve("dev.json"), record.toJson().toString())

                val cli = McDebugCli()
                cli.parse(listOf("--sessions-dir", registry.toString(), "--session", "dev", "--timeout", "400", "--request-timeout", "2"))
                for ((options, expectedPath, plane) in listOf(
                    Triple(cli.clientOptions(), server.path, "server"), Triple(cli.clientPlaneOptions(), client.path, "client"),
                )) {
                    assertEquals(expectedPath.toString(), options.socket)
                    assertEquals(plane, options.plane)
                    assertEquals(identity, options.expectedProcess)
                    assertEquals(400, options.timeoutMs)
                    assertEquals(2000L, options.requestTimeoutMs)
                }
                buildCli().parse(listOf("--sessions-dir", registry.toString(), "--session", "dev",
                    "--timeout", "400", "--request-timeout", "2", "--batch",
                    "-c", "raw world.test", "-c", "raw gui.test", "-c", "raw world.test", "-c", "raw gui.test"))
                assertEquals(2, server.methods.count { it == "world.test" })
                assertEquals(2, client.methods.count { it == "gui.test" })
                assertFalse(server.methods.any { it.startsWith("gui.") })
                assertFalse(client.methods.any { it.startsWith("world.") })
                assertFalse(batchActive)
                assertNull(batchClient)
                assertNull(batchClientPlane)
                val fresh = McDebugCli()
                fresh.parse(emptyList())
                assertNull(fresh.session, "session selection must never become a global current session")
            }
        }
    }

    @Test
    fun `request timeout seconds apply to both planes independently from connect milliseconds`() {
        val cli = McDebugCli()
        cli.parse(listOf("--request-timeout", "2", "--timeout", "75"))
        assertEquals(2000L, cli.clientOptions().requestTimeoutMs)
        assertEquals(2000L, cli.clientPlaneOptions().requestTimeoutMs)
        assertEquals(75, cli.clientOptions().timeoutMs)
        assertEquals(75, cli.clientPlaneOptions().timeoutMs)

        val unlimited = McDebugCli()
        unlimited.parse(listOf("--request-timeout", "0"))
        assertEquals(0L, unlimited.clientOptions().requestTimeoutMs)
        assertEquals(0L, unlimited.clientPlaneOptions().requestTimeoutMs)
    }

    @Test
    fun `negative request timeout is a usage error before connecting`() {
        assertThrows(UsageError::class.java) {
            buildCli().parse(listOf("--request-timeout", "-1", "raw", "gui.status"))
        }
    }

    @Test
    fun `session excludes each explicit transport before registry lookup or connection`() {
        for ((option, value) in listOf(
            "--socket" to "/nonexistent", "--tcp" to "localhost:25580", "--host" to "localhost",
            "--port" to "25580", "--port-file" to "/nonexistent", "--client-socket" to "/nonexistent",
            "--client-tcp" to "localhost:25581",
        )) {
            for (command in listOf(listOf("raw", "world.getBlock"), listOf("raw", "gui.status"), listOf("session", "list"))) {
                val error = assertThrows(UsageError::class.java) {
                    buildCli().parse(listOf("--session", "missing-session", option, value) + command)
                }
                assertTrue(error.message!!.contains("--session cannot be combined"), "$option: ${error.message}")
            }
        }
    }

    @Test
    fun `batch inherits outer client address and explicit request deadline`() {
        LoopbackRpcServer("client") { socket ->
            val input = socket.getInputStream().bufferedReader(UTF_8)
            assertNotNull(input.readLine())
            assertNull(input.readLine(), "batch request timeout must close its shared connection")
        }.use { server ->
            val error = assertThrows(RpcRequestTimeoutException::class.java) {
                buildCli().parse(listOf(
                    "--client-tcp", server.options().tcp!!,
                    "--request-timeout", "1",
                    "-c", "raw gui.status",
                ))
            }
            assertEquals(1000L, error.timeoutMs)
            assertFalse(batchActive)
            assertNull(batchClientPlane)
            server.await()
        }
    }
}

/** Finite, identity-bearing Unix peer; supports the manager's probes and the actual batch connections. */
private class SessionUnixPeer(val path: Path, plane: String, identity: RpcProcessIdentity) : AutoCloseable {
    val methods = CopyOnWriteArrayList<String>()
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
        bind(UnixDomainSocketAddress.of(path))
    }
    private val current = AtomicReference<SocketChannel?>()
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private val work = executor.submit {
        try {
            while (!closed) {
                server.accept().use { socket ->
                    current.set(socket)
                    val output = Channels.newOutputStream(socket).bufferedWriter(UTF_8)
                    val ready = JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        addProperty("method", "server.ready")
                        add("params", JsonObject().apply {
                            addProperty("plane", plane)
                            addProperty("protocolVersion", 1)
                            addProperty("modVersion", "dev")
                            add("process", identity.toJson())
                        })
                    }
                    output.write(ready.toString()); output.newLine(); output.flush()
                    val input = Channels.newInputStream(socket).bufferedReader(UTF_8)
                    while (true) {
                        val line = input.readLine() ?: break
                        val request = JsonParser.parseString(line).asJsonObject
                        val method = request.get("method").asString
                        methods.add(method)
                        val reply = JsonObject().apply {
                            addProperty("jsonrpc", "2.0")
                            add("id", request.get("id"))
                            add("result", JsonObject().apply {
                                addProperty("inWorld", true)
                                addProperty("singleplayer", true)
                            })
                        }
                        output.write(reply.toString()); output.newLine(); output.flush()
                    }
                    current.set(null)
                }
            }
        } catch (e: Exception) {
            if (!closed) throw e
        }
    }

    override fun close() {
        closed = true
        current.getAndSet(null)?.close()
        server.close()
        executor.shutdownNow()
        work.get(2, TimeUnit.SECONDS)
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }
}
