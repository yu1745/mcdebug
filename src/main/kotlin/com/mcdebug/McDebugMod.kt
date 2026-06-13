package com.mcdebug

import com.mcdebug.api.BlockEntityOps
import com.mcdebug.api.CraftingOps
import com.mcdebug.api.FluidOps
import com.mcdebug.api.InventoryOps
import com.mcdebug.api.ScanOps
import com.mcdebug.api.ServerOps
import com.mcdebug.api.WorldOps
import com.mcdebug.rpc.RpcDispatcher
import com.mcdebug.rpc.RpcServer
import com.mcdebug.wait.WaitOps
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

/**
 * mcdebug — localhost JSON-RPC debug server for mod development automation.
 *
 * The RPC server binds 127.0.0.1:0 (OS-assigned port) and writes the bound port to
 * `<run_dir>/mcdebug/port` so the TypeScript CLI can find it.
 */
object McDebugMod : DedicatedServerModInitializer {
    const val MOD_ID = "mcdebug"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    val MOD_VERSION: String = FabricLoader.getInstance().getModContainer(MOD_ID)
        .map { it.metadata.version.friendlyString }
        .orElse("0.0.0")

    private var rpcServer: RpcServer? = null

    /** Live JSON-RPC dispatcher for the running MC server. */
    @Volatile
    var dispatcher: RpcDispatcher? = null
        private set

    /** The MinecraftServer instance backing the current RPC session. */
    @Volatile
    var currentServer: MinecraftServer? = null
        private set

    override fun onInitializeServer() {
        LOGGER.info("mcdebug {} initializing", MOD_VERSION)

        ServerLifecycleEvents.SERVER_STARTED.register(::onServerStarted)
        ServerLifecycleEvents.SERVER_STOPPING.register(::onServerStopping)
    }

    private fun onServerStarted(server: MinecraftServer) {
        val d = RpcDispatcher().apply {
            registerGroup("world", WorldOps)
            registerGroup("be", BlockEntityOps)
            registerGroup("inv", InventoryOps)
            registerGroup("scan", ScanOps)
            registerGroup("server", ServerOps)
            registerGroup("wait", WaitOps)
            registerGroup("fluid", FluidOps)
            registerGroup("craft", CraftingOps)
        }
        dispatcher = d
        currentServer = server

        // Install the tick listener for wait.until. This is passive — it only observes server tick events.
        WaitOps.install()

        rpcServer = RpcServer(d, ::portFilePath).also { it.start(server) }
        LOGGER.info("mcdebug RPC ready on 127.0.0.1:{}", rpcServer?.boundPort)
    }

    private fun onServerStopping(server: MinecraftServer) {
        try {
            WaitOps.uninstall()
            rpcServer?.stop()
        } catch (e: Exception) {
            LOGGER.warn("error stopping mcdebug", e)
        } finally {
            rpcServer = null
            dispatcher = null
            currentServer = null
        }
    }

    private fun portFilePath(): Path {
        val runDir = FabricLoader.getInstance().gameDir.resolve("mcdebug")
        return Paths.get(runDir.toString(), "port")
    }
}
