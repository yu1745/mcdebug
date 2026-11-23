package com.mcdebug.client.loader

import net.minecraft.client.Minecraft
import java.nio.file.Path

/**
 * The client plane's loader seam — the counterpart of `com.mcdebug.loader.LoaderApi` for things
 * that only exist in a real client. Everything loader-specific in `src/client` goes through this
 * interface so the same client tree compiles and runs on Fabric and NeoForge:
 *
 *  - client tick / started / stopping events ([ClientTasks], [com.mcdebug.client.ClientPlane]),
 *  - the game directory ([com.mcdebug.client.ClientInfo]),
 *  - pre-mixin class bytes for `gui.hotswap` ([com.mcdebug.client.Hotswap]).
 *
 * The loader-neutral half of the seam ([com.mcdebug.loader.Loader]) is installed by the same
 * entrypoint; this one is installed by the loader's *client* entrypoint.
 */
interface ClientLoaderApi {
    /** Called once per client tick, on the client thread. Must never drive ticks itself (AGENTS.md §7). */
    fun onClientTick(callback: () -> Unit)

    /**
     * Called when the client is up (the first client tick on NeoForge, `CLIENT_STARTED` on Fabric).
     * Used by the session bootstrap to stop a client whose launch was cancelled or superseded.
     */
    fun onClientStarted(callback: (Minecraft) -> Unit)

    /**
     * Called while the client is still alive but shutting down, so the client plane can stop
     * accepting calls before the event loop disappears. Fabric uses `CLIENT_STOPPING`; NeoForge has
     * no equivalent event in 21.1 and uses a JVM shutdown hook instead.
     */
    fun onClientStopping(callback: (Minecraft) -> Unit)

    /** This loader's game directory. */
    val gameDir: Path

    /**
     * Bytes of [name] as the loader's own classpath provides them, or null when it cannot read
     * them. [runTransformers] asks for post-transformer (i.e. post-mixin) bytes where the loader
     * can provide them; NeoForge returns the same bytes for both and lets
     * [com.mcdebug.client.Hotswap] apply mixins explicitly.
     */
    fun classBytes(name: String, runTransformers: Boolean): ByteArray?
}

/** Holder for the installed [ClientLoaderApi]; mirrors `com.mcdebug.loader.Loader`. */
object ClientLoader {
    @Volatile
    private var impl: ClientLoaderApi? = null

    val api: ClientLoaderApi
        get() = impl ?: error("mcdebug client loader seam not installed (client entrypoint did not run)")

    val installed: Boolean get() = impl != null

    fun install(api: ClientLoaderApi) {
        impl = api
    }
}
