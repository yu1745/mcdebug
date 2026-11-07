package com.mcdebug.loader.forge

import com.mcdebug.McDebug
import com.mcdebug.ServerPlane
import com.mcdebug.client.ClientPlane
import com.mcdebug.client.loader.ClientLoader
import com.mcdebug.loader.Loader
import com.mcdebug.rpc.RpcEnv
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment

/**
 * Forge 1.20.1 entrypoint (KotlinForForge loads the `object`). Installs the loader seam and the
 * server plane; on a physical client it also installs the client plane (`gui.*`).
 */
@Mod(McDebug.MOD_ID)
object McDebugForge {
    init {
        Loader.install(ForgeLoaderApi())
        RpcEnv.gameDir = { Loader.api.gameDir }
        McDebug.LOGGER.info("mcdebug {} initializing (forge)", McDebug.MOD_VERSION)
        ServerPlane.installLifecycleHooks()
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientLoader.install(ForgeClientLoaderApi())
            ClientPlane.start()
        }
    }
}
