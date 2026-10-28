package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.ServerContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW

/*
 * Lenient JSON parameter helpers shared by the `gui.*` handlers (the strict
 * input parser for key/type/drag stays in GuiInputParams).
 */

internal fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

internal fun JsonObject.num(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

internal fun JsonObject.int(name: String, default: Int): Int =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: default

internal fun JsonObject.bool(name: String, default: Boolean): Boolean =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: default

internal fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

/** An int parameter that must lie in [range]; absent means [default]. */
internal fun JsonObject.boundedInt(name: String, default: Int, range: IntRange): Int {
    val value = int(name, default)
    if (value !in range) throw RpcException(RpcErrors.INVALID_PARAMS, "$name must be in ${range.first}..${range.last}")
    return value
}

/** `[x, y]` in GUI units, or PNG pixels for `png`. Falls back to `x` + `y`. */
internal fun JsonObject.pair(name: String): Pair<Double, Double> {
    val element = get(name)
    if (element != null && element.isJsonArray) {
        val array = element.asJsonArray
        if (array.size() >= 2) return Pair(array[0].asDouble, array[1].asDouble)
    }
    val x = num("x")
    val y = num("y")
    if (x != null && y != null) return Pair(x, y)
    throw RpcException(RpcErrors.INVALID_PARAMS, "$name must be [x, y], or accompanied by x and y")
}

/** `pos: [x,y,z]` (the server-plane shape) or separate `x`/`y`/`z` fields. */
internal fun JsonObject.pos(): BlockPos {
    val element = get("pos")
    if (element != null && element.isJsonArray) {
        val array = element.asJsonArray
        if (array.size() >= 3) return BlockPos(array[0].asInt, array[1].asInt, array[2].asInt)
    }
    val x = get("x")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    val y = get("y")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    val z = get("z")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    if (x != null && y != null && z != null) return BlockPos(x, y, z)
    throw RpcException(RpcErrors.INVALID_PARAMS, "pos [x,y,z] (or x/y/z) is required")
}

internal fun itemJson(stack: ItemStack): JsonObject = ServerContext.itemStackToJson(stack)

internal fun screenType(screen: Screen?): String = when {
    screen == null -> "none"
    screen is AbstractContainerScreen<*> -> "container"
    else -> screen.javaClass.simpleName
}

internal fun noScreen(): RpcException = RpcException(
    RpcErrors.SCREEN_NOT_FOUND,
    "no screen is open; use gui.openBlock or gui.connect first",
    JsonObject().apply { addProperty("reason", "SCREEN_NOT_FOUND") },
)

internal fun reason(value: String): JsonObject = JsonObject().apply { addProperty("reason", value) }

internal fun stringArray(values: Iterable<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

/**
 * Keeps hover/tooltips/carried stacks at a synthetic Screen click position.
 * Lite render modes use the virtual Screen-space cursor (no focus, no OS pointer
 * movement); continuous mode moves the real cursor.
 *
 * The real-cursor path deliberately inverts MouseHandler's ceil-sized guiScaled
 * mapping, NOT GameRenderer's unrounded width/guiScale projection. PNG coordinates
 * are converted to Screen semantics by Capture first; applying the render scale
 * here would make the click correct but leave hover at a different position.
 */
internal object GuiCursor {
    fun warp(client: Minecraft, x: Double, y: Double): Boolean {
        if (client.screen == null) return false
        if (RenderControl.moveCursor(x, y)) return true
        val window = client.window
        val width = window.getGuiScaledWidth()
        val height = window.getGuiScaledHeight()
        if (width <= 0 || height <= 0 || window.getWindow() == 0L) return false
        val cursor = CaptureGeometry.screenToWindow(x, y, window.getScreenWidth(), window.getScreenHeight(), width, height)
        if (!cursor.first.isFinite() || !cursor.second.isFinite()) return false
        return try {
            GLFW.glfwSetCursorPos(window.getWindow(), cursor.first, cursor.second)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
