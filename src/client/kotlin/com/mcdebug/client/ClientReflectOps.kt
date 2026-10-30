package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.mcdebug.api.ReflectCore
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.Mappings
import net.minecraft.client.Minecraft
import java.util.concurrent.CompletableFuture

/**
 * `gui.reflect.*`: the same reflection core as the server plane's `reflect.*`, in the
 * client JVM, for state that only exists there (the open Screen, widgets, client
 * caches, the integrated server object).
 *
 * Roots are client-specific: `minecraft`, `screen`, `player`, `level`, `server`.
 * Everything else — `path`, `ref`\ s, `set`, overload picking, name translation — is
 * shared, and every call runs on the client thread.
 */
object ClientReflectOps : ClientRpcGroup {

    private val ROOTS = listOf("minecraft", "screen", "player", "level", "server")

    private fun root(name: String, p: JsonObject, client: Minecraft): Any? = when (name) {
        "minecraft" -> client
        "screen" -> client.screen ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "no Screen is open")
        "player" -> client.player ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "not in a world (no player)")
        "level" -> client.level ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "not in a world (no level)")
        "server" -> client.singleplayerServer
            ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "no integrated server (not single-player)")
        else -> throw RpcException(RpcErrors.INVALID_PARAMS, "unknown root: $name (one of ${ROOTS.joinToString()})")
    }

    override fun methods(): Map<String, ClientRpcHandler> = linkedMapOf(
        "reflect.resolve" to { client, p -> onClient(client) { resolve(client, p ?: JsonObject()) } },
        "reflect.get" to { client, p -> onClient(client) { get(client, p ?: JsonObject()) } },
        "reflect.set" to { client, p -> onClient(client) { set(client, p ?: JsonObject()) } },
        "reflect.call" to { client, p -> onClient(client) { call(client, p ?: JsonObject()) } },
        "reflect.new" to { client, p -> onClient(client) { construct(client, p ?: JsonObject()) } },
        "reflect.refs" to { client, _ -> onClient(client) { coreFor(client).refsJson() } },
        "reflect.release" to { client, p -> onClient(client) { release(client, p ?: JsonObject()) } },
        "reflect.mappings" to { client, _ -> onClient(client) {
            Mappings.ensureLoaded()
            JsonObject().apply { addProperty("available", Mappings.isAvailable()) }
        } },
    )

    /** Client thread only (refs would pin Screen/widget graphs in memory). */
    fun clear() = built.clear()

    /** The core's roots close over one client; only touched on the client thread. */
    private var bound: Minecraft? = null
    private var built: ReflectCore = ReflectCore({ _, _ -> null }, ROOTS).apply {
        // ItemStack.componentPatch-style NBT is server-side API; here id + count is enough.
        itemSummary = { v ->
            (v as? net.minecraft.world.item.ItemStack)?.let {
                JsonObject().apply {
                    addProperty("\$ref", refOf(it))
                    addProperty("item", it.item.toString())
                    addProperty("count", it.count)
                }
            }
        }
    }

    private fun coreFor(client: Minecraft): ReflectCore {
        if (bound !== client) {
            bound = client
            built = ReflectCore({ name, p -> root(name, p, client) }, ROOTS)
        }
        return built
    }

    private fun argsArray(p: JsonObject, name: String): JsonArray? =
        p.get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun onClient(client: Minecraft, body: () -> JsonElement): CompletableFuture<JsonElement> =
        ClientTasks.onClient(client) { body() }

    // ---------------------------------------------------------------- methods

    private fun resolve(client: Minecraft, p: JsonObject): JsonElement {
        val c = coreFor(client)
        val t = c.target(p)
        if (t.isNull) throw RpcException(RpcErrors.INVALID_PARAMS, "value at path is null; nothing to resolve")
        val clazz = t.clazz
        return JsonObject().apply {
            p.string("class")?.let { addProperty("requested", it) }
            addProperty("runtimeClass", clazz.name)
            Mappings.mojangClassName(clazz)?.takeIf { it != clazz.name }?.let { addProperty("mojangClass", it) }
            addProperty("isInterface", clazz.isInterface)
            addProperty("isEnum", clazz.isEnum)
            clazz.superclass?.let { addProperty("superclass", it.name) }
            if (t.steps.size() > 0) add("steps", t.steps)
            if (p.bool("members", false)) {
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
                    Mappings.mojangMemberName(c, f.name, isMethod = false)?.let { addProperty("mojangName", it) }
                    addProperty("type", f.type.name)
                    addProperty("static", java.lang.reflect.Modifier.isStatic(f.modifiers))
                    addProperty("final", java.lang.reflect.Modifier.isFinal(f.modifiers))
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
                if (!seen.add(m.name + m.parameterTypes.joinToString(",", "(", ")") { it.name })) continue
                arr.add(JsonObject().apply {
                    addProperty("name", m.name)
                    Mappings.mojangMemberName(c, m.name, isMethod = true)?.let { addProperty("mojangName", it) }
                    addProperty("returnType", m.returnType.name)
                    addProperty("static", java.lang.reflect.Modifier.isStatic(m.modifiers))
                    addProperty("declaringClass", m.declaringClass.name)
                    add("params", JsonArray().apply { m.parameterTypes.forEach { add(it.name) } })
                })
            }
            c = c.superclass
        }
        return arr
    }

    private fun get(client: Minecraft, p: JsonObject): JsonElement {
        val c = coreFor(client)
        val field = p.string("field")
        if (field == null && p.string("path").isNullOrBlank()) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "path or field required")
        }
        val t = c.target(p, extraPath = field)
        return JsonObject().apply {
            field?.let { addProperty("field", it) }
            val last = t.steps.last().asJsonObject
            (last.get("field") ?: last.get("method"))?.let { add("resolvedTo", it) }
            add("steps", t.steps)
            add("value", c.toJson(t.instance.takeUnless { t.isNull }))
        }
    }

    private fun set(client: Minecraft, p: JsonObject): JsonElement {
        val c = coreFor(client)
        val name = p.string("field") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "field required")
        val value = p.get("value") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "value required")
        val t = c.target(p)
        if (t.isNull) throw RpcException(RpcErrors.INVALID_PARAMS, "object at path is null")
        val field = c.findField(t.clazz, name)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "field not found: $name in ${t.clazz.name}")
        val (before, after) = c.writeField(field, t.instance, name, value)
        return JsonObject().apply {
            addProperty("field", name)
            addProperty("resolvedTo", field.name)
            addProperty("declaringClass", field.declaringClass.name)
            add("before", c.toJson(before))
            add("after", c.toJson(after))
        }
    }

    private fun argTypes(p: JsonObject): List<String>? = argsArray(p, "argTypes")?.map { it.asString }

    private fun call(client: Minecraft, p: JsonObject): JsonElement {
        val c = coreFor(client)
        val name = p.string("method") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "method required")
        val args = (argsArray(p, "args") ?: JsonArray()).map(c::fromJson)
        val t = c.target(p)
        if (t.isNull) throw RpcException(RpcErrors.INVALID_PARAMS, "object at path is null")
        val m = c.findMethod(t.clazz, name, args, argTypes(p))
        val value = c.invoke(m, t.instance, args.map { it.second }.toTypedArray())
        return JsonObject().apply {
            addProperty("method", name)
            addProperty("resolvedTo", m.name)
            addProperty("declaringClass", m.declaringClass.name)
            addProperty("signature", m.parameterTypes.joinToString(",", "(", ")") { it.name })
            add("value", if (m.returnType == Void.TYPE) JsonNull.INSTANCE else c.toJson(value))
        }
    }

    private fun construct(client: Minecraft, p: JsonObject): JsonElement {
        val c = coreFor(client)
        val className = p.string("class") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "class required")
        val args = (argsArray(p, "args") ?: JsonArray()).map(c::fromJson)
        val clazz = c.loadClass(className)
        val ctor = c.findConstructor(clazz, args, argTypes(p))
        val obj = c.invoke(ctor, null, args.map { it.second }.toTypedArray())
        return JsonObject().apply {
            addProperty("class", className)
            addProperty("runtimeClass", clazz.name)
            addProperty("constructor", ctor.toString())
            add("value", c.toJson(obj))
        }
    }

    private fun release(client: Minecraft, p: JsonObject): JsonElement = JsonObject().apply {
        val c = coreFor(client)
        if (p.bool("all", false)) {
            val n = c.refsJson().get("count").asInt
            c.clear()
            addProperty("removed", n)
        } else {
            addProperty("removed", c.release(p.num("ref")?.toLong() ?: throw RpcException(RpcErrors.INVALID_PARAMS, "ref required")))
        }
    }
}
