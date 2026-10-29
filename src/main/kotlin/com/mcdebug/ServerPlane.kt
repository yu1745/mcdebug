package com.mcdebug

import com.mcdebug.api.BlockEntityOps
import com.mcdebug.api.CraftingOps
import com.mcdebug.api.EntityOps
import com.mcdebug.api.FakePlayerPool
import com.mcdebug.api.FixtureOps
import com.mcdebug.api.FluidOps
import com.mcdebug.api.InventoryOps
import com.mcdebug.api.RedstoneOps
import com.mcdebug.api.ReflectOps
import com.mcdebug.api.ScanOps
import com.mcdebug.api.ScreenOps
import com.mcdebug.api.ServerOps
import com.mcdebug.api.SnapshotOps
import com.mcdebug.api.StorageOps
import com.mcdebug.api.TraceOps
import com.mcdebug.api.WorldOps
import com.mcdebug.rpc.RpcDispatcher
import com.mcdebug.rpc.RpcEnv
import com.mcdebug.rpc.RpcServer
import com.mcdebug.wait.WaitOps
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The **server plane** of mcdebug: the original JSON-RPC surface (`world.*`, `be.*`,
 * `inv.*`, `wait.*`, …) that inspects and mutates a live [MinecraftServer].
 *
 * It is decoupled from [McDebugMod] (the Fabric `server` entrypoint, i.e.
 * `DedicatedServerModInitializer`) because the entrypoint only fires on **dedicated**
 * servers. The client entrypoint installs the very same hooks, so that a
 * single-player client (integrated server in the same JVM) exposes **both** planes:
 * `mcdebug/socket` (server plane, bound to the integrated server) and
 * `mcdebug/clientSocket` (client plane, `gui.*`).
 *
 * Lifecycle hooks are registered once per process; start/stop are idempotent so that
 * leaving and re-entering a single-player world is safe.
 */
object ServerPlane {
    @Volatile
    private var hooksInstalled = false

    @Volatile
    private var rpcServer: RpcServer? = null

    /** Live JSON-RPC dispatcher for the running MC server. */
    @Volatile
    var dispatcher: RpcDispatcher? = null
        private set

    /** The MinecraftServer instance backing the current RPC session. */
    @Volatile
    var currentServer: MinecraftServer? = null
        private set

    /** True while the server-plane RPC server is listening. */
    val running: Boolean get() = rpcServer != null

    /** Local (unix) socket path of the server plane, or null when not listening. */
    /** Published endpoint: socket path, or `tcp:127.0.0.1:N` for the loopback fallback. */
    val socketPath: String? get() = rpcServer?.endpoint

    /** TCP port of the server plane, or null when TCP is disabled/unbound. */
    val tcpPort: Int? get() = rpcServer?.tcpPort

    /**
     * Register SERVER_STARTED/SERVER_STOPPING listeners. Safe to call from both
     * entrypoints; only the first call has an effect.
     */
    fun installLifecycleHooks() {
        if (hooksInstalled) return
        hooksInstalled = true
        ServerLifecycleEvents.SERVER_STARTED.register(::onServerStarted)
        ServerLifecycleEvents.SERVER_STOPPING.register(::onServerStopping)
    }

    private fun onServerStarted(server: MinecraftServer) {
        if (rpcServer != null) {
            McDebugMod.LOGGER.warn("server plane already running; ignoring duplicate SERVER_STARTED")
            return
        }

        val d = RpcDispatcher().apply {
            registerGroup("world", WorldOps)
            registerGroup("be", BlockEntityOps)
            registerGroup("inv", InventoryOps)
            registerGroup("scan", ScanOps)
            registerGroup("server", ServerOps)
            registerGroup("wait", WaitOps)
            registerGroup("fluid", FluidOps)
            registerGroup("craft", CraftingOps)
            registerGroup("storage", StorageOps)
            registerGroup("snapshot", SnapshotOps)
            registerGroup("trace", TraceOps)
            registerGroup("screen", ScreenOps)
            registerGroup("redstone", RedstoneOps)
            registerGroup("entity", EntityOps)
            registerGroup("fixture", FixtureOps)
            registerGroup("reflect", ReflectOps)
        }
        dispatcher = d
        currentServer = server

        // Install the tick listener for wait.until. This is passive — it only observes server tick events.
        WaitOps.install()
        TraceOps.install()
        RedstoneOps.install()
        FakePlayerPool.install()

        rpcServer = RpcServer(d, ::socketFilePath, ::tcpPortFilePath).also { it.start(server) }
        McDebugMod.LOGGER.info(
            "mcdebug RPC ready (server plane): unix socket={}, tcpPort={}",
            rpcServer?.socketPath,
            rpcServer?.tcpPort,
        )
    }

    private fun onServerStopping(server: MinecraftServer) {
        try {
            WaitOps.uninstall()
            TraceOps.uninstall()
            RedstoneOps.uninstall()
            ScreenOps.clear()
            ReflectOps.clear()
            FakePlayerPool.shutdown()
            rpcServer?.stop()
        } catch (e: Exception) {
            McDebugMod.LOGGER.warn("error stopping mcdebug", e)
        } finally {
            rpcServer = null
            dispatcher = null
            currentServer = null
        }
    }

    /** Discovery file written by RpcServer: content is the unix socket path. */
    private fun socketFilePath(): Path =
        Paths.get(RpcEnv.gameDir().resolve("mcdebug").toString(), "port")

    /** Discovery file written by RpcServer: content is the TCP port (only if TCP bound). */
    private fun tcpPortFilePath(): Path =
        Paths.get(RpcEnv.gameDir().resolve("mcdebug").toString(), "tcpPort")
}
