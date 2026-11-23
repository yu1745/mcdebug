package com.mcdebug.client.loader.fabric

import com.mcdebug.client.loader.ClientLoaderApi
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.minecraft.client.Minecraft
import java.nio.file.Path

/** Fabric implementation of the client seam. */
class FabricClientLoaderApi : ClientLoaderApi {
    override fun onClientTick(callback: () -> Unit) {
        ClientTickEvents.END_CLIENT_TICK.register { callback() }
    }

    override fun onClientStarted(callback: (Minecraft) -> Unit) {
        ClientLifecycleEvents.CLIENT_STARTED.register { callback(it) }
    }

    override fun onClientStopping(callback: (Minecraft) -> Unit) {
        ClientLifecycleEvents.CLIENT_STOPPING.register { callback(it) }
    }

    override val gameDir: Path get() = FabricLoader.getInstance().gameDir

    /**
     * Knot's classpath copy. `runTransformers = true` is the loader's own pre-mixin pipeline (it
     * runs the class through the launch transformers), which is what `gui.hotswap` needs so a
     * redefinition does not strip mixin-applied changes.
     */
    override fun classBytes(name: String, runTransformers: Boolean): ByteArray? =
        try {
            FabricLauncherBase.getLauncher().getClassByteArray(name, runTransformers)
        } catch (_: Throwable) {
            null
        }
}
