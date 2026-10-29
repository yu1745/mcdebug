package com.mcdebug.cli.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.cli.RpcClientOptions
import com.mcdebug.cli.RpcProcessIdentity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Timeout(10)
class SessionManagerTest {
    @TempDir lateinit var temp: Path
    private val runtime = FakeSessionRuntime()
    private val registryDir get() = temp.resolve("registry")
    private val manager get() = SessionManager(registryDir, runtime)
    private val game get() = temp.resolve("project with spaces/run")
    private fun request(world: String? = null): StartRequest {
        val project = temp.resolve("project with spaces")
        Files.createDirectories(project)
        Files.writeString(project.resolve("gradlew"), "#!/bin/sh\nexit 0\n")
        return StartRequest("test", project = project, world = world, waitSeconds = 0)
    }
    private fun publish(token: String = "", pid: Long = 41, instance: String = "instance", gameDir: Path = game): RpcProcessIdentity {
        val identity = RpcProcessIdentity(pid, "2026-01-01T00:00:00Z", instance, token, gameDir.toRealPath().toString())
        Files.createDirectories(gameDir.resolve("mcdebug"))
        Files.writeString(gameDir.resolve("mcdebug/client.json"), JsonObject().apply {
            add("process", identity.toJson()); addProperty("clientSocket", gameDir.resolve("mcdebug/actual.sock").toString())
        }.toString())
        runtime.live[pid] = identity.startTime
        runtime.observation = SessionObservation(identity, status(true, true))
        return identity
    }
    private fun onLaunch(world: Boolean = true, singleplayer: Boolean = true) {
        runtime.onLaunch = { spec ->
            val identity = publish(spec.environment.getValue("MCDEBUG_SESSION_TOKEN"))
            runtime.observation = SessionObservation(identity, status(world, singleplayer))
        }
    }
    private fun state(json: JsonObject) = json.get("state").asString
    private fun status(inWorld: Boolean, singleplayer: Boolean) = JsonObject().apply {
        addProperty("inWorld", inWorld); addProperty("singleplayer", singleplayer)
    }

    @Test fun `start passes target wrapper args and launch token without a shell command string`() {
        val req = request("New 世界")
        Files.createDirectories(game.resolve("saves/New 世界"))
        onLaunch()
        val result = manager.start(req)
        assertEquals("world_ready", state(result))
        val spec = runtime.launches.single()
        assertEquals(listOf("setsid", "--wait", "/bin/sh"), spec.command.take(3))
        assertEquals(req.project.resolve("gradlew").toRealPath().toString(), spec.command[3])
        assertTrue(spec.command.last().contains("\"New 世界\""))
        assertTrue(spec.command.last().contains("\"${game.toRealPath()}\""))
        assertEquals("demand", spec.environment["MCDEBUG_CLIENT_RENDER_MODE"])
        assertEquals("0", spec.environment["MCDEBUG_TCP_PORT"])
        assertEquals("false", spec.environment["MCDEBUG_CLIENT_TCP_ENABLED"])
        assertEquals("false", spec.environment["MCDEBUG_TCP_ENABLED"])
        assertEquals(game.resolve("mcdebug/socket").toString(), spec.environment["MCDEBUG_SOCKET"])
        assertEquals(game.resolve("mcdebug/clientSocket").toString(), spec.environment["MCDEBUG_CLIENT_SOCKET"])
        assertEquals(result.get("sessionToken").asString, spec.environment["MCDEBUG_SESSION_TOKEN"])
        assertTrue(Files.isDirectory(game.resolve("saves/New 世界")))
    }

    @Test fun `same live configuration reuses launch while changed configuration is refused`() {
        val req = request()
        onLaunch(false)
        assertEquals("client_ready", state(manager.start(req)))
        assertEquals("client_ready", state(manager.start(req)))
        assertEquals(1, runtime.launches.size)
        assertThrows(IllegalStateException::class.java) { manager.start(req.copy(render = "continuous")) }
        assertThrows(IllegalStateException::class.java) { manager.start(req.copy(name = "other")) }
    }

    @Test fun `persistent owner refuses bootstrap duplicate across different registries`() {
        val req = request()
        assertEquals("start_timeout", state(manager.start(req)))
        val other = SessionManager(temp.resolve("other-registry"), runtime)
        assertThrows(IllegalStateException::class.java) { other.start(req.copy(name = "other")) }
        assertEquals(1, runtime.launches.size)
    }

    @Test fun `external live client cannot be launched over and attached client can never be stopped`() {
        val req = request()
        Files.createDirectories(game)
        val identity = publish()
        assertThrows(IllegalStateException::class.java) { manager.start(req) }
        val attached = manager.attach("external", game)
        assertFalse(attached.get("owned").asBoolean)
        assertEquals(identity, RpcProcessIdentity.fromJson(attached.getAsJsonObject("process")))
        for (force in listOf(false, true)) assertThrows(IllegalStateException::class.java) { manager.stop("external", force, 0) }
        assertTrue(runtime.terminations.isEmpty())
        assertEquals(0, runtime.stopRequests)
    }

    @Test fun `world ready requires both inWorld and singleplayer`() {
        val req = request("Existing")
        Files.createDirectories(game.resolve("saves/Existing"))
        onLaunch(world = true, singleplayer = false)
        assertEquals("start_timeout", state(manager.start(req)))
        runtime.observation = runtime.observation!!.copy(status = status(true, true))
        assertEquals("world_ready", state(manager.status("test")))
    }

    @Test fun `timeout preserves wrapper identity and logs for diagnostics`() {
        val result = manager.start(request())
        assertEquals("start_timeout", state(result))
        assertEquals(runtime.wrapper.pid, result.getAsJsonObject("wrapper").get("pid").asLong)
        assertTrue(result.get("lastError").asString.contains("may still be running"))
        assertNotNull(result.get("logPath").asString)
        assertEquals("start_timeout", state(manager.status("test")))
    }

    @Test fun `client crash before discovery fails start immediately with the crash line`() {
        runtime.onLaunch = { spec ->
            Files.createDirectories(spec.logPath.parent); Files.writeString(spec.logPath, "> Task :runClient\nCaused by: java.lang.ClassCastException: boom\nBUILD FAILED in 29s\n")
            runtime.live.remove(runtime.wrapper.pid)
        }
        val result = manager.start(request().copy(waitSeconds = 600))
        assertEquals("failed", state(result))
        assertTrue(result.get("lastError").asString.contains("ClassCastException: boom"))
        assertEquals(0L, runtime.nanoTime(), "must not wait for the deadline")
    }

    @Test fun `launcher failure is persisted without deleting game data`() {
        val req = request()
        Files.createDirectories(game.resolve("saves/Keep"))
        runtime.onLaunch = { throw IllegalStateException("fake launch failed") }
        val result = manager.start(req)
        assertEquals("failed", state(result))
        assertTrue(result.get("lastError").asString.contains("fake launch failed"))
        assertEquals("failed", state(manager.status("test")))
        assertTrue(Files.isDirectory(game.resolve("saves/Keep")))
    }

    @Test fun `root qualified runClient is accepted without explicit gameDir`() {
        val req = request().copy(task = ":runClient")
        onLaunch(false)
        assertEquals("client_ready", state(manager.start(req)))
        assertEquals(":runClient", runtime.launches.single().command[4])
    }

    @Test fun `qualified submodule runClient is accepted with explicit gameDir`() {
        val req = request().copy(task = ":core:runClient", gameDir = game)
        assertEquals("start_timeout", state(manager.start(req)))
        assertEquals(":core:runClient", runtime.launches.single().command[4])
    }

    @Test fun `submodule needs explicit gameDir and malformed tasks and worlds are rejected`() {
        val req = request()
        for (task in listOf(":core:runClient", "core:runClient")) {
            assertThrows(IllegalArgumentException::class.java) { manager.start(req.copy(task = task)) }
        }
        for (task in listOf("--stop", "build", "runClient --daemon", ":core:runClient;touch x")) {
            assertThrows(IllegalArgumentException::class.java) { manager.start(req.copy(task = task, gameDir = game)) }
        }
        for (world in listOf("..", ".", "../outside", "a/b", "a\\b", "x\n", "Missing")) {
            assertThrows(IllegalArgumentException::class.java) { manager.start(req.copy(world = world)) }
        }
        assertFalse(Files.exists(game.resolve("saves/Missing")))
        assertTrue(runtime.launches.isEmpty())
    }

    @Test fun `world symlink cannot escape saves`() {
        val req = request()
        Files.createDirectories(game.resolve("saves"))
        val outside = Files.createDirectories(temp.resolve("outside-world"))
        Files.createSymbolicLink(game.resolve("saves/escape"), outside)
        assertThrows(IllegalArgumentException::class.java) { manager.start(req.copy(world = "escape")) }
        assertTrue(runtime.launches.isEmpty())
    }

    @Test fun `canonical gameDir aliases share cross registry ownership`() {
        val req = request()
        Files.createDirectories(game)
        val alias = temp.resolve("alias")
        Files.createSymbolicLink(alias, game)
        manager.start(req)
        assertThrows(IllegalStateException::class.java) {
            SessionManager(temp.resolve("second"), runtime).start(req.copy(name = "other", gameDir = alias))
        }
    }

    @Test fun `startup stop writes token and cancels only recorded wrapper not daemon`() {
        manager.start(request())
        runtime.live[99] = "2026-01-01T00:00:00Z" // unrelated shared daemon
        val result = manager.stop("test", force = true, waitSeconds = 0)
        assertEquals("stopping", state(result))
        val marker = JsonParser.parseString(Files.readString(game.resolve("mcdebug/session-stop.json"))).asJsonObject
        assertEquals(result.get("sessionToken").asString, marker.get("sessionToken").asString)
        assertEquals(listOf(runtime.wrapper to false), runtime.terminations)
        assertTrue(runtime.live.containsKey(99))
        assertEquals(0, runtime.stopRequests)
        assertThrows(IllegalStateException::class.java) {
            SessionManager(temp.resolve("second"), runtime).start(request().copy(name = "other"))
        }
    }

    @Test fun `startup stop never cancels reused wrapper PID`() {
        manager.start(request())
        runtime.live[runtime.wrapper.pid] = "2026-01-02T00:00:00Z"
        manager.stop("test", waitSeconds = 0)
        assertTrue(runtime.terminations.isEmpty())
    }

    @Test fun `late bootstrap after startup stop is observed without resurrecting stopped state`() {
        val initial = manager.start(request())
        manager.stop("test", waitSeconds = 0)
        val late = publish(initial.get("sessionToken").asString)
        assertEquals("stopping", state(manager.status("test")))
        runtime.live.remove(late.pid)
        assertEquals("stopped", state(manager.status("test")))
    }

    @Test fun `normal owned stop validates endpoint and force affects only confirmed MC`() {
        onLaunch()
        manager.start(request())
        val process = runtime.observation!!.process
        runtime.normalStopKills = false
        val stopped = manager.stop("test", force = true, waitSeconds = 0)
        assertEquals("stopped", state(stopped))
        assertEquals(1, runtime.stopRequests)
        assertEquals(listOf(SessionProcess(process.pid, process.startTime) to true), runtime.terminations)
        assertTrue(runtime.live.containsKey(runtime.wrapper.pid))
        assertEquals(process, runtime.seenOptions.last().expectedProcess)
    }

    @Test fun `replaced instance and reused PID are never stopped or force killed`() {
        onLaunch()
        manager.start(request())
        val owned = runtime.observation!!.process
        publish(owned.sessionToken, instance = "replacement")
        assertThrows(IllegalStateException::class.java) { manager.stop("test", true, 0) }
        assertTrue(runtime.terminations.isEmpty())
        assertEquals(0, runtime.stopRequests)
        // Even if discovery is restored, PID reuse cannot satisfy ProcessHandle identity.
        publish(owned.sessionToken, instance = owned.instanceId)
        runtime.live[owned.pid] = "2026-01-02T00:00:00Z"
        assertEquals("stopped", state(manager.stop("test", true, 0)))
        assertTrue(runtime.terminations.isEmpty())
        assertEquals(0, runtime.stopRequests)
    }

    @Test fun `force briefly waits for asynchronous process death and stays bounded`() {
        onLaunch()
        manager.start(request())
        runtime.normalStopKills = false
        runtime.forceKillDelayMillis = 250
        assertEquals("stopped", state(manager.stop("test", force = true, waitSeconds = 0)))
        assertEquals(250_000_000L, runtime.nanoTime())
    }

    @Test fun `force confirmation timeout retains stopping instead of claiming death`() {
        onLaunch()
        manager.start(request())
        runtime.normalStopKills = false
        runtime.forceKillDelayMillis = 2000
        assertEquals("stopping", state(manager.stop("test", force = true, waitSeconds = 0)))
        assertEquals(1_000_000_000L, runtime.nanoTime())
    }

    @Test fun `force works after gui stop closes RPC while MC is still saving`() {
        onLaunch()
        manager.start(request())
        runtime.normalStopKills = false
        runtime.onStop = { runtime.verifyFailure = java.io.IOException("RPC closed during world save") }
        assertEquals("stopped", state(manager.stop("test", force = true, waitSeconds = 0)))
        assertEquals(1, runtime.stopRequests)
        assertEquals(1, runtime.terminations.size)
    }

    @Test fun `force accepts initially unavailable RPC only with exact discovery and live PID startTime`() {
        onLaunch()
        manager.start(request())
        runtime.verifyFailure = java.io.IOException("client hung")
        assertThrows(IllegalStateException::class.java) { manager.stop("test", waitSeconds = 0) }
        assertEquals("stopped", state(manager.stop("test", force = true, waitSeconds = 0)))
        assertEquals(1, runtime.terminations.size)
    }

    @Test fun `force refuses a successfully handshaken but mismatched endpoint`() {
        onLaunch()
        manager.start(request())
        runtime.verifyOverride = runtime.observation!!.process.copy(instanceId = "imposter")
        assertThrows(IllegalStateException::class.java) { manager.stop("test", force = true, waitSeconds = 0) }
        assertTrue(runtime.terminations.isEmpty())
    }

    @Test fun `force rechecks discovery after normal stop before killing anything`() {
        onLaunch()
        manager.start(request())
        runtime.normalStopKills = false
        runtime.onStop = { publish(runtime.observation!!.process.sessionToken, instance = "replacement") }
        assertThrows(IllegalStateException::class.java) { manager.stop("test", force = true, waitSeconds = 0) }
        assertTrue(runtime.terminations.isEmpty())
    }

    @Test fun `resolve can adopt bootstrap discovery without a gui status probe`() {
        val first = manager.start(request())
        val process = publish(first.get("sessionToken").asString)
        runtime.seenOptions.clear()
        assertEquals(process, manager.resolve("test", "client").expectedProcess)
        assertTrue(runtime.seenOptions.isEmpty())
    }

    @Test fun `old startup probe cannot overwrite concurrent stop intent`() {
        val req = request()
        onLaunch()
        runtime.onInspect = {
            runtime.onInspect = null
            manager.stop("test", waitSeconds = 0)
        }
        assertEquals("stopped", state(manager.start(req)))
        assertEquals("stopped", state(manager.status("test")))
    }

    @Test fun `old probe cannot overwrite different attached generation with empty launch token`() {
        request()
        Files.createDirectories(game)
        val first = publish()
        manager.attach("attached", game)
        runtime.onInspect = {
            runtime.onInspect = null
            val registry = SessionRegistry(registryDir)
            registry.locked {
                val old = read("attached")!!
                write(old.copy(recordId = "replacement-generation", state = "stopped", process = first))
            }
        }
        assertEquals("stopped", state(manager.status("attached")))
        assertEquals("replacement-generation", manager.status("attached").get("recordId").asString)
    }

    @Test fun `resolve uses discovered absolute endpoints and binds complete process on both planes`() {
        onLaunch()
        manager.start(request())
        val serverSocket = game.resolve("mcdebug/custom-integrated.sock").toString()
        Files.writeString(game.resolve("mcdebug/port"), serverSocket)
        val client = manager.resolve("test", "client", 321, 654)
        val server = manager.resolve("test", "server", 123, 456)
        assertEquals(game.resolve("mcdebug/actual.sock").toString(), client.socket)
        assertEquals(serverSocket, server.socket)
        assertEquals(321, client.timeoutMs)
        assertEquals(654L, client.requestTimeoutMs)
        assertEquals(runtime.observation!!.process, server.expectedProcess)
        assertEquals(client.expectedProcess, server.expectedProcess)
        assertNull(server.tcp)
        assertNull(server.portFile)
        Files.delete(game.resolve("mcdebug/port"))
        assertThrows(IllegalStateException::class.java) { manager.resolve("test", "server") }
    }

    @Test fun `resolve binds identity without extra RPC probes and never follows replacements`() {
        onLaunch()
        manager.start(request())
        Files.writeString(game.resolve("mcdebug/port"), game.resolve("mcdebug/server.sock").toString())
        runtime.seenOptions.clear()
        val expected = runtime.observation!!.process
        assertEquals(expected, manager.resolve("test", "server").expectedProcess)
        assertEquals(expected, manager.resolve("test", "client").expectedProcess)
        assertTrue(runtime.seenOptions.isEmpty(), "resolve must leave the single handshake to the actual command")
        publish(runtime.observation!!.process.sessionToken, instance = "replacement")
        assertThrows(IllegalStateException::class.java) { manager.resolve("test", "client") }
    }

    @Test fun `log tail preserves Chinese emoji CRLF final newline and absent file`() {
        val record = manager.start(request())
        val path = Path.of(record.get("logPath").asString)
        assertEquals("", manager.logs("test"))
        Files.writeString(path, "first\r\n中文😀\r\nlast\n")
        assertEquals("中文😀\r\nlast\n", manager.logs("test", 2))
        assertEquals("", manager.logs("test", 0))
        Files.writeString(path, "first\n中文😀")
        assertEquals("中文😀", manager.logs("test", 1))
        assertThrows(IllegalArgumentException::class.java) { manager.logs("test", 10001) }
    }
}

internal class FakeSessionRuntime : SessionRuntime {
    val wrapper = SessionProcess(22, "2026-01-01T00:00:00Z")
    val live = mutableMapOf(11L to "2026-01-01T00:00:00Z")
    val launches = mutableListOf<SessionLaunch>()
    val terminations = mutableListOf<Pair<SessionProcess, Boolean>>()
    val seenOptions = mutableListOf<RpcClientOptions>()
    var observation: SessionObservation? = null
    var verifyOverride: RpcProcessIdentity? = null
    var onLaunch: ((SessionLaunch) -> Unit)? = null
    var onInspect: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var verifyFailure: Exception? = null
    var normalStopKills = true
    var stopRequests = 0
    private var nanos = 0L
    var forceKillDelayMillis = 0L
    private var pendingDeath: Pair<Long, Long>? = null
    override fun currentProcess() = SessionProcess(11, live.getValue(11))
    override fun isAlive(process: SessionProcess) = live[process.pid] == process.startTime
    override fun launch(spec: SessionLaunch): SessionProcess {
        launches.add(spec)
        live[wrapper.pid] = wrapper.startTime
        onLaunch?.invoke(spec)
        return wrapper
    }
    override fun inspect(options: RpcClientOptions): SessionObservation {
        seenOptions.add(options)
        val result = checkNotNull(observation) { "not ready" }
        check(result.process == options.expectedProcess) { "identity mismatch" }
        onInspect?.invoke()
        return result
    }
    override fun verify(options: RpcClientOptions): RpcProcessIdentity {
        seenOptions.add(options)
        verifyFailure?.let { throw it }
        return verifyOverride ?: checkNotNull(observation).process
    }
    override fun requestStop(options: RpcClientOptions) {
        check(verify(options) == options.expectedProcess)
        stopRequests++
        onStop?.invoke()
        if (normalStopKills) live.remove(checkNotNull(options.expectedProcess).pid)
    }
    override fun terminate(process: SessionProcess, force: Boolean) {
        check(isAlive(process))
        terminations.add(process to force)
        if (force && forceKillDelayMillis > 0) pendingDeath = process.pid to (nanos + forceKillDelayMillis * 1_000_000)
        else live.remove(process.pid)
    }
    override fun nanoTime() = nanos
    override fun pause(millis: Long) {
        nanos += millis * 1_000_000
        pendingDeath?.takeIf { nanos >= it.second }?.let { live.remove(it.first); pendingDeath = null }
    }
}
