package com.mcdebug

import com.mcdebug.api.BlockEntityOps
import com.mcdebug.api.CraftingOps
import com.mcdebug.api.FluidOps
import com.mcdebug.api.InventoryOps
import com.mcdebug.api.McDebugOps
import com.mcdebug.api.ScanOps
import com.mcdebug.api.ServerOps
import com.mcdebug.api.WorldOps
import com.mcdebug.rpc.RpcDispatcher
import com.mcdebug.rpc.RpcServer
import com.mcdebug.test.McDebugTestRegistry
import com.mcdebug.test.TestDiscovery
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
    private var dispatcher: RpcDispatcher? = null

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
            registerGroup("mcdebug", McDebugOps)
        }
        dispatcher = d

        // Install the tick listener for wait.until. This is passive — it only observes server tick events.
        WaitOps.install()

        // Discover and register @McDebugTests-annotated test objects.
        // Package list comes from MCDEBUG_TEST_SCAN_PACKAGES env var (comma-separated),
        // defaulting to "com" — broad but acceptable for dev. Consumers (gradle plugin)
        // should set the env var to a tight set to avoid scanning unrelated jars.
        discoverAndRegisterTests()

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
            McDebugTestRegistry.clear()
        }
    }

    private fun discoverAndRegisterTests() {
        val pkgEnv = System.getenv("MCDEBUG_TEST_SCAN_PACKAGES")
        val packages = pkgEnv
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("com")
        val testClasses = TestDiscovery.discover(basePackages = packages)
        var registered = 0
        for (kclass in testClasses) {
            val instance = kclass.objectInstance
            if (instance == null) {
                LOGGER.warn("mcdebug test class ${kclass.qualifiedName} is not a Kotlin object; skipping")
                continue
            }
            McDebugTestRegistry.register(instance)
            registered++
        }
        LOGGER.info("mcdebug: discovered ${testClasses.size} test classes from $packages, registered $registered")
    }

    private fun portFilePath(): Path {
        val runDir = FabricLoader.getInstance().gameDir.resolve("mcdebug")
        return Paths.get(runDir.toString(), "port")
    }
}
