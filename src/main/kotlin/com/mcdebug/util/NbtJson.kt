package com.mcdebug.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.nbt.NbtByte
import net.minecraft.nbt.NbtByteArray
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtDouble
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtEnd
import net.minecraft.nbt.NbtFloat
import net.minecraft.nbt.NbtInt
import net.minecraft.nbt.NbtIntArray
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtLong
import net.minecraft.nbt.NbtLongArray
import net.minecraft.nbt.NbtShort
import net.minecraft.nbt.NbtString
import net.minecraft.registry.Registries

/**
 * Convert NbtElement <-> Gson JsonElement.
 *
 * Lossy on the JSON->NBT direction (integers default to NbtInt, no way to distinguish byte/short/int).
 * Preserve on NBT->JSON direction.
 */
object NbtJson {

    fun toJson(nbt: NbtElement): JsonElement = when (nbt) {
        is NbtEnd -> JsonNull.INSTANCE
        is NbtCompound -> {
            val obj = JsonObject()
            nbt.keys.forEach { key -> obj.add(key, toJson(nbt[key]!!)) }
            obj
        }
        is NbtList -> {
            val arr = JsonArray()
            for (i in 0 until nbt.size) {
                arr.add(toJson(nbt[i]!!))
            }
            arr
        }
        is NbtByte -> JsonPrimitive(nbt.byteValue().toInt())
        is NbtShort -> JsonPrimitive(nbt.shortValue().toInt())
        is NbtInt -> JsonPrimitive(nbt.intValue())
        is NbtLong -> JsonPrimitive(nbt.longValue())
        is NbtFloat -> JsonPrimitive(nbt.floatValue().toDouble())
        is NbtDouble -> JsonPrimitive(nbt.doubleValue())
        is NbtString -> JsonPrimitive(nbt.asString())
        is NbtByteArray -> {
            val arr = JsonArray()
            nbt.byteArray.forEach { arr.add(JsonPrimitive(it.toInt())) }
            arr
        }
        is NbtIntArray -> {
            val arr = JsonArray()
            nbt.intArray.forEach { arr.add(JsonPrimitive(it)) }
            arr
        }
        is NbtLongArray -> {
            val arr = JsonArray()
            nbt.longArray.forEach { arr.add(JsonPrimitive(it)) }
            arr
        }
        else -> throw RpcException(RpcErrors.NBT_PARSE_ERROR, "unsupported NBT type: ${nbt.nbtType}")
    }

    fun fromJson(json: JsonElement, hint: Byte? = null): NbtElement = when {
        json.isJsonNull -> NbtEnd.INSTANCE
        json.isJsonObject -> {
            val compound = NbtCompound()
            json.asJsonObject.entrySet().forEach { (k, v) -> compound.put(k, fromJson(v)) }
            compound
        }
        json.isJsonArray -> {
            // Promote all elements to NbtInt for simplicity; users wanting precise types should construct NBT directly.
            val list = NbtList()
            json.asJsonArray.forEach { list.add(fromJson(it)) }
            list
        }
        json.isJsonPrimitive -> {
            val p = json.asJsonPrimitive
            when {
                p.isBoolean -> NbtByte.of(if (p.asBoolean) 1.toByte() else 0.toByte())
                p.isNumber -> {
                    // NbtTypes has no public LONG/DOUBLE constants in 1.20.1, so use literal byte ids (4=LONG, 6=DOUBLE).
                    when (hint) {
                        4.toByte() -> NbtLong.of(p.asLong)
                        6.toByte() -> NbtDouble.of(p.asDouble)
                        else -> NbtInt.of(p.asInt)
                    }
                }
                p.isString -> NbtString.of(p.asString)
                else -> throw RpcException(RpcErrors.NBT_PARSE_ERROR, "unsupported JSON primitive")
            }
        }
        else -> throw RpcException(RpcErrors.NBT_PARSE_ERROR, "unsupported JSON element")
    }

    /**
     * Get a nested value by JSON Pointer-like path:
     *   "Items.0.count" or "Items[0].count"
     * Returns null if the path doesn't resolve.
     */
    fun getByPath(nbt: NbtElement, path: String): NbtElement? {
        if (path.isEmpty()) return nbt
        val tokens = tokenizePath(path)
        var current: NbtElement? = nbt
        for (tok in tokens) {
            current = when {
                current is NbtCompound && tok is PathToken.Key -> current[tok.name]
                current is NbtList && tok is PathToken.Index -> {
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
    fun getByPathAsJson(nbt: NbtElement, path: String): JsonElement {
        val v = getByPath(nbt, path) ?: return JsonNull.INSTANCE
        return toJson(v)
    }

    /**
     * Set a nested value by path. Creates intermediate compounds if needed.
     * If a parent isn't a NbtCompound, this throws.
     */
    fun setByPath(root: NbtCompound, path: String, value: NbtElement) {
        val tokens = tokenizePath(path)
        if (tokens.isEmpty()) throw RpcException(RpcErrors.INVALID_PARAMS, "empty path")
        var current: NbtElement = root
        for (i in tokens.indices) {
            val tok = tokens[i]
            val isLast = i == tokens.size - 1
            if (isLast) {
                when {
                    current is NbtCompound && tok is PathToken.Key -> current.put(tok.name, value)
                    current is NbtList && tok is PathToken.Index -> {
                        val idx = tok.index
                        // pad with NbtEnd (will become NbtInt default) if needed
                        while (current.size <= idx) current.add(NbtInt.of(0))
                        current.set(idx, value)
                    }
                    else -> throw RpcException(RpcErrors.INVALID_PARAMS, "cannot set path: parent not a container")
                }
                return
            } else {
                val nextTok = tokens[i + 1]
                val next = when {
                    current is NbtCompound && tok is PathToken.Key -> {
                        val existing = current[tok.name]
                        if (existing == null) {
                            val created = if (nextTok is PathToken.Index) NbtList() else NbtCompound()
                            current.put(tok.name, created)
                            created
                        } else existing
                    }
                    current is NbtList && tok is PathToken.Index -> {
                        val idx = tok.index
                        while (current.size <= idx) current.add(NbtCompound())
                        current[idx]!!
                    }
                    else -> throw RpcException(RpcErrors.INVALID_PARAMS, "cannot traverse path at '$tok'")
                }
                current = next
            }
        }
    }

    /** Convert Minecraft identifier to canonical string ("minecraft:stone") */
    fun idToString(id: net.minecraft.util.Identifier): String = id.toString()

    /** Resolve "minecraft:stone" to a registered identifier, or null. */
    fun identifier(s: String): net.minecraft.util.Identifier? =
        try { net.minecraft.util.Identifier.tryParse(s) } catch (_: Exception) { null }

    fun blockId(s: String): net.minecraft.block.Block? {
        val id = identifier(s) ?: return null
        return Registries.BLOCK.getOrEmpty(id).orElse(null)
    }

    fun itemId(s: String): net.minecraft.item.Item? {
        val id = identifier(s) ?: return null
        return Registries.ITEM.getOrEmpty(id).orElse(null)
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
