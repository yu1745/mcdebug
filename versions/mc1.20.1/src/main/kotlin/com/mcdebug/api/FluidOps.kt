package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.loader.FluidStorage
import com.mcdebug.loader.Loader
import com.mcdebug.loader.StorageView
import com.mcdebug.util.ServerContext
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.resources.ResourceLocation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.concurrent.CompletableFuture

/**
 * FluidOps — fluid storage read/write operations.
 *
 * All commands are side-aware. Pass --side north/south/east/west/up/down to query a
 * specific face; omit --side (null) to use the "complete inventory" path per the
 * FluidStorage.SIDED contract.
 *
 * Indexing: the loader's fluid storage is treated as a list of tanks (`parts`), which the
 * storage seam reports in the loader's own terms (Fabric: SingleVariantStorage → 1 part even
 * when empty, CombinedStorage → one part per sub-storage; NeoForge: one part per `IFluidHandler`
 * tank).
 *
 * For insert/extract, --index targets a specific part. If --index is omitted:
 *  - 1 part  → defaults to 0
 *  - N parts → error, ask the caller to pass --index
 *
 * Operations go through the storage's own `insert` / `extract` (not raw NBT),
 * so machine-side validation (canInsert / canExtract) is respected.
 */
object FluidOps : RpcHandlerGroup {

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "info" to ::info,
        "get" to ::get,
        "insert" to ::insert,
        "extract" to ::extract,
    )

    // ---- handlers ----

    private fun info(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (world, pos, side) = resolve(server, p)
            val storage = findStorage(world, pos, side)
            val parts = partsOf(storage)
            JsonObject().apply {
                addProperty("side", side?.getSerializedName() ?: "null")
                addProperty("type", storage.typeName)
                addProperty("supportsInsertion", storage.supportsInsertion)
                addProperty("supportsExtraction", storage.supportsExtraction)
                add("parts", JsonArray().apply { parts.forEach { add(tankInfoToJson(it)) } })
            }
        }

    private fun get(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val (world, pos, side) = resolve(server, params)
            val storage = findStorage(world, pos, side)
            val parts = partsOf(storage)
            val idx = resolveIndex(params, parts)
            val tank = parts[idx]
            JsonObject().apply {
                addProperty("index", idx)
                addProperty("fluid", tank.fluidId)
                addProperty("amount", tank.amount)
                addProperty("capacity", tank.capacity)
            }
        }

    private fun insert(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (world, pos, side) = resolve(server, p)
            val storage = findStorage(world, pos, side)
            val parts = partsOf(storage)
            val idx = resolveIndex(p, parts)
            val fluidId = p.requireString("fluid")
            val amount = p.requireLong("amount")
            val fluid = resolveFluid(server, fluidId)
            val target = storage.partAt(idx) ?: throw RpcException(
                RpcErrors.FLUID_INDEX_OUT_OF_RANGE, "index $idx out of range [0, ${parts.size})"
            )
            val inserted = target.insert(fluid, amount, simulate = false)
            JsonObject().apply {
                addProperty("index", idx)
                addProperty("requested", amount)
                addProperty("inserted", inserted)
                addProperty("remaining", amount - inserted)
            }
        }

    private fun extract(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (world, pos, side) = resolve(server, p)
            val storage = findStorage(world, pos, side)
            val parts = partsOf(storage)
            val idx = resolveIndex(p, parts)
            val amount = p.requireLong("amount")
            // Extract whatever the tank currently holds (component patch included).
            val currentFluid = parts[idx].fluidId
                ?: throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "tank $idx is empty or contains a non-extractable fluid variant"
                )
            val target = storage.partAt(idx) ?: throw RpcException(
                RpcErrors.FLUID_INDEX_OUT_OF_RANGE, "index $idx out of range [0, ${parts.size})"
            )
            val extracted = target.extractCurrent(amount, simulate = false)
            JsonObject().apply {
                addProperty("index", idx)
                addProperty("fluid", currentFluid)
                addProperty("requested", amount)
                addProperty("extracted", extracted)
                addProperty("remaining", amount - extracted)
            }
        }

    // ---- helpers ----

    private fun resolve(server: MinecraftServer, params: JsonObject?): Triple<net.minecraft.server.level.ServerLevel, BlockPos, Direction?> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        val pos = p.requirePos("pos")
        val world = ServerContext.world(server, p.getStringOrNull("dim"))
        val side = p.getStringOrNull("side")?.let { parseDirection(it) }
        return Triple(world, pos, side)
    }

    private fun findStorage(world: net.minecraft.server.level.ServerLevel, pos: BlockPos, side: Direction?): FluidStorage =
        Loader.api.storage.fluidStorage(world, pos, side)
            ?: throw RpcException(
                RpcErrors.FLUID_NOT_FOUND,
                "no fluid storage at $pos${if (side != null) " on side=$side" else ""}"
            )

    /** Tanks as `fluid.*` indexes them. */
    private fun partsOf(storage: FluidStorage): List<StorageView> = storage.parts()

    private fun resolveIndex(params: JsonObject?, parts: List<StorageView>): Int {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        return if (p.has("index") && !p.get("index").isJsonNull) {
            val idx = p.get("index").asInt
            if (idx < 0 || idx >= parts.size) {
                throw RpcException(
                    RpcErrors.FLUID_INDEX_OUT_OF_RANGE,
                    "index $idx out of range [0, ${parts.size})"
                )
            }
            idx
        } else {
            when {
                parts.isEmpty() -> throw RpcException(
                    RpcErrors.FLUID_INDEX_OUT_OF_RANGE,
                    "storage is empty: no enumerable fluid parts to operate on"
                )
                parts.size == 1 -> 0
                else -> throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "storage has ${parts.size} tanks; --index is required (0..${parts.size - 1})"
                )
            }
        }
    }

    private fun tankInfoToJson(t: StorageView): JsonElement = JsonObject().apply {
        addProperty("fluid", t.fluidId)
        addProperty("amount", t.amount)
        addProperty("capacity", t.capacity)
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

    private fun resolveFluid(server: MinecraftServer, id: String): Fluid {
        val identifier = ResourceLocation.tryParse(id)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid fluid id: $id")
        val key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.FLUID, identifier)
        val fluid = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.FLUID).get(key)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "fluid not registered: $id")
        if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "fluid is EMPTY (no real fluid for: $id)")
        }
        return fluid
    }
}
