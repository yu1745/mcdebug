package com.mcdebug.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.McDebugMod
import com.mcdebug.ServerPlane
import com.mcdebug.rpc.RpcServer
import com.mcdebug.rpc.RpcEnv
import com.mcdebug.rpc.TransportConfig
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * Facts about the **client plane** process, published through `gui.status`.
 *
 * The point of this object is observability for the deployment step: the mod is
 * shipped as a release jar that a human copies into `<gameDir>/mods`, so the agent
 * needs a way to tell *which* jar is actually loaded, *where* it is listening, and
 * whether the loaded method set matches the contract it was built against.
 *
 * `McDebugClientMod` fills this in during `onInitializeClient`. Every getter is
 * total: it degrades to an empty string / null instead of throwing when the plane
 * never started, and the same facts are written to the game log at startup — the
 * log is the only channel left when no listener exists at all.
 */
object ClientInfo {

    const val PROTOCOL_VERSION = 1

    @Volatile
    private var server: RpcServer? = null

    @Volatile
    private var intendedSocket: Path? = null

    @Volatile
    private var mismatch: List<String> = emptyList()
    private var gameDirChannel: FileChannel? = null
    private var gameDirLock: FileLock? = null

    /** Hold for this JVM's lifetime, including bootstrap before client.json exists. */
    fun claimGameDir() {
        val path = RpcEnv.gameDir().resolve("mcdebug/client.lock")
        Files.createDirectories(path.parent)
        val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            val lock = checkNotNull(channel.tryLock()) { "another Minecraft client already owns gameDir ${RpcEnv.gameDir()}" }
            gameDirChannel = channel
            gameDirLock = lock
            Runtime.getRuntime().addShutdownHook(Thread({
                try { gameDirLock?.release() } finally { gameDirChannel?.close() }
            }, "mcdebug-game-dir-lock"))
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    /** Called by [McDebugClientMod] once the client-plane listener is created. */
    fun attach(rpcServer: RpcServer?) {
        server = rpcServer
    }

    /** Bootstrap discovery is readable before the main thread can answer GUI RPCs. */
    fun publish() {
        val dir = RpcEnv.gameDir().resolve("mcdebug")
        Files.createDirectories(dir)
        val discovery = JsonObject().apply {
            add("process", RpcEnv.processInfo())
            addProperty("clientSocket", server?.endpoint ?: "")
        }
        val temporary = Files.createTempFile(dir, ".client-", ".json.part")
        try {
            Files.writeString(temporary, discovery.toString())
            Files.move(temporary, dir.resolve("client.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** A cancelled bootstrap may outlive its Gradle wrapper. Match this launch only. */
    fun bootstrapStopRequested(): Boolean {
        if (RpcEnv.sessionToken.isEmpty()) return false
        val dir = RpcEnv.gameDir().resolve("mcdebug")
        fun token(path: Path): String? = if (!Files.isRegularFile(path) || Files.size(path) > 65536) null else
            Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject.get("sessionToken")?.asString }
        return try {
            // A newer launch may have replaced the registry reservation while an
            // old Gradle daemon was still bootstrapping. Never let that old JVM
            // bind/unlink the new client's endpoints or overwrite its discovery.
            val owner = token(dir.resolve("mdb-owner.json"))
            (owner != null && owner != RpcEnv.sessionToken) || token(dir.resolve("session-stop.json")) == RpcEnv.sessionToken
        } catch (e: Exception) {
            McDebugMod.LOGGER.warn("Cannot read session bootstrap markers in {}: {}", dir, e.message)
            false
        }
    }

    /** Stop accepting calls before the client's event loop disappears. */
    fun close() {
        server?.stop()
        server = null
        // CLIENT_STOPPING precedes integrated-server saves. Keep the directory
        // lock until JVM shutdown, not just until the RPC endpoints are closed.
    }

    /** The socket path the client plane *tries* to bind, for diagnostics before/without a listener. */
    fun setIntendedSocket(path: Path?) {
        intendedSocket = path
    }

    fun setContractMismatch(entries: List<String>) {
        mismatch = entries
    }

    /** Methods declared in `Methods.CLIENT_METHODS` but not registered, and vice versa. */
    fun contractMismatch(): List<String> = mismatch

    fun modVersion(): String = try {
        McDebugMod.MOD_VERSION
    } catch (t: Throwable) {
        "unknown"
    }

    /** Bound unix socket path (`""` when the unix listener failed to bind). */
    fun socketPath(): Path? = server?.socketPath ?: intendedSocket

    fun socketBound(): Boolean = server?.socketPath != null

    /** Published endpoint: socket path, or `tcp:127.0.0.1:N` when the socket fell back to loopback TCP. */
    fun endpoint(): String? = server?.endpoint ?: intendedSocket?.toString()

    /** Actually bound TCP port, or null when TCP is disabled or failed to bind. */
    fun tcpPort(): Int? = server?.tcpPort

    fun endpointDescription(): String {
        // Only a bound socket counts; the intended path of a failed bind is not an endpoint.
        val socket = server?.socketPath?.toString()
        val tcp = tcpPort()
        return when {
            socket != null && tcp != null -> "unix=$socket tcp=127.0.0.1:$tcp"
            socket != null -> "unix=$socket"
            tcp != null -> "tcp=127.0.0.1:$tcp"
            else -> "not listening"
        }
    }

    /**
     * The jar this class was loaded from — the single most useful field when a
     * stale copy is sitting in `mods/`.
     */
    fun jarPath(): String = try {
        ClientInfo::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { Path.of(it).toString() } ?: ""
    } catch (t: Throwable) {
        ""
    }

    fun gameDirString(): String = try {
        FabricLoader.getInstance().gameDir.toAbsolutePath().toString()
    } catch (t: Throwable) {
        ""
    }

    /**
     * The server plane lives in the same jar; on a single-player client the client
     * entrypoint installs its lifecycle hooks, so both planes normally report a
     * listener here.
     */
    fun serverPlaneJson(): JsonObject = JsonObject().apply {
        addProperty("running", ServerPlane.running)
        addProperty("socket", ServerPlane.socketPath ?: "")
        addProperty("tcpPort", ServerPlane.tcpPort ?: -1)
        addProperty("server", ServerPlane.currentServer?.let { "present" } ?: "")
    }

    /** Config keys the client plane honours, for error messages and docs. */
    fun configHints(): JsonObject = JsonObject().apply {
        addProperty("socketProperty", TransportConfig.CLIENT.socketProperty)
        addProperty("socketEnv", TransportConfig.CLIENT.socketEnv)
        addProperty("socketJsonKey", TransportConfig.CLIENT.socketJsonKey)
        addProperty("tcpPortProperty", TransportConfig.CLIENT.tcpPortProperty)
        addProperty("tcpPortEnv", TransportConfig.CLIENT.tcpPortEnv)
        addProperty("tcpPortJsonKey", TransportConfig.CLIENT.tcpPortJsonKey)
        addProperty("tcpEnabledProperty", TransportConfig.CLIENT.tcpEnabledProperty)
        addProperty("tcpEnabledEnv", TransportConfig.CLIENT.tcpEnabledEnv)
        addProperty("tcpEnabledJsonKey", TransportConfig.CLIENT.tcpEnabledJsonKey)
        addProperty("defaultSocket", "<gameDir>/mcdebug/${TransportConfig.CLIENT.defaultSocketFileName}")
        addProperty("defaultTcpPort", TransportConfig.CLIENT.defaultTcpPort)
        addProperty("defaultTcpEnabled", TransportConfig.CLIENT.defaultTcpEnabled)
    }
}
