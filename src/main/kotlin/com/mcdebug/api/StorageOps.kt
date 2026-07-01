package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
import net.minecraft.entity.Entity
import net.minecraft.entity.InventoryOwner
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.fluid.Fluid
import net.minecraft.inventory.Inventory
import net.minecraft.inventory.SidedInventory
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.screen.ScreenHandler
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.World
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.CompletableFuture

object StorageOps : RpcHandlerGroup {
    internal const val HANDLE_VANILLA_INVENTORY = "vanilla:inventory"
    internal const val HANDLE_FABRIC_ITEM = "fabric:item"
    internal const val HANDLE_FABRIC_FLUID = "fabric:fluid"
    internal const val HANDLE_TEAM_REBORN_ENERGY = "teamreborn:energy"

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "list" to ::list,
        "get" to ::get,
        "insert" to ::insert,
        "extract" to ::extract,
        "transfer" to ::transfer,
    )

    private fun list(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val target = resolveTarget(server, p.requireObject("target"))
            val side = sideFromOpts(p, "side")
            JsonObject().apply { add("handles", handlesFor(target, side)) }
        }

    private fun get(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val target = resolveTarget(server, p.requireObject("target"))
            val side = sideFromOpts(p, "side")
            getHandleJson(target, side, p.requireString("handle"))
        }

    private fun insert(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val target = resolveTarget(server, p.requireObject("target"))
            val side = sideFromOpts(p, "side")
            val handle = p.requireString("handle")
            val resource = p.requireObject("resource")
            val amount = p.requireLong("amount")
            val simulate = p.getBoolOrFalse("simulate")
            when (kindOfHandle(handle)) {
                "item" -> insertItem(server, target, side, handle, resource, amount, simulate)
                "fluid" -> insertFluid(server, target, side, resource, amount, simulate)
                "energy" -> insertEnergy(target, side, amount, simulate)
                else -> throwStorageNotFound(handle, target)
            }
        }

    private fun extract(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val target = resolveTarget(server, p.requireObject("target"))
            val side = sideFromOpts(p, "side")
            val handle = p.requireString("handle")
            val resource = p.requireObject("resource")
            val amount = p.requireLong("amount")
            val simulate = p.getBoolOrFalse("simulate")
            when (kindOfHandle(handle)) {
                "item" -> extractItem(server, target, side, handle, resource, amount, simulate)
                "fluid" -> extractFluid(server, target, side, resource, amount, simulate)
                "energy" -> extractEnergy(target, side, amount, simulate)
                else -> throwStorageNotFound(handle, target)
            }
        }

    private fun transfer(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val from = resolveTarget(server, p.requireObject("from"))
            val to = resolveTarget(server, p.requireObject("to"))
            val resource = p.requireObject("resource")
            val kind = resource.requireString("kind")
            val amount = p.requireLong("amount")
            val fromSide = sideFromOpts(p, "fromSide")
            val toSide = sideFromOpts(p, "toSide")
            val simulate = p.getBoolOrFalse("simulate")
            when (kind) {
                "item" -> transferItems(server, from, to, fromSide, toSide, resource, amount, simulate)
                "fluid" -> transferFluids(server, from, to, fromSide, toSide, resource, amount, simulate)
                "energy" -> transferEnergy(from, to, fromSide, toSide, amount, simulate)
                else -> throw RpcException(RpcErrors.INVALID_PARAMS, "unknown resource kind: $kind")
            }
        }

    private fun insertItem(
        server: MinecraftServer,
        target: TargetRef,
        side: Direction?,
        handle: String,
        resource: JsonObject,
        amount: Long,
        simulate: Boolean,
    ): JsonElement {
        val stack = itemStackResource(server, resource, amount.toInt())
        val inserted = when (handle) {
            HANDLE_VANILLA_INVENTORY -> {
                val inv = inventoryFor(target) ?: throwStorageNotFound(handle, target)
                vanillaInsert(inv, sideForInventory(target, side), stack, simulate).toLong()
            }
            HANDLE_FABRIC_ITEM -> {
                val storage = itemStorageFor(target, side) ?: throwStorageNotFound(handle, target)
                transferInsert(storage, ItemVariant.of(stack), amount, simulate)
            }
            else -> throwStorageNotFound(handle, target)
        }
        return moveResult(handle, "item", amount, inserted, simulate, insertedKey = "inserted")
    }

    private fun extractItem(
        server: MinecraftServer,
        target: TargetRef,
        side: Direction?,
        handle: String,
        resource: JsonObject,
        amount: Long,
        simulate: Boolean,
    ): JsonElement {
        val stack = itemStackResource(server, resource, 1)
        val extracted = when (handle) {
            HANDLE_VANILLA_INVENTORY -> {
                val inv = inventoryFor(target) ?: throwStorageNotFound(handle, target)
                vanillaExtract(inv, sideForInventory(target, side), stack, amount.toInt(), simulate).toLong()
            }
            HANDLE_FABRIC_ITEM -> {
                val storage = itemStorageFor(target, side) ?: throwStorageNotFound(handle, target)
                transferExtract(storage, ItemVariant.of(stack), amount, simulate)
            }
            else -> throwStorageNotFound(handle, target)
        }
        return moveResult(handle, "item", amount, extracted, simulate, insertedKey = "extracted")
    }

    private fun insertFluid(
        server: MinecraftServer,
        target: TargetRef,
        side: Direction?,
        resource: JsonObject,
        amount: Long,
        simulate: Boolean,
    ): JsonElement {
        val variant = FluidVariant.of(resolveFluid(server, resource.requireString("fluid")))
        val endpoint = fluidEndpoint(target, side, mutableItem = true) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, target)
        val inserted = transferInsert(endpoint.storage, variant, amount, simulate)
        return moveResult(HANDLE_FABRIC_FLUID, "fluid", amount, inserted, simulate, "inserted").apply {
            endpoint.targetAfter()?.let { add("targetAfter", it) }
        }
    }

    private fun extractFluid(
        server: MinecraftServer,
        target: TargetRef,
        side: Direction?,
        resource: JsonObject,
        amount: Long,
        simulate: Boolean,
    ): JsonElement {
        val variant = FluidVariant.of(resolveFluid(server, resource.requireString("fluid")))
        val endpoint = fluidEndpoint(target, side, mutableItem = true) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, target)
        val extracted = transferExtract(endpoint.storage, variant, amount, simulate)
        return moveResult(HANDLE_FABRIC_FLUID, "fluid", amount, extracted, simulate, "extracted").apply {
            endpoint.targetAfter()?.let { add("targetAfter", it) }
        }
    }

    private fun insertEnergy(target: TargetRef, side: Direction?, amount: Long, simulate: Boolean): JsonElement {
        val endpoint = energyEndpoint(target, side, mutableItem = true) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, target)
        val inserted = EnergyReflection.insert(endpoint.storage, amount, simulate)
        return moveResult(HANDLE_TEAM_REBORN_ENERGY, "energy", amount, inserted, simulate, "inserted").apply {
            endpoint.targetAfter()?.let { add("targetAfter", it) }
        }
    }

    private fun extractEnergy(target: TargetRef, side: Direction?, amount: Long, simulate: Boolean): JsonElement {
        val endpoint = energyEndpoint(target, side, mutableItem = true) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, target)
        val extracted = EnergyReflection.extract(endpoint.storage, amount, simulate)
        return moveResult(HANDLE_TEAM_REBORN_ENERGY, "energy", amount, extracted, simulate, "extracted").apply {
            endpoint.targetAfter()?.let { add("targetAfter", it) }
        }
    }

    private fun transferItems(
        server: MinecraftServer,
        from: TargetRef,
        to: TargetRef,
        fromSide: Direction?,
        toSide: Direction?,
        resource: JsonObject,
        amount: Long,
        simulate: Boolean,
    ): JsonElement {
        val stack = itemStackResource(server, resource, 1)
        val fromStorage = itemStorageFor(from, fromSide) ?: throwStorageNotFound(HANDLE_FABRIC_ITEM, from)
        val toStorage = itemStorageFor(to, toSide) ?: throwStorageNotFound(HANDLE_FABRIC_ITEM, to)
        val moved = transferBetween(fromStorage, toStorage, ItemVariant.of(stack), amount, simulate)
        return transferResult("item", moved, amount, simulate)
    }

    private fun transferFluids(
        server: MinecraftServer,
        from: TargetRef,
        to: TargetRef,
        fromSide: Direction?,
        toSide: Direction?,
        resource: JsonObject,
        amount: Long,
        simulate: Boolean,
    ): JsonElement {
        val variant = FluidVariant.of(resolveFluid(server, resource.requireString("fluid")))
        val fromEndpoint = fluidEndpoint(from, fromSide, mutableItem = true) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, from)
        val toEndpoint = fluidEndpoint(to, toSide, mutableItem = true) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, to)
        val moved = transferBetween(fromEndpoint.storage, toEndpoint.storage, variant, amount, simulate)
        return transferResult("fluid", moved, amount, simulate).apply {
            fromEndpoint.targetAfter()?.let { add("fromAfter", it) }
            toEndpoint.targetAfter()?.let { add("toAfter", it) }
        }
    }

    private fun transferEnergy(
        from: TargetRef,
        to: TargetRef,
        fromSide: Direction?,
        toSide: Direction?,
        amount: Long,
        simulate: Boolean,
    ): JsonElement {
        val fromEndpoint = energyEndpoint(from, fromSide, mutableItem = true) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, from)
        val toEndpoint = energyEndpoint(to, toSide, mutableItem = true) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, to)
        val moved = EnergyReflection.transfer(fromEndpoint.storage, toEndpoint.storage, amount, simulate)
        return transferResult("energy", moved, amount, simulate).apply {
            fromEndpoint.targetAfter()?.let { add("fromAfter", it) }
            toEndpoint.targetAfter()?.let { add("toAfter", it) }
        }
    }

    private fun moveResult(
        handle: String,
        kind: String,
        requested: Long,
        moved: Long,
        simulate: Boolean,
        insertedKey: String,
    ): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", kind)
            addProperty("requested", requested)
            addProperty(insertedKey, moved)
            addProperty("remaining", requested - moved)
            addProperty("simulated", simulate)
        }

    private fun transferResult(kind: String, moved: Long, requested: Long, simulate: Boolean): JsonObject =
        JsonObject().apply {
            addProperty("kind", kind)
            addProperty("requested", requested)
            addProperty("transferred", moved)
            addProperty("remaining", requested - moved)
            addProperty("simulated", simulate)
        }

    private fun <T> transferInsert(storage: Storage<T>, resource: T, amount: Long, simulate: Boolean): Long {
        Transaction.openOuter().use { tx ->
            val inserted = storage.insert(resource, amount, tx)
            if (!simulate && inserted > 0) tx.commit()
            return inserted
        }
    }

    private fun <T> transferExtract(storage: Storage<T>, resource: T, amount: Long, simulate: Boolean): Long {
        Transaction.openOuter().use { tx ->
            val extracted = storage.extract(resource, amount, tx)
            if (!simulate && extracted > 0) tx.commit()
            return extracted
        }
    }

    private fun <T> transferBetween(from: Storage<T>, to: Storage<T>, resource: T, amount: Long, simulate: Boolean): Long {
        Transaction.openOuter().use { tx ->
            val canExtract = Transaction.openNested(tx).use { nested -> from.extract(resource, amount, nested) }
            val accepted = to.insert(resource, canExtract, tx)
            val extracted = from.extract(resource, accepted, tx)
            val moved = if (accepted == extracted) accepted else 0
            if (!simulate && moved > 0) tx.commit()
            return moved
        }
    }

    private fun vanillaInsert(inv: Inventory, side: Direction?, stack: ItemStack, simulate: Boolean): Int {
        if (stack.isEmpty) return 0
        var remaining = stack.count
        val slots = slotsFor(inv, side)

        for (slot in slots) {
            if (remaining <= 0) break
            val current = inv.getStack(slot)
            if (current.isEmpty || !ItemStack.canCombine(current, stack)) continue
            if (!canInsert(inv, slot, stack, side)) continue
            val space = minOf(current.maxCount, inv.maxCountPerStack) - current.count
            if (space <= 0) continue
            val moved = minOf(space, remaining)
            if (!simulate) {
                val next = current.copy()
                next.increment(moved)
                inv.setStack(slot, next)
            }
            remaining -= moved
        }

        for (slot in slots) {
            if (remaining <= 0) break
            if (!inv.getStack(slot).isEmpty) continue
            if (!canInsert(inv, slot, stack, side)) continue
            val moved = minOf(stack.maxCount, inv.maxCountPerStack, remaining)
            if (!simulate) {
                val next = stack.copy()
                next.count = moved
                inv.setStack(slot, next)
            }
            remaining -= moved
        }

        if (!simulate && remaining != stack.count) inv.markDirty()
        return stack.count - remaining
    }

    private fun vanillaExtract(inv: Inventory, side: Direction?, exemplar: ItemStack, amount: Int, simulate: Boolean): Int {
        if (exemplar.isEmpty || amount <= 0) return 0
        var remaining = amount
        for (slot in slotsFor(inv, side)) {
            if (remaining <= 0) break
            val current = inv.getStack(slot)
            if (current.isEmpty || !ItemStack.canCombine(current, exemplar)) continue
            if (!canExtract(inv, slot, current, side)) continue
            val moved = minOf(current.count, remaining)
            if (!simulate) {
                val next = current.copy()
                next.decrement(moved)
                inv.setStack(slot, if (next.isEmpty) ItemStack.EMPTY else next)
            }
            remaining -= moved
        }
        if (!simulate && remaining != amount) inv.markDirty()
        return amount - remaining
    }

    private fun slotsFor(inv: Inventory, side: Direction?): List<Int> {
        if (side != null && inv is SidedInventory) return inv.getAvailableSlots(side).toList()
        return (0 until inv.size()).toList()
    }

    private fun canInsert(inv: Inventory, slot: Int, stack: ItemStack, side: Direction?): Boolean {
        if (!inv.isValid(slot, stack)) return false
        return side == null || inv !is SidedInventory || inv.canInsert(slot, stack, side)
    }

    private fun canExtract(inv: Inventory, slot: Int, stack: ItemStack, side: Direction?): Boolean =
        side == null || inv !is SidedInventory || inv.canExtract(slot, stack, side)

    internal fun handlesFor(world: ServerWorld, pos: BlockPos, side: Direction?): JsonArray =
        handlesFor(TargetRef.Block(world, pos), side)

    private fun handlesFor(target: TargetRef, side: Direction?): JsonArray =
        JsonArray().apply {
            val inv = inventoryFor(target)
            if (inv != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_VANILLA_INVENTORY)
                    addProperty("kind", "item")
                    addProperty("slots", slotsFor(inv, sideForInventory(target, side)).size)
                })
            }

            val itemStorage = itemStorageFor(target, side)
            if (itemStorage != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_FABRIC_ITEM)
                    addProperty("kind", "item")
                    addProperty("slots", itemSlotCount(itemStorage))
                })
            }

            val fluid = fluidEndpoint(target, side, mutableItem = false)
            if (fluid != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_FABRIC_FLUID)
                    addProperty("kind", "fluid")
                    addProperty("tanks", fluidTankCount(fluid.storage))
                })
            }

            val energy = energyEndpoint(target, side, mutableItem = false)
            if (energy != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_TEAM_REBORN_ENERGY)
                    addProperty("kind", "energy")
                    addProperty("amount", EnergyReflection.amount(energy.storage))
                    addProperty("capacity", EnergyReflection.capacity(energy.storage))
                })
            }
        }

    internal fun getHandleJson(world: ServerWorld, pos: BlockPos, side: Direction?, handle: String): JsonObject =
        getHandleJson(TargetRef.Block(world, pos), side, handle)

    private fun getHandleJson(target: TargetRef, side: Direction?, handle: String): JsonObject =
        when (handle) {
            HANDLE_VANILLA_INVENTORY -> {
                val inv = inventoryFor(target) ?: throwStorageNotFound(handle, target)
                inventoryToJson(inv, sideForInventory(target, side), handle)
            }
            HANDLE_FABRIC_ITEM -> {
                val storage = itemStorageFor(target, side) ?: throwStorageNotFound(handle, target)
                itemStorageToJson(storage, handle)
            }
            HANDLE_FABRIC_FLUID -> {
                val storage = fluidEndpoint(target, side, mutableItem = false)?.storage ?: throwStorageNotFound(handle, target)
                fluidStorageToJson(storage, handle)
            }
            HANDLE_TEAM_REBORN_ENERGY -> {
                val storage = energyEndpoint(target, side, mutableItem = false)?.storage ?: throwStorageNotFound(handle, target)
                energyStorageToJson(storage, handle)
            }
            else -> throwStorageNotFound(handle, target)
        }

    internal fun blockResourcesToJson(world: ServerWorld, pos: BlockPos, side: Direction? = null): JsonObject =
        JsonObject().apply {
            add("handles", handlesFor(world, pos, side))
            val resources = JsonArray()
            for (handleEl in getAsJsonArray("handles")) {
                val handle = handleEl.asJsonObject.requireString("handle")
                resources.add(getHandleJson(world, pos, side, handle))
            }
            add("resources", resources)
        }

    private fun inventoryToJson(inv: Inventory, side: Direction?, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "item")
            addProperty("supportsInsertion", true)
            addProperty("supportsExtraction", true)
            add("slots", JsonArray().apply {
                for (slot in slotsFor(inv, side)) {
                    add(JsonObject().apply {
                        addProperty("index", slot)
                        add("stack", ServerContext.itemStackToJson(inv.getStack(slot)))
                        addProperty("capacity", minOf(inv.maxCountPerStack, inv.getStack(slot).maxCount))
                    })
                }
            })
        }

    @Suppress("UNCHECKED_CAST")
    private fun itemStorageToJson(storage: Storage<ItemVariant>, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "item")
            addProperty("supportsInsertion", storage.supportsInsertion())
            addProperty("supportsExtraction", storage.supportsExtraction())
            add("slots", JsonArray().apply {
                if (storage is SlottedStorage<*>) {
                    val slotted = storage as SlottedStorage<ItemVariant>
                    for (i in 0 until slotted.slotCount) add(itemViewToJson(i, slotted.getSlot(i)))
                } else {
                    var i = 0
                    for (view in storage) {
                        if (!view.isResourceBlank || view.amount > 0) add(itemViewToJson(i, view))
                        i++
                    }
                }
            })
        }

    private fun fluidStorageToJson(storage: Storage<FluidVariant>, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "fluid")
            addProperty("supportsInsertion", storage.supportsInsertion())
            addProperty("supportsExtraction", storage.supportsExtraction())
            add("tanks", JsonArray().apply {
                var i = 0
                for (view in storage) {
                    if (!view.isResourceBlank || view.amount > 0 || view.capacity > 0) add(fluidViewToJson(i, view))
                    i++
                }
            })
        }

    private fun energyStorageToJson(storage: Any, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "energy")
            addProperty("supportsInsertion", EnergyReflection.supportsInsertion(storage))
            addProperty("supportsExtraction", EnergyReflection.supportsExtraction(storage))
            addProperty("amount", EnergyReflection.amount(storage))
            addProperty("capacity", EnergyReflection.capacity(storage))
        }

    private fun itemViewToJson(index: Int, view: StorageView<ItemVariant>): JsonObject =
        JsonObject().apply {
            addProperty("index", index)
            val variant = view.resource
            add("stack", if (variant.isBlank) ServerContext.itemStackToJson(ItemStack.EMPTY) else ServerContext.itemStackToJson(variant.toStack(view.amount.toInt())))
            addProperty("amount", view.amount)
            addProperty("capacity", view.capacity)
        }

    private fun fluidViewToJson(index: Int, view: StorageView<FluidVariant>): JsonObject =
        JsonObject().apply {
            addProperty("index", index)
            val variant = view.resource
            if (variant.isBlank) add("fluid", JsonNull.INSTANCE) else addProperty("fluid", Registries.FLUID.getId(variant.fluid).toString())
            if (!variant.isBlank && variant.nbt != null) add("nbt", NbtJson.toJson(variant.nbt!!))
            addProperty("amount", view.amount)
            addProperty("capacity", view.capacity)
        }

    @Suppress("UNCHECKED_CAST")
    private fun itemSlotCount(storage: Storage<ItemVariant>): Int =
        if (storage is SlottedStorage<*>) (storage as SlottedStorage<ItemVariant>).slotCount else storage.count()

    private fun fluidTankCount(storage: Storage<FluidVariant>): Int = storage.count()

    @Suppress("UNCHECKED_CAST")
    private fun itemStorageFor(target: TargetRef, side: Direction?): Storage<ItemVariant>? =
        when (target) {
            is TargetRef.Block -> ItemStorage.SIDED.find(target.world, target.pos, side)
            is TargetRef.Entity -> inventoryFor(target)?.let { InventoryStorage.of(it, null) as Storage<ItemVariant> }
            is TargetRef.Item -> null
        }

    private fun fluidEndpoint(target: TargetRef, side: Direction?, mutableItem: Boolean): FluidEndpoint? =
        when (target) {
            is TargetRef.Block -> FluidStorage.SIDED.find(target.world, target.pos, side)?.let { FluidEndpoint(it, null) }
            is TargetRef.Entity -> null
            is TargetRef.Item -> {
                val context = itemContext(target.stack, mutableItem)
                context.find(FluidStorage.ITEM)?.let { FluidEndpoint(it, context) }
            }
        }

    private fun energyEndpoint(target: TargetRef, side: Direction?, mutableItem: Boolean): EnergyEndpoint? =
        when (target) {
            is TargetRef.Block -> EnergyReflection.find(target.world, target.pos, side)?.let { EnergyEndpoint(it, null) }
            is TargetRef.Entity -> null
            is TargetRef.Item -> {
                val context = itemContext(target.stack, mutableItem)
                EnergyReflection.findItem(context)?.let { EnergyEndpoint(it, context) }
            }
        }

    private fun inventoryFor(target: TargetRef): Inventory? =
        when (target) {
            is TargetRef.Block -> target.world.getBlockEntity(target.pos) as? Inventory
            is TargetRef.Entity -> entityInventory(target.entity)
            is TargetRef.Item -> null
        }

    private fun entityInventory(entity: Entity): Inventory? =
        when (entity) {
            is Inventory -> entity
            is InventoryOwner -> entity.inventory
            is PlayerEntity -> entity.inventory
            else -> null
        }

    private fun sideForInventory(target: TargetRef, side: Direction?): Direction? =
        if (target is TargetRef.Block) side else null

    @Suppress("DEPRECATION")
    private fun itemContext(stack: ItemStack, mutable: Boolean): ContainerItemContext =
        if (mutable) ContainerItemContext.withInitial(stack.copy()) else ContainerItemContext.withConstant(stack.copy())

    private fun itemTargetAfter(context: ContainerItemContext): JsonObject {
        val variant = context.itemVariant
        val stack = if (variant.isBlank) ItemStack.EMPTY else variant.toStack(context.amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        return JsonObject().apply {
            addProperty("kind", "item")
            add("stack", ServerContext.itemStackToJson(stack))
        }
    }

    private data class FluidEndpoint(val storage: Storage<FluidVariant>, val context: ContainerItemContext?) {
        fun targetAfter(): JsonObject? = context?.let(::itemTargetAfter)
    }

    private data class EnergyEndpoint(val storage: Any, val context: ContainerItemContext?) {
        fun targetAfter(): JsonObject? = context?.let(::itemTargetAfter)
    }

    private fun itemStackResource(server: MinecraftServer, resource: JsonObject, count: Int): ItemStack {
        if (resource.requireString("kind") != "item") {
            throw RpcException(RpcErrors.INVALID_PARAMS, "resource.kind must be item")
        }
        val item = resource.getStringOrNull("item") ?: resource.getStringOrNull("id")
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "item resource requires item")
        val stack = ServerContext.itemStackFromJson(server, item, count, resource.get("nbt"))
        if (stack.isEmpty) throw RpcException(RpcErrors.INVALID_PARAMS, "item resource must not be empty")
        return stack
    }

    private fun targetStack(server: MinecraftServer, stackJson: JsonObject): ItemStack {
        val item = stackJson.getStringOrNull("item") ?: "minecraft:air"
        val count = stackJson.getIntOr("count", 0)
        return ServerContext.itemStackFromJson(server, item, count, stackJson.get("nbt"))
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

    private fun kindOfHandle(handle: String): String? = when (handle) {
        HANDLE_VANILLA_INVENTORY, HANDLE_FABRIC_ITEM -> "item"
        HANDLE_FABRIC_FLUID -> "fluid"
        HANDLE_TEAM_REBORN_ENERGY -> "energy"
        else -> null
    }

    private fun resolveTarget(server: MinecraftServer, target: JsonObject): TargetRef =
        when (val kind = target.requireString("kind")) {
            "block" -> {
                val pos = ServerContext.pos(target.getAsJsonArray("pos"))
                val world = ServerContext.world(server, target.getStringOrNull("dim"))
                TargetRef.Block(world, pos)
            }
            "entity" -> {
                val uuid = try {
                    UUID.fromString(target.requireString("uuid"))
                } catch (e: IllegalArgumentException) {
                    throw RpcException(RpcErrors.INVALID_PARAMS, "invalid entity UUID: ${target.getStringOrNull("uuid")}")
                }
                val world = ServerContext.world(server, target.getStringOrNull("dim"))
                val entity = world.getEntity(uuid)
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "entity not found: $uuid")
                TargetRef.Entity(world, entity)
            }
            "item" -> TargetRef.Item(targetStack(server, target.requireObject("stack")))
            else -> throw RpcException(RpcErrors.INVALID_PARAMS, "unknown target kind: $kind")
        }

    internal fun sideFromOpts(p: JsonObject, name: String): Direction? {
        val direct = p.getStringOrNull(name)
        val nested = p.getAsJsonObject("opts")?.getStringOrNull(name)
        return parseSide(direct ?: nested)
    }

    internal fun parseSide(value: String?): Direction? {
        val s = value ?: return null
        return when (s.lowercase()) {
            "null" -> null
            "up" -> Direction.UP
            "down" -> Direction.DOWN
            "north" -> Direction.NORTH
            "south" -> Direction.SOUTH
            "east" -> Direction.EAST
            "west" -> Direction.WEST
            else -> throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "invalid side: $s (allowed: null, north, south, east, west, up, down)"
            )
        }
    }

    private sealed class TargetRef {
        data class Block(val world: ServerWorld, val pos: BlockPos) : TargetRef()
        data class Entity(val world: ServerWorld, val entity: net.minecraft.entity.Entity) : TargetRef()
        data class Item(val stack: ItemStack) : TargetRef()

        fun label(): String = when (this) {
            is Block -> "block:${world.registryKey.value}:${pos.x},${pos.y},${pos.z}"
            is Entity -> "entity:${world.registryKey.value}:${entity.uuidAsString}"
            is Item -> "item:${Registries.ITEM.getId(stack.item)}:${stack.count}"
        }
    }

    private fun throwStorageNotFound(handle: String, target: TargetRef): Nothing =
        throwStorageNotFound(handle, target.label())

    private fun throwStorageNotFound(handle: String, target: String): Nothing {
        val data = JsonObject().apply {
            addProperty("reason", "STORAGE_NOT_FOUND")
            addProperty("handle", handle)
            addProperty("target", target)
        }
        throw RpcException(RpcErrors.STORAGE_NOT_FOUND, "storage not found: $handle at $target", data)
    }

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

        fun find(world: World, pos: BlockPos, side: Direction?): Any? {
            val lookup = sidedLookup ?: return null
            val method = sidedFindMethod ?: return null
            return method.invoke(lookup, world, pos, side)
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

        fun insert(storage: Any, amount: Long, simulate: Boolean): Long {
            Transaction.openOuter().use { tx ->
                val inserted = callTransfer(storage, "insert", amount, tx)
                if (!simulate && inserted > 0) tx.commit()
                return inserted
            }
        }

        fun extract(storage: Any, amount: Long, simulate: Boolean): Long {
            Transaction.openOuter().use { tx ->
                val extracted = callTransfer(storage, "extract", amount, tx)
                if (!simulate && extracted > 0) tx.commit()
                return extracted
            }
        }

        fun transfer(from: Any, to: Any, amount: Long, simulate: Boolean): Long {
            Transaction.openOuter().use { tx ->
                val canExtract = Transaction.openNested(tx).use { nested -> callTransfer(from, "extract", amount, nested) }
                val accepted = callTransfer(to, "insert", canExtract, tx)
                val extracted = callTransfer(from, "extract", accepted, tx)
                val moved = if (accepted == extracted) accepted else 0
                if (!simulate && moved > 0) tx.commit()
                return moved
            }
        }

        private fun callLong(storage: Any, name: String): Long =
            energyClass!!.getMethod(name).invoke(storage) as Long

        private fun callBoolean(storage: Any, name: String): Boolean =
            energyClass!!.getMethod(name).invoke(storage) as Boolean

        private fun callTransfer(storage: Any, name: String, amount: Long, tx: Any): Long {
            val method = energyClass!!.methods.first {
                it.name == name && it.parameterCount == 2 && it.parameterTypes[0] == java.lang.Long.TYPE
            }
            return method.invoke(storage, amount, tx) as Long
        }
    }
}

internal fun JsonObject.requireObject(name: String): JsonObject =
    if (has(name) && !get(name).isJsonNull && get(name).isJsonObject) getAsJsonObject(name)
    else throw RpcException(RpcErrors.INVALID_PARAMS, "missing object param: $name")

internal fun boxFromParams(p: JsonObject): BlockBox {
    val box = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
    val from = ServerContext.pos(box.getAsJsonArray("from"))
    val to = ServerContext.pos(box.getAsJsonArray("to"))
    return ServerContext.boxFrom(from, to)
}

internal fun ensureChunksLoaded(world: World, box: BlockBox) {
    val minCx = box.minX shr 4
    val maxCx = box.maxX shr 4
    val minCz = box.minZ shr 4
    val maxCz = box.maxZ shr 4
    for (cx in minCx..maxCx) {
        for (cz in minCz..maxCz) {
            if (!world.chunkManager.isChunkLoaded(cx, cz)) {
                throw RpcException(RpcErrors.CHUNK_NOT_LOADED, "chunk not loaded: ($cx, $cz)")
            }
        }
    }
}

internal fun screenHandlerProperties(handler: ScreenHandler): List<Int> {
    val field = listOf("properties", "field_7765").firstNotNullOfOrNull { name ->
        runCatching {
            ScreenHandler::class.java.getDeclaredField(name).apply { isAccessible = true }
        }.getOrNull()
    } ?: return emptyList()
    val properties = field.get(handler) as? List<*> ?: return emptyList()
    return properties.mapNotNull { prop ->
        runCatching { prop!!.javaClass.getMethod("get").invoke(prop) as Int }.getOrNull()
    }
}
