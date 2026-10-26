package com.mcdebug.client

import com.google.gson.JsonObject
import com.mcdebug.McDebugMod
import com.mcdebug.ServerPlane
import com.mcdebug.rpc.RpcServer
import com.mcdebug.rpc.TransportConfig
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

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

    /** Called by [McDebugClientMod] once the client-plane listener is created. */
    fun attach(rpcServer: RpcServer?) {
        server = rpcServer
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

    /** Actually bound TCP port, or null when TCP is disabled or failed to bind. */
    fun tcpPort(): Int? = server?.tcpPort

    fun endpointDescription(): String {
        val socket = socketPath()?.toString()
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
