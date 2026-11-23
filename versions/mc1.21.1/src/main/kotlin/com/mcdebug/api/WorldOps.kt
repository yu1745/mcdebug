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
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import java.util.concurrent.CompletableFuture

object WorldOps : RpcHandlerGroup {

    private val LOGGER: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("mcdebug-worldops")
    private const val DEFAULT_FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_NEIGHBORS or Block.UPDATE_IMMEDIATE

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "getBlock" to ::getBlock,
        "setBlock" to ::setBlock,
        "setBlocks" to ::setBlocks,
        "placeAsPlayer" to WorldPlayerActions::placeAsPlayer,
        "useItem" to WorldPlayerActions::useItem,
        "useItemHold" to WorldPlayerActions::useItemHold,
        "useOnBlock" to WorldPlayerActions::useOnBlock,
        "attackBlock" to WorldPlayerActions::attackBlock,
        "breakBlock" to WorldPlayerActions::breakBlock,
        "interactEntity" to WorldPlayerActions::interactEntity,
        "attackEntity" to WorldPlayerActions::attackEntity,
        "getRegion" to ::getRegion,
        "selectBlocks" to ::selectBlocks,
        "fillBox" to ::fillBox,
        "clearBox" to ::clearBox,
        "forceloadChunk" to ::forceloadChunk,
        "unforceloadChunk" to ::unforceloadChunk
    )

    private fun getBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val state = world.getBlockState(pos)
            val hasBe = world.getBlockEntity(pos) != null
            val includeNbt = p.getBoolOrFalse("includeNbt")
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("dim", world.dimension().location().toString())
                add("state", ServerContext.blockStateToJson(state))
                addProperty("hasBlockEntity", hasBe)
                if (includeNbt && hasBe) {
                    val nbt = world.getBlockEntity(pos)!!.saveWithoutMetadata(world.registryAccess())
                    add("nbt", NbtJson.toJson(nbt))
                }
            }
        }

    private fun setBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val blockId = p.requireString("block")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val stateProps = p.stateMap()
            val flags = p.getIntOr("flags", DEFAULT_FLAGS)
            val state = shaped(world, pos, ServerContext.blockState(server, blockId, stateProps), stateProps)
            val previous = world.getBlockState(pos)
            val ok = world.setBlock(pos, state, flags)
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("ok", ok)
                add("previous", ServerContext.blockStateToJson(previous))
            }
        }

    /**
     * Like /setblock: a block placed without explicit properties takes the shape its
     * neighbours imply (redstone wire connections, fences, stairs); otherwise wire stays
     * an unconnected dot and does not power the block beside it. Explicit properties win.
     */
    private fun shaped(world: ServerLevel, pos: BlockPos, state: BlockState, props: Map<String, String>?): BlockState =
        if (props.isNullOrEmpty()) Block.updateFromNeighbourShapes(state, world, pos) else state

    private fun setBlocks(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val ops = p.requireArray("ops")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val flags = p.getIntOr("flags", DEFAULT_FLAGS)
            var count = 0
            ops.forEach { el ->
                val op = el.asJsonObject
                val pos = op.requirePos("pos")
                val props = op.stateMap()
                val block = shaped(world, pos, ServerContext.blockState(server, op.requireString("block"), props), props)
                if (world.setBlock(pos, block, flags)) count++
            }
            JsonObject().apply { addProperty("count", count) }
        }

    private fun fillBox(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            fillBoxNow(server, p, p.requireString("block"), p.stateMap())
        }

    private fun clearBox(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            fillBoxNow(server, p, "minecraft:air", null)
        }


    private fun getRegion(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val box = boxFromParams(p)
            val includeNbt = p.getBoolOrFalse("includeNbt")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            // No ensureChunkLoaded: Level.getChunk synchronously loads on the main thread
            // (create=true), so getBlockState never NPEs. This matches getBlock/setBlock/etc.
            val results = JsonArray()
            BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).forEach { pos ->
                val state = world.getBlockState(pos)
                val be = world.getBlockEntity(pos)
                val entry = JsonObject().apply {
                    add("pos", ServerContext.posAsJson(pos))
                    addProperty("dim", world.dimension().location().toString())
                    add("state", ServerContext.blockStateToJson(state))
                    addProperty("hasBlockEntity", be != null)
                    if (includeNbt && be != null) add("nbt", NbtJson.toJson(be.saveWithoutMetadata(world.registryAccess())))
                }
                results.add(entry)
            }
            JsonObject().apply { add("blocks", results) }
        }

    private fun selectBlocks(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val box = boxFromParams(p)
            val pred = p.requireObject("predicate")
            val blockId = pred.getStringOrNull("block")
            val includeNbt = p.getBoolOrFalse("includeNbt")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            // No ensureChunkLoaded: see getRegion.
            val target = if (blockId != null) ServerContext.blockById(server, blockId) else null
            val matches = JsonArray()
            BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).forEach { pos ->
                val state = world.getBlockState(pos)
                if (target != null && state.block != target) return@forEach
                val entry = JsonObject().apply { add("pos", ServerContext.posAsJson(pos)) }
                if (includeNbt) {
                    val be = world.getBlockEntity(pos)
                    if (be != null) entry.add("nbt", NbtJson.toJson(be.saveWithoutMetadata(world.registryAccess())))
                }
                matches.add(entry)
            }
            JsonObject().apply { add("matches", matches) }
        }

    /**
     * Force-load a chunk so its block entities tick.
     * Uses ServerLevel.setChunkForced (persistent, same as /forceload command).
     * This ensures the chunk stays in getForcedChunks(), which keeps tickBlockEntities() alive.
     *
     * 同步等待区块真正加载到 FULL 状态再返回。`setChunkForced` 只是把区块加入
     * forced 集合（发 ticket），实际加载在 chunk worker 线程异步进行——若 RPC
     * 立即返回，紧跟而来的 setBlocks/place 可能命中"还在加载"的区块，导致
     * 方块写入被吞或 BE 未注册（高并行测试下的 `no block entity` 竞态）。
     * getChunk(..., FULL, create=true) 在主线程会阻塞直到该区块 FULL。
     */
    private fun forceloadChunk(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val chunkPos = chunkPosFromParams(p)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val changed = world.setChunkForced(chunkPos.x, chunkPos.z, true)
            // 同步等到区块 FULL：在主线程调用，create=true 阻塞直到加载完成。
            val chunk = world.chunkSource.getChunk(
                chunkPos.x, chunkPos.z,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true,
            )
            val loaded = chunk != null
            JsonObject().apply {
                add("chunk", JsonArray().apply { add(chunkPos.x); add(chunkPos.z) })
                addProperty("forced", true)
                addProperty("changed", changed)
                addProperty("loaded", loaded)
                addProperty("dim", world.dimension().location().toString())
            }
        }

    /**
     * Stop force-loading a chunk.
     */
    private fun unforceloadChunk(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val chunkPos = chunkPosFromParams(p)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val changed = world.setChunkForced(chunkPos.x, chunkPos.z, false)
            JsonObject().apply {
                add("chunk", JsonArray().apply { add(chunkPos.x); add(chunkPos.z) })
                addProperty("forced", false)
                addProperty("changed", changed)
                addProperty("dim", world.dimension().location().toString())
            }
        }

    private fun chunkPosFromParams(p: JsonObject): ChunkPos {
        val arr = p.getArrayOrNull("chunk")
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "chunk [cx,cz] required")
        if (arr.size() != 2 || arr.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "chunk must be [cx, cz] integers, got $arr")
        }
        return ChunkPos(arr.get(0).asInt, arr.get(1).asInt)
    }

    // ---- helpers ----

    private fun ensureChunkLoaded(world: ServerLevel, box: BoundingBox) {
        val minCx = box.minX() shr 4
        val maxCx = box.maxX() shr 4
        val minCz = box.minZ() shr 4
        val maxCz = box.maxZ() shr 4
        for (cx in minCx..maxCx) for (cz in minCz..maxCz) {
            // ChunkNotLoaded: we just check; auto-loading is left to v2
            if (!world.chunkSource.hasChunk(cx, cz)) {
                throw RpcException(RpcErrors.CHUNK_NOT_LOADED, "chunk not loaded: ($cx, $cz)")
            }
        }
    }

    private fun boxBlockCount(box: BoundingBox): Int =
        (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1)

    private fun fillBoxNow(
        server: MinecraftServer,
        p: JsonObject,
        blockId: String,
        stateProps: Map<String, String>?
    ): JsonObject {
        val box = boxFromParams(p)
        val world = ServerContext.world(server, p.getStringOrNull("dim"))
        val maxBlocks = p.getIntOr("maxBlocks", 32768)
        val total = boxBlockCount(box)
        if (total > maxBlocks) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "fillBox touches $total blocks; pass maxBlocks >= $total to confirm")
        }
        ensureChunkLoaded(world, box)
        val flags = p.getIntOr("flags", DEFAULT_FLAGS)
        val state = ServerContext.blockState(server, blockId, stateProps)
        var changed = 0
        BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).forEach { pos ->
            if (world.setBlock(pos, state, flags)) changed++
        }
        return JsonObject().apply {
            addProperty("count", total)
            addProperty("changed", changed)
            addProperty("dim", world.dimension().location().toString())
        }
    }
}
