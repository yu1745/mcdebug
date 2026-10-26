package com.mcdebug.client

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcMethodHost
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture

/**
 * A `gui.*` handler. Mirrors the server-side `RpcHandler`, but the target is the
 * client singleton instead of a `MinecraftServer`.
 */
typealias ClientRpcHandler = (client: Minecraft, params: JsonObject?) -> CompletableFuture<JsonElement>

/** A group of related client methods (`GuiOps` ↔ `gui.*`). */
interface ClientRpcGroup {
    fun methods(): Map<String, ClientRpcHandler>
}

/** Client-plane counterpart of `RpcDispatcher`; same registration/dispatch semantics. */
class ClientRpcDispatcher {

    private val handlers = LinkedHashMap<String, ClientRpcHandler>()

    fun register(method: String, handler: ClientRpcHandler) {
        synchronized(handlers) {
            handlers[method] = handler
        }
    }

    fun registerGroup(prefix: String, group: ClientRpcGroup) {
        group.methods().forEach { (name, handler) -> register("$prefix.$name", handler) }
    }

    fun has(method: String): Boolean = synchronized(handlers) { handlers.containsKey(method) }

    fun methods(): Set<String> = synchronized(handlers) { LinkedHashSet(handlers.keys) }

    fun dispatch(method: String, params: JsonObject?): CompletableFuture<JsonElement> {
        val handler = synchronized(handlers) { handlers[method] }
            ?: throw RpcException(RpcErrors.METHOD_NOT_FOUND, "method not found: $method")
        val client = Minecraft.getInstance()
            ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "client instance not available")
        return handler(client, params)
    }
}

/**
 * Client-plane [RpcMethodHost]. The `Minecraft` singleton is process-wide, so the
 * server passed to [attach] is ignored; a missing client is reported as
 * [RpcErrors.CLIENT_NOT_READY] so the CLI can tell "no GUI agent" from "bad request".
 */
class ClientMethodHost(private val dispatcher: ClientRpcDispatcher) : RpcMethodHost {

    override fun attach(server: MinecraftServer?) = Unit

    override fun dispatch(method: String, params: JsonObject?): CompletableFuture<JsonElement> =
        dispatcher.dispatch(method, params)
}
