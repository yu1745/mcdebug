package com.mcdebug.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.McDebugMod
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.nio.file.Files
import java.nio.file.Path

/** Client-plane settings: JVM property `mcdebug.<key>` → env → `config/mcdebug.json` → default. */
object ClientConfig {
    private var config = JsonObject()

    fun load(gameDir: Path) {
        val path = gameDir.resolve("config/mcdebug.json")
        config = try {
            if (Files.exists(path)) Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject } else JsonObject()
        } catch (e: Exception) {
            // The shared transport config is best-effort too. A broken/empty
            // file must not prevent a normal player client from starting.
            McDebugMod.LOGGER.warn("Cannot read client config {}: {}; ignoring file", path, e.message)
            JsonObject()
        }
    }

    fun setting(key: String, env: String, default: String): String =
        System.getProperty("mcdebug.$key") ?: System.getenv(env) ?: config.get(key)?.asString ?: default
}

/**
 * Keeps a client alive when the open Screen's render code throws.
 *
 * Vanilla turns such an exception into a crash report and exits, which costs an
 * agent a full client restart for one typo. With the guard on (default;
 * `clientScreenGuard=false` restores vanilla), the exception is logged at ERROR,
 * recorded in [ProblemLog] and that Screen instance stops being rendered. The
 * world, HUD, ticks and RPC keep running; `gui.reinit` (after a hotswap) or
 * closing the screen clears the fault.
 *
 * Only `Screen.renderWithTooltip` is guarded: world/HUD/overlay exceptions crash
 * exactly as before.
 */
object ScreenGuard {
    @Volatile var enabled = true
        private set
    private var faulted: Screen? = null // client thread only
    @Volatile private var fault: JsonObject? = null

    fun install() {
        enabled = ClientConfig.setting("clientScreenGuard", "MCDEBUG_CLIENT_SCREEN_GUARD", "true").toBoolean()
    }

    @JvmStatic
    fun shouldSkip(screen: Screen): Boolean = faulted != null && faulted === screen

    /** Returns false when the guard is off: the caller then rethrows into vanilla's crash path. */
    @JvmStatic
    fun capture(screen: Screen, error: Throwable): Boolean {
        if (!enabled) return false
        faulted = screen
        val description = ProblemLog.describe(error)
        McDebugMod.LOGGER.error("mcdebug screen guard: {} threw while rendering; it is no longer drawn until gui.reinit or close",
            screen.javaClass.name, error)
        // The ERROR line above already reaches ProblemLog through its appender.
        if (!ProblemLog.available) ProblemLog.record("ERROR", "mcdebug/screen-guard", "render failed in ${screen.javaClass.name}", error)
        fault = JsonObject().apply {
            addProperty("screen", screen.javaClass.name)
            addProperty("clientTick", ClientTasks.currentTick())
            addProperty("error", error.toString())
            addProperty("trace", description)
        }
        return true
    }

    /** Kotlin may throw a Throwable without declaring it; the Java mixin cannot. */
    @JvmStatic
    fun rethrow(error: Throwable): Nothing = throw error

    /** Client thread only. */
    fun clear() {
        faulted = null
        fault = null
    }

    /** Client thread only: a closed/replaced Screen no longer counts as faulted. */
    fun currentFault(client: Minecraft): JsonObject? {
        if (faulted != null && client.screen !== faulted) clear()
        return fault?.deepCopy()
    }

    fun statusJson(client: Minecraft): JsonObject = JsonObject().apply {
        addProperty("enabled", enabled)
        currentFault(client)?.let { add("fault", it) }
    }
}
