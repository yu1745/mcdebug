package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel access in ARGB, so the overlay code needs neither GL nor NativeImage. */
interface PixelCanvas {
    val width: Int
    val height: Int
    fun get(x: Int, y: Int): Int
    fun set(x: Int, y: Int, argb: Int)
}

/**
 * Draws numbered boxes over slots and widgets of a screenshot, so a vision model
 * can say "click slot 36" / "click widget 3" and the CLI can do exactly that
 * (`gui click --slot 36` / `--widget 3`). The clean PNG is never modified; the
 * overlay goes to a separate `*.annotated.png`.
 */
object Annotator {
    /** A target in Screen (GUI) units. `kind` is `slot` or `widget`. */
    data class Box(val kind: String, val index: Int, val x: Double, val y: Double, val w: Double, val h: Double, val text: String?)

    private const val SLOT_COLOR = 0xFF00E5FF.toInt()
    private const val WIDGET_COLOR = 0xFFFF40FF.toInt()
    private const val LABEL_BACKGROUND = 0xE0000000.toInt()

    // 3x5 digit glyphs, one row per 3 bits (MSB = left column).
    private val DIGITS = arrayOf(
        intArrayOf(7, 5, 5, 5, 7), intArrayOf(2, 6, 2, 2, 7), intArrayOf(7, 1, 7, 4, 7), intArrayOf(7, 1, 7, 1, 7),
        intArrayOf(5, 5, 7, 1, 1), intArrayOf(7, 4, 7, 1, 7), intArrayOf(7, 4, 7, 5, 7), intArrayOf(7, 1, 1, 1, 1),
        intArrayOf(7, 5, 7, 5, 7), intArrayOf(7, 5, 7, 1, 7),
    )

    /** Draws [boxes] and returns the legend (index → PNG rectangle) for the boxes that are visible. */
    fun draw(canvas: PixelCanvas, boxes: List<Box>, scaleX: Double, scaleY: Double, cropX: Double, cropY: Double): JsonArray {
        val legend = JsonArray()
        val glyph = max(1, min(4, scaleX.roundToInt()))
        for (box in boxes) {
            val left = floor(box.x * scaleX - cropX).toInt()
            val top = floor(box.y * scaleY - cropY).toInt()
            val right = ceil((box.x + box.w) * scaleX - cropX).toInt() - 1
            val bottom = ceil((box.y + box.h) * scaleY - cropY).toInt() - 1
            if (right < 0 || bottom < 0 || left >= canvas.width || top >= canvas.height || right < left || bottom < top) continue
            val color = if (box.kind == "slot") SLOT_COLOR else WIDGET_COLOR
            outline(canvas, left, top, right, bottom, color)
            label(canvas, box.index.toString(), left, top, glyph, color)
            legend.add(JsonObject().apply {
                addProperty("kind", box.kind)
                addProperty("index", box.index)
                box.text?.let { addProperty("text", it) }
                add("png", JsonArray().apply { add(left); add(top); add(right - left + 1); add(bottom - top + 1) })
                add("gui", JsonArray().apply { add(box.x); add(box.y); add(box.w); add(box.h) })
            })
        }
        return legend
    }

    private fun outline(canvas: PixelCanvas, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (x in left..right) {
            put(canvas, x, top, color)
            put(canvas, x, bottom, color)
        }
        for (y in top..bottom) {
            put(canvas, left, y, color)
            put(canvas, right, y, color)
        }
    }

    private fun label(canvas: PixelCanvas, text: String, left: Int, top: Int, scale: Int, color: Int) {
        val width = (text.length * 4 + 1) * scale
        val height = 7 * scale
        for (y in top until top + height) for (x in left until left + width) put(canvas, x, y, LABEL_BACKGROUND, blend = true)
        text.forEachIndexed { i, c ->
            val rows = DIGITS[c - '0']
            for (row in 0 until 5) for (col in 0 until 3) {
                if (rows[row] shr (2 - col) and 1 == 0) continue
                val px = left + (1 + i * 4 + col) * scale
                val py = top + (1 + row) * scale
                for (dy in 0 until scale) for (dx in 0 until scale) put(canvas, px + dx, py + dy, color)
            }
        }
    }

    private fun put(canvas: PixelCanvas, x: Int, y: Int, argb: Int, blend: Boolean = false) {
        if (x < 0 || y < 0 || x >= canvas.width || y >= canvas.height) return
        if (!blend) {
            canvas.set(x, y, argb)
            return
        }
        val a = (argb ushr 24) / 255.0
        val under = canvas.get(x, y)
        fun channel(shift: Int) = ((argb shr shift and 255) * a + (under shr shift and 255) * (1 - a)).roundToInt()
        canvas.set(x, y, (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0))
    }
}
