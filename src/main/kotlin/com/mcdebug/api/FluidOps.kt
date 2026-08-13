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
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
import net.minecraft.fluid.Fluid
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import java.util.concurrent.CompletableFuture

/**
 * FluidOps — fluid storage read/write operations.
 *
 * All commands are side-aware. Pass --side north/south/east/west/up/down to query a
 * specific face; omit --side (null) to use the "complete inventory" path per the
 * FluidStorage.SIDED contract.
 *
 * Indexing: a `Storage<FluidVariant>` is treated as a list of tanks (`parts`).
 *  - SingleVariantStorage       → 1 part (always shown, even if empty)
 *  - CombinedStorage<*,*>       → N parts (one per sub-storage)
 *  - Other (rare)               → non-blank StorageViews from iterator()
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
            val parts = enumerateParts(storage)
            JsonObject().apply {
                addProperty("side", side?.asString() ?: "null")
                addProperty("type", typeNameOf(storage))
                addProperty("supportsInsertion", storage.supportsInsertion())
                addProperty("supportsExtraction", storage.supportsExtraction())
                add("parts", JsonArray().apply { parts.forEach { add(tankInfoToJson(it)) } })
            }
        }

    private fun get(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val (world, pos, side) = resolve(server, params)
            val storage = findStorage(world, pos, side)
            val parts = enumerateParts(storage)
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
            val parts = enumerateParts(storage)
            val idx = resolveIndex(p, parts)
            val fluidId = p.requireString("fluid")
            val amount = p.requireLong("amount")
            val fluid = resolveFluid(server, fluidId)
            val variant = FluidVariant.of(fluid)

            val target = pickTarget(storage, idx)
            Transaction.openOuter().use { tx ->
                val inserted = target.insert(variant, amount, tx)
                if (inserted > 0) tx.commit()
                else tx.abort()
                JsonObject().apply {
                    addProperty("index", idx)
                    addProperty("requested", amount)
                    addProperty("inserted", inserted)
                    addProperty("remaining", amount - inserted)
                }
            }
        }

    private fun extract(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val (world, pos, side) = resolve(server, p)
            val storage = findStorage(world, pos, side)
            val parts = enumerateParts(storage)
            val idx = resolveIndex(p, parts)
            val amount = p.requireLong("amount")
            // Extract any fluid currently in the tank: use the part's current variant.
            val currentFluid = currentFluidOf(parts[idx])
                ?: throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "tank $idx is empty or contains a non-extractable fluid variant"
                )

            val target = pickTarget(storage, idx)
            Transaction.openOuter().use { tx ->
                val extracted = target.extract(currentFluid, amount, tx)
                if (extracted > 0) tx.commit()
                else tx.abort()
                JsonObject().apply {
                    addProperty("index", idx)
                    addProperty("fluid", Registries.FLUID.getId(currentFluid.fluid).toString())
                    addProperty("requested", amount)
                    addProperty("extracted", extracted)
                    addProperty("remaining", amount - extracted)
                }
            }
        }

    // ---- helpers ----

    private data class TankInfo(
        val storage: Storage<FluidVariant>,
        val variant: FluidVariant,
        val amount: Long,
        val capacity: Long,
    ) {
        val fluidId: String?
            get() = if (variant.isBlank) null else Registries.FLUID.getId(variant.fluid).toString()
    }

    private fun resolve(server: MinecraftServer, params: JsonObject?): Triple<net.minecraft.server.world.ServerWorld, BlockPos, Direction?> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        val pos = ServerContext.pos(p.getAsJsonArray("pos"))
        val world = ServerContext.world(server, p.getStringOrNull("dim"))
        val side = p.getStringOrNull("side")?.let { parseDirection(it) }
        return Triple(world, pos, side)
    }

    private fun findStorage(world: net.minecraft.server.world.ServerWorld, pos: BlockPos, side: Direction?): Storage<FluidVariant> =
        FluidStorage.SIDED.find(world, pos, side)
            ?: throw RpcException(
                RpcErrors.FLUID_NOT_FOUND,
                "no fluid storage at $pos${if (side != null) " on side=$side" else ""}"
            )

    /**
     * Enumerate the parts of a storage. SingleVariantStorage is special-cased so an
     * empty tank still shows up as 1 part (iterator() would hide it).
     */
    @Suppress("UNCHECKED_CAST")
    private fun enumerateParts(storage: Storage<FluidVariant>): List<TankInfo> {
        if (storage is SingleVariantStorage<*>) {
            val s = storage as SingleVariantStorage<FluidVariant>
            val v = s.variant
            val cap = s.getCapacity()
            return listOf(TankInfo(storage, v, s.amount, cap))
        }
        if (storage is CombinedStorage<*, *>) {
            // parts: public List<S extends Storage<T>>; cast each element to Storage<FluidVariant>
            val rawParts: List<*> = storage.parts
            return rawParts.mapIndexedNotNull { i, part ->
                val p = part as? Storage<FluidVariant> ?: return@mapIndexedNotNull null
                if (p is SingleVariantStorage<*>) {
                    val s = p as SingleVariantStorage<FluidVariant>
                    val v = s.variant
                    val cap = s.getCapacity()
                    TankInfo(p, v, s.amount, cap)
                } else {
                    val view = p.iterator().asSequence().firstOrNull { !it.isResourceBlank }
                    if (view == null) {
                        TankInfo(p, FluidVariant.blank(), 0L, 0L)
                    } else {
                        TankInfo(p, view.resource, view.amount, view.capacity)
                    }
                }
            }
        }
        // Fallback: enumerate non-blank views.
        return storage.iterator().asSequence()
            .filter { !it.isResourceBlank }
            .map { view ->
                TankInfo(storage, view.resource, view.amount, view.capacity)
            }
            .toList()
    }

    private fun readTank(tank: TankInfo, idx: Int): TankInfo = tank

    private fun currentFluidOf(tank: TankInfo): FluidVariant? =
        if (tank.variant.isBlank) null else tank.variant

    private fun resolveIndex(params: JsonObject?, parts: List<TankInfo>): Int {
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

    /**
     * Pick the actual Storage<FluidVariant> to operate on. For SingleVariantStorage,
     * the storage itself is the part. For CombinedStorage, parts[idx] is the target.
     * For "Other" storages, --index 0 is treated as the whole storage.
     */
    @Suppress("UNCHECKED_CAST")
    private fun pickTarget(storage: Storage<FluidVariant>, idx: Int): Storage<FluidVariant> {
        if (storage is CombinedStorage<*, *>) {
            val rawParts: List<*> = storage.parts
            val target = rawParts.getOrNull(idx) as? Storage<FluidVariant>
                ?: throw RpcException(
                    RpcErrors.FLUID_INDEX_OUT_OF_RANGE,
                    "index $idx out of range [0, ${rawParts.size})"
                )
            return target
        }
        // SingleVariantStorage and "Other" (rare): only index 0 makes sense.
        if (idx != 0) {
            throw RpcException(
                RpcErrors.FLUID_INDEX_OUT_OF_RANGE,
                "non-composite storage only supports --index 0"
            )
        }
        return storage
    }

    private fun typeNameOf(storage: Storage<FluidVariant>): String = when (storage) {
        is SingleVariantStorage<*> -> "SingleVariantStorage"
        is CombinedStorage<*, *> -> "CombinedStorage"
        else -> "Other"
    }

    private fun tankInfoToJson(t: TankInfo): JsonElement = JsonObject().apply {
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
        val identifier = Identifier.tryParse(id)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid fluid id: $id")
        val key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.FLUID, identifier)
        val fluid = server.registryManager.get(net.minecraft.registry.RegistryKeys.FLUID).getOrEmpty(key).orElse(null)
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "fluid not registered: $id")
        if (fluid == net.minecraft.fluid.Fluids.EMPTY) {
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
