package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.McDebugMod
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.World
import java.util.concurrent.CompletableFuture

object ServerOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "status" to ::status,
        "listDimensions" to ::listDimensions
    )

    private fun status(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val dims = JsonArray()
            server.worlds.forEach { dims.add(it.registryKey.value.toString()) }
            val overworld = server.getWorld(World.OVERWORLD)
            val dayTime = overworld?.timeOfDay ?: 0L
            JsonObject().apply {
                addProperty("mcVersion", server.version)
                addProperty("modVersion", McDebugMod.MOD_VERSION)
                addProperty("modLoader", "fabric")
                addProperty("protocolVersion", 1)
                add("dims", dims)
                addProperty("players", server.currentPlayerCount)
                addProperty("dayTime", dayTime)
                addProperty("tick", server.ticks)
            }
        }

    private fun listDimensions(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val dims = JsonArray()
            server.worlds.forEach { dims.add(it.registryKey.value.toString()) }
            JsonObject().apply { add("dims", dims) }
        }
}
