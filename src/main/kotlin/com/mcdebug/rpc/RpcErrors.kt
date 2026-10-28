package com.mcdebug.rpc

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * JSON-RPC 2.0 standard error codes + mcdebug custom codes.
 * Custom codes live in the -32099 to -32000 range (reserved by spec for implementation-defined server-errors).
 */
object RpcErrors {
    // Standard JSON-RPC
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // mcdebug custom
    const val INVALID_POSITION = -32001
    const val BLOCK_NOT_FOUND = -32002
    const val INVALID_BLOCK_STATE = -32003
    const val BLOCK_ENTITY_MISSING = -32004
    const val SLOT_OUT_OF_RANGE = -32005
    const val TICK_TIMEOUT = -32006
    const val NBT_PARSE_ERROR = -32007
    const val CHUNK_NOT_LOADED = -32008
    const val PERMISSION_DENIED = -32009
    const val WORLD_READ_ONLY = -32010
    const val DIMENSION_NOT_FOUND = -32011
    const val INVALID_PREDICATE = -32012
    const val FLUID_NOT_FOUND = -32013
    const val FLUID_INDEX_OUT_OF_RANGE = -32014
    const val STORAGE_NOT_FOUND = -32015
    const val SNAPSHOT_NOT_FOUND = -32016
    const val TRACE_NOT_FOUND = -32017
    const val SCREEN_NOT_FOUND = -32018
    const val ENTITY_NOT_FOUND = -32019

    // client plane (`gui.*`) — produced by the client-side agent, see RpcErrors KDoc
    /** No client is running / not in a world / client plane not reachable. */
    const val CLIENT_NOT_READY = -32020
    /** A GUI element (widget, text, slot) matching the request was not found. */
    const val GUI_ELEMENT_NOT_FOUND = -32021

    /**
     * An unexpected handler exception as INTERNAL_ERROR. The message alone is often
     * useless ("null", a Kotlin null-check text), so it keeps the exception type and
     * the top stack frames in `data`.
     */
    fun internal(t: Throwable): RpcException {
        val data = JsonObject().apply {
            addProperty("exception", t.javaClass.name)
            add("at", JsonArray().apply { t.stackTrace.take(6).forEach { add(it.toString()) } })
            t.cause?.let { addProperty("cause", it.toString()) }
        }
        return RpcException(INTERNAL_ERROR, "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}", data, t)
    }
}
