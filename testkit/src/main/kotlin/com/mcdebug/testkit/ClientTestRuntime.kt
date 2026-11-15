package com.mcdebug.testkit

import com.mcdebug.sdk.McDebugConnection
import com.mcdebug.sdk.RpcClientOptions
import com.mcdebug.sdk.gson
import com.mcdebug.sdk.session.LocalSessionRuntime
import com.mcdebug.sdk.session.SessionManager
import com.mcdebug.sdk.session.SessionRecord
import com.mcdebug.sdk.session.StartRequest
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Owns a launched session, or attaches without acquiring permission to stop another client. */
internal class ClientTestRuntime : ExtensionContext.Store.CloseableResource, AutoCloseable {
    private val gameDir = Path.of(required("mcdebug.gameDir")).toAbsolutePath().normalize()
    private val launchProject = property("mcdebug.launchProject")?.let { Path.of(it).toRealPath() }
    private val registryDir = property("mcdebug.sessionsDir")?.let(Path::of)
        ?: gameDir.resolveSibling("mcdebug-test-sessions")
    private val manager = SessionManager(registryDir)
    private var ownedName: String? = null
    private var connection: McDebugConnection? = null
    val artifactsDir: Path = Path.of(property("mcdebug.artifactsDir") ?: "build/reports/mcdebug")
        .toAbsolutePath().normalize().resolve("run-${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID().toString().take(8)}")
    val requestTimeoutMillis: Long = positiveLong("mcdebug.requestTimeoutMs", 30_000)

    init {
        Files.createDirectories(artifactsDir)
        try {
            if (launchProject != null) startOwned()
            connection = connect()
            record("ready")
        } catch (error: Throwable) {
            runCatching { Files.writeString(artifactsDir.resolve("startup-failure.txt"), error.stackTraceToString()) }
                .exceptionOrNull()?.let(error::addSuppressed)
            runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun connection(): McDebugConnection = checkNotNull(connection) { "client test runtime is closed" }

    private fun startOwned() {
        // Launch from a separate runtime project: a nested invocation of the test build would
        // wait for Gradle project locks held by the test worker's parent build.
        property("mcdebug.testProject")?.let {
            require(launchProject != Path.of(it).toRealPath()) {
                "mcdebug.launchProject must be a separate runtime project, not the build running these tests"
            }
        }
        val name = "junit-${UUID.randomUUID().toString().take(12)}"
        ownedName = name
        val record = manager.start(StartRequest(
            name = name,
            project = checkNotNull(launchProject),
            gameDir = gameDir,
            world = required("mcdebug.world"),
            render = "demand",
            display = property("mcdebug.display"),
            width = positiveLong("mcdebug.width", 1920).toInt(),
            height = positiveLong("mcdebug.height", 1080).toInt(),
            waitSeconds = positiveLong("mcdebug.startTimeoutSeconds", 180),
        ))
        check(record["state"].asString == "world_ready") { "client startup failed: $record" }
    }

    private fun connect(): McDebugConnection {
        val serverOptions = options(RpcClientOptions.PLANE_SERVER)
        val clientOptions = options(RpcClientOptions.PLANE_CLIENT)
        val result = McDebugConnection(serverOptions, clientOptions)
        try {
            result.connect()
            var status = result.gui.status().asJsonObject
            check(status["inWorld"].asBoolean) { "client has no loaded world: $status" }
            check(status.getAsJsonArray("contractMismatch").isEmpty) { "client RPC contract mismatch: $status" }
            if (ownedName != null) {
                // inWorld becomes true before the joining Screen disappears. An input sent in
                // that window can be ignored while openBlock reports the unrelated Screen change.
                val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
                while (status["screen"].asString.isNotEmpty() || status["overlay"].asString.isNotEmpty()) {
                    check(System.nanoTime() < deadline) { "client is still showing its world-loading Screen: $status" }
                    Thread.sleep(50)
                    status = result.gui.status().asJsonObject
                }
                result.gui.settle()
            }
            Files.writeString(artifactsDir.resolve("connected-client.json"), gson.toJson(status))
            return result
        } catch (error: Throwable) {
            result.close()
            throw error
        }
    }

    private fun options(plane: String): RpcClientOptions {
        ownedName?.let { return manager.resolve(it, plane, requestTimeoutMs = requestTimeoutMillis) }
        property("mcdebug.session")?.let { return manager.resolve(it, plane, requestTimeoutMs = requestTimeoutMillis) }
        val file = gameDir.resolve(if (plane == "client") "mcdebug/clientPort" else "mcdebug/port")
        // Resolve only the explicitly selected game directory; stale/missing discovery must not
        // fall through to another project's running client or silently skip the test suite.
        check(Files.isRegularFile(file)) { "missing $plane discovery file: $file; start a client or configure mcdebug.launchProject" }
        val endpoint = Files.readString(file).trim()
        check(endpoint.isNotEmpty()) { "empty $plane discovery file: $file" }
        return RpcClientOptions(socket = endpoint, plane = plane, requestTimeoutMs = requestTimeoutMillis)
    }

    /** Restart only a client launched by this runtime; preserve its world and resolve fresh identities. */
    fun restart() {
        check(launchProject != null && ownedName != null) { "restart requires an owned test client" }
        connection?.close()
        connection = null
        stopOwned()
        startOwned()
        connection = connect()
    }

    private fun stopOwned() {
        val name = ownedName ?: return
        val stopped = manager.stop(name, waitSeconds = 30)
        check(stopped["state"].asString == "stopped") { "client shutdown did not complete: $stopped" }
        val wrapper = SessionRecord.fromJson(stopped).wrapper
        if (wrapper != null) {
            val runtime = LocalSessionRuntime()
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (runtime.isAlive(wrapper)) {
                check(System.nanoTime() < deadline) { "owned Gradle wrapper is still exiting after client shutdown" }
                Thread.sleep(100)
            }
        }
        ownedName = null
    }

    override fun close() {
        try { connection?.close() } finally {
            connection = null
            stopOwned()
            record("closed")
        }
    }

    private fun record(state: String) {
        Files.writeString(artifactsDir.resolve("runtime.json"), gson.toJson(mapOf(
            "state" to state, "gameDir" to gameDir.toString(), "launchProject" to launchProject?.toString(),
            "session" to ownedName, "sessionsDir" to registryDir.toString(), "updatedAt" to Instant.now().toString(),
        )))
    }
}

internal fun property(name: String): String? = System.getProperty(name)?.takeIf(String::isNotBlank)
internal fun required(name: String): String = property(name)
    ?: throw IllegalStateException("required system property is missing: $name")
internal fun positiveLong(name: String, default: Long): Long = (property(name)?.toLong() ?: default).also {
    require(it > 0) { "$name must be positive" }
}
