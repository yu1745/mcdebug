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
import net.minecraft.block.Block
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import java.util.concurrent.CompletableFuture

object FixtureOps : RpcHandlerGroup {
    private const val DEFAULT_FLAGS = Block.NOTIFY_LISTENERS or Block.NOTIFY_NEIGHBORS or Block.REDRAW_ON_MAIN_THREAD

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "capture" to ::capture,
        "load" to ::load,
    )

    private fun capture(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val box = boxFromParams(p)
            val includeNbt = !p.has("includeNbt") || p.get("includeNbt").asBoolean
            val origin = BlockPos(box.minX, box.minY, box.minZ)
            JsonObject().apply {
                addProperty("version", 1)
                addProperty("dim", world.registryKey.value.toString())
                add("origin", ServerContext.posAsJson(origin))
                add("size", JsonArray().apply {
                    add(box.maxX - box.minX + 1)
                    add(box.maxY - box.minY + 1)
                    add(box.maxZ - box.minZ + 1)
                })
                add("blocks", JsonArray().apply {
                    BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
                        val state = world.getBlockState(pos)
                        val be = world.getBlockEntity(pos)
                        add(JsonObject().apply {
                            add("rel", ServerContext.posAsJson(BlockPos(pos.x - origin.x, pos.y - origin.y, pos.z - origin.z)))
                            add("state", ServerContext.blockStateToJson(state))
                            if (includeNbt && be != null) add("nbt", NbtJson.toJson(be.createNbt()))
                        })
                    }
                })
            }
        }

    private fun load(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val fixture = p.requireObject("fixture")
            val world = ServerContext.world(server, p.getStringOrNull("dim") ?: fixture.getStringOrNull("dim"))
            val origin = p.getAsJsonArray("origin")?.let(ServerContext::pos)
                ?: fixture.getAsJsonArray("origin")?.let(ServerContext::pos)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "origin required")
            val flags = p.getIntOr("flags", DEFAULT_FLAGS)
            val blocks = fixture.getAsJsonArray("blocks") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "fixture.blocks required")
            var placed = 0
            blocks.forEach { el ->
                val entry = el.asJsonObject
                val pos = targetPos(origin, entry)
                val stateObj = entry.requireObject("state")
                val state = ServerContext.blockState(server, stateObj.requireString("name"), stateObj.getStringMapOrNull("props"))
                if (world.setBlockState(pos, state, flags)) placed++
            }
            var blockEntities = 0
            blocks.forEach { el ->
                val entry = el.asJsonObject
                if (!entry.has("nbt") || entry.get("nbt").isJsonNull) return@forEach
                val pos = targetPos(origin, entry)
                val be = world.getBlockEntity(pos) ?: return@forEach
                val nbt = NbtJson.fromJson(entry.get("nbt")) as? NbtCompound
                    ?: throw RpcException(RpcErrors.NBT_PARSE_ERROR, "fixture nbt must be a JSON object")
                nbt.putInt("x", pos.x)
                nbt.putInt("y", pos.y)
                nbt.putInt("z", pos.z)
                be.readNbt(nbt)
                be.markDirty()
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), flags)
                blockEntities++
            }
            JsonObject().apply {
                addProperty("placed", placed)
                addProperty("blockEntities", blockEntities)
                addProperty("dim", world.registryKey.value.toString())
                add("origin", ServerContext.posAsJson(origin))
            }
        }

    private fun targetPos(origin: BlockPos, entry: JsonObject): BlockPos {
        val rel = entry.getAsJsonArray("rel")?.let(ServerContext::pos)
            ?: entry.getAsJsonArray("pos")?.let(ServerContext::pos)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "fixture block requires rel or pos")
        return origin.add(rel.x, rel.y, rel.z)
    }

    private fun boxFromParams(p: JsonObject): BlockBox {
        val box = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
        val from = ServerContext.pos(box.getAsJsonArray("from"))
        val to = ServerContext.pos(box.getAsJsonArray("to"))
        return ServerContext.boxFrom(from, to)
    }
}
