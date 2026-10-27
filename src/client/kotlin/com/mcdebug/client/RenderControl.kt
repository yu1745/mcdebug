package com.mcdebug.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.McDebugMod
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.GameRenderer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keep the normal game loop, network, client/server ticks and window event pump.
 * Only the GameRenderer call is gated; a screenshot is read back after that call
 * returns (and GuiGraphics has flushed), before the window blit.
 *
 * Loading/reload and non-container screens deliberately keep normal rendering:
 * vanilla and mods may complete screen transitions from render callbacks.
 */
object RenderControl {
    private var schedule = RenderSchedule(RenderSettings())
    private var lastScreen: Screen? = null
    private var frames = 0L
    private var skipped = 0L
    private var effectiveMode = "continuous"
    private val virtualCursor = RenderCursor<Screen>()

    fun install(gameDir: Path) {
        val path = gameDir.resolve("config/mcdebug.json")
        val config = try {
            if (Files.exists(path)) Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject } else JsonObject()
        } catch (e: Exception) {
            // The shared transport config is best-effort too. A broken/empty
            // file must not prevent a normal player client from starting.
            McDebugMod.LOGGER.warn("Cannot read render config {}: {}; ignoring file", path, e.message)
            JsonObject()
        }
        fun setting(key: String, env: String, default: String): String =
            System.getProperty("mcdebug.$key") ?: System.getenv(env) ?: config.get(key)?.asString ?: default
        val settings = RenderSettings(
            RenderMode.parse(setting("clientRenderMode", "MCDEBUG_CLIENT_RENDER_MODE", "continuous")),
            setting("clientLoopFps", "MCDEBUG_CLIENT_LOOP_FPS", "20").toInt(),
            setting("clientIdleFps", "MCDEBUG_CLIENT_IDLE_FPS", "1").toInt(),
        )
        schedule = RenderSchedule(settings)
        McDebugMod.LOGGER.info("mcdebug render policy: mode={} loopFps={} idleFps={}", settings.mode.id, settings.loopFps, settings.idleFps)
    }

    private fun safeToThrottle(client: Minecraft): Boolean =
        client.isGameLoadFinished && client.getOverlay() == null && client.level != null && client.player != null &&
            (client.screen == null || client.screen is AbstractContainerScreen<*>)

    @JvmStatic
    fun renderFrame(renderer: GameRenderer, delta: DeltaTracker, renderWorld: Boolean) {
        val client = Minecraft.getInstance()
        val changed = client.screen !== lastScreen
        virtualCursor.observe(client.screen)
        lastScreen = client.screen
        val mode = schedule.nextFrame(System.nanoTime(), safeToThrottle(client), Capture.hasPendingRequests(), changed)
        if (mode == null) {
            skipped++
            effectiveMode = "idle"
            return
        }
        effectiveMode = mode.id
        val screenBefore = client.screen
        val overlayBefore = client.getOverlay()
        if (mode == RenderMode.GUI_ONLY) {
            // Unlike renderLevel, GUI-only never clears the colour attachment.
            // Start with a neutral target, not the previous GUI/world underneath
            // translucent widgets. clear() unbinds, so explicitly bind it again.
            client.mainRenderTarget.clear(Minecraft.ON_OSX)
            client.mainRenderTarget.bindWrite(true)
        }
        renderer.render(delta, renderWorld && mode != RenderMode.GUI_ONLY)
        frames++
        // LoadingOverlay and mod render callbacks may switch/init screens. Do not
        // pair the old frame's pixels with a new Screen's bounds; keep requests
        // pending so idleFps=0 still forces the following natural frame.
        if (client.screen === screenBefore && client.getOverlay() === overlayBefore) Capture.afterFrame(client)
    }

    /** Limit the outer loop, not the natural tick rate. Never throttle loading. */
    @JvmStatic
    fun frameLimit(original: Int): Int =
        if (schedule.settings.mode != RenderMode.CONTINUOUS && safeToThrottle(Minecraft.getInstance())) schedule.settings.loopFps else original

    /**
     * A lite client must not need focus or warp the user's desktop pointer just
     * to draw a carried stack/tooltip at the last RPC click's Screen coordinates.
     */
    fun moveCursor(x: Double, y: Double): Boolean {
        if (schedule.settings.mode == RenderMode.CONTINUOUS) return false
        val screen = Minecraft.getInstance().screen ?: return false
        virtualCursor.move(screen, x, y)
        return true
    }

    @JvmStatic
    fun keepRunningUnfocused(): Boolean = schedule.settings.mode != RenderMode.CONTINUOUS

    @JvmStatic
    fun mouseX(original: Int): Int = virtualCursor.x(original)

    @JvmStatic
    fun mouseY(original: Int): Int = virtualCursor.y(original)

    fun statusJson(): JsonObject = JsonObject().apply {
        addProperty("mode", schedule.settings.mode.id)
        addProperty("effectiveMode", effectiveMode)
        addProperty("loopFps", schedule.settings.loopFps)
        addProperty("idleFps", schedule.settings.idleFps)
        addProperty("frames", frames)
        addProperty("skippedFrames", skipped)
        addProperty("capturePending", Capture.hasPendingRequests())
        addProperty("virtualCursor", virtualCursor.active)
    }
}
