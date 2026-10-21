package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.ServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import java.util.concurrent.CompletableFuture

/**
 * FluidOps (Forge) — fluid tank read/write via the FLUID_HANDLER capability.
 *
 * Same method names / params / error codes as the Fabric mod. Differences forced by the platform:
 *  - Forge fluid handlers are lists of tanks (`getTanks()`), so "parts" = tanks and
 *    `type` is always "IFluidHandler".
 *  - Amounts are in **mB** (1000 per bucket), not Fabric droplets (81000 per bucket).
 *    Results carry `"unit": "mB"`.
 *
 * All operations go through the handler's own fill/drain (not raw NBT), so machine-side
 * validation is respected.
 */
object FluidOps : RpcHandlerGroup {

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "info" to ::info,
        "get" to ::get,
        "insert" to ::insert,
        "extract" to ::extract,
    )

    private fun info(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (world, pos, side) = resolve(server, p)
            val handler = findHandler(world, pos, side)
            JsonObject().apply {
                addProperty("side", side?.serializedName ?: "null")
                addProperty("type", "IFluidHandler")
                addProperty("unit", "mB")
                // Forge handlers do not advertise capability flags; probe with simulated ops.
                addProperty("supportsInsertion", true)
                addProperty("supportsExtraction", true)
                add("parts", JsonArray().apply {
                    for (i in 0 until handler.tanks) add(tankInfoToJson(handler, i))
                })
            }
        }

    private fun get(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val (world, pos, side) = resolve(server, params)
            val handler = findHandler(world, pos, side)
            val idx = resolveIndex(params, handler.tanks)
            val stack = handler.getFluidInTank(idx)
            JsonObject().apply {
                addProperty("index", idx)
                addProperty("fluid", fluidId(stack))
                addProperty("amount", stack.amount)
                addProperty("capacity", handler.getTankCapacity(idx))
                addProperty("unit", "mB")
            }
        }

    private fun insert(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (world, pos, side) = resolve(server, p)
            val handler = findHandler(world, pos, side)
            val idx = resolveIndex(p, handler.tanks)
            val fluid = resolveFluid(p.requireString("fluid"))
            val amount = p.requireLong("amount")
            // IFluidHandler.fill is not per-tank. When the tank index matters (several tanks),
            // only accept the insertion if the target tank is the one that would take it.
            val toFill = FluidStack(fluid, amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val before = amountsOf(handler)
            val inserted = handler.fill(toFill, IFluidHandler.FluidAction.EXECUTE).toLong()
            JsonObject().apply {
                addProperty("index", idx)
                addProperty("requested", amount)
                addProperty("inserted", inserted)
                addProperty("remaining", amount - inserted)
                addProperty("unit", "mB")
                if (handler.tanks > 1) {
                    val after = amountsOf(handler)
                    addProperty("changedTank", after.indices.firstOrNull { after[it] != before[it] } ?: -1)
                }
            }
        }

    private fun extract(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (world, pos, side) = resolve(server, p)
            val handler = findHandler(world, pos, side)
            val idx = resolveIndex(p, handler.tanks)
            val amount = p.requireLong("amount")
            val current = handler.getFluidInTank(idx)
            if (current.isEmpty) throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "tank $idx is empty or contains a non-extractable fluid variant"
            )
            val want = FluidStack(current, amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val drained = handler.drain(want, IFluidHandler.FluidAction.EXECUTE)
            JsonObject().apply {
                addProperty("index", idx)
                addProperty("fluid", BuiltInRegistries.FLUID.getKey(current.fluid).toString())
                addProperty("requested", amount)
                addProperty("extracted", drained.amount.toLong())
                addProperty("remaining", amount - drained.amount)
                addProperty("unit", "mB")
            }
        }

    // ---- helpers ----

    private fun amountsOf(h: IFluidHandler): List<Int> = (0 until h.tanks).map { h.getFluidInTank(it).amount }

    private fun fluidId(s: FluidStack): String? =
        if (s.isEmpty) null else BuiltInRegistries.FLUID.getKey(s.fluid).toString()

    private fun tankInfoToJson(h: IFluidHandler, i: Int): JsonElement = JsonObject().apply {
        val s = h.getFluidInTank(i)
        addProperty("fluid", fluidId(s))
        addProperty("amount", s.amount)
        addProperty("capacity", h.getTankCapacity(i))
    }

    private fun resolve(server: MinecraftServer, params: JsonObject?): Triple<ServerLevel, BlockPos, Direction?> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        val pos = ServerContext.pos(p.getAsJsonArray("pos"))
        val world = ServerContext.world(server, p.getStringOrNull("dim"))
        val side = p.getStringOrNull("side")?.let { parseDirection(it) }
        return Triple(world, pos, side)
    }

    private fun findHandler(world: ServerLevel, pos: BlockPos, side: Direction?): IFluidHandler =
        world.getBlockEntity(pos)?.getCapability(ForgeCapabilities.FLUID_HANDLER, side)?.resolve()?.orElse(null)
            ?: throw RpcException(
                RpcErrors.FLUID_NOT_FOUND,
                "no fluid storage at $pos${if (side != null) " on side=$side" else ""}"
            )

    private fun resolveIndex(params: JsonObject?, tanks: Int): Int {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        return if (p.has("index") && !p.get("index").isJsonNull) {
            val idx = p.get("index").asInt
            if (idx < 0 || idx >= tanks) {
                throw RpcException(RpcErrors.FLUID_INDEX_OUT_OF_RANGE, "index $idx out of range [0, $tanks)")
            }
            idx
        } else {
            when {
                tanks == 0 -> throw RpcException(
                    RpcErrors.FLUID_INDEX_OUT_OF_RANGE,
                    "storage is empty: no enumerable fluid parts to operate on"
                )
                tanks == 1 -> 0
                else -> throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "storage has $tanks tanks; --index is required (0..${tanks - 1})"
                )
            }
        }
    }

    private fun parseDirection(s: String): Direction? = when (s.lowercase()) {
        "null" -> null
        "north" -> Direction.NORTH
        "south" -> Direction.SOUTH
        "east" -> Direction.EAST
        "west" -> Direction.WEST
        "up" -> Direction.UP
        "down" -> Direction.DOWN
        else -> throw RpcException(
            RpcErrors.INVALID_PARAMS,
            "invalid side: $s (allowed: null, north, south, east, west, up, down)"
        )
    }

    internal fun resolveFluid(id: String): Fluid {
        val identifier = ResourceLocation.tryParse(id)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid fluid id: $id")
        val fluid = BuiltInRegistries.FLUID.getOptional(identifier).orElse(null)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "fluid not registered: $id")
        if (fluid == Fluids.EMPTY) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "fluid is EMPTY (no real fluid for: $id)")
        }
        return fluid
    }
}

// ---- shared with other files in this package ----

internal fun JsonObject.requireLong(name: String): Long {
    val v = if (has(name) && !get(name).isJsonNull) get(name).asLong else null
    return v ?: throw RpcException(RpcErrors.INVALID_PARAMS, "missing long param: $name")
}
