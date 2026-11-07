package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min

/**
 * Records the GuiGraphics calls of ONE rendered frame: textured quads (with texture,
 * texel UV and sprite name), fills, strings, items and tooltips, each with its
 * on-screen rectangle in Screen (GUI) units after the pose transform — the same
 * units `gui click --gui` and `gui screen` use.
 *
 * Machine GUIs draw progress arrows, energy bars and tanks straight from
 * `renderBg`; they are not widgets, so `gui.screen` cannot see them. The draw
 * log can: an agent checks "the energy bar is 2px off" or "this label overflows"
 * as data, and maps any PNG pixel back to the call that drew it.
 *
 * Only armed for frames that a screenshot request with `drawLog` captures; the
 * mixin hooks are a single field check otherwise. Client/render thread only.
 */
object DrawRecorder {
    const val MAX_ENTRIES = 5000

    @JvmField var active = false
    private val entries = ArrayList<JsonObject>()
    private var truncated = false
    // Context set by outer GuiGraphics calls and attached to the innerBlit they reach.
    private var sprite: String? = null
    private var texel: IntArray? = null
    private var depth = 0

    fun begin() {
        entries.clear()
        truncated = false
        sprite = null
        texel = null
        depth = 0
        active = true
    }

    fun end(): JsonObject {
        active = false
        val log = JsonObject().apply {
            addProperty("count", entries.size)
            addProperty("truncated", truncated)
            addProperty("coordinateSpace", "screen")
            add("entries", JsonArray().apply { entries.forEach { add(it) } })
        }
        entries.clear()
        return log
    }

    private fun add(op: String, body: JsonObject.() -> Unit) {
        if (entries.size >= MAX_ENTRIES) {
            truncated = true
            return
        }
        entries.add(JsonObject().apply {
            addProperty("i", entries.size)
            addProperty("op", op)
            body()
        })
    }

    @JvmStatic
    fun enterSprite(name: String) {
        if (depth++ == 0) sprite = name
    }

    @JvmStatic
    fun enterTexel(u: Float, v: Float, width: Int, height: Int, textureWidth: Int, textureHeight: Int) {
        if (depth++ == 0) texel = intArrayOf(u.toInt(), v.toInt(), width, height, textureWidth, textureHeight)
    }

    @JvmStatic
    fun exit() {
        if (depth > 0 && --depth == 0) {
            sprite = null
            texel = null
        }
    }

    @JvmStatic
    fun blit(pose: Matrix4f, texture: ResourceLocation, x1: Int, x2: Int, y1: Int, y2: Int, z: Int,
             u0: Float, u1: Float, v0: Float, v1: Float, color: FloatArray?) = add("blit") {
        addProperty("texture", texture.toString())
        add("rect", rect(pose, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), z.toFloat()))
        add("uv", floats(u0, v0, u1, v1))
        texel?.let {
            add("texel", ints(it[0], it[1], it[2], it[3]))
            add("textureSize", ints(it[4], it[5]))
        }
        sprite?.let { addProperty("sprite", it) }
        color?.let { add("color", floats(*it)) }
    }

    @JvmStatic
    fun fill(pose: Matrix4f, x1: Int, y1: Int, x2: Int, y2: Int, z: Int, argb: Int) = add("fill") {
        add("rect", rect(pose, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), z.toFloat()))
        addProperty("color", argbHex(argb))
    }

    @JvmStatic
    fun text(pose: Matrix4f, text: String, x: Int, y: Int, argb: Int, shadow: Boolean, width: Int) = add("text") {
        addProperty("text", text)
        add("rect", rect(pose, x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + 9).toFloat(), 0f))
        addProperty("color", argbHex(argb))
        addProperty("shadow", shadow)
    }

    @JvmStatic
    fun text(pose: Matrix4f, text: FormattedCharSequence, x: Int, y: Int, argb: Int, shadow: Boolean, width: Int) =
        text(pose, plain(text), x, y, argb, shadow, width)

    @JvmStatic
    fun item(pose: Matrix4f, stack: ItemStack, x: Int, y: Int) {
        if (stack.isEmpty) return
        add("item") {
            addProperty("item", BuiltInRegistries.ITEM.getKey(stack.item).toString())
            addProperty("count", stack.count)
            add("rect", rect(pose, x.toFloat(), y.toFloat(), (x + 16).toFloat(), (y + 16).toFloat(), 0f))
        }
    }

    @JvmStatic
    fun tooltip(lines: List<String>, x: Int, y: Int) = add("tooltip") {
        add("lines", stringArray(lines))
        add("mouse", ints(x, y))
    }

    fun plain(text: FormattedCharSequence): String {
        val out = StringBuilder()
        text.accept { _, _, codePoint ->
            out.appendCodePoint(codePoint)
            true
        }
        return out.toString()
    }

    /** Axis-aligned bounds `[x, y, w, h]` of the transformed quad (pure; unit-tested). */
    fun rect(pose: Matrix4f, x1: Float, y1: Float, x2: Float, y2: Float, z: Float): JsonArray {
        val a = pose.transformPosition(x1, y1, z, Vector3f())
        val b = pose.transformPosition(x2, y2, z, Vector3f())
        val left = min(a.x, b.x)
        val top = min(a.y, b.y)
        return floats(round(left), round(top), round(max(a.x, b.x) - left), round(max(a.y, b.y) - top))
    }

    private fun round(v: Float): Float = Math.round(v * 1000f) / 1000f
    private fun argbHex(argb: Int) = "#%08X".format(argb)
    private fun ints(vararg values: Int) = JsonArray().apply { values.forEach { add(it) } }
    private fun floats(vararg values: Float) = JsonArray().apply { values.forEach { add(it) } }
}
