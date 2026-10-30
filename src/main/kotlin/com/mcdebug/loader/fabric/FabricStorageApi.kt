package com.mcdebug.loader.fabric

import com.mcdebug.loader.EnergyStorage
import com.mcdebug.loader.FluidStorage
import com.mcdebug.loader.ItemStorage
import com.mcdebug.loader.StorageApi
import com.mcdebug.loader.StorageView
import com.mcdebug.loader.fluidComponentsToJson
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage as FabricFluidStorageApi
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage as FabricItemStorageApi
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView as FabricStorageView
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.Fluid
import java.lang.reflect.Method

/**
 * Fabric implementation of the storage seam: the Transfer API (`ItemStorage.SIDED`,
 * `FluidStorage.SIDED`, item-hosted `ContainerItemContext`) plus Team Reborn Energy through
 * reflection (so the mod keeps working when that API is absent).
 *
 * Every transfer runs in a `Transaction`, so `simulate` is a real dry run (aborted transaction)
 * and execute is atomic — the shared code only sees moved amounts.
 */
class FabricStorageApi : StorageApi {
    override fun itemStorage(level: ServerLevel, pos: BlockPos, side: Direction?): ItemStorage? =
        FabricItemStorageApi.SIDED.find(level, pos, side)?.let(::FabricItemStorage)

    override fun itemStorageOf(container: Container, side: Direction?): ItemStorage =
        FabricItemStorage(InventoryStorage.of(container, side) as Storage<ItemVariant>)

    /** Fabric Transfer API has an item-in-item lookup, but `storage.*` never exposed it (parity). */
    override fun itemStorageOfItem(stack: ItemStack): ItemStorage? = null

    override fun fluidStorage(level: ServerLevel, pos: BlockPos, side: Direction?): FluidStorage? =
        FabricFluidStorageApi.SIDED.find(level, pos, side)?.let { FabricFluidStorage(it) }

    override fun fluidStorageOfItem(stack: ItemStack): FluidStorage? {
        val context = itemContext(stack, mutable = true)
        return context.find(FabricFluidStorageApi.ITEM)?.let { FabricFluidStorage(it, context) }
    }

    override fun energyStorage(level: ServerLevel, pos: BlockPos, side: Direction?): EnergyStorage? =
        EnergyReflection.find(level, pos, side)?.let { FabricEnergyStorage(it) }

    override fun energyStorageOfItem(stack: ItemStack): EnergyStorage? {
        val context = itemContext(stack, mutable = true)
        return EnergyReflection.findItem(context)?.let { FabricEnergyStorage(it, context) }
    }

    // ------------------------------------------------------------ atomic moves

    /**
     * Both moves below reproduce what the pre-port Fabric-only `StorageOps.transferBetween` did:
     * decide the amount with a nested (always aborted) transaction, stage insert+extract in the
     * outer transaction, and commit **only** if the destination accepted exactly what the source
     * gave up. Otherwise the outer transaction is closed without committing and nothing moves —
     * which is the whole point of the hook (see [StorageApi.moveItemsAtomically]).
     */
    override fun moveItemsAtomically(
        from: ItemStorage,
        to: ItemStorage,
        stack: ItemStack,
        amount: Long,
        simulate: Boolean,
    ): Long? {
        val source = (from as? FabricItemStorage)?.raw ?: return null
        val sink = (to as? FabricItemStorage)?.raw ?: return null
        return atomicMove(source, sink, ItemVariant.of(stack), amount, simulate)
    }

    override fun moveFluidsAtomically(
        from: FluidStorage,
        to: FluidStorage,
        fluid: Fluid,
        amount: Long,
        simulate: Boolean,
    ): Long? {
        val source = (from as? FabricFluidStorage)?.raw ?: return null
        val sink = (to as? FabricFluidStorage)?.raw ?: return null
        return atomicMove(source, sink, FluidVariant.of(fluid), amount, simulate)
    }

    private fun <T : Any> atomicMove(
        from: Storage<T>,
        to: Storage<T>,
        resource: T,
        amount: Long,
        simulate: Boolean,
    ): Long = Transaction.openOuter().use { tx ->
        val canExtract = Transaction.openNested(tx).use { nested -> from.extract(resource, amount, nested) }
        if (canExtract <= 0) return@use 0
        val accepted = to.insert(resource, canExtract, tx)
        val extracted = from.extract(resource, accepted, tx)
        val moved = if (accepted == extracted) accepted else 0
        if (!simulate && moved > 0) tx.commit()
        moved
    }

    // ---------------------------------------------------------------- item storages

    private class FabricItemStorage(private val storage: Storage<ItemVariant>) : ItemStorage {
        /** Raw Transfer API handle, for the atomic-move hook (the class is private to this file). */
        val raw: Storage<ItemVariant> get() = storage

        override val typeName: String =
            if (storage is SlottedStorage<*>) "SlottedStorage" else storage.javaClass.simpleName

        override val slotCount: Int =
            if (storage is SlottedStorage<*>) (storage as SlottedStorage<ItemVariant>).slotCount else storage.count()

        override val supportsInsertion: Boolean = storage.supportsInsertion()
        override val supportsExtraction: Boolean = storage.supportsExtraction()

        override fun views(): List<StorageView> {
            val out = ArrayList<StorageView>()
            if (storage is SlottedStorage<*>) {
                val slotted = storage as SlottedStorage<ItemVariant>
                for (i in 0 until slotted.slotCount) out.add(viewOf(i, slotted.getSlot(i)))
            } else {
                var i = 0
                for (view in storage) {
                    if (!view.isResourceBlank || view.amount > 0) out.add(viewOf(i, view))
                    i++
                }
            }
            return out
        }

        private fun viewOf(index: Int, view: FabricStorageView<ItemVariant>): StorageView {
            val variant = view.resource
            val stack = if (variant.isBlank) ItemStack.EMPTY
            else variant.toStack(view.amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            return StorageView(index = index, stack = stack, amount = view.amount, capacity = view.capacity)
        }

        override fun insert(stack: ItemStack, maxAmount: Long, simulate: Boolean): Long =
            transfer(simulate) { tx -> storage.insert(ItemVariant.of(stack), maxAmount, tx) }

        override fun extract(exemplar: ItemStack, maxAmount: Long, simulate: Boolean): Long =
            transfer(simulate) { tx -> storage.extract(ItemVariant.of(exemplar), maxAmount, tx) }
    }

    // ---------------------------------------------------------------- fluid storages

    private class FabricFluidStorage(
        private val storage: Storage<FluidVariant>,
        private val context: ContainerItemContext? = null,
    ) : FluidStorage {
        /** Raw Transfer API handle, for the atomic-move hook (the class is private to this file). */
        val raw: Storage<FluidVariant> get() = storage

        override val typeName: String = when (storage) {
            is SingleVariantStorage<*> -> "SingleVariantStorage"
            is CombinedStorage<*, *> -> "CombinedStorage"
            else -> "Other"
        }

        override val tankCount: Int = storage.count()
        override val supportsInsertion: Boolean = storage.supportsInsertion()
        override val supportsExtraction: Boolean = storage.supportsExtraction()

        /** `fluid.*` index space: a single-variant storage always shows one part, even when empty. */
        @Suppress("UNCHECKED_CAST")
        override fun parts(): List<StorageView> {
            if (storage is SingleVariantStorage<*>) {
                val s = storage as SingleVariantStorage<FluidVariant>
                return listOf(partView(0, s.variant, s.amount, s.getCapacity()))
            }
            if (storage is CombinedStorage<*, *>) {
                val rawParts: List<*> = storage.parts
                return rawParts.mapIndexedNotNull { i, part ->
                    val p = part as? Storage<FluidVariant> ?: return@mapIndexedNotNull null
                    if (p is SingleVariantStorage<*>) {
                        val s = p as SingleVariantStorage<FluidVariant>
                        partView(i, s.variant, s.amount, s.getCapacity())
                    } else {
                        val view = p.iterator().asSequence().firstOrNull { !it.isResourceBlank }
                        if (view == null) partView(i, FluidVariant.blank(), 0L, 0L)
                        else partView(i, view.resource, view.amount, view.capacity)
                    }
                }
            }
            var i = 0
            return storage.iterator().asSequence()
                .filter { !it.isResourceBlank }
                .map { view -> partView(i++, view.resource, view.amount, view.capacity) }
                .toList()
        }

        @Suppress("UNCHECKED_CAST")
        override fun partAt(index: Int): FluidStorage? {
            if (storage is CombinedStorage<*, *>) {
                val rawParts: List<*> = storage.parts
                val target = rawParts.getOrNull(index) as? Storage<FluidVariant> ?: return null
                return FabricFluidStorage(target, context)
            }
            return if (index == 0) this else null
        }

        /** `storage.*` reports raw iterator views (index = iterator position), filtered as before. */
        override fun views(): List<StorageView> {
            val out = ArrayList<StorageView>()
            var i = 0
            for (view in storage) {
                if (!view.isResourceBlank || view.amount > 0 || view.capacity > 0) {
                    out.add(partView(i, view.resource, view.amount, view.capacity))
                }
                i++
            }
            return out
        }

        private fun partView(index: Int, variant: FluidVariant, amount: Long, capacity: Long): StorageView =
            StorageView(
                index = index,
                fluidId = if (variant.isBlank) null else BuiltInRegistries.FLUID.getKey(variant.fluid).toString(),
                fluidNbt = if (!variant.isBlank && variant.hasComponents()) fluidComponentsToJson(variant.components) else null,
                amount = amount,
                capacity = capacity,
            )

        override fun insert(fluid: Fluid, maxAmount: Long, simulate: Boolean): Long =
            transfer(simulate) { tx -> storage.insert(FluidVariant.of(fluid), maxAmount, tx) }

        override fun extract(fluid: Fluid, maxAmount: Long, simulate: Boolean): Long =
            transfer(simulate) { tx -> storage.extract(FluidVariant.of(fluid), maxAmount, tx) }

        /** Extract the exact variant this tank holds (components included). */
        @Suppress("UNCHECKED_CAST")
        override fun extractCurrent(maxAmount: Long, simulate: Boolean): Long {
            val s = storage
            if (s is SingleVariantStorage<*>) {
                @Suppress("UNCHECKED_CAST")
                val single = s as SingleVariantStorage<FluidVariant>
                if (single.variant.isBlank) return 0
                return transfer(simulate) { tx -> single.extract(single.variant, maxAmount, tx) }
            }
            if (s is CombinedStorage<*, *>) {
                val rawParts: List<*> = s.parts
                for (part in rawParts) {
                    val sub = part as? Storage<FluidVariant> ?: continue
                    val moved = FabricFluidStorage(sub, context).extractCurrent(maxAmount, simulate)
                    if (moved > 0) return moved
                }
                return 0
            }
            val view = s.iterator().asSequence().firstOrNull { !it.isResourceBlank } ?: return 0
            return transfer(simulate) { tx -> s.extract(view.resource, maxAmount, tx) }
        }

        override fun itemAfter(): ItemStack? = context?.let(::stackOf)
    }

    // ---------------------------------------------------------------- energy (Team Reborn, reflective)

    private class FabricEnergyStorage(
        private val storage: Any,
        private val context: ContainerItemContext? = null,
    ) : EnergyStorage {
        override val typeName: String = "TeamRebornEnergy"
        override val amount: Long = EnergyReflection.amount(storage)
        override val capacity: Long = EnergyReflection.capacity(storage)
        override val supportsInsertion: Boolean = EnergyReflection.supportsInsertion(storage)
        override val supportsExtraction: Boolean = EnergyReflection.supportsExtraction(storage)

        override fun insert(maxAmount: Long, simulate: Boolean): Long =
            EnergyReflection.insert(storage, maxAmount, simulate)

        override fun extract(maxAmount: Long, simulate: Boolean): Long =
            EnergyReflection.extract(storage, maxAmount, simulate)

        override fun itemAfter(): ItemStack? = context?.let(::stackOf)
    }

    /**
     * Team Reborn Energy is an optional API: it is reached reflectively so the mod keeps working
     * (energy handles simply absent) on instances that do not have it.
     */
    private object EnergyReflection {
        private val energyClass: Class<*>? by lazy {
            try {
                Class.forName("team.reborn.energy.api.EnergyStorage")
            } catch (_: ClassNotFoundException) {
                null
            }
        }

        private val sidedLookup: Any? by lazy { energyClass?.getField("SIDED")?.get(null) }
        private val itemLookup: Any? by lazy { energyClass?.getField("ITEM")?.get(null) }

        private val sidedFindMethod: Method? by lazy {
            sidedLookup?.javaClass?.methods?.firstOrNull { it.name == "find" && it.parameterCount == 3 }
        }

        private val itemFindMethod: Method? by lazy {
            itemLookup?.javaClass?.methods?.firstOrNull { it.name == "find" && it.parameterCount == 2 }
        }

        fun find(level: Level, pos: BlockPos, side: Direction?): Any? {
            val lookup = sidedLookup ?: return null
            val method = sidedFindMethod ?: return null
            return method.invoke(lookup, level, pos, side)
        }

        fun findItem(context: ContainerItemContext): Any? {
            val lookup = itemLookup ?: return null
            val method = itemFindMethod ?: return null
            if (context.itemVariant.isBlank) return null
            return method.invoke(lookup, context.itemVariant.toStack(context.amount.toInt()), context)
        }

        fun amount(storage: Any): Long = callLong(storage, "getAmount")
        fun capacity(storage: Any): Long = callLong(storage, "getCapacity")
        fun supportsInsertion(storage: Any): Boolean = callBoolean(storage, "supportsInsertion")
        fun supportsExtraction(storage: Any): Boolean = callBoolean(storage, "supportsExtraction")

        fun insert(storage: Any, amount: Long, simulate: Boolean): Long =
            Transaction.openOuter().use { tx ->
                val moved = callTransfer(storage, "insert", amount, tx)
                if (simulate) tx.abort() else tx.commit()
                moved
            }

        fun extract(storage: Any, amount: Long, simulate: Boolean): Long =
            Transaction.openOuter().use { tx ->
                val moved = callTransfer(storage, "extract", amount, tx)
                if (simulate) tx.abort() else tx.commit()
                moved
            }

        private fun callLong(storage: Any, name: String): Long =
            (storage.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(storage) as? Number)
                ?.toLong() ?: 0L

        private fun callBoolean(storage: Any, name: String): Boolean =
            storage.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(storage) as? Boolean
                ?: false

        private fun callTransfer(storage: Any, name: String, amount: Long, tx: Any): Long {
            val method = storage.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 2 } ?: return 0L
            return (method.invoke(storage, amount, tx) as? Number)?.toLong() ?: 0L
        }
    }

    private companion object {
        /**
         * A mutable one-slot container reproduces `ContainerItemContext.withInitial` (removed in
         * fabric-transfer for 1.21): the item-hosted storage can change the stack, and [stackOf]
         * reads the result back for `targetAfter`.
         */
        fun itemContext(stack: ItemStack, mutable: Boolean): ContainerItemContext =
            if (mutable) {
                val inv = SimpleContainer(1)
                inv.setItem(0, stack.copy())
                ContainerItemContext.ofSingleSlot(InventoryStorage.of(inv, null).getSlot(0))
            } else {
                ContainerItemContext.withConstant(stack.copy())
            }

        fun stackOf(context: ContainerItemContext): ItemStack {
            val variant = context.itemVariant
            return if (variant.isBlank) ItemStack.EMPTY
            else variant.toStack(context.amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
}

/** Run a Transfer-API transfer inside a transaction; `simulate` aborts it. */
private fun transfer(simulate: Boolean, body: (Transaction) -> Long): Long =
    Transaction.openOuter().use { tx ->
        val moved = body(tx)
        if (simulate) tx.abort() else tx.commit()
        moved
    }
