package com.mcdebug.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real TCP framing/lifecycle tests; every fake peer speaks the current ready handshake. */
@Timeout(10)
class RpcClientTransportTest {
    @Test
    fun `version warning consumes only ready and no status RPC on either plane`() {
        for (plane in listOf("server", "client")) {
            val method = if (plane == "client") "gui.screen" else "world.getBlock"
            LoopbackRpcServer(plane) { socket ->
                val request = readRequest(socket)
                assertEquals(method, request.get("method").asString)
                reply(socket, request, JsonObject())
            }.use { server ->
                RpcClient(server.options(plane = plane)).use { client ->
                    warnVersionMismatch(client, localVersion = "different-cli")
                    assertEquals(RpcHandshake(plane, 1, "test-mod", testProcessIdentity), client.handshake())
                    client.call(method)
                }
                server.await()
            }
        }
    }

    @Test
    fun `gui status and screenshot requests have a finite independent deadline`() {
        for (method in listOf("gui.status", "gui.screenshot")) {
            LoopbackRpcServer("client") { socket ->
                val input = socket.getInputStream().bufferedReader(UTF_8)
                assertEquals(method, JsonParser.parseString(input.readLine()).asJsonObject.get("method").asString)
                assertNull(input.readLine(), "deadline must close the connection")
            }.use { server ->
                RpcClient(server.options(plane = "client", requestTimeoutMs = 100)).use { client ->
                    val error = assertThrows(RpcRequestTimeoutException::class.java) { client.call(method) }
                    assertEquals(method, error.method)
                    assertEquals(100L, error.timeoutMs)
                    assertTrue(error.message!!.contains("request deadline"))
                    assertThrows(IOException::class.java) { client.call(method) }
                }
                server.await()
            }
        }
    }

    @Test
    fun `automatic policy preserves tick based wait and explicit deadline overrides it`() {
        val automatic = RpcClientOptions()
        assertEquals(30_000L, automatic.requestTimeoutFor("gui.status"))
        assertEquals(30_000L, automatic.requestTimeoutFor("gui.screenshot"))
        assertEquals(0L, automatic.requestTimeoutFor("wait.until"))
        assertEquals(50L, RpcClientOptions(requestTimeoutMs = 50).requestTimeoutFor("wait.until"))
        assertEquals(0L, RpcClientOptions(requestTimeoutMs = 0).requestTimeoutFor("gui.status"))
        LoopbackRpcServer { socket ->
            val input = socket.getInputStream().bufferedReader(UTF_8)
            assertEquals("wait.until", JsonParser.parseString(input.readLine()).asJsonObject.get("method").asString)
            assertNull(input.readLine())
        }.use { server ->
            RpcClient(server.options()).use { client ->
                assertThrows(RpcRequestTimeoutException::class.java) {
                    client.call("wait.until", requestTimeoutMs = 100)
                }
            }
            server.await()
        }
    }

    @Test
    fun `concurrent calls write complete frames without holding lock for responses`() {
        LoopbackRpcServer { socket ->
            val input = socket.getInputStream().bufferedReader(UTF_8)
            val first = JsonParser.parseString(input.readLine()).asJsonObject
            val second = JsonParser.parseString(input.readLine()).asJsonObject
            // A synchronized call() would deadlock before receiving the second request.
            reply(socket, second, second.get("params"))
            reply(socket, first, first.get("params"))
        }.use { server ->
            RpcClient(server.options()).use { client ->
                val first = asyncCall { client.call("echo", JsonObject().apply { addProperty("text", "中文😀") }) }
                val second = asyncCall { client.call("echo", JsonObject().apply { addProperty("text", "café") }) }
                assertEquals("中文😀", first.get(3, TimeUnit.SECONDS).asJsonObject.get("text").asString)
                assertEquals("café", second.get(3, TimeUnit.SECONDS).asJsonObject.get("text").asString)
            }
            server.await()
        }
    }

    @Test
    fun `EOF fails every outstanding request even unlimited waits`() {
        LoopbackRpcServer { socket ->
            val input = socket.getInputStream().bufferedReader(UTF_8)
            repeat(2) { assertNotNull(input.readLine()) }
            // Closing the peer without replies must release BOTH pending callers.
        }.use { server ->
            RpcClient(server.options()).use { client ->
                val first = asyncCall { client.call("wait.until") }
                val second = asyncCall { client.call("wait.until") }
                assertCallFailed(first)
                assertCallFailed(second)
            }
            server.await()
        }
    }

    @Test
    fun `local close immediately releases all pending callers`() {
        val received = CountDownLatch(1)
        LoopbackRpcServer { socket ->
            val input = socket.getInputStream().bufferedReader(UTF_8)
            repeat(2) { assertNotNull(input.readLine()) }
            received.countDown()
            assertNull(input.readLine())
        }.use { server ->
            RpcClient(server.options()).use { client ->
                val first = asyncCall { client.call("wait.until") }
                val second = asyncCall { client.call("wait.until") }
                assertTrue(received.await(3, TimeUnit.SECONDS))
                client.close()
                assertCallFailed(first)
                assertCallFailed(second)
                assertThrows(IOException::class.java) { client.call("server.status") }
            }
            server.await()
        }
    }

    @Test
    fun `deadline fails other pending calls and closes unlimited wait`() {
        LoopbackRpcServer { socket ->
            val input = socket.getInputStream().bufferedReader(UTF_8)
            repeat(2) { assertNotNull(input.readLine()) }
            assertNull(input.readLine())
        }.use { server ->
            RpcClient(server.options()).use { client ->
                val first = asyncCall { client.call("wait.until") }
                val second = asyncCall { client.call("gui.status", requestTimeoutMs = 200) }
                assertInstanceOf(RpcRequestTimeoutException::class.java, assertCallFailed(first))
                assertInstanceOf(RpcRequestTimeoutException::class.java, assertCallFailed(second))
            }
            server.await()
        }
    }

    @Test
    fun `reset during write fails writer and all already pending requests`() {
        val receivedWait = CountDownLatch(1)
        LoopbackRpcServer { socket ->
            // Read bytes directly to avoid buffering part of the next large frame.
            while (true) {
                val byte = socket.getInputStream().read()
                assertTrue(byte >= 0)
                if (byte == '\n'.code) break
            }
            receivedWait.countDown()
            assertTrue(socket.getInputStream().read() >= 0)
            socket.setSoLinger(true, 0) // RST while the large second frame is being written.
        }.use { server ->
            RpcClient(server.options()).use { client ->
                val waiting = asyncCall { client.call("wait.until") }
                assertTrue(receivedWait.await(3, TimeUnit.SECONDS))
                val writer = asyncCall {
                    client.call("be.setNbt", JsonObject().apply { addProperty("text", "x".repeat(8 * 1024 * 1024)) })
                }
                assertCallFailed(writer)
                assertCallFailed(waiting)
            }
            server.await()
        }
    }

    @Test
    fun `deadline also interrupts a blocked large write`() {
        val releasePeer = CountDownLatch(1)
        LoopbackRpcServer { _ ->
            // Do not drain the request: it cannot fit in the TCP buffers.
            assertTrue(releasePeer.await(3, TimeUnit.SECONDS))
        }.use { server ->
            try {
                val params = JsonObject().apply { addProperty("text", "x".repeat(16 * 1024 * 1024)) }
                RpcClient(server.options()).use { client ->
                    assertThrows(RpcRequestTimeoutException::class.java) {
                        client.call("be.setNbt", params, requestTimeoutMs = 500)
                    }
                }
            } finally {
                releasePeer.countDown()
            }
            server.await()
        }
    }

    @Test
    fun `ready has its own bounded timeout even with unlimited requests`() {
        LoopbackRpcServer(readyLine = null) { socket ->
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            RpcClient(server.options(timeoutMs = 100, requestTimeoutMs = 0)).use { client ->
                val error = assertThrows(IOException::class.java) { client.call("wait.until") }
                assertTrue(error.message!!.contains("server.ready handshake timed out"))
            }
            server.await()
        }
    }

    @Test
    fun `wrong plane and unsupported protocol fail before any request`() {
        for (ready in listOf(readyNotification("client"), readyNotification("server", 99))) {
            LoopbackRpcServer(readyLine = ready) { socket ->
                assertEquals(-1, socket.getInputStream().read())
            }.use { server ->
                RpcClient(server.options()).use { client ->
                    assertThrows(IllegalArgumentException::class.java) { client.call("server.status") }
                }
                server.await()
            }
        }
    }

    @Test
    fun `session identity is checked before any request reaches a replaced endpoint`() {
        val changed = testProcessIdentity.copy(instanceId = "different-jvm")
        LoopbackRpcServer(readyLine = readyNotification(process = changed)) { socket ->
            assertEquals(-1, socket.getInputStream().read(), "must not send even a read-only RPC to the wrong instance")
        }.use { server ->
            val options = server.options()
            RpcClient(RpcClientOptions(tcp = options.tcp, expectedProcess = testProcessIdentity)).use { client ->
                val error = assertThrows(IllegalArgumentException::class.java) { client.call("server.status") }
                assertTrue(error.message!!.contains("identity mismatch"))
            }
            server.await()
        }
    }

    @Test
    fun `batch binding includes complete session identity and deadlines`() {
        val options = RpcClientOptions(expectedProcess = testProcessIdentity)
        RpcClient(options).use { client ->
            client.requireSameBatchOptions(options.copy())
            assertThrows(IllegalArgumentException::class.java) {
                client.requireSameBatchOptions(options.copy(expectedProcess = testProcessIdentity.copy(instanceId = "another-session")))
            }
            assertThrows(IllegalArgumentException::class.java) {
                client.requireSameBatchOptions(options.copy(requestTimeoutMs = 100))
            }
        }
    }

    @Test
    fun `process identity round trips without reflective JDK types`() {
        assertEquals(testProcessIdentity, RpcProcessIdentity.fromJson(testProcessIdentity.toJson()))
        assertThrows(IllegalArgumentException::class.java) { testProcessIdentity.copy(pid = -1) }
        assertThrows(java.time.format.DateTimeParseException::class.java) { testProcessIdentity.copy(startTime = "invalid") }
        assertThrows(IllegalArgumentException::class.java) { testProcessIdentity.copy(gameDir = "") }
        val remoteWindows = testProcessIdentity.copy(gameDir = "C:\\Minecraft\\run")
        assertEquals(remoteWindows, RpcProcessIdentity.fromJson(remoteWindows.toJson()))
    }

    @Test
    fun `invalid timeouts are rejected without connecting`() {
        assertThrows(IllegalArgumentException::class.java) { RpcClientOptions(timeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { RpcClientOptions(requestTimeoutMs = -1) }
    }

    private fun asyncCall(block: () -> JsonElement): CompletableFuture<JsonElement> {
        val future = CompletableFuture<JsonElement>()
        Thread({
            try { future.complete(block()) } catch (e: Throwable) { future.completeExceptionally(e) }
        }, "rpc-test-caller").apply { isDaemon = true; start() }
        return future
    }

    private fun assertCallFailed(future: CompletableFuture<JsonElement>): Throwable =
        assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }.cause!!
}

internal val testProcessIdentity = RpcProcessIdentity(
    ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant().orElseThrow().toString(),
    "test-instance", "", java.nio.file.Path.of(".").toAbsolutePath().normalize().toString(),
)

internal fun readyNotification(plane: String = "server", protocolVersion: Int = 1, process: RpcProcessIdentity = testProcessIdentity): String =
    """{"jsonrpc":"2.0","method":"server.ready","params":{"plane":"$plane","protocolVersion":$protocolVersion,"modVersion":"test-mod","process":${process.toJson()}}}"""

internal fun readRequest(socket: Socket): JsonObject =
    JsonParser.parseString(socket.getInputStream().bufferedReader(UTF_8).readLine()).asJsonObject

internal fun reply(socket: Socket, request: JsonObject, result: JsonElement) {
    val response = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", request.get("id"))
        add("result", result)
    }
    socket.getOutputStream().write((response.toString() + "\n").toByteArray(UTF_8))
    socket.getOutputStream().flush()
}

/** Bounded fake server: no swallowed peer assertions, daemon threads, deterministic cleanup. */
internal class LoopbackRpcServer(
    plane: String = "server",
    readyLine: String? = readyNotification(plane),
    handler: (Socket) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val peer = AtomicReference<Socket>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "rpc-test-peer").apply { isDaemon = true } }
    private val done = CompletableFuture.runAsync({
        server.accept().use { socket ->
            peer.set(socket)
            socket.soTimeout = 3000
            if (readyLine != null) {
                socket.getOutputStream().write((readyLine + "\n").toByteArray(UTF_8))
                socket.getOutputStream().flush()
            }
            handler(socket)
        }
    }, executor)

    fun options(plane: String = "server", timeoutMs: Int = 1000, requestTimeoutMs: Long? = null) =
        RpcClientOptions(tcp = "127.0.0.1:${server.localPort}", plane = plane, timeoutMs = timeoutMs, requestTimeoutMs = requestTimeoutMs)

    fun await() { done.get(3, TimeUnit.SECONDS) }

    override fun close() {
        server.close()
        peer.get()?.close()
        executor.shutdownNow()
        try {
            done.get(3, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            if (e.cause !is IOException) throw e
        }
    }
}
