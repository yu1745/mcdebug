package com.mcdebug.client.input

import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo

/** Converts the RPC's existing GLFW codes/masks to Minecraft 26.3 SDL events. */
object InputEvents {
    fun modifiers(mask: Int): Int =
        (if (mask and 1 != 0) 3 else 0) or (if (mask and 2 != 0) 192 else 0) or
            (if (mask and 4 != 0) 768 else 0) or (if (mask and 8 != 0) 3072 else 0)

    fun mouse(x: Double, y: Double, button: Int, mask: Int) =
        MouseButtonEvent(x, y, MouseButtonInfo(when (button) { 0 -> 1; 1 -> 3; 2 -> 2; else -> button + 1 }, modifiers(mask)))

    fun key(code: Int, mask: Int): KeyEvent {
        val scan = when (code) {
            in 65..90 -> code - 65 + 4
            in 49..57 -> code - 49 + 30
            48 -> 39
            32 -> 44; 39 -> 52; 44 -> 54; 45 -> 45; 46 -> 55; 47 -> 56
            59 -> 51; 61 -> 46; 91 -> 47; 92 -> 49; 93 -> 48; 96 -> 53
            161 -> 100; 162 -> 135
            256 -> 41; 257 -> 40; 258 -> 43; 259 -> 42; 260 -> 73; 261 -> 76
            262 -> 79; 263 -> 80; 264 -> 81; 265 -> 82; 266 -> 75; 267 -> 78
            268 -> 74; 269 -> 77
            280 -> 57; 281 -> 71; 282 -> 83; 283 -> 70; 284 -> 72
            in 290..301 -> code - 290 + 58
            in 302..313 -> code - 302 + 104
            314 -> 0 // SDL has no F25 scancode; do not alias it to F24.
            320 -> 98; in 321..329 -> code - 321 + 89
            330 -> 99; 331 -> 84; 332 -> 85; 333 -> 86; 334 -> 87; 335 -> 88; 336 -> 103
            340 -> 225; 341 -> 224; 342 -> 226; 343 -> 227
            344 -> 229; 345 -> 228; 346 -> 230; 347 -> 231; 348 -> 118
            else -> throw IllegalArgumentException("unsupported keyboard code: $code")
        }
        val keycode = when (code) {
            in 65..90 -> code + 32
            in 32..162 -> code
            256 -> 27; 257 -> 13; 258 -> 9; 259 -> 8; 261 -> 127; 314 -> 0
            else -> scan or (1 shl 30)
        }
        return KeyEvent(scan, keycode, modifiers(mask))
    }
}
