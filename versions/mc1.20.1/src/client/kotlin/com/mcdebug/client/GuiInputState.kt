package com.mcdebug.client

/** Pure, GL/Minecraft-free input validation and gesture bookkeeping. GLFW 3 key values. */
object GuiInputValidation {
    const val SHIFT = 1
    const val CONTROL = 2
    const val ALT = 4
    const val SUPER = 8

    fun modifiers(mask: Int): Int {
        require(mask >= 0 && mask and 15 == mask) { "modifiers only supports SHIFT/CONTROL/ALT/SUPER (0..15)" }
        return mask
    }

    fun key(name: String): Int {
        val normalized = name.uppercase(java.util.Locale.ROOT)
        if (normalized.length == 1 && (normalized[0] in 'A'..'Z' || normalized[0] in '0'..'9')) {
            return normalized[0].code
        }
        val named = when (normalized) {
            "SPACE" -> 32
            "ESCAPE" -> 256
            "ENTER" -> 257
            "TAB" -> 258
            "BACKSPACE" -> 259
            "DELETE" -> 261
            "RIGHT" -> 262
            "LEFT" -> 263
            "DOWN" -> 264
            "UP" -> 265
            "PAGE_UP" -> 266
            "PAGE_DOWN" -> 267
            "HOME" -> 268
            "END" -> 269
            else -> null
        }
        if (named != null) return named
        if (normalized.startsWith("F")) {
            val f = normalized.drop(1).toIntOrNull()
            if (f != null && f in 1..12 && normalized == "F$f") return 289 + f
        }
        throw IllegalArgumentException("unknown key '$name': use ASCII letter/digit, a supported GLFW name, or integer code")
    }

    fun key(code: Int): Int {
        // Reject GLFW's unused holes, UNKNOWN (-1) and values beyond KEY_LAST (348).
        require(code == 32 || code == 39 || code in 44..57 || code == 59 || code == 61 ||
            code in 65..93 || code == 96 || code in 161..162 || code in 256..269 ||
            code in 280..284 || code in 290..314 || code in 320..336 || code in 340..348) {
            "key is not a legal GLFW keyboard code"
        }
        return code
    }

    fun text(text: String): String {
        require(text.length <= 4096) { "text exceeds 4096 UTF-16 code units" }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            require(!Character.isISOControl(c) && c != '\u2028' && c != '\u2029') {
                "text cannot contain control characters or line separators; use gui.key"
            }
            when {
                Character.isHighSurrogate(c) -> {
                    require(i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) { "text contains an unpaired surrogate" }
                    i += 2
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("text contains an unpaired surrogate")
                else -> i++
            }
        }
        return text
    }

    fun button(button: Int): Int {
        require(button in 0..7) { "button must be 0..7" }
        return button
    }

    fun timing(steps: Int, stepTicks: Int) {
        require(steps in 1..128) { "steps must be 1..128" }
        require(stepTicks in 0..20) { "stepTicks must be 0..20" }
        require(steps * stepTicks <= 400) { "steps * stepTicks must be <= 400" }
    }

    fun point(x: Double, y: Double): Pair<Double, Double> {
        require(x.isFinite() && y.isFinite()) { "coordinates must be finite" }
        return x to y
    }

    fun interpolate(from: Pair<Double, Double>, to: Pair<Double, Double>, step: Int, steps: Int): Pair<Double, Double> {
        require(steps in 1..128 && step in 0..steps)
        point(from.first, from.second)
        point(to.first, to.second)
        if (step == 0) return from
        if (step == steps) return to
        val t = step.toDouble() / steps
        // Weighted sum avoids overflowing (to - from) for finite extreme endpoints.
        return point(from.first * (1 - t) + to.first * t, from.second * (1 - t) + to.second * t)
    }
}

/** No physical key queries; null outside the synchronous synthetic event call stack. */
object GuiInputModifiers {
    private val current = ThreadLocal<Int>()

    fun <T> withMask(mask: Int, body: () -> T): T {
        GuiInputValidation.modifiers(mask)
        val previous = current.get()
        current.set(mask)
        try {
            return body()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    @JvmStatic fun controlDown(osx: Boolean): Boolean? = current.get()?.let {
        it and (if (osx) GuiInputValidation.SUPER else GuiInputValidation.CONTROL) != 0
    }
    @JvmStatic fun shiftDown(): Boolean? = current.get()?.let { it and GuiInputValidation.SHIFT != 0 }
    @JvmStatic fun altDown(): Boolean? = current.get()?.let { it and GuiInputValidation.ALT != 0 }
}

/** Client-thread confined. Claim release BEFORE calling arbitrary Screen code, including throwing code. */
class GuiInputGestureState {
    var pressed = false
        private set
    var terminal = false
        private set
    var releaseClaimed = false
        private set
    var stepsCompleted = 0
        private set

    fun beginPress() {
        check(!terminal && !pressed)
        pressed = true
    }

    fun step() {
        check(pressed && !terminal && !releaseClaimed)
        stepsCompleted++
    }

    fun finish(release: () -> Unit) {
        terminal = true
        if (pressed && !releaseClaimed) {
            releaseClaimed = true
            release()
        }
    }
}
