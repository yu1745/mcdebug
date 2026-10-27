package com.mcdebug.rpc

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.UUID

/**
 * Loader-specific environment hook for the loader-independent RPC transport.
 * Fabric sets it from FabricLoader, Forge from FMLPaths; RpcServer only reads it.
 */
object RpcEnv {
    /** Returns the game (run) directory. Must be assigned by the loader entrypoint before RpcServer.start. */
    lateinit var gameDir: () -> Path

    // Both planes in an integrated client share this JVM identity. The token is
    // a launch/ownership correlation value, NOT authentication for the RPC port.
    val pid: Long = ProcessHandle.current().pid()
    val startTime: String = ProcessHandle.current().info().startInstant().map { it.toString() }.orElse("")
    val instanceId: String = UUID.randomUUID().toString()
    val sessionToken: String = System.getenv("MCDEBUG_SESSION_TOKEN") ?: ""

    fun processInfo(): JsonObject = JsonObject().apply {
        addProperty("pid", pid)
        addProperty("startTime", startTime)
        addProperty("instanceId", instanceId)
        addProperty("sessionToken", sessionToken)
        addProperty("gameDir", gameDir().toRealPath().toString())
    }
}
