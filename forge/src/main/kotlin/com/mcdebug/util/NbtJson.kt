package com.mcdebug.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.ByteArrayTag
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.EndTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.ShortTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation

/**
 * Convert Tag <-> Gson JsonElement.
 *
 * JSON->NBT: numbers default to IntTag for backward compatibility; use the
 * `{"#nbt":"<type>","value":...}` sentinel (see [fromJson]) for precise scalar
 * and array types. NBT->JSON preserves the type (LongTag → JSON number, etc.,
 * so round-tripping a plain number is lossy — only the `#nbt` form is lossless).
 */
object NbtJson {

    /** Reserved key marking a typed-NBT sentinel object. See [fromJson]. */
    const val TYPE_HINT_KEY = "#nbt"

    fun toJson(nbt: Tag): JsonElement = when (nbt) {
        is EndTag -> JsonNull.INSTANCE
        is CompoundTag -> {
            val obj = JsonObject()
            nbt.allKeys.forEach { key -> obj.add(key, toJson(nbt[key]!!)) }
            obj
        }
        is ListTag -> {
            val arr = JsonArray()
            for (i in 0 until nbt.size) {
                arr.add(toJson(nbt[i]!!))
            }
            arr
        }
        is ByteTag -> JsonPrimitive(nbt.asByte.toInt())
        is ShortTag -> JsonPrimitive(nbt.asShort.toInt())
        is IntTag -> JsonPrimitive(nbt.asInt)
        is LongTag -> JsonPrimitive(nbt.asLong)
        is FloatTag -> JsonPrimitive(nbt.asFloat.toDouble())
        is DoubleTag -> JsonPrimitive(nbt.asDouble)
        is StringTag -> JsonPrimitive(nbt.asString)
        is ByteArrayTag -> {
            val arr = JsonArray()
            nbt.asByteArray.forEach { arr.add(JsonPrimitive(it.toInt())) }
            arr
        }
        is IntArrayTag -> {
            val arr = JsonArray()
            nbt.asIntArray.forEach { arr.add(JsonPrimitive(it)) }
            arr
        }
        is LongArrayTag -> {
            val arr = JsonArray()
            nbt.asLongArray.forEach { arr.add(JsonPrimitive(it)) }
            arr
        }
        else -> throw RpcException(RpcErrors.NBT_PARSE_ERROR, "unsupported NBT type: ${nbt.type}")
    }

    /**
     * Convert a Gson JsonElement to its NBT equivalent.
     *
     * Default type promotion for JSON numbers is IntTag (backward-compatible with v1).
     * To force a precise NBT scalar type — needed for byte flags, long positions,
     * float vs double, or the various array types — wrap the value in an object with
     * a reserved `#nbt` key:
     *
     *   {"#nbt":"long",  "value": 123}        → LongTag
     *   {"#nbt":"byte",  "value": 1}          → ByteTag   (also covers boolean flags)
     *   {"#nbt":"short", "value": 32000}      → ShortTag
     *   {"#nbt":"float", "value": 1.5}        → FloatTag
     *   {"#nbt":"double","value": 1.5}        → DoubleTag
     *   {"#nbt":"string","value": 123}        → StringTag "123" (force string)
     *   {"#nbt":"byteArray", "value": [1,2,3]} → ByteArrayTag
     *   {"#nbt":"intArray",  "value": [1,2,3]} → IntArrayTag
     *   {"#nbt":"longArray", "value": [1,2,3]} → LongArrayTag
     *
     * The `#nbt` key is reserved: regular NBT compound keys beginning with `#` are
     * vanishingly rare (vanilla and mod NBT keys are plain identifiers), so this
     * sentinel cannot collide in practice. Objects without `#nbt` are parsed as
     * plain compounds, exactly as before.
     *
     * @throws RpcException on an unknown `#nbt` type, missing `value`, or a value
     *         whose JSON shape doesn't match the requested type.
     */
    fun fromJson(json: JsonElement): Tag = when {
        json.isJsonNull -> EndTag.INSTANCE
        json.isJsonObject -> {
            val obj = json.asJsonObject
            val typeHint = obj.get(TYPE_HINT_KEY)?.takeIf { !it.isJsonNull }?.asString
            if (typeHint != null) parseTyped(obj, typeHint)
            else {
                val compound = CompoundTag()
                obj.entrySet().forEach { (k, v) -> compound.put(k, fromJson(v)) }
                compound
            }
        }
        json.isJsonArray -> {
            // Promote all elements to IntTag for simplicity; users wanting precise types
            // should use {"#nbt":"intArray","value":[...]} etc.
            val list = ListTag()
            json.asJsonArray.forEach { list.add(fromJson(it)) }
            list
        }
        json.isJsonPrimitive -> parsePrimitive(json.asJsonPrimitive)
        else -> throw RpcException(RpcErrors.NBT_PARSE_ERROR, "unsupported JSON element")
    }

    private fun parsePrimitive(p: JsonPrimitive): Tag = when {
        p.isBoolean -> ByteTag.valueOf(if (p.asBoolean) 1.toByte() else 0.toByte())
        p.isNumber -> IntTag.valueOf(p.asInt)
        p.isString -> StringTag.valueOf(p.asString)
        else -> throw RpcException(RpcErrors.NBT_PARSE_ERROR, "unsupported JSON primitive")
    }

    private fun parseTyped(obj: JsonObject, type: String): Tag {
        val valueEl = obj.get("value") ?: obj.get("v")
            ?: throw RpcException(RpcErrors.NBT_PARSE_ERROR, "#nbt='$type' requires a 'value' field")
        fun requireNumber() =
            if (valueEl.isJsonPrimitive && valueEl.asJsonPrimitive.isNumber) valueEl.asJsonPrimitive
            else throw RpcException(RpcErrors.NBT_PARSE_ERROR, "#nbt='$type' requires a numeric value, got: $valueEl")
        fun requireString() =
            if (valueEl.isJsonPrimitive && (valueEl.asJsonPrimitive.isString || valueEl.asJsonPrimitive.isNumber))
                valueEl.asJsonPrimitive.asString
            else throw RpcException(RpcErrors.NBT_PARSE_ERROR, "#nbt='$type' requires a string value, got: $valueEl")
        return when (type) {
            "byte" -> ByteTag.valueOf(requireNumber().asInt.toByte())
            "short" -> ShortTag.valueOf(requireNumber().asInt.toShort())
            "int" -> IntTag.valueOf(requireNumber().asInt)
            "long" -> LongTag.valueOf(requireNumber().asLong)
            "float" -> FloatTag.valueOf(requireNumber().asFloat)
            "double" -> DoubleTag.valueOf(requireNumber().asDouble)
            "string" -> StringTag.valueOf(requireString())
            "byteArray" -> ByteArrayTag(requireNumberArray(valueEl).map { it.toByte() }.toByteArray())
            "intArray" -> IntArrayTag(requireNumberArray(valueEl).map { it.toInt() }.toIntArray())
            "longArray" -> LongArrayTag(requireNumberArray(valueEl).map { it.toLong() }.toLongArray())
            else -> throw RpcException(RpcErrors.NBT_PARSE_ERROR, "unknown #nbt type: '$type'")
        }
    }

    private fun requireNumberArray(el: JsonElement): List<Number> {
        if (!el.isJsonArray) throw RpcException(RpcErrors.NBT_PARSE_ERROR, "array type requires a JSON array value, got: $el")
        return el.asJsonArray.map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber)
                throw RpcException(RpcErrors.NBT_PARSE_ERROR, "array element must be a number, got: $it")
            it.asJsonPrimitive.asNumber
        }
    }

    /**
     * Get a nested value by JSON Pointer-like path:
     *   "Items.0.count" or "Items[0].count"
     * Returns null if the path doesn't resolve.
     */
    fun getByPath(nbt: Tag, path: String): Tag? {
        if (path.isEmpty()) return nbt
        val tokens = tokenizePath(path)
        var current: Tag? = nbt
        for (tok in tokens) {
            current = when {
                current is CompoundTag && tok is PathToken.Key -> current[tok.name]
                current is ListTag && tok is PathToken.Index -> {
                    val idx = tok.index
                    if (idx < 0 || idx >= current.size) null
                    else current[idx]
                }
                else -> return null
            } ?: return null
        }
        return current
    }

    /** Same as getByPath but JSON-encodes the result. Returns JsonNull if path doesn't resolve. */
    fun getByPathAsJson(nbt: Tag, path: String): JsonElement {
        val v = getByPath(nbt, path) ?: return JsonNull.INSTANCE
        return toJson(v)
    }

    /**
     * Set a nested value by path. Creates intermediate compounds if needed.
     * If a parent isn't a CompoundTag, this throws.
     */
    fun setByPath(root: CompoundTag, path: String, value: Tag) {
        val tokens = tokenizePath(path)
        if (tokens.isEmpty()) throw RpcException(RpcErrors.INVALID_PARAMS, "empty path")
        var current: Tag = root
        for (i in tokens.indices) {
            val tok = tokens[i]
            val isLast = i == tokens.size - 1
            if (isLast) {
                when {
                    current is CompoundTag && tok is PathToken.Key -> current.put(tok.name, value)
                    current is ListTag && tok is PathToken.Index -> {
                        val idx = tok.index
                        // pad with EndTag (will become IntTag default) if needed
                        while (current.size <= idx) current.add(IntTag.valueOf(0))
                        current.set(idx, value)
                    }
                    else -> throw RpcException(RpcErrors.INVALID_PARAMS, "cannot set path: parent not a container")
                }
                return
            } else {
                val nextTok = tokens[i + 1]
                val next = when {
                    current is CompoundTag && tok is PathToken.Key -> {
                        val existing = current[tok.name]
                        if (existing == null) {
                            val created = if (nextTok is PathToken.Index) ListTag() else CompoundTag()
                            current.put(tok.name, created)
                            created
                        } else existing
                    }
                    current is ListTag && tok is PathToken.Index -> {
                        val idx = tok.index
                        while (current.size <= idx) current.add(CompoundTag())
                        current[idx]!!
                    }
                    else -> throw RpcException(RpcErrors.INVALID_PARAMS, "cannot traverse path at '$tok'")
                }
                current = next
            }
        }
    }

    /** Convert Minecraft identifier to canonical string ("minecraft:stone") */
    fun idToString(id: ResourceLocation): String = id.toString()

    /** Resolve "minecraft:stone" to a registered identifier, or null. */
    fun identifier(s: String): ResourceLocation? =
        try { ResourceLocation.tryParse(s) } catch (_: Exception) { null }

    fun blockId(s: String): net.minecraft.world.level.block.Block? {
        val id = identifier(s) ?: return null
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null)
    }

    fun itemId(s: String): net.minecraft.world.item.Item? {
        val id = identifier(s) ?: return null
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null)
    }

    // ---- path tokenization ----

    private sealed class PathToken {
        data class Key(val name: String) : PathToken()
        data class Index(val index: Int) : PathToken()
    }

    private fun tokenizePath(path: String): List<PathToken> {
        val tokens = mutableListOf<PathToken>()
        val sb = StringBuilder()
        var i = 0
        while (i < path.length) {
            val c = path[i]
            when {
                c == '.' -> {
                    if (sb.isNotEmpty()) { tokens.add(PathToken.Key(sb.toString())); sb.clear() }
                }
                c == '[' -> {
                    if (sb.isNotEmpty()) { tokens.add(PathToken.Key(sb.toString())); sb.clear() }
                    val end = path.indexOf(']', i)
                    if (end < 0) throw RpcException(RpcErrors.INVALID_PARAMS, "unclosed '[' in path")
                    val idx = path.substring(i + 1, end).trim().toIntOrNull()
                        ?: throw RpcException(RpcErrors.INVALID_PARAMS, "bad index in path: ${path.substring(i + 1, end)}")
                    tokens.add(PathToken.Index(idx))
                    i = end
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) tokens.add(PathToken.Key(sb.toString()))
        return tokens
    }
}
