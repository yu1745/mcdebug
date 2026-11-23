package com.mcdebug.rpc

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * JSON-RPC 2.0 message types.
 * See https://www.jsonrpc.org/specification
 */
object JsonRpc {
    const val VERSION = "2.0"

    /** Request: { jsonrpc, id, method, params? } */
    data class Request(
        val id: Any?,
        val method: String,
        val params: JsonElement?
    ) {
        companion object {
            fun fromJsonObject(obj: JsonObject): Request {
                if (!obj.has("method") || obj.get("method").isJsonNull) {
                    throw RpcException(RpcErrors.INVALID_REQUEST, "missing 'method'")
                }
                val id: Any? = if (obj.has("id") && !obj.get("id").isJsonNull) {
                    val idEl = obj.get("id")
                    when {
                        idEl.isJsonPrimitive && idEl.asJsonPrimitive.isNumber -> idEl.asLong
                        idEl.isJsonPrimitive && idEl.asJsonPrimitive.isString -> idEl.asString
                        else -> idEl.toString()
                    }
                } else null
                val method = obj.get("method").asString
                val params = if (obj.has("params") && !obj.get("params").isJsonNull) obj.get("params") else null
                return Request(id, method, params)
            }
        }
    }

    /** Response: { jsonrpc, id, result? , error? } — exactly one of result/error */
    data class Response(
        val id: Any?,
        val result: JsonElement? = null,
        val error: Error? = null
    )

    data class Error(
        val code: Int,
        val message: String,
        val data: JsonElement? = null
    )

    object Notification {
        const val SERVER_READY = "server.ready"
        const val SERVER_SHUTTING_DOWN = "server.shuttingDown"
        const val TICK = "notify.tick"
        const val PLAYER_JOIN = "notify.playerJoin"
        const val PLAYER_LEAVE = "notify.playerLeave"
    }
}

/** Thrown by handlers to short-circuit with a structured JSON-RPC error. */
class RpcException(
    val rpcCode: Int,
    message: String,
    val data: JsonElement? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    fun toError(): JsonRpc.Error = JsonRpc.Error(rpcCode, message ?: "error", data)
}
