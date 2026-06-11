package com.mcdebug.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture

object BlockEntityOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "getNbt" to ::getNbt,
        "setNbt" to ::setNbt,
        "getField" to ::getField,
        "setField" to ::setField
    )

    private fun getNbt(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val be = ServerContext.blockEntity(world, pos)
            val nbt = be.createNbt()
            JsonObject().apply { add("nbt", NbtJson.toJson(nbt)) }
        }

    private fun setNbt(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val be = ServerContext.blockEntity(world, pos)
            val nbtJson = p.get("nbt") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "nbt required")
            val nbt = NbtJson.fromJson(nbtJson) as? NbtCompound
                ?: throw RpcException(RpcErrors.NBT_PARSE_ERROR, "nbt must be a JSON object")
            be.readNbt(nbt)
            be.markDirty()
            world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3)
            JsonObject().apply { addProperty("ok", true) }
        }

    private fun getField(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val path = p.requireString("path")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val be = ServerContext.blockEntity(world, pos)
            val nbt = be.createNbt()
            val value = NbtJson.getByPathAsJson(nbt, path)
            JsonObject().apply { add("value", value) }
        }

    private fun setField(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val path = p.requireString("path")
            val valueJson = p.get("value") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "value required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val be = ServerContext.blockEntity(world, pos)
            val nbt = be.createNbt()
            val newValue = NbtJson.fromJson(valueJson)
            NbtJson.setByPath(nbt, path, newValue)
            be.readNbt(nbt)
            be.markDirty()
            world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3)
            JsonObject().apply { addProperty("ok", true) }
        }
}
