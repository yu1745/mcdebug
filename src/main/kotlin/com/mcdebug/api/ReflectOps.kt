package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.Mappings
import com.mcdebug.util.NbtJson
import net.minecraft.server.MinecraftServer
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * Arbitrary runtime reflection over the live server, for deep debugging of mod
 * state that the high-level RPCs do not expose. Read-only by default: fields
 * are read, methods are invoked (their side effects are the caller's business),
 * and an object-reference table lets you walk into nested objects across calls.
 *
 * Name resolution: because the runtime uses intermediary names (class_1309,
 * method_6076, field_6222), the bundled yarn mapping is used to accept yarn
 * names too ("LivingEntity", "tickActiveItemStack", "itemUseTimeLeft").
 * Mod classes keep their own names (ic2_120.content.item.MiningLaserItem).
 *
 * Methods:
 *   reflect.resolve  { "class": "LivingEntity" | "net.minecraft.class_1309" | "ic2_120...", "members": true? }
 *                    -> runtime class name, yarn name, superclass, fields, methods
 *   reflect.get      { "class": "...", "field": "..." } | { "ref": 1, "field": "..." }
 *                    -> field value (objects become refs, see refs)
 *   reflect.call     { "class": "...", "method": "...", "args": [...] }
 *                  | { "ref": 1, "method": "...", "args": [...] }
 *                    -> return value
 *   reflect.refs     -> list live object references
 *   reflect.release  { "ref": 1 } -> drop a reference
 *   reflect.mappings -> mapping table load status
 *
 * Objects: when a field/method returns a non-primitive object, the server
 * stores it in a reference table (per server) and returns { "\$ref": 1, ... }.
 * Use the ref as the target in later calls. References live until released or
 * server stops. Refs are only meaningful while the object graph is stable —
 * Minecraft is not frozen, so treat returned state as a snapshot.
 */
object ReflectOps : RpcHandlerGroup {

    /** Per-server object reference table. */
    private val refTable = HashMap<MinecraftServer, MutableMap<Long, Any>>()
    private val refCounter = AtomicLong(1)

    private val PRIMITIVE_WRAPPER_TYPES = setOf(
        String::class.java, Int::class.java, Long::class.java, Double::class.java,
        Float::class.java, Boolean::class.java, Short::class.java, Byte::class.java,
        Character::class.java, java.math.BigDecimal::class.java, java.math.BigInteger::class.java,
        java.util.UUID::class.java,
    )

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "resolve" to ::resolve,
        "get" to ::get,
        "call" to ::call,
        "new" to ::construct,
        "refs" to ::listRefs,
        "release" to ::release,
        "mappings" to ::mappingsStatus,
    )

    // ---------------------------------------------------------------- resolve

    private fun resolve(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val className = p.requireString("class")
            Mappings.ensureLoaded()
            val runtime = Mappings.runtimeClassName(className)
            val clazz = try {
                Class.forName(runtime)
            } catch (e: ClassNotFoundException) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "class not found: $className (runtime: $runtime)")
            } catch (e: NoClassDefFoundError) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "class not loadable: $className (runtime: $runtime): ${e.message}")
            }
            val out = JsonObject().apply {
                addProperty("requested", className)
                addProperty("runtimeClass", clazz.name)
                Mappings.yarnClassName(clazz.name.replace('.', '/'))?.let { addProperty("yarnClass", it) }
                Mappings.mojangClassName(clazz.name.replace('.', '/'))?.let { addProperty("mojangClass", it) }
                addProperty("isInterface", clazz.isInterface)
                addProperty("isEnum", clazz.isEnum)
                clazz.superclass?.let { addProperty("superclass", it.name) }
            }
            if (p.getBoolOrFalse("members")) {
                out.add("fields", fieldsJson(clazz, includeInherited = true))
                out.add("methods", methodsJson(clazz, includeInherited = true))
            }
            out
        }

    private fun fieldsJson(clazz: Class<*>, includeInherited: Boolean): JsonArray {
        val arr = JsonArray()
        val seen = HashSet<String>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            val clsSlash = c.name.replace('.', '/')
            for (f in c.declaredFields) {
                if (!seen.add(f.name)) continue
                val obj = JsonObject().apply {
                    addProperty("name", f.name)
                    // map intermediary back to yarn for readability
                    val yarn = Mappings.yarnMemberName(clsSlash, f.name, isMethod = false)
                    if (yarn != null && yarn != f.name) addProperty("yarnName", yarn)
                    addProperty("type", f.type.name)
                    addProperty("static", Modifier.isStatic(f.modifiers))
                    addProperty("final", Modifier.isFinal(f.modifiers))
                    addProperty("declaringClass", f.declaringClass.name)
                }
                arr.add(obj)
            }
            c = if (includeInherited) c.superclass else null
        }
        return arr
    }

    private fun methodsJson(clazz: Class<*>, includeInherited: Boolean): JsonArray {
        val arr = JsonArray()
        val seen = HashSet<String>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            val clsSlash = c.name.replace('.', '/')
            for (m in c.declaredMethods) {
                if (!seen.add(m.name)) continue
                val obj = JsonObject().apply {
                    addProperty("name", m.name)
                    val yarn = Mappings.yarnMemberName(clsSlash, m.name, isMethod = true)
                    if (yarn != null && yarn != m.name) addProperty("yarnName", yarn)
                    addProperty("returnType", m.returnType.name)
                    addProperty("static", Modifier.isStatic(m.modifiers))
                    addProperty("declaringClass", m.declaringClass.name)
                    add("params", JsonArray().apply {
                        m.parameterTypes.forEach { add(it.name) }
                    })
                }
                arr.add(obj)
            }
            c = if (includeInherited) c.superclass else null
        }
        return arr
    }

    // ------------------------------------------------------------------- get

    private fun get(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val fieldName = p.requireString("field")
            val target = resolveTarget(server, p)
            val clazz = target.first
            val instance = target.second

            val runtimeField = Mappings.resolveMemberName(clazz.name.replace('.', '/'), fieldName, isMethod = false)
            val resolvedField = findField(clazz, runtimeField)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "field not found: $fieldName (runtime: $runtimeField) in ${clazz.name}")
            resolvedField.isAccessible = true
            val value = if (Modifier.isStatic(resolvedField.modifiers)) resolvedField.get(null) else {
                val inst = instance ?: throw RpcException(RpcErrors.INVALID_PARAMS, "field $fieldName is not static; pass a ref")
                resolvedField.get(inst)
            }
            JsonObject().apply {
                addProperty("field", fieldName)
                addProperty("resolvedTo", resolvedField.name)
                addProperty("declaringClass", resolvedField.declaringClass.name)
                add("value", toJson(server, value))
            }
        }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    // ------------------------------------------------------------------ call

    private fun call(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val methodName = p.requireString("method")
            val argsJson = p.get("args")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            val args = ArrayList<Pair<Class<*>?, Any?>>()
            for (i in 0 until argsJson.size()) args.add(fromJson(server, argsJson.get(i)))
            val target = resolveTarget(server, p)
            val clazz = target.first
            val instance = target.second

            val runtimeMethod = Mappings.resolveMemberName(clazz.name.replace('.', '/'), methodName, isMethod = true)
            val method = findMethod(clazz, runtimeMethod, args.map { it.first })
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "method not found: $methodName (runtime: $runtimeMethod) in ${clazz.name}")
            method.isAccessible = true
            val value = if (Modifier.isStatic(method.modifiers)) {
                method.invoke(null, *args.map { it.second }.toTypedArray())
            } else {
                val inst = instance ?: throw RpcException(RpcErrors.INVALID_PARAMS, "method $methodName is not static; pass a ref")
                method.invoke(inst, *args.map { it.second }.toTypedArray())
            }
            JsonObject().apply {
                addProperty("method", methodName)
                addProperty("resolvedTo", method.name)
                addProperty("declaringClass", method.declaringClass.name)
                add("value", toJson(server, value))
            }
        }

    /** Whether a runtime value class can satisfy a declared parameter type (handles primitive boxing). */
    private fun matchesParam(given: Class<*>?, param: Class<*>): Boolean {
        if (given == null) return true
        if (param.isAssignableFrom(given)) return true
        // primitive boxing: int <-> Integer, etc.
        val boxed = when (param) {
            java.lang.Integer.TYPE -> Integer::class.java
            java.lang.Long.TYPE -> Long::class.java
            java.lang.Double.TYPE -> Double::class.java
            java.lang.Float.TYPE -> Float::class.java
            java.lang.Boolean.TYPE -> Boolean::class.java
            java.lang.Short.TYPE -> Short::class.java
            java.lang.Byte.TYPE -> Byte::class.java
            java.lang.Character.TYPE -> Character::class.java
            else -> null
        }
        if (boxed != null && boxed.isAssignableFrom(given)) return true
        // and reverse: param is boxed but given is primitive class
        val unboxed = when (given) {
            Integer::class.java -> java.lang.Integer.TYPE
            Long::class.java -> java.lang.Long.TYPE
            Double::class.java -> java.lang.Double.TYPE
            Float::class.java -> java.lang.Float.TYPE
            Boolean::class.java -> java.lang.Boolean.TYPE
            Short::class.java -> java.lang.Short.TYPE
            Byte::class.java -> java.lang.Byte.TYPE
            Character::class.java -> java.lang.Character.TYPE
            else -> null
        }
        return unboxed != null && param.isAssignableFrom(unboxed)
    }

    private fun findMethod(clazz: Class<*>, name: String, argTypes: List<Class<*>?>): Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == name && (argTypes.isEmpty() || argTypes.size == m.parameterCount)) {
                    // best-effort: if arg types given, require assignability
                    val ok = argTypes.zip(m.parameterTypes).all { (given, param) ->
                        matchesParam(given, param)
                    }
                    if (ok) return m
                }
            }
            c = c.superclass
        }
        return null
    }

    /** Find a constructor whose parameter types accept the given argument classes. */
    private fun findConstructor(clazz: Class<*>, argTypes: List<Class<*>?>): java.lang.reflect.Constructor<*>? {
        for (ctor in clazz.declaredConstructors) {
            if (argTypes.size != ctor.parameterCount) continue
            val ok = argTypes.zip(ctor.parameterTypes).all { (given, param) ->
                matchesParam(given, param)
            }
            if (ok) return ctor
        }
        return null
    }

    // ------------------------------------------------------------------ new

    /**
     * Construct a new instance: { "class": "...", "args": [...] }.
     * Args follow the same JSON forms as reflect.call (primitives, \$ref, enum,
     * nested new). Returns the constructed object (usually as a ref).
     */
    private fun construct(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val className = p.requireString("class")
            val argsJson = p.get("args")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            val argVals = ArrayList<Pair<Class<*>?, Any?>>()
            for (i in 0 until argsJson.size()) argVals.add(fromJson(server, argsJson.get(i)))
            Mappings.ensureLoaded()
            val runtime = Mappings.runtimeClassName(className)
            val clazz = try {
                Class.forName(runtime)
            } catch (e: ClassNotFoundException) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "class not found: $className (runtime: $runtime)")
            }
            val ctor = findConstructor(clazz, argVals.map { it.first })
                ?: throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "constructor not found: $className(" + argVals.joinToString(", ") { it.first?.simpleName ?: "?" } + ")"
                )
            ctor.isAccessible = true
            val obj = ctor.newInstance(*argVals.map { it.second }.toTypedArray())
            JsonObject().apply {
                addProperty("class", className)
                addProperty("runtimeClass", clazz.name)
                addProperty("constructor", ctor.toString())
                add("value", toJson(server, obj))
            }
        }

    // ----------------------------------------------------------------- refs

    private fun resolveTarget(server: MinecraftServer, p: JsonObject): Pair<Class<*>, Any?> {
        if (p.has("ref")) {
            val id = p.get("ref").asLong
            val obj: Any = refTable[server]?.get(id)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "unknown ref: $id (release or re-acquire)")
            return obj.javaClass to obj
        }
        if (p.has("class")) {
            val className = p.requireString("class")
            Mappings.ensureLoaded()
            val runtime = Mappings.runtimeClassName(className)
            val clazz = try {
                Class.forName(runtime)
            } catch (e: ClassNotFoundException) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "class not found: $className (runtime: $runtime)")
            }
            return clazz to null
        }
        throw RpcException(RpcErrors.INVALID_PARAMS, "one of class or ref is required")
    }

    private fun listRefs(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val arr = JsonArray()
            val table: MutableMap<Long, Any>? = refTable[server]
            if (table != null) {
                for ((id, obj) in table) {
                    arr.add(JsonObject().apply {
                        addProperty("ref", id)
                        addProperty("class", obj.javaClass.name)
                        addProperty("toString", safeToString(obj))
                    })
                }
            }
            JsonObject().apply { add("refs", arr) }
        }

    private fun release(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val id = p.get("ref").asLong
            val removed = refTable[server]?.remove(id) != null
            JsonObject().apply { addProperty("removed", removed) }
        }

    private fun mappingsStatus(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            Mappings.ensureLoaded()
            JsonObject().apply { addProperty("available", Mappings.isAvailable()) }
        }

    // ------------------------------------------------------------ serialization

    private fun toJson(server: MinecraftServer, value: Any?): JsonElement {
        if (value == null) return JsonObject().apply { addProperty("\$null", true) }
        val v = unwrapOptional(value)
        return when {
            v is CharSequence || v is java.util.UUID || v is Enum<*> || v is net.minecraft.util.Identifier ->
                JsonPrimitive(v.toString())
            v is Boolean -> JsonPrimitive(v)
            v is Int -> JsonPrimitive(v)
            v is Long -> JsonPrimitive(v)
            v is Short -> JsonPrimitive(v.toInt())
            v is Byte -> JsonPrimitive(v.toInt())
            v is Float -> JsonPrimitive(v)
            v is Double -> JsonPrimitive(v)
            v is Number -> JsonPrimitive(v.toString())
            v is Map<*, *> -> JsonObject().apply {
                v.entries.take(200).forEach { (k, value) ->
                    add(k.toString(), toJson(server, value))
                }
                if (v.size > 200) addProperty("\$truncated", v.size - 200)
            }
            v is Collection<*> -> JsonArray().apply {
                v.take(200).forEach { add(toJson(server, it)) }
                if (v.size > 200) add(JsonObject().apply { addProperty("\$truncated", v.size - 200) })
            }
            v is Array<*> -> JsonArray().apply {
                v.take(200).forEach { add(toJson(server, it)) }
                if (v.size > 200) add(JsonObject().apply { addProperty("\$truncated", v.size - 200) })
            }
            v.javaClass.isArray && v.javaClass.componentType.isPrimitive -> {
                // primitive arrays (int[], double[], ...)
                val arr = JsonArray()
                val len = java.lang.reflect.Array.getLength(v)
                for (i in 0 until minOf(len, 200)) arr.add(toJson(server, java.lang.reflect.Array.get(v, i)))
                if (len > 200) arr.add(JsonObject().apply { addProperty("\$truncated", len - 200) })
                arr
            }
            v is net.minecraft.item.ItemStack -> {
                // friendly display for the most common debugging target;
                // ALSO register a ref so callers can pass it back as an arg
                val id = refCounter.getAndIncrement()
                refTable.getOrPut(server) { HashMap() }[id] = v
                JsonObject().apply {
                    addProperty("\$ref", id)
                    addProperty("item", v.item.toString())
                    addProperty("count", v.count)
                    if (v.nbt != null) add("nbt", NbtJson.toJson(v.nbt as net.minecraft.nbt.NbtElement))
                }
            }
            PRIMITIVE_WRAPPER_TYPES.contains(v.javaClass) -> JsonPrimitive(v.toString())
            else -> {
                // store as a reference for further traversal
                val id = refCounter.getAndIncrement()
                refTable.getOrPut(server) { HashMap() }[id] = v
                JsonObject().apply {
                    addProperty("\$ref", id)
                    addProperty("class", v.javaClass.name)
                    addProperty("toString", safeToString(v))
                }
            }
        }
    }

    private fun unwrapOptional(value: Any): Any {
        return when (value) {
            is java.util.Optional<*> -> value.orElse(null) ?: return "<empty Optional>"
            else -> value
        }
    }

    private fun safeToString(v: Any): String = try {
        val s = v.toString()
        if (s.length > 500) s.take(500) + "..." else s
    } catch (e: Exception) {
        "<toString threw ${e.javaClass.simpleName}>"
    }

    // ------------------------------------------------------------ arguments

    /** Parse an argument from JSON. Returns (declaredClassHint, value). */
    private fun fromJson(server: MinecraftServer, el: JsonElement): Pair<Class<*>?, Any?> {
        if (el.isJsonNull) return null to null
        if (el.isJsonPrimitive) {
            val prim = el.asJsonPrimitive
            return when {
                prim.isString -> String::class.java to prim.asString
                prim.isBoolean -> Boolean::class.java to prim.asBoolean
                prim.isNumber -> {
                    val n = prim.asDouble
                    when {
                        n == Math.floor(n) && !n.isInfinite() && n >= Int.MIN_VALUE && n <= Int.MAX_VALUE ->
                            Int::class.java to n.toInt()
                        else -> Double::class.java to n
                    }
                }
                else -> null to prim.asString
            }
        }
        if (el.isJsonObject) {
            val obj = el.asJsonObject
            if (obj.has("\$ref")) {
                val id = obj.get("\$ref").asLong
                val v = refTable[server]?.get(id)
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "unknown ref: $id")
                return v.javaClass to v
            }
            if (obj.has("new")) {
                // construct via a constructor with args: { "new": "class", "args": [...] }
                val className = obj.requireString("new")
                val argsJson = obj.get("args")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
                val argVals = ArrayList<Pair<Class<*>?, Any?>>()
                for (i in 0 until argsJson.size()) argVals.add(fromJson(server, argsJson.get(i)))
                Mappings.ensureLoaded()
                val runtime = Mappings.runtimeClassName(className)
                val clazz = try {
                    Class.forName(runtime)
                } catch (e: ClassNotFoundException) {
                    throw RpcException(RpcErrors.INVALID_PARAMS, "class not found: $className (runtime: $runtime)")
                }
                val ctor = findConstructor(clazz, argVals.map { it.first })
                    ?: throw RpcException(
                        RpcErrors.INVALID_PARAMS,
                        "constructor not found: $className(" + argVals.joinToString(", ") { it.first?.simpleName ?: "?" } + ")"
                    )
                ctor.isAccessible = true
                return clazz to ctor.newInstance(*argVals.map { it.second }.toTypedArray())
            }
            if (obj.has("class")) {
                // construct a new instance via its no-arg constructor
                val className = obj.requireString("class")
                Mappings.ensureLoaded()
                val runtime = Mappings.runtimeClassName(className)
                val clazz = try {
                    Class.forName(runtime)
                } catch (e: ClassNotFoundException) {
                    throw RpcException(RpcErrors.INVALID_PARAMS, "class not found: $className")
                }
                val ctor = clazz.getDeclaredConstructor()
                ctor.isAccessible = true
                return clazz to ctor.newInstance()
            }
            if (obj.has("enum")) {
                val enumClass = Mappings.runtimeClassName(obj.requireString("enum"))
                val clazz = Class.forName(enumClass)
                val name = obj.requireString("name")
                @Suppress("UNCHECKED_CAST")
                val e = java.lang.Enum.valueOf(clazz as Class<out Enum<*>>, name)
                return clazz to e
            }
            if (obj.has("array")) {
                // array arg: { "array": "componentType", "items": [...] }
                val compName = obj.requireString("array")
                val itemsJson = obj.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
                val itemVals = ArrayList<Pair<Class<*>?, Any?>>()
                for (i in 0 until itemsJson.size()) itemVals.add(fromJson(server, itemsJson.get(i)))
                Mappings.ensureLoaded()
                val runtime = Mappings.runtimeClassName(compName)
                val compClass = Class.forName(runtime)
                val arr = java.lang.reflect.Array.newInstance(compClass, itemVals.size)
                itemVals.forEachIndexed { i, (_, v) -> java.lang.reflect.Array.set(arr, i, v) }
                return compClass to arr
            }
            throw RpcException(RpcErrors.INVALID_PARAMS, "object arg must have \$ref, new, class, enum, or array")
        }
        throw RpcException(RpcErrors.INVALID_PARAMS, "unsupported arg: $el")
    }
}
