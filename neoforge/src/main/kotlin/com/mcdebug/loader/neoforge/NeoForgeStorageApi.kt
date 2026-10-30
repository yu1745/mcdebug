package com.mcdebug.loader.neoforge

import com.mcdebug.loader.EnergyStorage
import com.mcdebug.loader.FluidStorage
import com.mcdebug.loader.ItemStorage
import com.mcdebug.loader.StorageApi
import com.mcdebug.loader.StorageView
import com.mcdebug.loader.fluidComponentsToJson
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.material.Fluid
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.energy.IEnergyStorage
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.FluidUtil
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.ItemHandlerHelper
import net.neoforged.neoforge.items.wrapper.InvWrapper
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper

/**
 * NeoForge implementation of the storage seam: block/item capabilities
 * (`Capabilities.ItemHandler` / `FluidHandler` / `EnergyStorage`) instead of the Fabric
 * Transfer API.
 *
 * Semantic differences against the Fabric implementation (both are documented in
 * `docs/plan-neoforge-port.md`):
 *  - amounts are **mB** here and **droplets** on Fabric (reported by `server.status.fluidUnit`);
 *  - `IFluidHandler` is storage-wide, so `fluid.*`'s `--index` selects the tank to *report*, while
 *    fill/drain go through the handler, which routes to its own tanks;
 *  - `IItemHandler`/`IFluidHandler` have no `supportsInsertion`/`supportsExtraction`; they are
 *    derived from the tank/slot state (see [NeoForgeFluidStorage]).
 */
class NeoForgeStorageApi : StorageApi {
    override fun itemStorage(level: ServerLevel, pos: BlockPos, side: Direction?): ItemStorage? =
        NeoCapabilities.block(Capabilities.ItemHandler.BLOCK, level, pos, side)?.let(::NeoForgeItemStorage)

    override fun itemStorageOf(container: Container, side: Direction?): ItemStorage =
        // Parity with Fabric's `InventoryStorage.of(container, side)`: a *sided* container answers
        // per-face slot rules instead of the unsided set (`WorldlyContainer` is NeoForge's
        // `SidedInventory`); everything else is unsided, exactly like Fabric's fallback.
        NeoForgeItemStorage(
            if (side != null && container is WorldlyContainer) SidedInvWrapper(container, side)
            else InvWrapper(container),
        )

    /** Parity with Fabric: `storage.*` does not expose item-in-item storages. */
    override fun itemStorageOfItem(stack: ItemStack): ItemStorage? = null

    override fun fluidStorage(level: ServerLevel, pos: BlockPos, side: Direction?): FluidStorage? =
        NeoCapabilities.block(Capabilities.FluidHandler.BLOCK, level, pos, side)?.let(::NeoForgeFluidStorage)

    override fun fluidStorageOfItem(stack: ItemStack): FluidStorage? {
        // Registered item capabilities first, then FluidUtil (which covers vanilla buckets).
        val handler: IFluidHandler? = NeoCapabilities.item(Capabilities.FluidHandler.ITEM, stack)
            ?: FluidUtil.getFluidHandler(stack).orElse(null)
        return handler?.let(::NeoForgeFluidStorage)
    }

    override fun energyStorage(level: ServerLevel, pos: BlockPos, side: Direction?): EnergyStorage? =
        NeoCapabilities.block(Capabilities.EnergyStorage.BLOCK, level, pos, side)?.let(::NeoForgeEnergyStorage)

    override fun energyStorageOfItem(stack: ItemStack): EnergyStorage? =
        NeoCapabilities.item(Capabilities.EnergyStorage.ITEM, stack)?.let { NeoForgeEnergyStorage(it, stack) }

    // ---------------------------------------------------------------- item storages

    private class NeoForgeItemStorage(private val handler: IItemHandler) : ItemStorage {
        private companion object {
            /** Vanilla default; an empty slot has no item to ask, and Fabric reports the same. */
            const val DEFAULT_MAX_STACK_SIZE = 64
        }

        override val typeName: String = handler.javaClass.simpleName
        override val slotCount: Int = handler.slots
        override val supportsInsertion: Boolean = true
        override val supportsExtraction: Boolean = true

        override fun views(): List<StorageView> =
            (0 until handler.slots).map { i ->
                val stack = handler.getStackInSlot(i)
                StorageView(
                    index = i,
                    stack = stack,
                    amount = stack.count.toLong(),
                    // `IItemHandler.getSlotLimit` is the slot's own limit, *not* what a stack can
                    // hold: `InvWrapper` forwards to `Container.getMaxStackSize()`, which vanilla
                    // containers leave at 99. Reporting 99 for a chest slot is both wrong (nothing
                    // can put 99 items there) and a cross-loader mismatch — Fabric's Transfer API
                    // reports 64. The effective capacity is the smaller of the two.
                    capacity = minOf(
                        handler.getSlotLimit(i).toLong(),
                        (if (stack.isEmpty) DEFAULT_MAX_STACK_SIZE else stack.maxStackSize).toLong(),
                    ),
                )
            }

        override fun insert(stack: ItemStack, maxAmount: Long, simulate: Boolean): Long {
            if (stack.isEmpty || maxAmount <= 0) return 0
            // `stack` only identifies the item; how many to move is `maxAmount` — the Fabric
            // implementation does the same (`storage.insert(ItemVariant.of(stack), maxAmount, tx)`),
            // so an exemplar stack of size 1 must not cap the transfer at 1.
            val wanted = stack.copyWithCount(minOf(maxAmount, Int.MAX_VALUE.toLong()).toInt())
            val remainder = ItemHandlerHelper.insertItem(handler, wanted, simulate)
            return (wanted.count - remainder.count).toLong()
        }

        override fun extract(exemplar: ItemStack, maxAmount: Long, simulate: Boolean): Long {
            if (exemplar.isEmpty || maxAmount <= 0) return 0
            var remaining = minOf(maxAmount, Int.MAX_VALUE.toLong()).toInt()
            var moved = 0
            for (i in 0 until handler.slots) {
                if (remaining <= 0) break
                val inSlot = handler.getStackInSlot(i)
                if (inSlot.isEmpty || !ItemStack.isSameItemSameComponents(inSlot, exemplar)) continue
                val taken = handler.extractItem(i, remaining, simulate)
                if (taken.isEmpty) continue
                moved += taken.count
                remaining -= taken.count
            }
            return moved.toLong()
        }
    }

    // ---------------------------------------------------------------- fluid storages

    private class NeoForgeFluidStorage(private val handler: IFluidHandler) : FluidStorage {
        override val typeName: String = handler.javaClass.simpleName
        override val tankCount: Int = handler.tanks

        /**
         * Derived: `IFluidHandler` has no capability flags. These describe the **current state**
         * (`false` for a full tank even though it can be drained) — Fabric's flags are capability
         * flags, so never compare the two across loaders.
         *
         * A tank reporting `capacity <= 0` means "capacity unknown/unlimited", not "no room":
         * counting it as full made a storage that accepts fluid report `supportsInsertion:false`.
         */
        override val supportsInsertion: Boolean =
            (0 until handler.tanks).any {
                val capacity = handler.getTankCapacity(it)
                capacity <= 0 || handler.getFluidInTank(it).amount < capacity
            }

        override val supportsExtraction: Boolean =
            (0 until handler.tanks).any { !handler.getFluidInTank(it).isEmpty }

        override fun parts(): List<StorageView> = views()

        /**
         * NeoForge handlers are storage-wide: the index is validated, then the same handler is
         * returned, so fill/drain follow the handler's own tank routing.
         */
        override fun partAt(index: Int): FluidStorage? = if (index in 0 until handler.tanks) this else null

        override fun views(): List<StorageView> =
            (0 until handler.tanks).map { i ->
                val stack = handler.getFluidInTank(i)
                StorageView(
                    index = i,
                    fluidId = if (stack.isEmpty) null else BuiltInRegistries.FLUID.getKey(stack.fluid).toString(),
                    fluidNbt = if (!stack.isEmpty && !stack.componentsPatch.isEmpty) fluidComponentsToJson(stack.componentsPatch) else null,
                    amount = stack.amount.toLong(),
                    capacity = handler.getTankCapacity(i).toLong(),
                )
            }

        override fun insert(fluid: Fluid, maxAmount: Long, simulate: Boolean): Long {
            if (maxAmount <= 0) return 0
            val filled = handler.fill(FluidStack(fluid, clamp(maxAmount)), action(simulate))
            return filled.toLong()
        }

        override fun extract(fluid: Fluid, maxAmount: Long, simulate: Boolean): Long {
            if (maxAmount <= 0) return 0
            val drained = handler.drain(FluidStack(fluid, clamp(maxAmount)), action(simulate))
            return drained.amount.toLong()
        }

        override fun extractCurrent(maxAmount: Long, simulate: Boolean): Long {
            if (maxAmount <= 0) return 0
            val drained = handler.drain(clamp(maxAmount), action(simulate))
            return drained.amount.toLong()
        }

        override fun itemAfter(): ItemStack? = (handler as? IFluidHandlerItem)?.container

        private fun clamp(amount: Long): Int = minOf(amount, Int.MAX_VALUE.toLong()).toInt()

        private fun action(simulate: Boolean): FluidAction = if (simulate) FluidAction.SIMULATE else FluidAction.EXECUTE
    }

    // ---------------------------------------------------------------- energy

    /**
     * [host] is the stack the capability was looked up on (item-hosted storages only): the KDoc on
     * [StorageApi.energyStorageOfItem] requires `itemAfter` to report that stack **after** the
     * operation, which is how a battery item's charge is observed. Without it the item-hosted
     * `targetAfter` was silently missing on NeoForge (Fabric reads it back via its item context).
     */
    private class NeoForgeEnergyStorage(
        private val storage: IEnergyStorage,
        private val host: ItemStack? = null,
    ) : EnergyStorage {
        override val typeName: String = storage.javaClass.simpleName
        override val amount: Long = storage.energyStored.toLong()
        override val capacity: Long = storage.maxEnergyStored.toLong()
        override val supportsInsertion: Boolean = storage.canReceive()
        override val supportsExtraction: Boolean = storage.canExtract()

        override fun insert(maxAmount: Long, simulate: Boolean): Long =
            if (maxAmount <= 0) 0 else storage.receiveEnergy(clamp(maxAmount), simulate).toLong()

        override fun extract(maxAmount: Long, simulate: Boolean): Long =
            if (maxAmount <= 0) 0 else storage.extractEnergy(clamp(maxAmount), simulate).toLong()

        override fun itemAfter(): ItemStack? = host?.takeUnless { it.isEmpty }?.copy()

        private fun clamp(amount: Long): Int = minOf(amount, Int.MAX_VALUE.toLong()).toInt()
    }
}
