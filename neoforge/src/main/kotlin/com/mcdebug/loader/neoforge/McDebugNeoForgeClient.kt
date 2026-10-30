package com.mcdebug.loader.neoforge

import com.mcdebug.McDebug
import com.mcdebug.client.ClientPlane
import com.mcdebug.client.loader.ClientLoader
import com.mcdebug.loader.Loader
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod

/**
 * NeoForge client entrypoint (`gui.*` plane). Only constructed in a client.
 *
 * The *server* plane is installed by the common entrypoint [McDebugNeoForge]; on a single-player
 * client that is what gives the integrated server an RPC endpoint, and [ClientPlane] adds the
 * client plane next to it. Loading order of the two `@Mod` classes is not guaranteed, so the
 * common seam is installed here too when it is still missing.
 */
@Mod(value = McDebug.MOD_ID, dist = [Dist.CLIENT])
object McDebugNeoForgeClient {
    init {
        if (!Loader.installed) Loader.install(NeoForgeLoaderApi())
        ClientLoader.install(NeoForgeClientLoaderApi())
        ClientPlane.start()
    }
}
