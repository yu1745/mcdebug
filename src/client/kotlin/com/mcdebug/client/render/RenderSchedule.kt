package com.mcdebug.client

/** Explicit runtime policies, not alternate/legacy Minecraft implementations. */
enum class RenderMode(val id: String) {
    CONTINUOUS("continuous"),
    DEMAND("demand"),
    GUI_ONLY("gui-only");

    companion object {
        fun parse(value: String): RenderMode = entries.firstOrNull { it.id == value.trim().lowercase() }
            ?: throw IllegalArgumentException("clientRenderMode must be continuous, demand or gui-only (got '$value')")
    }
}

data class RenderSettings(
    val mode: RenderMode = RenderMode.CONTINUOUS,
    val loopFps: Int = 20,
    val idleFps: Int = 1,
) {
    init {
        require(loopFps in 20..260) { "clientLoopFps must be 20..260 (the loop also drives client ticks and RPC tasks)" }
        require(idleFps in 0..loopFps) { "clientIdleFps must be 0..clientLoopFps (0 = only render on request/change)" }
    }
}

/** A synthetic pointer belongs to a Screen, even before that Screen's first frame. */
class RenderCursor<T : Any> {
    private var owner: T? = null
    private var position: Pair<Double, Double>? = null
    val active: Boolean get() = position != null

    fun move(screen: T, x: Double, y: Double) {
        owner = screen
        position = x to y
    }

    fun observe(screen: T?) {
        if (owner !== screen) position = null
        owner = screen
    }

    fun x(original: Int): Int = position?.first?.toInt() ?: original
    fun y(original: Int): Int = position?.second?.toInt() ?: original
}

/** Pure scheduling logic: never drives ticks, sleeps, or calls Minecraft. */
class RenderSchedule(val settings: RenderSettings) {
    private var lastFrameNanos: Long? = null

    fun nextFrame(nowNanos: Long, safeToThrottle: Boolean, capturePending: Boolean, screenChanged: Boolean): RenderMode? {
        val mode = settings.mode
        if (mode == RenderMode.CONTINUOUS || !safeToThrottle) {
            lastFrameNanos = nowNanos
            return RenderMode.CONTINUOUS
        }
        val last = lastFrameNanos
        val heartbeatDue = settings.idleFps > 0 && (last == null || nowNanos - last >= 1_000_000_000L / settings.idleFps)
        if (!capturePending && !screenChanged && !heartbeatDue) return null
        lastFrameNanos = nowNanos
        return mode
    }
}
