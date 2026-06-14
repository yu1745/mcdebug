package com.mcdebug.rpc

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.McDebugMod
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture

/**
 * A handler receives the live MinecraftServer and the request params, and returns a future
 * that completes on the Minecraft server thread with the result.
 */
typealias RpcHandler = (server: MinecraftServer, params: JsonObject?) -> CompletableFuture<JsonElement>

/**
 * Method namespace router. Methods look like "world.setBlock"; we route by the full method name.
 */
class RpcDispatcher {
    private val handlers = HashMap<String, RpcHandler>()

    fun register(method: String, handler: RpcHandler) {
        handlers[method] = handler
    }

    fun registerGroup(prefix: String, group: RpcHandlerGroup) {
        group.methods().forEach { (suffix, h) ->
            register("$prefix.$suffix", h)
        }
    }

    fun dispatch(method: String, params: JsonObject?, server: MinecraftServer): CompletableFuture<JsonElement> {
        val h = handlers[method] ?: throw RpcException(RpcErrors.METHOD_NOT_FOUND, "method not found: $method")
        return h(server, params)
    }

    fun has(method: String): Boolean = handlers.containsKey(method)
}

interface RpcHandlerGroup {
    fun methods(): Map<String, RpcHandler>
}

object RpcContext {
    val GSON: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    /**
     * The id of the RPC connection currently being serviced on this thread, or null
     * if not set. Populated by RpcServer.handleConnection for the duration of a
     * request so that long-running handlers (e.g. WaitOps.until) can tag their
     * background work with the owning connection and let the server cancel it when
     * the client disconnects.
     */
    val currentConnectionId: ThreadLocal<Int?> = ThreadLocal()


    /**
     * Schedule a function on the Minecraft server thread and return a future of its result.
     * Used by every handler so that world reads/writes happen on the main thread.
     */
    fun onServer(server: MinecraftServer, fn: () -> JsonElement): CompletableFuture<JsonElement> {
        val f = CompletableFuture<JsonElement>()
        try {
            server.execute {
                try {
                    f.complete(fn())
                } catch (e: RpcException) {
                    f.completeExceptionally(e)
                } catch (e: Exception) {
                    McDebugMod.LOGGER.error("handler error", e)
                    f.completeExceptionally(RpcException(RpcErrors.INTERNAL_ERROR, e.message ?: "internal error"))
                }
            }
        } catch (e: Exception) {
            f.completeExceptionally(RpcException(RpcErrors.INTERNAL_ERROR, "server shutting down"))
        }
        return f
    }

    fun paramsObj(params: JsonElement?): JsonObject? = when {
        params == null || params is JsonNull -> null
        params is JsonObject -> params
        else -> throw RpcException(RpcErrors.INVALID_PARAMS, "params must be an object")
    }

    fun parseJsonLine(line: String): JsonElement = try {
        JsonParser.parseString(line)
    } catch (e: Exception) {
        throw RpcException(RpcErrors.PARSE_ERROR, "invalid JSON: ${e.message}")
    }
}
