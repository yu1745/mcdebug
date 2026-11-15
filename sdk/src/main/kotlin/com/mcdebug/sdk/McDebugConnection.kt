package com.mcdebug.sdk

import com.google.gson.JsonElement
import com.mcdebug.contract.Methods

/** Two persistent RPC connections, with routing shared by application front ends. */
class McDebugConnection(
    serverOptions: RpcClientOptions,
    clientOptions: RpcClientOptions,
) : AutoCloseable {
    init {
        require(serverOptions.plane == RpcClientOptions.PLANE_SERVER)
        require(clientOptions.plane == RpcClientOptions.PLANE_CLIENT)
    }

    val server = DebugApi(RpcClient(serverOptions))
    val client = DebugApi(RpcClient(clientOptions))
    val gui: GuiApi get() = client.gui

    fun connect() {
        val serverReady = server.rpc.handshake()
        val clientReady = client.rpc.handshake()
        check(serverReady.protocolVersion == clientReady.protocolVersion) { "RPC planes have different protocol versions" }
        check(serverReady.modVersion == clientReady.modVersion) { "RPC planes have different mod versions" }
    }

    fun call(method: String, params: JsonElement? = null): JsonElement = when (Methods.planeOf(method)) {
        "client" -> client.rpc.call(method, params)
        "server" -> server.rpc.call(method, params)
        else -> throw IllegalArgumentException("unknown mcdebug method: $method")
    }

    override fun close() {
        try { client.rpc.close() } finally { server.rpc.close() }
    }
}
