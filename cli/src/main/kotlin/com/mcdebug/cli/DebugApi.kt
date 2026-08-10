package com.mcdebug.cli

import com.google.gson.JsonElement

/**
 * 类型化 API（对照 TS 版 api.ts，按需逐步补齐）。
 * 字段名即 JSON 字段名，与 contract 模块的 DTO 一一对应。
 */
class DebugApi(val rpc: RpcClient) {

    fun status(): JsonElement = rpc.call("server.status")

    fun listDimensions(): JsonElement = rpc.call("server.listDimensions")

    fun runCommand(command: String, dim: String? = null): JsonElement =
        rpc.call("server.runCommand", jsonObj {
            addProperty("command", command)
            if (dim != null) addProperty("dim", dim)
        })

    fun getBlock(pos: List<Int>, dim: String? = null): JsonElement =
        rpc.call("world.getBlock", jsonObj {
            add("pos", gson.toJsonTree(pos))
            if (dim != null) addProperty("dim", dim)
        })

    fun setBlock(pos: List<Int>, block: String, stateProps: Map<String, String>? = null, dim: String? = null): JsonElement =
        rpc.call("world.setBlock", jsonObj {
            add("pos", gson.toJsonTree(pos))
            addProperty("block", block)
            if (stateProps != null) add("state", gson.toJsonTree(mapOf("name" to block, "props" to stateProps)))
            if (dim != null) addProperty("dim", dim)
        })

    fun setBlocks(ops: List<JsonElement>): JsonElement =
        rpc.call("world.setBlocks", jsonObj { add("ops", gson.toJsonTree(ops)) })

    fun getNbt(pos: List<Int>, dim: String? = null): JsonElement =
        rpc.call("be.getNbt", jsonObj {
            add("pos", gson.toJsonTree(pos))
            if (dim != null) addProperty("dim", dim)
        })
}
