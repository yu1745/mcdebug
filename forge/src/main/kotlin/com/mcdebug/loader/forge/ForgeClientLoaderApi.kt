package com.mcdebug.loader.forge

import com.mcdebug.client.loader.ClientLoaderApi
import net.minecraft.client.Minecraft
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forge 1.20.1 client seam. Like NeoForge there is no client "started" event (first client tick is
 * used) and no shutdown event on the game thread; a JVM shutdown hook is the closest equivalent.
 */
class ForgeClientLoaderApi : ClientLoaderApi {
    override fun onClientTick(callback: () -> Unit) {
        MinecraftForge.EVENT_BUS.addListener { event: TickEvent.ClientTickEvent ->
            if (event.phase == TickEvent.Phase.END) callback()
        }
    }

    override fun onClientStarted(callback: (Minecraft) -> Unit) {
        val fired = AtomicBoolean(false)
        MinecraftForge.EVENT_BUS.addListener { event: TickEvent.ClientTickEvent ->
            if (event.phase == TickEvent.Phase.END && fired.compareAndSet(false, true)) callback(Minecraft.getInstance())
        }
    }

    override fun onClientStopping(callback: (Minecraft) -> Unit) {
        val fired = AtomicBoolean(false)
        Runtime.getRuntime().addShutdownHook(Thread {
            if (fired.compareAndSet(false, true)) {
                runCatching { callback(Minecraft.getInstance()) }
                    .onFailure { LOGGER.warn("client-plane cleanup at shutdown failed: {}", it.toString()) }
            }
        })
    }

    private companion object {
        private val LOGGER = org.slf4j.LoggerFactory.getLogger("mcdebug")
    }

    override val gameDir: Path get() = FMLPaths.GAMEDIR.get()

    override fun classBytes(name: String, runTransformers: Boolean): ByteArray? =
        try {
            javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
}
