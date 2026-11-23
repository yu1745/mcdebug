package com.mcdebug.loader.neoforge

import com.mcdebug.client.loader.ClientLoaderApi
import net.minecraft.client.Minecraft
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.GameShuttingDownEvent
import net.neoforged.neoforge.common.NeoForge
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NeoForge implementation of the client seam.
 *
 * Two deliberate differences from the Fabric implementation:
 *  - NeoForge 21.1 has no client "started" event, so [onClientStarted] fires on the first client
 *    tick; [onClientStopping] uses `GameShuttingDownEvent`.
 *  - [classBytes] reads the classpath copy through the class loader; Mixin is applied explicitly by
 *    `gui.hotswap` afterwards, so both `runTransformers` values return the same bytes here.
 */
class NeoForgeClientLoaderApi : ClientLoaderApi {

    override fun onClientTick(callback: () -> Unit) {
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post -> callback() }
    }

    override fun onClientStarted(callback: (Minecraft) -> Unit) {
        // One flag per registration, so several callbacks can all fire (once each).
        val fired = AtomicBoolean(false)
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post ->
            if (fired.compareAndSet(false, true)) callback(Minecraft.getInstance())
        }
    }

    /**
     * `GameShuttingDownEvent` is fired on the client thread while the GL context is still valid
     * (before `System.exit`), which is exactly Fabric's `CLIENT_STOPPING`. A JVM shutdown hook
     * would run after the game loop is gone: `client.execute{}` never drains and GL may be dead.
     */
    override fun onClientStopping(callback: (Minecraft) -> Unit) {
        val fired = AtomicBoolean(false)
        NeoForge.EVENT_BUS.addListener { _: GameShuttingDownEvent ->
            if (fired.compareAndSet(false, true)) {
                runCatching { callback(Minecraft.getInstance()) }
                    .onFailure { LOGGER.warn("client-plane cleanup at shutdown failed: {}", it.toString()) }
            }
        }
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
