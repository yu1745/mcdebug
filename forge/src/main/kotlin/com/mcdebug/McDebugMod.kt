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
import com.mcdebug.wait.WaitOps
import com.mcdebug.rpc.RpcDispatcher
import com.mcdebug.rpc.RpcEnv
import com.mcdebug.rpc.RpcServer
import net.minecraft.server.MinecraftServer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLPaths
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * mcdebug (Forge 1.20.1 port) — same JSON-RPC surface as the Fabric mod.
 * Transport (RpcServer) and protocol (RpcHandler/JsonRpc/RpcErrors) are shared
 * source with the Fabric build; everything touching Minecraft is ported.
 */
@Mod(McDebugMod.MOD_ID)
object McDebugMod {
    const val MOD_ID = "mcdebug"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    val MOD_VERSION: String = ModList.get().getModContainerById(MOD_ID)
        .map { it.modInfo.version.toString() }
        .orElse("0.0.0")

    private var rpcServer: RpcServer? = null

    @Volatile
    var dispatcher: RpcDispatcher? = null
        private set

    @Volatile
    var currentServer: MinecraftServer? = null
        private set

    init {
        RpcEnv.gameDir = { FMLPaths.GAMEDIR.get() }
        LOGGER.info("mcdebug {} (forge) initializing", MOD_VERSION)
        MinecraftForge.EVENT_BUS.addListener(::onServerStarted)
        MinecraftForge.EVENT_BUS.addListener(::onServerStopping)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        val server = event.server
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

        // Passive tick observers (wait.until / trace / redstone pulses); they never advance ticks.
        WaitOps.install()
        TraceOps.install()
        RedstoneOps.install()
        FakePlayerPool.install()

        rpcServer = RpcServer(d, ::socketFilePath, ::tcpPortFilePath).also { it.start(server) }
        LOGGER.info("mcdebug RPC ready: unix socket={}, tcpPort={}", rpcServer?.socketPath, rpcServer?.tcpPort)
    }

    private fun onServerStopping(event: ServerStoppingEvent) {
        try {
            WaitOps.uninstall()
            TraceOps.uninstall()
            RedstoneOps.uninstall()
            ScreenOps.clear()
            FakePlayerPool.shutdown()
            rpcServer?.stop()
        } catch (e: Exception) {
            LOGGER.warn("error stopping mcdebug", e)
        } finally {
            rpcServer = null
            dispatcher = null
            currentServer = null
        }
    }

    private fun socketFilePath(): Path = FMLPaths.GAMEDIR.get().resolve("mcdebug").resolve("port")
    private fun tcpPortFilePath(): Path = FMLPaths.GAMEDIR.get().resolve("mcdebug").resolve("tcpPort")
}
