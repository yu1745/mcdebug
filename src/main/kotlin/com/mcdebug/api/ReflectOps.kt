package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.Mappings
import com.mcdebug.util.ServerContext
import net.minecraft.server.MinecraftServer
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Arbitrary runtime reflection over the live server, for deep debugging of mod
 * state that the high-level RPCs do not expose.
 *
 * Name resolution: a production game runs on intermediary names (class_1309,
 * method_6076, field_6222), a Loom dev game on Mojang names. The bundled table
 * (generated from this Minecraft version's mappings, see util/Mappings) accepts
 * either form in both ("LivingEntity", "updatingUsingItem", "useItemRemaining").
 * Mod classes keep their own names (ic2_120.content.item.MiningLaserItem).
 *
 * Every call starts from a base and may walk a `path` from it:
 *   base: { "root": "server" | "level" (+dim) | "be" (+pos, dim) | "entity" (+uuid) | "player" (+name?) }
 *       | { "ref": 7 }            object returned by an earlier call
 *       | { "class": "..." }      static members only
 *   path: dot-separated segments, e.g. "level.blockEntities[0].energy": `name` (field,
 *         else no-arg method, else getName/isName), `name()` (no-arg method only),
 *         `[3]` (list/array index), `["key"]` (map key). No arguments, no expressions,
 *         no eval: a method that needs arguments is reflect.call.
 *
 * Methods:
 *   reflect.resolve  { base, path?, "members": true? } -> class, Mojang name, superclass, fields, methods
 *   reflect.get      { base, path?, "field"? }         -> value at path (+field)
 *   reflect.set      { base, path?, "field", "value" }  -> before/after
 *   reflect.call     { base, path?, "method", "args"?, "argTypes"? } -> return value
 *   reflect.new      { "class", "args"?, "argTypes"? } -> new object
 *   reflect.refs / reflect.release { "ref" | "all": true } / reflect.mappings
 *
 * Plain values (numbers, strings, enums, ids, lists/maps of them) are returned
 * inline; objects come back as { "ref": N, "class", "toString" }, and object
 * collections as one ref with `size` and a summary of `items`. Minecraft is not
 * frozen: treat every value as a snapshot.
 */
object ReflectOps : RpcHandlerGroup {

    private val ROOTS = listOf("server", "level", "be", "entity", "player")

    private var server: MinecraftServer? = null

    private val core = ReflectCore(::root, ROOTS).apply {
        // Readable item stacks (id, count, custom NBT) instead of a bare toString.
        itemSummary = { v ->
            (v as? net.minecraft.world.item.ItemStack)?.let {
                JsonObject().apply {
                    addProperty("ref", refOf(it))
                    addProperty("item", it.item.toString())
                    addProperty("count", it.count)
                    ServerContext.stackNbt(it)?.let { nbt -> add("nbt", com.mcdebug.util.NbtJson.toJson(nbt)) }
                }
            }
        }
    }

    /** Drop every ref (server stopping): they would pin the old world in memory. */
    fun clear() {
        core.clear()
        server = null
    }

    private fun root(name: String, p: JsonObject): Any? {
        val s = server ?: return null
        return when (name) {
            "server" -> s
            "level" -> ServerContext.world(s, p.getStringOrNull("dim"))
            "be" -> ServerContext.blockEntity(ServerContext.world(s, p.getStringOrNull("dim")), p.requirePos("pos"))
            "entity" -> {
                val uuid = try {
                    UUID.fromString(p.requireString("uuid"))
                } catch (e: IllegalArgumentException) {
                    throw RpcException(RpcErrors.INVALID_PARAMS, "uuid is not a UUID")
                }
                s.allLevels.firstNotNullOfOrNull { it.getEntity(uuid) }
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "no loaded entity with uuid $uuid")
            }
            "player" -> p.getStringOrNull("name")?.let {
                s.playerList.getPlayerByName(it) ?: throw RpcException(RpcErrors.INVALID_PARAMS, "no player named $it")
            } ?: s.playerList.players.firstOrNull() ?: throw RpcException(RpcErrors.INVALID_PARAMS, "no player online")
            else -> throw RpcException(RpcErrors.INVALID_PARAMS, "unknown root: $name (one of ${ROOTS.joinToString()})")
        }
    }

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "resolve" to handler(::resolve),
        "get" to handler(::get),
        "set" to handler(::set),
        "call" to handler(::call),
        "new" to handler(::construct),
        "refs" to handler { core.refsJson() },
        "release" to handler(::release),
        "mappings" to handler {
            Mappings.ensureLoaded()
            JsonObject().apply { addProperty("available", Mappings.isAvailable()) }
        },
    )

    private fun handler(fn: (JsonObject) -> JsonElement): RpcHandler = { s, params ->
        RpcContext.onServer(s) {
            server = s
            fn(params ?: JsonObject())
        }
    }

    // ---------------------------------------------------------------- resolve

    private fun resolve(p: JsonObject): JsonElement {
        val t = core.target(p)
        if (t.isNull) throw RpcException(RpcErrors.INVALID_PARAMS, "value at path is null; nothing to resolve")
        val clazz = t.clazz
        return JsonObject().apply {
            p.getStringOrNull("class")?.let { addProperty("requested", it) }
            addProperty("runtimeClass", clazz.name)
            Mappings.mojangClassName(clazz)?.takeIf { it != clazz.name }?.let { addProperty("mojangClass", it) }
            addProperty("isInterface", clazz.isInterface)
            addProperty("isEnum", clazz.isEnum)
            clazz.superclass?.let { addProperty("superclass", it.name) }
            if (t.steps.size() > 0) add("steps", t.steps)
            if (p.getBoolOrFalse("members")) {
                add("fields", fieldsJson(clazz))
                add("methods", methodsJson(clazz))
            }
        }
    }

    private fun fieldsJson(clazz: Class<*>): JsonArray {
        val arr = JsonArray()
        val seen = HashSet<String>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!seen.add(f.name)) continue
                arr.add(JsonObject().apply {
                    addProperty("name", f.name)
                    // production: map intermediary back to Mojang for readability
                    Mappings.mojangMemberName(c, f.name, isMethod = false)?.let { addProperty("mojangName", it) }
                    addProperty("type", f.type.name)
                    addProperty("static", Modifier.isStatic(f.modifiers))
                    addProperty("final", Modifier.isFinal(f.modifiers))
                    addProperty("declaringClass", f.declaringClass.name)
                })
            }
            c = c.superclass
        }
        return arr
    }

    private fun methodsJson(clazz: Class<*>): JsonArray {
        val arr = JsonArray()
        val seen = HashSet<String>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.isBridge || m.isSynthetic) continue
                // overloads are separate entries; an override already listed is skipped
                if (!seen.add(m.name + m.parameterTypes.joinToString(",", "(", ")") { it.name })) continue
                arr.add(JsonObject().apply {
                    addProperty("name", m.name)
                    Mappings.mojangMemberName(c, m.name, isMethod = true)?.let { addProperty("mojangName", it) }
                    addProperty("returnType", m.returnType.name)
                    addProperty("static", Modifier.isStatic(m.modifiers))
                    addProperty("declaringClass", m.declaringClass.name)
                    add("params", JsonArray().apply { m.parameterTypes.forEach { add(it.name) } })
                })
            }
            c = c.superclass
        }
        return arr
    }

    // ------------------------------------------------------------- get / set

    private fun get(p: JsonObject): JsonElement {
        val field = p.getStringOrNull("field")
        if (field == null && p.getStringOrNull("path").isNullOrBlank()) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "path or field required")
        }
        val t = core.target(p, extraPath = field)
        return JsonObject().apply {
            field?.let { addProperty("field", it) }
            val last = t.steps.last().asJsonObject
            (last.get("field") ?: last.get("method"))?.let { add("resolvedTo", it) }
            add("steps", t.steps)
            add("value", core.toJson(t.instance.takeUnless { t.isNull }))
        }
    }

    private fun set(p: JsonObject): JsonElement {
        val name = p.requireString("field")
        val value = p.get("value") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "value required")
        val t = core.target(p)
        if (t.isNull) throw RpcException(RpcErrors.INVALID_PARAMS, "object at path is null")
        val field = core.findField(t.clazz, name)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "field not found: $name in ${t.clazz.name}" + core.fieldNameHint(name))
        val (before, after) = core.writeField(field, t.instance, name, value)
        return JsonObject().apply {
            addProperty("field", name)
            addProperty("resolvedTo", field.name)
            addProperty("declaringClass", field.declaringClass.name)
            add("before", core.toJson(before))
            add("after", core.toJson(after))
        }
    }

    // ------------------------------------------------------------ call / new

    private fun argTypes(p: JsonObject): List<String>? = p.getArrayOrNull("argTypes")?.map { it.asString }

    private fun call(p: JsonObject): JsonElement {
        val name = p.requireString("method")
        val args = (p.getArrayOrNull("args") ?: JsonArray()).map(core::fromJson)
        val t = core.target(p)
        if (t.isNull) throw RpcException(RpcErrors.INVALID_PARAMS, "object at path is null")
        val m = core.findMethod(t.clazz, name, args, argTypes(p))
        val value = core.invoke(m, t.instance, args.map { it.second }.toTypedArray())
        return JsonObject().apply {
            addProperty("method", name)
            addProperty("resolvedTo", m.name)
            addProperty("declaringClass", m.declaringClass.name)
            addProperty("signature", m.parameterTypes.joinToString(",", "(", ")") { it.name })
            add("value", if (m.returnType == Void.TYPE) com.google.gson.JsonNull.INSTANCE else core.toJson(value))
        }
    }

    private fun construct(p: JsonObject): JsonElement {
        val className = p.requireString("class")
        val args = (p.getArrayOrNull("args") ?: JsonArray()).map(core::fromJson)
        val clazz = core.loadClass(className)
        val ctor = core.findConstructor(clazz, args, argTypes(p))
        val obj = core.invoke(ctor, null, args.map { it.second }.toTypedArray())
        return JsonObject().apply {
            addProperty("class", className)
            addProperty("runtimeClass", clazz.name)
            addProperty("constructor", ctor.toString())
            add("value", core.toJson(obj))
        }
    }

    private fun release(p: JsonObject): JsonElement = JsonObject().apply {
        if (p.getBoolOrFalse("all")) {
            val n = core.refsJson().get("count").asInt
            core.clear()
            addProperty("removed", n)
        } else {
            addProperty("removed", core.release(p.requireLong("ref")))
        }
    }
}
