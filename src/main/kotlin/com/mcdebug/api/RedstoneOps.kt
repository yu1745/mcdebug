package com.mcdebug.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.McDebugMod
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.ServerContext
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.concurrent.CompletableFuture

object RedstoneOps : RpcHandlerGroup {
    private val pulses = mutableListOf<LeverPulse>()
    private var installed = false

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "getPower" to ::getPower,
        "isPowered" to ::isPowered,
        "setLever" to ::setLever,
        "pulse" to ::pulse,
        "notifyNeighbors" to ::notifyNeighbors,
    )

    fun install() {
        if (installed) return
        installed = true
        ServerTickEvents.END_SERVER_TICK.register { server -> tick(server) }
    }

    fun uninstall() {
        pulses.clear()
    }

    private fun getPower(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val side = p.getDirectionOrNull("side")
            powerSnapshot(world, pos, side)
        }

    private fun isPowered(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("dim", world.dimension().location().toString())
                addProperty("powered", world.hasNeighborSignal(pos))
                addProperty("received", world.getSignal(pos, null))
            }
        }

    private fun setLever(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val powered = p.getBoolOrFalse("powered")
            setLeverPowered(world, pos, powered)
        }

    private fun pulse(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val ticks = p.getIntOr("ticks", 2)
            if (ticks < 1) throw RpcException(RpcErrors.INVALID_PARAMS, "ticks must be >= 1")
            val result = setLeverPowered(world, pos, true)
            pulses.removeIf { it.world == world && it.pos == pos }
            pulses.add(LeverPulse(world, pos, server.tickCount + ticks))
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("dim", world.dimension().location().toString())
                addProperty("powered", result.get("powered").asBoolean)
                addProperty("offTick", server.tickCount + ticks)
            }
        }

    private fun notifyNeighbors(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val state = world.getBlockState(pos)
            world.updateNeighborsAt(pos, state.block)
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("dim", world.dimension().location().toString())
                add("state", ServerContext.blockStateToJson(state))
                addProperty("notified", true)
            }
        }

    private fun tick(server: MinecraftServer) {
        if (pulses.isEmpty()) return
        val due = pulses.filter { server.tickCount >= it.offTick }
        if (due.isEmpty()) return
        pulses.removeAll(due.toSet())
        for (pulse in due) {
            try {
                val state = pulse.world.getBlockState(pulse.pos)
                if (state.block is LeverBlock && state.getValue(LeverBlock.POWERED)) {
                    setLeverPowered(pulse.world, pulse.pos, false)
                }
            } catch (e: Exception) {
                McDebugMod.LOGGER.warn("redstone pulse cleanup failed at {}", pulse.pos, e)
            }
        }
    }

    private fun setLeverPowered(world: ServerLevel, pos: BlockPos, powered: Boolean): JsonObject {
        val state = world.getBlockState(pos)
        val lever = state.block as? LeverBlock
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "block at $pos is not a vanilla lever")
        val before = state.getValue(LeverBlock.POWERED)
        //1.21: togglePower returns void and mutates the world; read the resulting state back
        val afterState = if (before != powered) {
            lever.pull(state, world, pos, null)
            world.getBlockState(pos)
        } else state
        if (before == powered) world.updateNeighborsAt(pos, afterState.block)
        return JsonObject().apply {
            add("pos", ServerContext.posAsJson(pos))
            addProperty("dim", world.dimension().location().toString())
            addProperty("changed", before != powered)
            addProperty("powered", afterState.getValue(LeverBlock.POWERED))
            add("state", ServerContext.blockStateToJson(afterState))
        }
    }

    private fun powerSnapshot(world: ServerLevel, pos: BlockPos, side: Direction?): JsonObject =
        JsonObject().apply {
            add("pos", ServerContext.posAsJson(pos))
            addProperty("dim", world.dimension().location().toString())
            addProperty("powered", world.hasNeighborSignal(pos))
            addProperty("received", world.getSignal(pos, null))
            add("state", ServerContext.blockStateToJson(world.getBlockState(pos)))
            add("inputs", JsonObject().apply {
                Direction.entries.forEach { dir ->
                    addProperty(dir.name.lowercase(), world.getSignal(pos.relative(dir), dir))
                }
            })
            add("outputs", JsonObject().apply {
                Direction.entries.forEach { dir ->
                    addProperty(dir.name.lowercase(), world.getSignal(pos, dir))
                }
            })
            if (side != null) {
                addProperty("side", side.name.lowercase())
                addProperty("sideInput", world.getSignal(pos.relative(side), side))
                addProperty("sideOutput", world.getSignal(pos, side))
            }
        }

    private data class LeverPulse(val world: ServerLevel, val pos: BlockPos, val offTick: Int)
}
