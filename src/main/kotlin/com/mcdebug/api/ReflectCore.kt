package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.Mappings
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Plane-agnostic reflection core: object-reference table, path walking, overload
 * selection, argument coercion and JSON (de)serialization. A plane (server, client)
 * supplies its own named roots and runs every call on its own game thread, so a
 * [ReflectCore] instance is only ever touched from one thread.
 *
 * Refs are global to the plane (each CLI command is its own connection, so binding
 * them to a connection would make them useless across commands), but bounded: an
 * LRU of [maxRefs] entries, the same object always gets the same id, and the
 * plane clears the table when its game stops.
 */
class ReflectCore(
    private val roots: (name: String, params: JsonObject) -> Any?,
    private val rootNames: List<String>,
    private val maxRefs: Int = 1024,
) {
    // ------------------------------------------------------------- ref table

    private var nextRef = 1L
    private val byId = object : LinkedHashMap<Long, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>): Boolean {
            if (size <= maxRefs) return false
            byObject.remove(eldest.value)
            evicted++
            return true
        }
    }
    private val byObject = IdentityHashMap<Any, Long>()
    private var evicted = 0L

    fun refOf(obj: Any): Long = byObject[obj]?.also { byId[it] } ?: nextRef++.also { id ->
        byObject[obj] = id
        byId[id] = obj
    }

    fun deref(id: Long): Any = byId[id] ?: throw RpcException(
        RpcErrors.INVALID_PARAMS,
        "unknown ref: $id (released, evicted after $maxRefs live refs, or the game restarted); re-acquire it from a root or path",
    )

    fun release(id: Long): Boolean = byId.remove(id)?.also { byObject.remove(it) } != null

    fun clear() {
        byId.clear(); byObject.clear()
    }

    fun refsJson(): JsonObject = JsonObject().apply {
        addProperty("count", byId.size)
        addProperty("max", maxRefs)
        addProperty("evicted", evicted)
        add("refs", JsonArray().apply {
            for ((id, obj) in byId) add(JsonObject().apply {
                addProperty("ref", id)
                addProperty("class", obj.javaClass.name)
                addProperty("toString", safeToString(obj))
            })
        })
    }

    // ---------------------------------------------------------------- targets

    /** A resolved object (or a class for static access) plus the path steps taken. */
    class Target(val clazz: Class<*>, val instance: Any?, val steps: JsonArray, val isNull: Boolean = false)

    /**
     * Base = `ref` | `root` (+ root params) | `class` (static access), then an optional
     * `path` walked from it. Without a path the base itself is the target.
     */
    fun target(p: JsonObject, extraPath: String? = null): Target {
        var clazz: Class<*>
        var instance: Any?
        when {
            p.has("ref") -> { instance = deref(p.requireLong("ref")); clazz = instance.javaClass }
            p.has("root") -> {
                val name = p.requireString("root")
                instance = roots(name, p) ?: throw RpcException(RpcErrors.INVALID_PARAMS, "root $name is not available")
                clazz = instance.javaClass
            }
            p.has("class") -> { clazz = loadClass(p.requireString("class")); instance = null }
            else -> throw RpcException(RpcErrors.INVALID_PARAMS, "one of ref, root (${rootNames.joinToString("|")}) or class is required")
        }
        val steps = JsonArray()
        val path = listOfNotNull(p.getString("path")?.takeIf { it.isNotBlank() }, extraPath).joinToString(".")
        val segs = parsePath(path)
        for ((i, seg) in segs.withIndex()) {
            val (next, step) = step(clazz, instance, seg)
            steps.add(step)
            if (next == null) {
                if (i != segs.lastIndex) throw RpcException(RpcErrors.INVALID_PARAMS, "path $path: ${seg.text} is null")
                return Target(clazz, null, steps, isNull = true)
            }
            val v = unwrapOptional(next) ?: throw RpcException(RpcErrors.INVALID_PARAMS, "path $path: ${seg.text} is an empty Optional")
            instance = v; clazz = v.javaClass
        }
        return Target(clazz, instance, steps)
    }

    fun loadClass(name: String): Class<*> {
        Mappings.ensureLoaded()
        val runtime = Mappings.runtimeClassName(name)
        return try {
            Class.forName(runtime)
        } catch (e: ClassNotFoundException) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "class not found: $name (runtime: $runtime)")
        } catch (e: NoClassDefFoundError) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "class not loadable: $name (runtime: $runtime): ${e.message}")
        }
    }

    // ------------------------------------------------------------------ paths

    /** `name` (field, else no-arg method, else getX/isX), `name()` (method only), `[3]`, `["key"]`. */
    sealed class Seg(val text: String) {
        class Member(val name: String, val methodOnly: Boolean, text: String) : Seg(text)
        class Index(val index: Int, text: String) : Seg(text)
        class Key(val key: String, text: String) : Seg(text)
    }

    fun parsePath(path: String): List<Seg> {
        val out = ArrayList<Seg>()
        var i = 0
        fun bad(msg: String): Nothing = throw RpcException(RpcErrors.INVALID_PARAMS, "bad path '$path' at $i: $msg")
        while (i < path.length) {
            when (val c = path[i]) {
                '.' -> { if (out.isEmpty() || i + 1 >= path.length) bad("misplaced '.'"); i++ }
                '[' -> {
                    val end = path.indexOf(']', i).takeIf { it > 0 } ?: bad("unclosed '['")
                    val inner = path.substring(i + 1, end).trim()
                    out += if (inner.length >= 2 && inner.first() == '"' && inner.last() == '"') {
                        Seg.Key(inner.substring(1, inner.length - 1), "[$inner]")
                    } else {
                        Seg.Index(inner.toIntOrNull() ?: bad("index must be a number or a \"quoted\" key"), "[$inner]")
                    }
                    i = end + 1
                }
                else -> {
                    if (!(c.isJavaIdentifierStart())) bad("unexpected '$c'")
                    var j = i
                    while (j < path.length && path[j].isJavaIdentifierPart()) j++
                    val name = path.substring(i, j)
                    val call = path.startsWith("()", j)
                    if (call) j += 2
                    out += Seg.Member(name, call, path.substring(i, j))
                    i = j
                }
            }
        }
        return out
    }

    private fun step(clazz: Class<*>, instance: Any?, seg: Seg): Pair<Any?, JsonObject> {
        val step = JsonObject().apply { addProperty("seg", seg.text) }
        val value: Any? = when (seg) {
            is Seg.Index -> {
                val obj = instance ?: throw RpcException(RpcErrors.INVALID_PARAMS, "${seg.text}: needs an object, not a class")
                indexInto(obj, seg.index)
            }
            is Seg.Key -> {
                val map = instance as? Map<*, *> ?: throw RpcException(RpcErrors.INVALID_PARAMS, "${seg.text}: not a Map (${clazz.name})")
                map.entries.firstOrNull { it.key.toString() == seg.key }?.value
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "${seg.text}: no such key (${map.size} entries)")
            }
            is Seg.Member -> {
                val field = if (seg.methodOnly) null else findField(clazz, seg.name)
                if (field != null) {
                    step.addProperty("field", field.name)
                    readField(field, instance, seg.name)
                } else {
                    val names = listOf(seg.name) + if (seg.methodOnly) emptyList() else
                        listOf("get", "is").map { it + seg.name.replaceFirstChar(Char::uppercaseChar) }
                    val m = names.firstNotNullOfOrNull { n -> findNoArgMethod(clazz, n) } ?: throw RpcException(
                        RpcErrors.INVALID_PARAMS,
                        "${seg.text}: no field or no-arg method in ${clazz.name} (reflect resolve --members lists them)",
                    )
                    step.addProperty("method", m.name)
                    invoke(m, instance, emptyArray())
                }
            }
        }
        value?.let { step.addProperty("class", it.javaClass.name) }
        return value to step
    }

    private fun indexInto(obj: Any, i: Int): Any? = when {
        obj is List<*> -> obj.getOrNull(i) ?: throw RpcException(RpcErrors.INVALID_PARAMS, "[$i]: out of range (size ${obj.size})")
        obj.javaClass.isArray -> {
            val len = java.lang.reflect.Array.getLength(obj)
            if (i !in 0 until len) throw RpcException(RpcErrors.INVALID_PARAMS, "[$i]: out of range (length $len)")
            java.lang.reflect.Array.get(obj, i)
        }
        obj is Iterable<*> -> obj.drop(i).firstOrNull() ?: throw RpcException(RpcErrors.INVALID_PARAMS, "[$i]: out of range")
        obj is Map<*, *> -> obj.entries.firstOrNull { it.key.toString() == i.toString() }?.value
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "[$i]: no such key")
        else -> throw RpcException(RpcErrors.INVALID_PARAMS, "[$i]: ${obj.javaClass.name} is not indexable")
    }

    // ---------------------------------------------------------------- members

    fun findField(clazz: Class<*>, userName: String): Field? {
        for (name in Mappings.runtimeMemberNames(clazz, userName, isMethod = false)) {
            var c: Class<*>? = clazz
            while (c != null && c != Any::class.java) {
                c.declaredFields.firstOrNull { it.name == name }?.let { return it }
                c = c.superclass
            }
        }
        return null
    }

    private fun findNoArgMethod(clazz: Class<*>, userName: String): Method? =
        Mappings.runtimeMemberNames(clazz, userName, isMethod = true).firstNotNullOfOrNull { name ->
            allMethods(clazz).firstOrNull { it.name == name && it.parameterCount == 0 }
        }

    /** Declared methods of the class chain and all interfaces, most-derived first. */
    private fun allMethods(clazz: Class<*>): List<Method> {
        val out = ArrayList<Method>()
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>().apply { add(clazz) }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c) || c == Any::class.java) continue
            out += c.declaredMethods.filterNot { it.isBridge || it.isSynthetic }
            c.superclass?.let(queue::add)
            queue.addAll(c.interfaces)
        }
        return out
    }

    fun readField(field: Field, instance: Any?, userName: String): Any? {
        field.isAccessible = true
        if (Modifier.isStatic(field.modifiers)) return field.get(null)
        val inst = instance ?: throw RpcException(RpcErrors.INVALID_PARAMS, "field $userName is not static; start from a ref, root or path")
        return field.get(inst)
    }

    /** Write a field, coercing JSON to its type; returns (before, after). */
    fun writeField(field: Field, instance: Any?, userName: String, json: JsonElement): Pair<Any?, Any?> {
        if (Modifier.isFinal(field.modifiers)) throw RpcException(RpcErrors.INVALID_PARAMS, "field $userName is final")
        val before = readField(field, instance, userName)
        val (hint, raw) = fromJson(json)
        val v = coerce(hint, raw, field.type) ?: if (raw == null && !field.type.isPrimitive) null else
            throw RpcException(RpcErrors.INVALID_PARAMS, "value $json does not fit field $userName of type ${field.type.name}")
        field.set(if (Modifier.isStatic(field.modifiers)) null else instance, v)
        return before to readField(field, instance, userName)
    }

    /**
     * Pick the overload of [userName] whose parameters best fit [args]: exact type 0,
     * boxing/subclass 1, numeric widening 2, narrowing a fitting integer literal 3.
     * A tie between different signatures is an error unless [argTypes] disambiguates.
     */
    fun findMethod(clazz: Class<*>, userName: String, args: List<Pair<Class<*>?, Any?>>, argTypes: List<String>?): Method {
        val names = Mappings.runtimeMemberNames(clazz, userName, isMethod = true).toSet()
        val byName = allMethods(clazz).filter { it.name in names }
        if (byName.isEmpty()) throw RpcException(
            RpcErrors.INVALID_PARAMS, "method not found: $userName (tried ${names.joinToString()}) in ${clazz.name}",
        )
        return pickOverload(byName, args, argTypes, "$userName in ${clazz.name}") as Method
    }

    fun findConstructor(clazz: Class<*>, args: List<Pair<Class<*>?, Any?>>, argTypes: List<String>?): java.lang.reflect.Constructor<*> =
        pickOverload(clazz.declaredConstructors.toList(), args, argTypes, "constructor of ${clazz.name}") as java.lang.reflect.Constructor<*>

    private fun pickOverload(all: List<Executable>, args: List<Pair<Class<*>?, Any?>>, argTypes: List<String>?, what: String): Executable {
        val wanted = argTypes?.map(::typeByName)
        val scored = all.filter { it.parameterCount == args.size }
            .filter { e -> wanted == null || e.parameterTypes.toList() == wanted }
            .mapNotNull { e ->
                val scores = args.zip(e.parameterTypes).map { (a, p) -> fit(a.first, a.second, p) }
                if (scores.any { it < 0 }) null else e to scores.sum()
            }
        if (scored.isEmpty()) throw RpcException(RpcErrors.INVALID_PARAMS,
            "no overload of $what accepts (${args.joinToString { it.first?.simpleName ?: "null" }}); candidates: " +
                all.joinToString("; ") { sig(it) })
        val best = scored.minOf { it.second }
        // the same signature can appear on a class and its supertypes: keep the most derived
        val top = scored.filter { it.second == best }.map { it.first }.distinctBy { it.parameterTypes.toList() }
        if (top.size > 1) throw RpcException(RpcErrors.INVALID_PARAMS,
            "ambiguous $what: ${top.joinToString("; ") { sig(it) }}; pass argTypes, e.g. [\"int\",\"long\"]")
        return top.first()
    }

    private fun sig(e: Executable) = e.name.substringAfterLast('.') + "(" + e.parameterTypes.joinToString(",") { it.name } + ")"

    private fun typeByName(name: String): Class<*> = PRIMITIVES[name] ?: loadClass(name)

    fun invoke(e: Executable, instance: Any?, args: Array<Any?>): Any? {
        e.isAccessible = true
        val coerced = args.mapIndexed { i, a -> coerce(a?.javaClass, a, e.parameterTypes[i]) ?: a }.toTypedArray()
        return try {
            when (e) {
                is Method -> if (Modifier.isStatic(e.modifiers)) e.invoke(null, *coerced) else
                    e.invoke(instance ?: throw RpcException(RpcErrors.INVALID_PARAMS,
                        "method ${e.name} is not static; start from a ref, root or path"), *coerced)
                is java.lang.reflect.Constructor<*> -> e.newInstance(*coerced)
                else -> error("unsupported executable $e")
            }
        } catch (ex: java.lang.reflect.InvocationTargetException) {
            val cause = ex.targetException
            throw RpcException(RpcErrors.INTERNAL_ERROR, "${sig(e)} threw ${cause.javaClass.name}: ${cause.message}")
        }
    }

    // ------------------------------------------------------------ coercion

    /** -1 = does not fit; lower is a better fit. */
    private fun fit(given: Class<*>?, value: Any?, param: Class<*>): Int {
        if (given == null) return if (param.isPrimitive) -1 else 1
        if (param == given) return 0
        val p = BOXED[param] ?: param
        if (p == given) return 1
        if (value is Number && p in NUMERIC_RANK && given in NUMERIC_RANK) {
            val from = NUMERIC_RANK.getValue(given); val to = NUMERIC_RANK.getValue(p)
            return when {
                to > from -> 2
                value is Int || value is Long -> if (fitsIntegral(value.toLong(), p)) 3 else -1
                else -> -1
            }
        }
        if (p.isEnum && value is String) return if (p.enumConstants.any { (it as Enum<*>).name == value }) 2 else -1
        return if (p.isAssignableFrom(given)) 1 else -1
    }

    private fun fitsIntegral(v: Long, type: Class<*>): Boolean = when (type) {
        java.lang.Byte::class.java -> v in Byte.MIN_VALUE..Byte.MAX_VALUE
        java.lang.Short::class.java -> v in Short.MIN_VALUE..Short.MAX_VALUE
        java.lang.Integer::class.java -> v in Int.MIN_VALUE..Int.MAX_VALUE
        else -> true
    }

    /** Convert [value] to [type] when it is a numeric/enum literal that needs it; null = leave as is / no fit. */
    private fun coerce(given: Class<*>?, value: Any?, type: Class<*>): Any? {
        val t = BOXED[type] ?: type
        if (value == null || t.isInstance(value)) return value
        if (value is Number) return when (t) {
            java.lang.Long::class.java -> value.toLong()
            java.lang.Integer::class.java -> value.toLong().takeIf { fitsIntegral(it, t) }?.toInt()
            java.lang.Short::class.java -> value.toLong().takeIf { fitsIntegral(it, t) }?.toInt()?.toShort()
            java.lang.Byte::class.java -> value.toLong().takeIf { fitsIntegral(it, t) }?.toInt()?.toByte()
            java.lang.Double::class.java -> value.toDouble()
            java.lang.Float::class.java -> value.toFloat()
            else -> null
        }
        if (value is String && t.isEnum) return t.enumConstants.firstOrNull { (it as Enum<*>).name == value }
        return null
    }

    // ------------------------------------------------------------ JSON out

    fun toJson(value: Any?): JsonElement {
        if (value == null) return JsonObject().apply { addProperty("\$null", true) }
        val v = unwrapOptional(value) ?: return JsonPrimitive("<empty Optional>")
        simpleJson(v)?.let { return it }
        if (v is net.minecraft.world.item.ItemStack) return JsonObject().apply {
            addProperty("\$ref", refOf(v))
            addProperty("item", v.item.toString())
            addProperty("count", v.count)
            ServerContext.stackNbt(v)?.let { add("nbt", NbtJson.toJson(it)) }
        }
        val elements: List<Any?>? = when {
            v is Collection<*> -> v.take(LIST_LIMIT + 1)
            v is Array<*> -> v.take(LIST_LIMIT + 1)
            v.javaClass.isArray -> (0 until minOf(java.lang.reflect.Array.getLength(v), LIST_LIMIT + 1)).map { java.lang.reflect.Array.get(v, it) }
            else -> null
        }
        val size = when {
            v is Collection<*> -> v.size
            v is Map<*, *> -> v.size
            v.javaClass.isArray -> java.lang.reflect.Array.getLength(v)
            else -> -1
        }
        // A collection of plain values is returned inline; one holding objects becomes a
        // ref with a summary (one ref for the collection, not one per element: reach an
        // element with a path such as `[3]`).
        if (elements != null && elements.all { it == null || simpleJson(it) != null }) {
            return JsonArray().apply {
                elements.take(LIST_LIMIT).forEach { add(it?.let(::simpleJson) ?: JsonNull.INSTANCE) }
                if (size > LIST_LIMIT) add(JsonObject().apply { addProperty("\$truncated", size - LIST_LIMIT) })
            }
        }
        if (v is Map<*, *> && v.size <= LIST_LIMIT && v.values.all { it == null || simpleJson(it) != null }) {
            return JsonObject().apply { v.forEach { (k, x) -> add(k.toString(), x?.let(::simpleJson) ?: JsonNull.INSTANCE) } }
        }
        return JsonObject().apply {
            addProperty("\$ref", refOf(v))
            addProperty("class", v.javaClass.name)
            Mappings.mojangClassName(v.javaClass)?.takeIf { it != v.javaClass.name }?.let { addProperty("mojangClass", it) }
            if (size >= 0) {
                addProperty("size", size)
                add("items", when {
                    v is Map<*, *> -> JsonObject().apply {
                        v.entries.take(SUMMARY_LIMIT).forEach { (k, x) -> add(k.toString(), summary(x)) }
                    }
                    else -> JsonArray().apply { elements!!.take(SUMMARY_LIMIT).forEach { add(summary(it)) } }
                })
            } else {
                addProperty("toString", safeToString(v))
            }
        }
    }

    private fun summary(x: Any?): JsonElement = when {
        x == null -> JsonNull.INSTANCE
        else -> simpleJson(x) ?: JsonObject().apply {
            addProperty("class", x.javaClass.name)
            addProperty("toString", safeToString(x))
        }
    }

    private fun simpleJson(v: Any): JsonElement? = when (v) {
        is Boolean -> JsonPrimitive(v)
        is Int, is Long, is Float, is Double -> JsonPrimitive(v as Number)
        is Short -> JsonPrimitive(v.toInt())
        is Byte -> JsonPrimitive(v.toInt())
        is Number -> JsonPrimitive(v.toString())
        is Char -> JsonPrimitive(v.toString())
        is CharSequence, is java.util.UUID, is Enum<*>, is net.minecraft.resources.ResourceLocation -> JsonPrimitive(v.toString())
        else -> null
    }

    private fun unwrapOptional(value: Any): Any? = if (value is java.util.Optional<*>) value.orElse(null) else value

    fun safeToString(v: Any): String = try {
        val s = v.toString()
        if (s.length > 500) s.take(500) + "..." else s
    } catch (e: Exception) {
        "<toString threw ${e.javaClass.simpleName}>"
    }

    // ------------------------------------------------------------- JSON in

    /** Parse an argument. Returns (class hint, value). */
    fun fromJson(el: JsonElement): Pair<Class<*>?, Any?> {
        if (el.isJsonNull) return null to null
        if (el.isJsonPrimitive) {
            val prim = el.asJsonPrimitive
            return when {
                prim.isString -> String::class.java to prim.asString
                prim.isBoolean -> java.lang.Boolean::class.java to prim.asBoolean
                prim.isNumber -> {
                    // integers keep full long precision (asDouble loses it above 2^53)
                    val text = prim.asString
                    val asLong = text.toLongOrNull()
                    when {
                        asLong == null -> java.lang.Double::class.java to text.toDouble()
                        asLong in Int.MIN_VALUE..Int.MAX_VALUE -> java.lang.Integer::class.java to asLong.toInt()
                        else -> java.lang.Long::class.java to asLong
                    }
                }
                else -> null to prim.asString
            }
        }
        if (el.isJsonObject) {
            val obj = el.asJsonObject
            if (obj.has("\$ref")) return deref(obj.get("\$ref").asLong).let { it.javaClass to it }
            if (obj.has("root") || obj.has("path")) {
                val t = target(obj)
                return t.instance?.let { it.javaClass to it } ?: (null to null)
            }
            if (obj.has("new")) {
                val clazz = loadClass(obj.requireString("new"))
                val args = (obj.getArrayOrNull("args") ?: JsonArray()).map(::fromJson)
                val ctor = findConstructor(clazz, args, obj.getArrayOrNull("argTypes")?.map { it.asString })
                return clazz to invoke(ctor, null, args.map { it.second }.toTypedArray())
            }
            if (obj.has("class")) {
                val clazz = loadClass(obj.requireString("class"))
                val ctor = clazz.getDeclaredConstructor()
                ctor.isAccessible = true
                return clazz to ctor.newInstance()
            }
            if (obj.has("enum")) {
                val clazz = loadClass(obj.requireString("enum"))
                val name = obj.requireString("name")
                val e = clazz.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "no enum constant ${clazz.name}.$name")
                return clazz to e
            }
            if (obj.has("array")) {
                val comp = typeByName(obj.requireString("array"))
                val items = (obj.getArrayOrNull("items") ?: JsonArray()).map(::fromJson)
                val arr = java.lang.reflect.Array.newInstance(comp, items.size)
                items.forEachIndexed { i, (h, v) -> java.lang.reflect.Array.set(arr, i, coerce(h, v, comp) ?: v) }
                return arr.javaClass to arr
            }
            throw RpcException(RpcErrors.INVALID_PARAMS, "object arg must have \$ref, root/path, new, class, enum, or array")
        }
        throw RpcException(RpcErrors.INVALID_PARAMS, "unsupported arg: $el")
    }

    companion object {
        const val LIST_LIMIT = 200
        const val SUMMARY_LIMIT = 20

        private val PRIMITIVES: Map<String, Class<*>> = mapOf(
            "int" to Integer.TYPE, "long" to java.lang.Long.TYPE, "short" to java.lang.Short.TYPE,
            "byte" to java.lang.Byte.TYPE, "double" to java.lang.Double.TYPE, "float" to java.lang.Float.TYPE,
            "boolean" to java.lang.Boolean.TYPE, "char" to Character.TYPE,
        )

        private val BOXED: Map<Class<*>, Class<*>> = mapOf(
            Integer.TYPE to java.lang.Integer::class.java, java.lang.Long.TYPE to java.lang.Long::class.java,
            java.lang.Short.TYPE to java.lang.Short::class.java, java.lang.Byte.TYPE to java.lang.Byte::class.java,
            java.lang.Double.TYPE to java.lang.Double::class.java, java.lang.Float.TYPE to java.lang.Float::class.java,
            java.lang.Boolean.TYPE to java.lang.Boolean::class.java, Character.TYPE to java.lang.Character::class.java,
        )

        private val NUMERIC_RANK: Map<Class<*>, Int> = mapOf(
            java.lang.Byte::class.java to 0, java.lang.Short::class.java to 1, java.lang.Integer::class.java to 2,
            java.lang.Long::class.java to 3, java.lang.Float::class.java to 4, java.lang.Double::class.java to 5,
        )
    }
}
