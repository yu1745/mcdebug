package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import java.util.concurrent.CompletableFuture

object ScanOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "findBlocks" to ::findBlocks,
        "countByBlock" to ::countByBlock
    )

    private fun findBlocks(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val boxObj = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
            val from = ServerContext.pos(boxObj.getAsJsonArray("from"))
            val to = ServerContext.pos(boxObj.getAsJsonArray("to"))
            val box = ServerContext.boxFrom(from, to)
            val blockId = p.requireString("block")
            val count = p.getBoolOrFalse("count")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val target = ServerContext.blockById(server, blockId)
            val positions = JsonArray()
            var n = 0
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
                if (world.getBlockState(pos).block == target) {
                    positions.add(ServerContext.posAsJson(pos))
                    n++
                }
            }
            JsonObject().apply {
                add("positions", positions)
                if (count) addProperty("count", n)
            }
        }

    private fun countByBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val boxObj = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
            val from = ServerContext.pos(boxObj.getAsJsonArray("from"))
            val to = ServerContext.pos(boxObj.getAsJsonArray("to"))
            val box = ServerContext.boxFrom(from, to)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val counts = HashMap<String, Int>()
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
                val state = world.getBlockState(pos)
                val key = Registries.BLOCK.getId(state.block).toString()
                counts.merge(key, 1) { a, b -> a + b }
            }
            JsonObject().apply {
                val obj = JsonObject()
                counts.entries.sortedBy { it.key }.forEach { (k, v) -> obj.addProperty(k, v) }
                add("counts", obj)
            }
        }
}
