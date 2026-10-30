package com.mcdebug.loader.neoforge

import com.mcdebug.client.loader.ClientLoaderApi
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NeoForge implementation of the client seam.
 *
 * Two deliberate differences from the Fabric implementation:
 *  - NeoForge 21.1 has `ClientTickEvent.Post` but no client *lifecycle* event, so
 *    [onClientStarted] fires on the first client tick and [onClientStopping] uses a JVM shutdown
 *    hook (which still runs before the process goes away, and after the integrated server saved).
 *  - [classBytes] reads the classpath copy through the class loader; Mixin is applied explicitly by
 *    `gui.hotswap` afterwards, so both `runTransformers` values return the same bytes here.
 */
class NeoForgeClientLoaderApi : ClientLoaderApi {

    private val startedCallback = AtomicBoolean(false)

    override fun onClientTick(callback: () -> Unit) {
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post -> callback() }
    }

    override fun onClientStarted(callback: (Minecraft) -> Unit) {
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post ->
            if (startedCallback.compareAndSet(false, true)) callback(Minecraft.getInstance())
        }
    }

    /**
     * 21.1 has no client-stopping event, so this is a JVM shutdown hook: it runs on a fresh thread
     * while the game thread may already be tearing down. Cleanup therefore runs best-effort on the
     * client thread first (so it sees a live client, like Fabric's CLIENT_STOPPING) and only then
     * inline, and a failure is logged instead of silently swallowed — a silent no-op used to look
     * exactly like a successful stop (stale socket file, uncancelled waiters).
     */
    override fun onClientStopping(callback: (Minecraft) -> Unit) {
        Runtime.getRuntime().addShutdownHook(Thread({
            val client = runCatching { Minecraft.getInstance() }.getOrNull()
            val ran = java.util.concurrent.CompletableFuture<Boolean>()
            if (client != null) {
                // Exactly once: prefer the client thread (what Fabric's CLIENT_STOPPING gives us).
                runCatching {
                    client.execute {
                        ran.complete(
                            runCatching { callback(client) }
                                .onFailure { LOGGER.warn("client-plane cleanup on the client thread failed: {}", it.toString()) }
                                .isSuccess,
                        )
                    }
                }
            }
            val completed = runCatching { ran.get(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
            if (!completed) {
                // Client thread already gone (or too busy to accept): run inline, still exactly once.
                runCatching { callback(client ?: Minecraft.getInstance()) }
                    .onFailure { LOGGER.warn("client-plane cleanup at shutdown failed: {}", it.toString()) }
            }
        }, "mcdebug-client-stopping"))
    }

    private companion object {
        private val LOGGER = org.slf4j.LoggerFactory.getLogger("mcdebug")
    }

    override val gameDir: Path get() = NeoForgeLoaderApi().gameDir

    override fun classBytes(name: String, runTransformers: Boolean): ByteArray? =
        try {
            javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
}
