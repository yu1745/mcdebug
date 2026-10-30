package com.mcdebug.loader.neoforge

import com.mcdebug.McDebug
import com.mcdebug.ServerPlane
import com.mcdebug.loader.Loader
import com.mcdebug.rpc.RpcEnv
import net.neoforged.fml.common.Mod

/**
 * NeoForge entrypoint — installs the loader seam and the server plane.
 *
 * A common (dist-agnostic) `@Mod` class is constructed on the dedicated server **and** on a
 * client; on a single-player client the integrated server fires the same
 * `ServerStartedEvent`, so one installation gives both planes in one process — mirroring the
 * Fabric side where the client entrypoint calls [ServerPlane.installLifecycleHooks].
 *
 * The client plane (`gui.*`) lives in [McDebugNeoForgeClient] (`dist = Dist.CLIENT`), which
 * NeoForge only loads in a client.
 *
 * Kotlin note: this is a Kotlin `object`, which requires KotlinForForge (declared as
 * `modLoader="kotlinforforge"` in `neoforge.mods.toml`).
 */
@Mod(McDebug.MOD_ID)
object McDebugNeoForge {
    init {
        Loader.install(NeoForgeLoaderApi())
        RpcEnv.gameDir = { Loader.api.gameDir }
        McDebug.LOGGER.info("mcdebug {} initializing (neoforge)", McDebug.MOD_VERSION)
        ServerPlane.installLifecycleHooks()
    }
}
