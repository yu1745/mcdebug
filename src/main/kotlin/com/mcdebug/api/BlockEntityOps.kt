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
import net.minecraft.block.entity.BlockEntity
import net.minecraft.block.entity.BlockEntityTicker
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import java.util.concurrent.CompletableFuture

object BlockEntityOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "getNbt" to ::getNbt,
        "setNbt" to ::setNbt,
        "getField" to ::getField,
        "setField" to ::setField,
        "tick" to ::tick
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
            applyBeNbt(world, pos, be, nbt)
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
            applyBeNbt(world, pos, be, nbt)
            JsonObject().apply { addProperty("ok", true) }
        }

    /**
     * Apply a full NBT compound to a block entity and fire all the downstream
     * notifications that `BlockEntity.readNbt` alone does NOT trigger:
     *
     *   - `markDirty()` — flag the chunk for save (vanilla readNbt does not do this)
     *   - `ServerWorld.updateListeners(pos, old, new, NOTIFY_ALL)` — re-broadcast the
     *     block state to any tracking client (BE NBT deltas are part of this packet).
     *     On a dedicated server with no real player tracking the chunk this is a no-op,
     *     which is fine.
     *   - `World.updateComparators(pos, block)` — re-evaluate adjacent comparators so
     *     inventory/content changes (furnace cook progress, chest fullness, IC2 machine
     *     energy level via property delegate) propagate to redstone immediately. This is
     *     the bit the old implementation was missing, causing "set NBT but comparator
     *     signal didn't update" symptoms.
     *
     * Note: some BlockEntity subclasses cache derived state (redstone output, model
     * state) that `readNbt` doesn't fully rebuild. That is a per-subclass vanilla/mod
     * limitation; this helper covers the common inventory/comparator case.
     */
    private fun applyBeNbt(world: ServerWorld, pos: BlockPos, be: BlockEntity, nbt: NbtCompound) {
        be.readNbt(nbt)
        be.markDirty()
        val state = world.getBlockState(pos)
        world.updateListeners(pos, state, state, 3)
        world.updateComparators(pos, state.block)
    }
}

// ---- tick ----

/**
 * 主动 tick 指定位置的方块实体 N 次（默认 1 次）。
 *
 * 用途：机器类测试的加速器——`wait.until` 是干等自然 tick（15s=300t 预算），
 * 而很多机器逻辑（配方进度、热量、能量消耗）只依赖机器自身 tick 与相邻
 * 能量接口（`adjacentEnergyTransfer.tick()` 在机器 tick 内部调用），
 * 可以直接 `be.tick` 驱动 N 次，毫秒级完成原本十几秒的等待。
 *
 * 注意：
 *  - 在 server 线程执行，与自然 tick 语义一致（并发安全）；
 *  - 只 tick 目标 BE，不 tick 邻居/世界（能量网络等全局逻辑不在其内）；
 *  - 不适用于依赖自然 tick 跨度的用例（如"机器在 300 自然 tick 内完成"
 *    的时序验证），这类仍用 `wait.until`；
 *  - 机器 tick 中可能主动 `markDirty` / 改变世界状态（如离心机加热），
 *    与真实 tick 行为一致。
 */
private fun tick(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
    RpcContext.onServer(server) {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        val pos = ServerContext.pos(p.getAsJsonArray("pos"))
        val world = ServerContext.world(server, p.getStringOrNull("dim"))
        val count = p.getIntOr("ticks", 1).coerceAtLeast(0)
        val be = ServerContext.blockEntity(world, pos)
        val state = world.getBlockState(pos)
        // 走 BlockState.getBlockEntityTicker —— 与自然 tick 完全相同的调用路径（BlockEntityTicker）。
        @Suppress("UNCHECKED_CAST")
        val ticker = state.getBlockEntityTicker(world, be.type) as BlockEntityTicker<BlockEntity>? 
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "block at $pos has no ticker (not a ticking block entity)")
        repeat(count) {
            ticker.tick(world, pos, state, be)
        }
        JsonObject().apply {
            add("pos", ServerContext.posAsJson(pos))
            addProperty("dim", world.registryKey.value.toString())
            addProperty("ticked", count)
        }
    }
