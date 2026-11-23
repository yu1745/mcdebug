package com.mcdebug.loader

import com.google.gson.JsonElement
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.material.Fluid

/**
 * Loader-neutral view of one item slot / fluid tank / energy buffer, as `storage.*` and
 * `fluid.*` report it. Implementations fill in whatever the loader's storage API exposes:
 *
 *  - items:  [stack] (empty for a blank slot) + [amount]/[capacity]
 *  - fluids: [fluidId] (null for a blank tank) + [fluidNbt] (component patch as NBT-JSON, 1.21)
 *  - energy: a single view with [amount]/[capacity]
 */
class StorageView(
    val index: Int,
    val stack: ItemStack = ItemStack.EMPTY,
    val fluidId: String? = null,
    val fluidNbt: JsonElement? = null,
    val amount: Long = 0,
    val capacity: Long = 0,
)

/**
 * Item storage. `storage.insert/extract` and the `vanilla:inventory` / `fabric:item` handles go
 * through this; the JSON DTO is built by the shared code in `StorageOps`.
 */
interface ItemStorage {
    /** Implementation label for diagnostics (`SlottedStorage`, `InvWrapper`, `Container`, …). */
    val typeName: String
    val slotCount: Int
    val supportsInsertion: Boolean
    val supportsExtraction: Boolean

    /** One entry per slot the implementation exposes, in index order. */
    fun views(): List<StorageView>

    /** Insert up to [maxAmount] of [stack]; [simulate] must not change anything. Returns the amount moved. */
    fun insert(stack: ItemStack, maxAmount: Long, simulate: Boolean): Long

    /** Extract up to [maxAmount] of items matching [exemplar]; returns the amount moved. */
    fun extract(exemplar: ItemStack, maxAmount: Long, simulate: Boolean): Long

    /**
     * When this storage is hosted by an item stack (bucket, battery, backpack), the stack as it
     * looks after the operation — reported as `targetAfter`. Null when not tracked.
     */
    fun itemAfter(): ItemStack? = null
}

/**
 * Fluid storage. Two index spaces, mirroring the two RPC groups:
 *  - [parts] / [partAt] are what `fluid.*` addresses (`--index`): a composite storage exposes
 *    one part per tank, a single-variant storage exposes exactly one even when empty.
 *  - [views] is what `storage.*` reports for the `fabric:fluid` handle.
 */
interface FluidStorage {
    val typeName: String
    val tankCount: Int
    val supportsInsertion: Boolean
    val supportsExtraction: Boolean

    /** Tanks as `fluid.*` indexes them (position in this list = `--index`). */
    fun parts(): List<StorageView>

    /** Operable sub-storage for part [index], or null when out of range. */
    fun partAt(index: Int): FluidStorage?

    /** Tanks as `storage.*` reports them (`{index, fluid, nbt?, amount, capacity}`). */
    fun views(): List<StorageView>

    fun insert(fluid: Fluid, maxAmount: Long, simulate: Boolean): Long

    fun extract(fluid: Fluid, maxAmount: Long, simulate: Boolean): Long

    /**
     * Extract whatever this tank currently holds, including any component patch — what
     * `fluid.extract` (which names no fluid) means. Returns the amount moved.
     */
    fun extractCurrent(maxAmount: Long, simulate: Boolean): Long

    fun itemAfter(): ItemStack? = null
}

/** Energy buffer (Fabric: Team Reborn Energy when present; NeoForge: `IEnergyStorage`). */
interface EnergyStorage {
    val typeName: String
    val amount: Long
    val capacity: Long
    val supportsInsertion: Boolean
    val supportsExtraction: Boolean

    fun insert(maxAmount: Long, simulate: Boolean): Long

    fun extract(maxAmount: Long, simulate: Boolean): Long

    fun itemAfter(): ItemStack? = null
}

/**
 * Storage lookup seam. Exactly one implementation per loader:
 *  - Fabric:   Transfer API (`ItemStorage.SIDED` / `FluidStorage.SIDED` / `EnergyStorage`), with
 *              `ContainerItemContext` for item-hosted storages
 *  - NeoForge: capabilities (`Capabilities.ItemHandler.BLOCK` / `FluidHandler.BLOCK` /
 *              `EnergyStorage.BLOCK`, plus the `.ITEM` variants for item-hosted storages)
 *
 * Handles are *snapshots*: look one up, use it, drop it. Block lookups take the level, position
 * and (nullable) face; item lookups take the stack the storage belongs to.
 */
interface StorageApi {
    fun itemStorage(level: ServerLevel, pos: BlockPos, side: Direction?): ItemStorage?

    /** Bridge a vanilla [Container] (entity inventories, `vanilla:inventory`) as an item storage. */
    fun itemStorageOf(container: Container, side: Direction?): ItemStorage

    /** Item storage hosted by an item stack; null when the loader has none (both loaders: unused today). */
    fun itemStorageOfItem(stack: ItemStack): ItemStorage?

    fun fluidStorage(level: ServerLevel, pos: BlockPos, side: Direction?): FluidStorage?

    fun fluidStorageOfItem(stack: ItemStack): FluidStorage?

    fun energyStorage(level: ServerLevel, pos: BlockPos, side: Direction?): EnergyStorage?

    fun energyStorageOfItem(stack: ItemStack): EnergyStorage?

    // ------------------------------------------------------------ atomic moves

    /**
     * Move up to [amount] between two handles **this same implementation produced**, inside one
     * loader-native transaction, or return `null` when the loader has no such mechanism.
     *
     * Why this exists: a transaction-free move can only *stage* the decision with dry runs, and a
     * race (or a destination that accepts less than it promised) leaves the surplus at the source
     * only if the source takes it back — a loss the Fabric Transfer API never had, because it
     * aborts the whole `Transaction`. Implementations with transactions must return the moved
     * amount and leave **both** sides untouched when the destination cannot take everything.
     *
     * Returning `null` (NeoForge capabilities commit per call, or handles from another
     * implementation) makes the caller fall back to the documented best-effort path in
     * [com.mcdebug.api.StorageOps].
     */
    fun moveItemsAtomically(
        from: ItemStorage,
        to: ItemStorage,
        stack: ItemStack,
        amount: Long,
        simulate: Boolean,
    ): Long? = null

    /** See [moveItemsAtomically] — fluids use the same all-or-nothing rule. */
    fun moveFluidsAtomically(
        from: FluidStorage,
        to: FluidStorage,
        fluid: Fluid,
        amount: Long,
        simulate: Boolean,
    ): Long? = null
}
