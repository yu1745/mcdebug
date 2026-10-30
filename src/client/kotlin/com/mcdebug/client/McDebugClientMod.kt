package com.mcdebug.client

import com.mcdebug.client.loader.ClientLoader
import com.mcdebug.client.loader.fabric.FabricClientLoaderApi
import com.mcdebug.loader.Loader
import com.mcdebug.loader.fabric.FabricLoaderApi
import net.fabricmc.api.ClientModInitializer

/**
 * Fabric `client` entrypoint: installs both loader seams and hands over to [ClientPlane], which
 * holds the loader-neutral bootstrap shared with the NeoForge client entrypoint.
 */
class McDebugClientMod : ClientModInitializer {

    override fun onInitializeClient() {
        Loader.install(FabricLoaderApi())
        ClientLoader.install(FabricClientLoaderApi())
        ClientPlane.start()
    }
}
