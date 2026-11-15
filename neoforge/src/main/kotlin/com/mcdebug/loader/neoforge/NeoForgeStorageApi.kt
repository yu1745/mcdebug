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
import net.neoforged.neoforge.transfer.ResourceHandler
import net.neoforged.neoforge.transfer.access.ItemAccess
import net.neoforged.neoforge.transfer.energy.EnergyHandler
import net.neoforged.neoforge.transfer.fluid.FluidResource
import net.neoforged.neoforge.transfer.item.ItemResource
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper
import net.neoforged.neoforge.transfer.resource.Resource
import net.neoforged.neoforge.transfer.transaction.Transaction

/** Minecraft 26.3 NeoForge transfer API. Fluid amounts remain native mB. */
class NeoForgeStorageApi : StorageApi {
    override fun itemStorage(level: ServerLevel, pos: BlockPos, side: Direction?): ItemStorage? =
        NeoCapabilities.block(Capabilities.Item.BLOCK, level, pos, side)?.let(::NeoItemStorage)

    override fun itemStorageOf(container: Container, side: Direction?): ItemStorage = NeoItemStorage(
        if (side != null && container is WorldlyContainer) WorldlyContainerWrapper(container, side)
        else VanillaContainerWrapper.of(container),
    )

    override fun itemStorageOfItem(stack: ItemStack): ItemStorage? = null

    override fun fluidStorage(level: ServerLevel, pos: BlockPos, side: Direction?): FluidStorage? =
        NeoCapabilities.block(Capabilities.Fluid.BLOCK, level, pos, side)?.let { NeoFluidStorage(it) }

    override fun fluidStorageOfItem(stack: ItemStack): FluidStorage? {
        if (stack.isEmpty) return null
        val access = itemAccess(stack)
        return access.getCapability(Capabilities.Fluid.ITEM)?.let { NeoFluidStorage(it, access) }
    }

    override fun energyStorage(level: ServerLevel, pos: BlockPos, side: Direction?): EnergyStorage? =
        NeoCapabilities.block(Capabilities.Energy.BLOCK, level, pos, side)?.let { NeoEnergyStorage(it) }

    override fun energyStorageOfItem(stack: ItemStack): EnergyStorage? {
        if (stack.isEmpty) return null
        val access = itemAccess(stack)
        return access.getCapability(Capabilities.Energy.ITEM)?.let { NeoEnergyStorage(it, access) }
    }

    // A real, transactional item location lets a filled bucket become an empty bucket.
    private fun itemAccess(stack: ItemStack): ItemAccess {
        val holder = ItemStacksResourceHandler(1)
        holder.set(0, ItemResource.of(stack), stack.count)
        return ItemAccess.forHandlerIndexStrict(holder, 0)
    }

    override fun moveItemsAtomically(from: ItemStorage, to: ItemStorage, stack: ItemStack, amount: Long, simulate: Boolean): Long? {
        val source = (from as? NeoItemStorage)?.handler ?: return null
        val sink = (to as? NeoItemStorage)?.handler ?: return null
        return atomicMove(source, sink, ItemResource.of(stack), amount, simulate)
    }

    override fun moveFluidsAtomically(from: FluidStorage, to: FluidStorage, fluid: Fluid, amount: Long, simulate: Boolean): Long? {
        val source = (from as? NeoFluidStorage)?.handler ?: return null
        val sink = (to as? NeoFluidStorage)?.handler ?: return null
        return atomicMove(source, sink, FluidResource.of(fluid), amount, simulate)
    }

    private fun <T : Resource> atomicMove(from: ResourceHandler<T>, to: ResourceHandler<T>, resource: T, amount: Long, simulate: Boolean): Long =
        Transaction.openRoot().use { tx ->
            val available = Transaction.open(tx).use { nested -> from.extract(resource, clamp(amount), nested) }
            if (available <= 0) return@use 0L
            val accepted = to.insert(resource, available, tx)
            val extracted = from.extract(resource, accepted, tx)
            val moved = if (accepted == extracted) accepted else 0
            if (!simulate && moved > 0) tx.commit()
            moved.toLong()
        }

    private class NeoItemStorage(val handler: ResourceHandler<ItemResource>) : ItemStorage {
        override val typeName: String get() = handler.javaClass.simpleName
        override val slotCount: Int get() = handler.size()
        override val supportsInsertion = true
        override val supportsExtraction = true

        override fun views(): List<StorageView> = (0 until handler.size()).map { index ->
            val resource = handler.getResource(index)
            val count = handler.getAmountAsInt(index)
            StorageView(index, stack = resource.toStack(count), amount = count.toLong(),
                capacity = minOf(handler.getCapacityAsLong(index, resource), if (resource.isEmpty) 64L else resource.maxStackSize.toLong()))
        }

        override fun insert(stack: ItemStack, maxAmount: Long, simulate: Boolean): Long =
            if (stack.isEmpty || maxAmount <= 0) 0 else transaction(simulate) { handler.insert(ItemResource.of(stack), clamp(maxAmount), it) }

        override fun extract(exemplar: ItemStack, maxAmount: Long, simulate: Boolean): Long =
            if (exemplar.isEmpty || maxAmount <= 0) 0 else transaction(simulate) { handler.extract(ItemResource.of(exemplar), clamp(maxAmount), it) }
    }

    private class NeoFluidStorage(val handler: ResourceHandler<FluidResource>, private val host: ItemAccess? = null) : FluidStorage {
        override val typeName: String get() = handler.javaClass.simpleName
        override val tankCount: Int get() = handler.size()
        override val supportsInsertion: Boolean get() = (0 until handler.size()).any {
            val capacity = handler.getCapacityAsLong(it, handler.getResource(it))
            capacity <= 0 || handler.getAmountAsLong(it) < capacity
        }
        override val supportsExtraction: Boolean get() = (0 until handler.size()).any { handler.getAmountAsLong(it) > 0 }
        override fun parts(): List<StorageView> = views()
        // Keep the 1.21 NeoForge contract: index selects the reported tank; the handler routes transfers.
        override fun partAt(index: Int): FluidStorage? = if (index in 0 until handler.size()) this else null

        override fun views(): List<StorageView> = (0 until handler.size()).map { index ->
            val resource = handler.getResource(index)
            StorageView(index, fluidId = if (resource.isEmpty) null else BuiltInRegistries.FLUID.getKey(resource.fluid).toString(),
                fluidNbt = if (resource.isEmpty || resource.componentsPatch.isEmpty) null else fluidComponentsToJson(resource.componentsPatch),
                amount = handler.getAmountAsLong(index), capacity = handler.getCapacityAsLong(index, resource))
        }

        override fun insert(fluid: Fluid, maxAmount: Long, simulate: Boolean): Long =
            if (maxAmount <= 0) 0 else transaction(simulate) { handler.insert(FluidResource.of(fluid), clamp(maxAmount), it) }

        override fun extract(fluid: Fluid, maxAmount: Long, simulate: Boolean): Long =
            if (maxAmount <= 0) 0 else transaction(simulate) { handler.extract(FluidResource.of(fluid), clamp(maxAmount), it) }

        override fun extractCurrent(maxAmount: Long, simulate: Boolean): Long {
            val index = (0 until handler.size()).firstOrNull { !handler.getResource(it).isEmpty } ?: return 0
            return if (maxAmount <= 0) 0 else transaction(simulate) { handler.extract(handler.getResource(index), clamp(maxAmount), it) }
        }

        override fun itemAfter(): ItemStack? = host?.let { it.resource.toStack(it.amount) }
    }

    private class NeoEnergyStorage(private val handler: EnergyHandler, private val host: ItemAccess? = null) : EnergyStorage {
        override val typeName: String get() = handler.javaClass.simpleName
        override val amount: Long get() = handler.amountAsLong
        override val capacity: Long get() = handler.capacityAsLong
        override val supportsInsertion: Boolean get() = insert(1, true) > 0
        override val supportsExtraction: Boolean get() = extract(1, true) > 0
        override fun insert(maxAmount: Long, simulate: Boolean): Long =
            if (maxAmount <= 0) 0 else transaction(simulate) { handler.insert(clamp(maxAmount), it) }
        override fun extract(maxAmount: Long, simulate: Boolean): Long =
            if (maxAmount <= 0) 0 else transaction(simulate) { handler.extract(clamp(maxAmount), it) }
        override fun itemAfter(): ItemStack? = host?.let { it.resource.toStack(it.amount) }
    }

    private companion object {
        fun clamp(amount: Long): Int = amount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        fun transaction(simulate: Boolean, action: (Transaction) -> Int): Long = Transaction.openRoot().use { tx ->
            val moved = action(tx)
            if (!simulate) tx.commit()
            moved.toLong()
        }
    }
}
