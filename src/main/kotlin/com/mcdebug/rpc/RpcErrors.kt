package com.mcdebug.rpc

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
}
