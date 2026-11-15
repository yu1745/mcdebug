package com.mcdebug.client.render

import com.mcdebug.client.devloop.ClientConfig

import com.google.gson.JsonObject
import com.mcdebug.McDebug
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.GameRenderer
import java.util.concurrent.locks.LockSupport

/**
 * Keep the normal game loop, network, client/server ticks and window event pump.
 * Only the GameRenderer call is gated; a screenshot is read back after that call
 * returns (and GuiGraphicsExtractor has flushed), before the window blit.
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

    fun install() {
        fun setting(key: String, env: String, default: String) = ClientConfig.setting(key, env, default)
        val settings = RenderSettings(
            RenderMode.parse(setting("clientRenderMode", "MCDEBUG_CLIENT_RENDER_MODE", "continuous")),
            setting("clientLoopFps", "MCDEBUG_CLIENT_LOOP_FPS", "20").toInt(),
            setting("clientIdleFps", "MCDEBUG_CLIENT_IDLE_FPS", "1").toInt(),
        )
        schedule = RenderSchedule(settings)
        McDebug.LOGGER.info("mcdebug render policy: mode={} loopFps={} idleFps={}", settings.mode.id, settings.loopFps, settings.idleFps)
    }

    private fun safeToThrottle(client: Minecraft): Boolean =
        client.isGameLoadFinished && client.gui.overlay() == null && client.level != null && client.player != null &&
            (client.gui.screen() == null || client.gui.screen() is AbstractContainerScreen<*>)

    private var prepared = false
    private var screenBefore: Screen? = null
    private var overlayBefore: Any? = null
    private var frameCursor: String? = null
    private var drawLog: JsonObject? = null

    @JvmStatic
    fun extractFrame(renderer: GameRenderer, delta: DeltaTracker, advanceGameTime: Boolean) {
        val client = Minecraft.getInstance()
        val changed = client.gui.screen() !== lastScreen
        virtualCursor.observe(client.gui.screen())
        lastScreen = client.gui.screen()
        val mode = schedule.nextFrame(System.nanoTime(), safeToThrottle(client), Capture.hasPendingRequests(), changed)
        prepared = mode != null
        if (mode == null) {
            skipped++
            effectiveMode = "idle"
            return
        }
        effectiveMode = mode.id
        screenBefore = client.gui.screen()
        overlayBefore = client.gui.overlay()
        frameCursor = Capture.nextFrameCursor()
        val recordDraws = Capture.wantsDrawLog(frameCursor)
        if (recordDraws) DrawRecorder.begin()
        hideCursor = frameCursor == "hide"
        drawLog = null
        WidgetFrames.beginFrame()
        try {
            renderer.extract(delta, advanceGameTime && mode != RenderMode.GUI_ONLY)
        } finally {
            hideCursor = false
            if (recordDraws) drawLog = DrawRecorder.end()
        }
        if (client.gui.screen() === screenBefore) WidgetFrames.endFrame(screenBefore)
    }

    @JvmStatic
    fun renderFrame(renderer: GameRenderer) {
        if (!prepared) return
        val client = Minecraft.getInstance()
        renderer.render()
        frames++
        // Extraction observes widgets; readback must follow GPU rendering of that same state.
        if (client.gui.screen() === screenBefore && client.gui.overlay() === overlayBefore) {
            Capture.afterFrame(client, drawLog, frameCursor ?: "keep")
        }
        prepared = false
    }

    /** Limit the outer loop, not the natural tick rate. Never throttle loading. */
    @JvmStatic
    fun frameLimit(original: Int): Int {
        throttling = schedule.settings.mode != RenderMode.CONTINUOUS && safeToThrottle(Minecraft.getInstance())
        return if (throttling) schedule.settings.loopFps else original
    }

    // Set by frameLimit right before runTick calls limitDisplayFPS; read by RPC threads in wake().
    @Volatile private var throttling = false
    @Volatile private var wakeRequested = false
    private var lastDrawTime = 0L
    @Volatile private var loopThread: Thread? = null
    private var wakes = 0L

    /**
     * Replaces vanilla's limitDisplayFPS only while throttling. Same deadline
     * arithmetic, but an RPC hop queued during the wait ends it early, so a lite
     * client answers the next task within one event-pump round instead of up to a
     * whole 1/loopFps sleep. Returns false to leave vanilla's limiter in charge.
     */
    @JvmStatic
    fun limitDisplayFps(fps: Int): Boolean {
        if (!throttling || fps <= 0) return false
        val deadline = lastDrawTime + 1_000_000_000L / fps
        loopThread = Thread.currentThread()
        var now = System.nanoTime()
        while (now < deadline && !wakeRequested) {
            LockSupport.parkNanos(deadline - now)
            now = System.nanoTime()
        }
        if (wakeRequested) wakes++
        wakeRequested = false
        lastDrawTime = now
        return true
    }

    /** Any thread: wake only the client loop's own frame limiter, leaving SDL's event queue intact. */
    fun wake() {
        if (!throttling) return
        wakeRequested = true
        loopThread?.let(LockSupport::unpark)
    }

    /**
     * A lite client must not need focus or warp the user's desktop pointer just
     * to draw a carried stack/tooltip at the last RPC click's Screen coordinates.
     */
    fun moveCursor(x: Double, y: Double): Boolean {
        if (schedule.settings.mode == RenderMode.CONTINUOUS) return false
        val screen = Minecraft.getInstance().gui.screen() ?: return false
        virtualCursor.move(screen, x, y)
        return true
    }

    @JvmStatic
    fun keepRunningUnfocused(): Boolean = schedule.settings.mode != RenderMode.CONTINUOUS

    // Client thread, only during one capture frame (`cursor: hide`).
    private var hideCursor = false
    private const val HIDDEN = -100_000

    @JvmStatic
    fun mouseX(original: Int): Int = if (hideCursor) HIDDEN else virtualCursor.x(original)

    @JvmStatic
    fun mouseY(original: Int): Int = if (hideCursor) HIDDEN else virtualCursor.y(original)

    fun statusJson(): JsonObject = JsonObject().apply {
        addProperty("mode", schedule.settings.mode.id)
        addProperty("effectiveMode", effectiveMode)
        addProperty("loopFps", schedule.settings.loopFps)
        addProperty("idleFps", schedule.settings.idleFps)
        addProperty("frames", frames)
        addProperty("skippedFrames", skipped)
        addProperty("capturePending", Capture.hasPendingRequests())
        addProperty("virtualCursor", virtualCursor.active)
        addProperty("rpcWakes", wakes)
    }
}
