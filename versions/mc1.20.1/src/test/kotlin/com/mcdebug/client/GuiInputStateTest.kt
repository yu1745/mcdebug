package com.mcdebug.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** Only pure helpers are referenced: this suite never initializes Minecraft or GL. */
class GuiInputStateTest {
    @Test fun `ASCII and common key names have GLFW values`() {
        assertEquals(65, GuiInputValidation.key("a"))
        assertEquals(90, GuiInputValidation.key("Z"))
        assertEquals(48, GuiInputValidation.key("0"))
        val names = mapOf("ESCAPE" to 256, "ENTER" to 257, "TAB" to 258, "BACKSPACE" to 259,
            "DELETE" to 261, "HOME" to 268, "END" to 269, "LEFT" to 263, "RIGHT" to 262,
            "UP" to 265, "DOWN" to 264, "SPACE" to 32, "PAGE_UP" to 266, "PAGE_DOWN" to 267)
        names.forEach { (name, code) -> assertEquals(code, GuiInputValidation.key(name.lowercase())) }
        for (f in 1..12) assertEquals(289 + f, GuiInputValidation.key("F$f"))
        for (name in listOf("", "é", "CTRL", "F0", "F13", "F01", "ENTER ", "257")) {
            assertThrows(IllegalArgumentException::class.java) { GuiInputValidation.key(name) }
        }
    }

    @Test fun `numeric keys reject unknown and unused GLFW holes`() {
        for (code in listOf(32, 39, 44, 57, 59, 61, 65, 93, 96, 161, 162, 256, 269,
            280, 284, 290, 314, 320, 336, 340, 348)) assertEquals(code, GuiInputValidation.key(code))
        for (code in listOf(Int.MIN_VALUE, -1, 0, 31, 33, 43, 58, 60, 64, 94, 95, 97, 163,
            255, 270, 279, 285, 289, 315, 319, 337, 339, 349, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GuiInputValidation.key(code) }
        }
    }

    @Test fun `modifier mask excludes lock and arbitrary bits`() {
        for (mask in 0..15) assertEquals(mask, GuiInputValidation.modifiers(mask))
        for (mask in listOf(-1, 16, 32, 63, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GuiInputValidation.modifiers(mask) }
        }
    }

    @Test fun `Unicode validation allows non ASCII supplementary and exactly 4096 units`() {
        for (text in listOf("", "中文 café", "a😀𝄞", "x".repeat(4096), "😀".repeat(2048))) {
            assertEquals(text, GuiInputValidation.text(text))
        }
        for (text in listOf("x".repeat(4097), "😀".repeat(2049), "\uD800", "\uDC00", "\uD800x", "x\uDC00",
            "\uDC00\uD800", "x\ny", "\r", "\t", "\u0000", "\u007F", "\u0085", "\u2028", "\u2029")) {
            assertThrows(IllegalArgumentException::class.java) { GuiInputValidation.text(text) }
        }
        assertEquals(listOf('\uD83D', '\uDE00'), GuiInputValidation.text("😀").toList())
    }

    @Test fun `modifier nesting restores zero and exceptions without changing other threads`() {
        assertNull(GuiInputModifiers.controlDown(false))
        assertNull(GuiInputModifiers.shiftDown())
        assertNull(GuiInputModifiers.altDown())
        GuiInputModifiers.withMask(7) {
            assertEquals(true, GuiInputModifiers.controlDown(false))
            assertEquals(false, GuiInputModifiers.controlDown(true))
            assertEquals(true, GuiInputModifiers.shiftDown())
            assertEquals(true, GuiInputModifiers.altDown())
            assertThrows(IllegalStateException::class.java) {
                GuiInputModifiers.withMask(0) {
                    assertEquals(false, GuiInputModifiers.controlDown(false))
                    assertEquals(false, GuiInputModifiers.shiftDown())
                    assertEquals(false, GuiInputModifiers.altDown())
                    throw IllegalStateException("widget failed")
                }
            }
            assertEquals(true, GuiInputModifiers.shiftDown())
            assertNull(CompletableFuture.supplyAsync { GuiInputModifiers.shiftDown() }.join())
            GuiInputModifiers.withMask(8) {
                assertEquals(true, GuiInputModifiers.controlDown(true))
                assertEquals(false, GuiInputModifiers.controlDown(false))
            }
            assertEquals(true, GuiInputModifiers.controlDown(false))
        }
        assertNull(GuiInputModifiers.controlDown(true))
        assertNull(GuiInputModifiers.shiftDown())
        assertThrows(IllegalArgumentException::class.java) { GuiInputModifiers.withMask(16) {} }
        assertNull(GuiInputModifiers.altDown())
    }

    @Test fun `gesture bounds and interpolation include exact endpoints and finite deltas`() {
        GuiInputValidation.timing(1, 0)
        GuiInputValidation.timing(128, 0)
        GuiInputValidation.timing(20, 20)
        for ((steps, ticks) in listOf(0 to 0, 129 to 0, 1 to -1, 1 to 21, 21 to 20, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GuiInputValidation.timing(steps, ticks) }
        }
        val from = 10.0 to 20.0
        val to = 30.0 to -20.0
        assertEquals(from, GuiInputValidation.interpolate(from, to, 0, 8))
        assertEquals(to, GuiInputValidation.interpolate(from, to, 8, 8))
        assertEquals(20.0 to 0.0, GuiInputValidation.interpolate(from, to, 4, 8))
        assertEquals(to, GuiInputValidation.interpolate(from, to, 1, 1))
        assertEquals(0.0 to 0.0, GuiInputValidation.interpolate(-Double.MAX_VALUE to 0.0, Double.MAX_VALUE to 0.0, 1, 2))
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { GuiInputValidation.point(v, 0.0) }
            assertThrows(IllegalArgumentException::class.java) { GuiInputValidation.point(0.0, v) }
        }
        for (button in 0..7) assertEquals(button, GuiInputValidation.button(button))
        for (button in listOf(-1, 8, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GuiInputValidation.button(button) }
        }
    }

    @Test fun `normal finish cancellation timeout and stopping all release only once`() {
        for (reason in listOf("finish", "cancel", "timeout", "screen change", "size change", "stop")) {
            val state = GuiInputGestureState()
            var releases = 0
            state.beginPress()
            state.step()
            state.step()
            state.finish { releases++ }
            repeat(3) { state.finish { releases++ } }
            assertTrue(state.terminal, reason)
            assertTrue(state.releaseClaimed, reason)
            assertEquals(2, state.stepsCompleted, reason)
            assertEquals(1, releases, reason)
            assertThrows(IllegalStateException::class.java) { state.step() }
            assertThrows(IllegalStateException::class.java) { state.beginPress() }
        }
    }

    @Test fun `cancelled initial hop never presses or releases`() {
        val state = GuiInputGestureState()
        var releases = 0
        state.finish { releases++ }
        state.finish { releases++ }
        assertFalse(state.pressed)
        assertFalse(state.releaseClaimed)
        assertEquals(0, releases)
        assertThrows(IllegalStateException::class.java) { state.beginPress() }
    }

    @Test fun `throwing press drag or release still claims release once including reentrancy`() {
        for (phase in listOf("press", "drag", "release")) {
            val state = GuiInputGestureState()
            var releases = 0
            assertThrows(IllegalStateException::class.java) {
                try {
                    state.beginPress()
                    if (phase == "press") throw IllegalStateException("press failure")
                    state.step()
                    if (phase == "drag") throw IllegalStateException("drag failure")
                } finally {
                    state.finish {
                        releases++
                        state.finish { releases++ }
                        if (phase == "release") throw IllegalStateException("release failure")
                    }
                }
            }
            state.finish { releases++ }
            assertEquals(1, releases, phase)
        }
    }
}
