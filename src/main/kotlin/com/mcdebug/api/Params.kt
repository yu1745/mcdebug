package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.ServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB

// Parameter readers shared by every *Ops handler. A missing or mistyped param is the
// caller's error: always INVALID_PARAMS naming the param, never a Gson/Kotlin exception
// that would surface as INTERNAL_ERROR.

private fun JsonObject.present(name: String): JsonElement? = get(name)?.takeIf { !it.isJsonNull }

private fun invalid(message: String): Nothing = throw RpcException(RpcErrors.INVALID_PARAMS, message)

/** Primitive value of [name] read with [read]; null when absent, INVALID_PARAMS when not that type. */
private inline fun <T> JsonObject.primitive(name: String, type: String, read: (JsonElement) -> T): T? {
    val el = present(name) ?: return null
    if (!el.isJsonPrimitive) invalid("param $name must be a $type, got $el")
    return try {
        read(el)
    } catch (e: RuntimeException) {
        invalid("param $name must be a $type, got $el")
    }
}

internal fun JsonObject.getString(name: String): String? = primitive(name, "string") { it.asString }

internal fun JsonObject.getString(name: String, default: String): String = getString(name) ?: default

internal fun JsonObject.requireString(name: String): String = getString(name) ?: invalid("missing string param: $name")

internal fun JsonObject.getStringOrNull(name: String): String? = getString(name)

internal fun JsonObject.getIntOrNull(name: String): Int? =
    primitive(name, "integer") { if (it.asJsonPrimitive.isNumber) it.asInt else it.asString.toInt() }

internal fun JsonObject.getIntOr(name: String, default: Int): Int = getIntOrNull(name) ?: default

internal fun JsonObject.requireInt(name: String): Int = getIntOrNull(name) ?: invalid("missing integer param: $name")

internal fun JsonObject.requireLong(name: String): Long =
    primitive(name, "number") { if (it.asJsonPrimitive.isNumber) it.asLong else it.asString.toLong() }
        ?: invalid("missing long param: $name")

internal fun JsonObject.getBoolOrFalse(name: String): Boolean =
    primitive(name, "boolean") {
        val p = it.asJsonPrimitive
        if (p.isBoolean) p.asBoolean else when (p.asString) { "true" -> true; "false" -> false; else -> error("not a boolean") }
    } ?: false

internal fun JsonObject.requireObject(name: String): JsonObject {
    val el = present(name) ?: invalid("missing object param: $name")
    return el as? JsonObject ?: invalid("param $name must be an object, got $el")
}

internal fun JsonObject.getObjectOrNull(name: String): JsonObject? {
    val el = present(name) ?: return null
    return el as? JsonObject ?: invalid("param $name must be an object, got $el")
}

internal fun JsonObject.requireArray(name: String): JsonArray {
    val el = present(name) ?: invalid("missing array param: $name")
    return el as? JsonArray ?: invalid("param $name must be an array, got $el")
}

internal fun JsonObject.getArrayOrNull(name: String): JsonArray? {
    val el = present(name) ?: return null
    return el as? JsonArray ?: invalid("param $name must be an array, got $el")
}

/** `[x, y, z]` block position. */
internal fun JsonObject.requirePos(name: String): BlockPos =
    posOrNull(name) ?: invalid("missing param: $name (block position [x, y, z])")

internal fun JsonObject.posOrNull(name: String): BlockPos? {
    val arr = getArrayOrNull(name) ?: return null
    if (arr.size() != 3 || arr.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) {
        throw RpcException(RpcErrors.INVALID_POSITION, "param $name must be [x, y, z] integers, got $arr")
    }
    return BlockPos(arr[0].asInt, arr[1].asInt, arr[2].asInt)
}

/** `{"from": [x,y,z], "to": [x,y,z]}`, corners in any order, both inclusive. */
internal fun JsonObject.requireBox(name: String = "box"): BoundingBox {
    val box = getObjectOrNull(name) ?: invalid("missing param: $name ({\"from\": [x,y,z], \"to\": [x,y,z]})")
    return ServerContext.boxFrom(box.requirePos("from"), box.requirePos("to"))
}

internal fun boxFromParams(p: JsonObject): BoundingBox = p.requireBox("box")

/** The same block box as [requireBox], as the AABB covering those whole blocks (for entity queries). */
internal fun JsonObject.requireEntityBox(name: String = "box"): AABB {
    val box = requireBox(name)
    return AABB(
        box.minX().toDouble(), box.minY().toDouble(), box.minZ().toDouble(),
        box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0,
    )
}

/** `[x, y, z]` doubles. */
internal fun JsonObject.requireVec3(name: String): net.minecraft.world.phys.Vec3 {
    val arr = getArrayOrNull(name) ?: invalid("missing param: $name ([x, y, z])")
    if (arr.size() != 3 || arr.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) {
        invalid("param $name must be [x, y, z] numbers, got $arr")
    }
    return net.minecraft.world.phys.Vec3(arr[0].asDouble, arr[1].asDouble, arr[2].asDouble)
}

internal fun JsonObject.getStringMapOrNull(name: String): Map<String, String>? {
    val obj = getObjectOrNull(name) ?: return null
    val out = mutableMapOf<String, String>()
    obj.entrySet().forEach { (k, v) ->
        if (v.isJsonPrimitive) out[k] = v.asString
    }
    return out
}

private val DIRECTION_NAMES = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
    "north" to Direction.NORTH,
    "south" to Direction.SOUTH,
    "east" to Direction.EAST,
    "west" to Direction.WEST,
)

internal fun JsonObject.requireDirection(name: String): Direction {
    val s = getString(name) ?: invalid("missing string param: $name")
    return DIRECTION_NAMES[s.lowercase()]
        ?: invalid("invalid $name: $s; expected one of ${DIRECTION_NAMES.keys.sorted()}")
}

internal fun JsonObject.getDirectionOrNull(name: String): Direction? {
    val s = getString(name) ?: return null
    return DIRECTION_NAMES[s.lowercase()]
}
