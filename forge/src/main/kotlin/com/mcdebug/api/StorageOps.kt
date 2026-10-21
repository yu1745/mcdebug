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
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.InventoryCarrier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.energy.IEnergyStorage
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.fluids.capability.IFluidHandlerItem
import net.minecraftforge.items.IItemHandler
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * StorageOps (Forge) — unified storage RPC over Forge capabilities.
 *
 * Handle ids are protocol identifiers shared with the Fabric mod (the `mdb` CLI hard-codes them):
 *   vanilla:inventory   -> net.minecraft.world.Container (BlockEntity / entity inventory)
 *   fabric:item         -> ForgeCapabilities.ITEM_HANDLER (IItemHandler)
 *   fabric:fluid        -> ForgeCapabilities.FLUID_HANDLER (IFluidHandler) / FLUID_HANDLER_ITEM
 *   teamreborn:energy   -> ForgeCapabilities.ENERGY (IEnergyStorage, FE)
 * `forge:item` / `forge:fluid` / `forge:energy` are accepted as aliases on input.
 * Fluid amounts are mB (1000/bucket); energy is FE; items are item counts.
 */
object StorageOps : RpcHandlerGroup {
    internal const val HANDLE_VANILLA_INVENTORY = "vanilla:inventory"
    internal const val HANDLE_FABRIC_ITEM = "fabric:item"
    internal const val HANDLE_FABRIC_FLUID = "fabric:fluid"
    internal const val HANDLE_TEAM_REBORN_ENERGY = "teamreborn:energy"

    private fun canonicalHandle(h: String): String = when (h) {
        "forge:item" -> HANDLE_FABRIC_ITEM
        "forge:fluid" -> HANDLE_FABRIC_FLUID
        "forge:energy" -> HANDLE_TEAM_REBORN_ENERGY
        else -> h
    }

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
            getHandleJson(target, side, canonicalHandle(p.requireString("handle")))
        }

    private fun insert(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val target = resolveTarget(server, p.requireObject("target"))
            val side = sideFromOpts(p, "side")
            val handle = canonicalHandle(p.requireString("handle"))
            val resource = p.requireObject("resource")
            val amount = p.requireLong("amount")
            val simulate = p.getBoolOrFalse("simulate")
            when (kindOfHandle(handle)) {
                "item" -> insertItem(server, target, side, handle, resource, amount, simulate)
                "fluid" -> insertFluid(target, side, resource, amount, simulate)
                "energy" -> insertEnergy(target, side, amount, simulate)
                else -> throwStorageNotFound(handle, target)
            }
        }

    private fun extract(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val target = resolveTarget(server, p.requireObject("target"))
            val side = sideFromOpts(p, "side")
            val handle = canonicalHandle(p.requireString("handle"))
            val resource = p.requireObject("resource")
            val amount = p.requireLong("amount")
            val simulate = p.getBoolOrFalse("simulate")
            when (kindOfHandle(handle)) {
                "item" -> extractItem(server, target, side, handle, resource, amount, simulate)
                "fluid" -> extractFluid(target, side, resource, amount, simulate)
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
                "fluid" -> transferFluids(from, to, fromSide, toSide, resource, amount, simulate)
                "energy" -> transferEnergy(from, to, fromSide, toSide, amount, simulate)
                else -> throw RpcException(RpcErrors.INVALID_PARAMS, "unknown resource kind: $kind")
            }
        }

    // ---------------------------------------------------------------- item

    private fun insertItem(
        server: MinecraftServer, target: TargetRef, side: Direction?, handle: String,
        resource: JsonObject, amount: Long, simulate: Boolean,
    ): JsonElement {
        val stack = itemStackResource(server, resource, amount.toInt())
        val inserted = when (handle) {
            HANDLE_VANILLA_INVENTORY -> {
                val inv = inventoryFor(target) ?: throwStorageNotFound(handle, target)
                vanillaInsert(inv, sideForInventory(target, side), stack, simulate).toLong()
            }
            HANDLE_FABRIC_ITEM -> {
                val handler = itemHandlerFor(target, side) ?: throwStorageNotFound(handle, target)
                handlerInsert(handler, stack, simulate).toLong()
            }
            else -> throwStorageNotFound(handle, target)
        }
        return moveResult(handle, "item", amount, inserted, simulate, insertedKey = "inserted")
    }

    private fun extractItem(
        server: MinecraftServer, target: TargetRef, side: Direction?, handle: String,
        resource: JsonObject, amount: Long, simulate: Boolean,
    ): JsonElement {
        val stack = itemStackResource(server, resource, 1)
        val extracted = when (handle) {
            HANDLE_VANILLA_INVENTORY -> {
                val inv = inventoryFor(target) ?: throwStorageNotFound(handle, target)
                vanillaExtract(inv, sideForInventory(target, side), stack, amount.toInt(), simulate).toLong()
            }
            HANDLE_FABRIC_ITEM -> {
                val handler = itemHandlerFor(target, side) ?: throwStorageNotFound(handle, target)
                handlerExtract(handler, stack, amount.toInt(), simulate).toLong()
            }
            else -> throwStorageNotFound(handle, target)
        }
        return moveResult(handle, "item", amount, extracted, simulate, insertedKey = "extracted")
    }

    private fun transferItems(
        server: MinecraftServer, from: TargetRef, to: TargetRef, fromSide: Direction?, toSide: Direction?,
        resource: JsonObject, amount: Long, simulate: Boolean,
    ): JsonElement {
        val stack = itemStackResource(server, resource, 1)
        val fromH = itemHandlerFor(from, fromSide) ?: throwStorageNotFound(HANDLE_FABRIC_ITEM, from)
        val toH = itemHandlerFor(to, toSide) ?: throwStorageNotFound(HANDLE_FABRIC_ITEM, to)
        val canExtract = handlerExtract(fromH, stack, amount.toInt(), true)
        val move = ItemStack(stack.item, canExtract).also { it.tag = stack.tag?.copy() }
        val accepted = if (canExtract > 0) handlerInsert(toH, move, true) else 0
        var moved = 0
        if (accepted > 0 && !simulate) {
            val taken = handlerExtract(fromH, stack, accepted, false)
            val put = ItemStack(stack.item, taken).also { it.tag = stack.tag?.copy() }
            moved = if (taken > 0) handlerInsert(toH, put, false) else 0
        } else if (simulate) {
            moved = accepted
        }
        return transferResult("item", moved.toLong(), amount, simulate)
    }

    private fun handlerInsert(handler: IItemHandler, stack: ItemStack, simulate: Boolean): Int {
        if (stack.isEmpty) return 0
        var remaining = stack.copy()
        // first pass: stack onto matching stacks, second: empty slots (net.minecraftforge.items.ItemHandlerHelper semantics)
        for (pass in 0..1) {
            for (slot in 0 until handler.slots) {
                if (remaining.isEmpty) break
                val existing = handler.getStackInSlot(slot)
                if ((pass == 0) == existing.isEmpty) continue
                remaining = handler.insertItem(slot, remaining, simulate)
            }
        }
        return stack.count - remaining.count
    }

    private fun handlerExtract(handler: IItemHandler, exemplar: ItemStack, amount: Int, simulate: Boolean): Int {
        if (exemplar.isEmpty || amount <= 0) return 0
        var remaining = amount
        for (slot in 0 until handler.slots) {
            if (remaining <= 0) break
            val current = handler.getStackInSlot(slot)
            if (current.isEmpty || !ItemStack.isSameItemSameTags(current, exemplar)) continue
            val got = handler.extractItem(slot, remaining, simulate)
            remaining -= got.count
        }
        return amount - remaining
    }

    private fun vanillaInsert(inv: Container, side: Direction?, stack: ItemStack, simulate: Boolean): Int {
        if (stack.isEmpty) return 0
        var remaining = stack.count
        val slots = slotsFor(inv, side)

        for (slot in slots) {
            if (remaining <= 0) break
            val current = inv.getItem(slot)
            if (current.isEmpty || !ItemStack.isSameItemSameTags(current, stack)) continue
            if (!canInsert(inv, slot, stack, side)) continue
            val space = minOf(current.maxStackSize, inv.maxStackSize) - current.count
            if (space <= 0) continue
            val moved = minOf(space, remaining)
            if (!simulate) {
                val next = current.copy()
                next.grow(moved)
                inv.setItem(slot, next)
            }
            remaining -= moved
        }

        for (slot in slots) {
            if (remaining <= 0) break
            if (!inv.getItem(slot).isEmpty) continue
            if (!canInsert(inv, slot, stack, side)) continue
            val moved = minOf(stack.maxStackSize, inv.maxStackSize, remaining)
            if (!simulate) {
                val next = stack.copy()
                next.count = moved
                inv.setItem(slot, next)
            }
            remaining -= moved
        }

        if (!simulate && remaining != stack.count) inv.setChanged()
        return stack.count - remaining
    }

    private fun vanillaExtract(inv: Container, side: Direction?, exemplar: ItemStack, amount: Int, simulate: Boolean): Int {
        if (exemplar.isEmpty || amount <= 0) return 0
        var remaining = amount
        for (slot in slotsFor(inv, side)) {
            if (remaining <= 0) break
            val current = inv.getItem(slot)
            if (current.isEmpty || !ItemStack.isSameItemSameTags(current, exemplar)) continue
            if (!canExtract(inv, slot, current, side)) continue
            val moved = minOf(current.count, remaining)
            if (!simulate) {
                val next = current.copy()
                next.shrink(moved)
                inv.setItem(slot, if (next.isEmpty) ItemStack.EMPTY else next)
            }
            remaining -= moved
        }
        if (!simulate && remaining != amount) inv.setChanged()
        return amount - remaining
    }

    private fun slotsFor(inv: Container, side: Direction?): List<Int> {
        if (side != null && inv is WorldlyContainer) return inv.getSlotsForFace(side).toList()
        return (0 until inv.containerSize).toList()
    }

    private fun canInsert(inv: Container, slot: Int, stack: ItemStack, side: Direction?): Boolean {
        if (!inv.canPlaceItem(slot, stack)) return false
        return side == null || inv !is WorldlyContainer || inv.canPlaceItemThroughFace(slot, stack, side)
    }

    private fun canExtract(inv: Container, slot: Int, stack: ItemStack, side: Direction?): Boolean =
        side == null || inv !is WorldlyContainer || inv.canTakeItemThroughFace(slot, stack, side)

    // --------------------------------------------------------------- fluid

    private fun insertFluid(
        target: TargetRef, side: Direction?, resource: JsonObject, amount: Long, simulate: Boolean,
    ): JsonElement {
        val fluid = FluidOps.resolveFluid(resource.requireString("fluid"))
        val endpoint = fluidEndpoint(target, side) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, target)
        val action = fluidAction(simulate)
        val inserted = endpoint.handler.fill(FluidStack(fluid, clampInt(amount)), action).toLong()
        return moveResult(HANDLE_FABRIC_FLUID, "fluid", amount, inserted, simulate, "inserted").apply {
            addProperty("unit", "mB")
            endpoint.targetAfter()?.let { add("targetAfter", it) }
        }
    }

    private fun extractFluid(
        target: TargetRef, side: Direction?, resource: JsonObject, amount: Long, simulate: Boolean,
    ): JsonElement {
        val fluid = FluidOps.resolveFluid(resource.requireString("fluid"))
        val endpoint = fluidEndpoint(target, side) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, target)
        val drained = endpoint.handler.drain(FluidStack(fluid, clampInt(amount)), fluidAction(simulate)).amount.toLong()
        return moveResult(HANDLE_FABRIC_FLUID, "fluid", amount, drained, simulate, "extracted").apply {
            addProperty("unit", "mB")
            endpoint.targetAfter()?.let { add("targetAfter", it) }
        }
    }

    private fun transferFluids(
        from: TargetRef, to: TargetRef, fromSide: Direction?, toSide: Direction?,
        resource: JsonObject, amount: Long, simulate: Boolean,
    ): JsonElement {
        val fluid = FluidOps.resolveFluid(resource.requireString("fluid"))
        val fromE = fluidEndpoint(from, fromSide) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, from)
        val toE = fluidEndpoint(to, toSide) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, to)
        val want = FluidStack(fluid, clampInt(amount))
        val canDrain = fromE.handler.drain(want, IFluidHandler.FluidAction.SIMULATE).amount
        val accepted = if (canDrain > 0) toE.handler.fill(FluidStack(fluid, canDrain), IFluidHandler.FluidAction.SIMULATE) else 0
        var moved = accepted
        if (!simulate && accepted > 0) {
            val drained = fromE.handler.drain(FluidStack(fluid, accepted), IFluidHandler.FluidAction.EXECUTE).amount
            moved = if (drained > 0) toE.handler.fill(FluidStack(fluid, drained), IFluidHandler.FluidAction.EXECUTE) else 0
        }
        return transferResult("fluid", moved.toLong(), amount, simulate).apply {
            addProperty("unit", "mB")
            fromE.targetAfter()?.let { add("fromAfter", it) }
            toE.targetAfter()?.let { add("toAfter", it) }
        }
    }

    private fun fluidAction(simulate: Boolean) =
        if (simulate) IFluidHandler.FluidAction.SIMULATE else IFluidHandler.FluidAction.EXECUTE

    private fun clampInt(v: Long): Int = v.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    // -------------------------------------------------------------- energy

    private fun insertEnergy(target: TargetRef, side: Direction?, amount: Long, simulate: Boolean): JsonElement {
        val e = energyEndpoint(target, side) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, target)
        val inserted = e.storage.receiveEnergy(clampInt(amount), simulate).toLong()
        return moveResult(HANDLE_TEAM_REBORN_ENERGY, "energy", amount, inserted, simulate, "inserted").apply {
            e.targetAfter()?.let { add("targetAfter", it) }
        }
    }

    private fun extractEnergy(target: TargetRef, side: Direction?, amount: Long, simulate: Boolean): JsonElement {
        val e = energyEndpoint(target, side) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, target)
        val extracted = e.storage.extractEnergy(clampInt(amount), simulate).toLong()
        return moveResult(HANDLE_TEAM_REBORN_ENERGY, "energy", amount, extracted, simulate, "extracted").apply {
            e.targetAfter()?.let { add("targetAfter", it) }
        }
    }

    private fun transferEnergy(
        from: TargetRef, to: TargetRef, fromSide: Direction?, toSide: Direction?, amount: Long, simulate: Boolean,
    ): JsonElement {
        val fromE = energyEndpoint(from, fromSide) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, from)
        val toE = energyEndpoint(to, toSide) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, to)
        val canExtract = fromE.storage.extractEnergy(clampInt(amount), true)
        val accepted = if (canExtract > 0) toE.storage.receiveEnergy(canExtract, true) else 0
        var moved = accepted
        if (!simulate && accepted > 0) {
            val taken = fromE.storage.extractEnergy(accepted, false)
            moved = if (taken > 0) toE.storage.receiveEnergy(taken, false) else 0
        }
        return transferResult("energy", moved.toLong(), amount, simulate).apply {
            fromE.targetAfter()?.let { add("fromAfter", it) }
            toE.targetAfter()?.let { add("toAfter", it) }
        }
    }

    // ---------------------------------------------------------- shared json

    private fun moveResult(
        handle: String, kind: String, requested: Long, moved: Long, simulate: Boolean, insertedKey: String,
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

    internal fun handlesFor(world: ServerLevel, pos: BlockPos, side: Direction?): JsonArray =
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
            val itemH = itemHandlerFor(target, side)
            if (itemH != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_FABRIC_ITEM)
                    addProperty("kind", "item")
                    addProperty("slots", itemH.slots)
                })
            }
            val fluid = fluidEndpoint(target, side)
            if (fluid != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_FABRIC_FLUID)
                    addProperty("kind", "fluid")
                    addProperty("tanks", fluid.handler.tanks)
                })
            }
            val energy = energyEndpoint(target, side)
            if (energy != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_TEAM_REBORN_ENERGY)
                    addProperty("kind", "energy")
                    addProperty("amount", energy.storage.energyStored.toLong())
                    addProperty("capacity", energy.storage.maxEnergyStored.toLong())
                })
            }
        }

    internal fun getHandleJson(world: ServerLevel, pos: BlockPos, side: Direction?, handle: String): JsonObject =
        getHandleJson(TargetRef.Block(world, pos), side, handle)

    private fun getHandleJson(target: TargetRef, side: Direction?, handle: String): JsonObject =
        when (handle) {
            HANDLE_VANILLA_INVENTORY -> {
                val inv = inventoryFor(target) ?: throwStorageNotFound(handle, target)
                inventoryToJson(inv, sideForInventory(target, side), handle)
            }
            HANDLE_FABRIC_ITEM -> {
                val h = itemHandlerFor(target, side) ?: throwStorageNotFound(handle, target)
                itemHandlerToJson(h, handle)
            }
            HANDLE_FABRIC_FLUID -> {
                val h = fluidEndpoint(target, side)?.handler ?: throwStorageNotFound(handle, target)
                fluidHandlerToJson(h, handle)
            }
            HANDLE_TEAM_REBORN_ENERGY -> {
                val s = energyEndpoint(target, side)?.storage ?: throwStorageNotFound(handle, target)
                JsonObject().apply {
                    addProperty("handle", handle)
                    addProperty("kind", "energy")
                    addProperty("supportsInsertion", s.canReceive())
                    addProperty("supportsExtraction", s.canExtract())
                    addProperty("amount", s.energyStored.toLong())
                    addProperty("capacity", s.maxEnergyStored.toLong())
                }
            }
            else -> throwStorageNotFound(handle, target)
        }

    internal fun blockResourcesToJson(world: ServerLevel, pos: BlockPos, side: Direction? = null): JsonObject =
        JsonObject().apply {
            add("handles", handlesFor(world, pos, side))
            val resources = JsonArray()
            for (handleEl in getAsJsonArray("handles")) {
                val handle = handleEl.asJsonObject.requireString("handle")
                resources.add(getHandleJson(world, pos, side, handle))
            }
            add("resources", resources)
        }

    private fun inventoryToJson(inv: Container, side: Direction?, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "item")
            addProperty("supportsInsertion", true)
            addProperty("supportsExtraction", true)
            add("slots", JsonArray().apply {
                for (slot in slotsFor(inv, side)) {
                    add(JsonObject().apply {
                        addProperty("index", slot)
                        add("stack", ServerContext.itemStackToJson(inv.getItem(slot)))
                        addProperty("capacity", minOf(inv.maxStackSize, inv.getItem(slot).maxStackSize))
                    })
                }
            })
        }

    private fun itemHandlerToJson(h: IItemHandler, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "item")
            addProperty("supportsInsertion", true)
            addProperty("supportsExtraction", true)
            add("slots", JsonArray().apply {
                for (i in 0 until h.slots) {
                    val st = h.getStackInSlot(i)
                    add(JsonObject().apply {
                        addProperty("index", i)
                        add("stack", ServerContext.itemStackToJson(st))
                        addProperty("amount", st.count.toLong())
                        addProperty("capacity", h.getSlotLimit(i).toLong())
                    })
                }
            })
        }

    private fun fluidHandlerToJson(h: IFluidHandler, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "fluid")
            addProperty("supportsInsertion", true)
            addProperty("supportsExtraction", true)
            addProperty("unit", "mB")
            add("tanks", JsonArray().apply {
                for (i in 0 until h.tanks) {
                    val s = h.getFluidInTank(i)
                    val cap = h.getTankCapacity(i)
                    if (s.isEmpty && cap <= 0) continue
                    add(JsonObject().apply {
                        addProperty("index", i)
                        if (s.isEmpty) add("fluid", JsonNull.INSTANCE)
                        else addProperty("fluid", BuiltInRegistries.FLUID.getKey(s.fluid).toString())
                        if (!s.isEmpty && s.tag != null) add("nbt", NbtJson.toJson(s.tag!!))
                        addProperty("amount", s.amount.toLong())
                        addProperty("capacity", cap.toLong())
                    })
                }
            })
        }

    // ----------------------------------------------------------- endpoints

    private fun <T> capOf(provider: net.minecraftforge.common.capabilities.ICapabilityProvider?, cap: Capability<T>, side: Direction?): T? =
        provider?.getCapability(cap, side)?.resolve()?.orElse(null)

    private fun itemHandlerFor(target: TargetRef, side: Direction?): IItemHandler? = when (target) {
        is TargetRef.Block -> capOf(target.world.getBlockEntity(target.pos), ForgeCapabilities.ITEM_HANDLER, side)
        is TargetRef.Entity -> capOf(target.entity, ForgeCapabilities.ITEM_HANDLER, side)
        is TargetRef.Item -> null
    }

    private class FluidEndpoint(val handler: IFluidHandler, val itemStack: ItemStack?) {
        fun targetAfter(): JsonObject? = itemStack?.let { stackAfter ->
            val after = (handler as? IFluidHandlerItem)?.container ?: stackAfter
            JsonObject().apply {
                addProperty("kind", "item")
                add("stack", ServerContext.itemStackToJson(after))
            }
        }
    }

    private class EnergyEndpoint(val storage: IEnergyStorage, val itemStack: ItemStack?) {
        fun targetAfter(): JsonObject? = itemStack?.let {
            JsonObject().apply {
                addProperty("kind", "item")
                add("stack", ServerContext.itemStackToJson(it))
            }
        }
    }

    private fun fluidEndpoint(target: TargetRef, side: Direction?): FluidEndpoint? = when (target) {
        is TargetRef.Block -> capOf(target.world.getBlockEntity(target.pos), ForgeCapabilities.FLUID_HANDLER, side)?.let { FluidEndpoint(it, null) }
        is TargetRef.Entity -> null
        is TargetRef.Item -> {
            // The item target is a private copy; the handler mutates it (IFluidHandlerItem.getContainer).
            val copy = target.stack.copy()
            capOf(copy, ForgeCapabilities.FLUID_HANDLER_ITEM, null)?.let { FluidEndpoint(it, copy) }
        }
    }

    private fun energyEndpoint(target: TargetRef, side: Direction?): EnergyEndpoint? = when (target) {
        is TargetRef.Block -> capOf(target.world.getBlockEntity(target.pos), ForgeCapabilities.ENERGY, side)?.let { EnergyEndpoint(it, null) }
        is TargetRef.Entity -> null
        is TargetRef.Item -> {
            val copy = target.stack.copy()
            capOf(copy, ForgeCapabilities.ENERGY, null)?.let { EnergyEndpoint(it, copy) }
        }
    }

    private fun inventoryFor(target: TargetRef): Container? = when (target) {
        is TargetRef.Block -> target.world.getBlockEntity(target.pos) as? Container
        is TargetRef.Entity -> entityInventory(target.entity)
        is TargetRef.Item -> null
    }

    private fun entityInventory(entity: Entity): Container? = when (entity) {
        is Container -> entity
        is InventoryCarrier -> entity.inventory
        is Player -> entity.inventory
        else -> null
    }

    private fun sideForInventory(target: TargetRef, side: Direction?): Direction? =
        if (target is TargetRef.Block) side else null

    // ------------------------------------------------------------- resolve

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
        data class Block(val world: ServerLevel, val pos: BlockPos) : TargetRef()
        data class Entity(val world: ServerLevel, val entity: net.minecraft.world.entity.Entity) : TargetRef()
        data class Item(val stack: ItemStack) : TargetRef()

        fun label(): String = when (this) {
            is Block -> "block:${world.dimension().location()}:${pos.x},${pos.y},${pos.z}"
            is Entity -> "entity:${world.dimension().location()}:${entity.stringUUID}"
            is Item -> "item:${BuiltInRegistries.ITEM.getKey(stack.item)}:${stack.count}"
        }
    }

    private fun throwStorageNotFound(handle: String, target: TargetRef): Nothing {
        val label = target.label()
        val data = JsonObject().apply {
            addProperty("reason", "STORAGE_NOT_FOUND")
            addProperty("handle", handle)
            addProperty("target", label)
        }
        throw RpcException(RpcErrors.STORAGE_NOT_FOUND, "storage not found: $handle at $label", data)
    }
}

internal fun JsonObject.requireObject(name: String): JsonObject =
    if (has(name) && !get(name).isJsonNull && get(name).isJsonObject) getAsJsonObject(name)
    else throw RpcException(RpcErrors.INVALID_PARAMS, "missing object param: $name")

internal fun boxFromParams(p: JsonObject): BoundingBox {
    val box = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
    val from = ServerContext.pos(box.getAsJsonArray("from"))
    val to = ServerContext.pos(box.getAsJsonArray("to"))
    return ServerContext.boxFrom(from, to)
}

internal fun ensureChunksLoaded(world: Level, box: BoundingBox) {
    val minCx = box.minX() shr 4
    val maxCx = box.maxX() shr 4
    val minCz = box.minZ() shr 4
    val maxCz = box.maxZ() shr 4
    for (cx in minCx..maxCx) {
        for (cz in minCz..maxCz) {
            if (!world.hasChunk(cx, cz)) {
                throw RpcException(RpcErrors.CHUNK_NOT_LOADED, "chunk not loaded: ($cx, $cz)")
            }
        }
    }
}

/** DataSlot values of a menu (the Fabric version reflects the private `properties` list). */
internal fun screenHandlerProperties(handler: AbstractContainerMenu): List<Int> {
    val field = runCatching {
        net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(AbstractContainerMenu::class.java, "f_38842_")
    }.getOrNull() ?: return emptyList()
    val slots = field.get(handler) as? List<*> ?: return emptyList()
    return slots.mapNotNull { (it as? net.minecraft.world.inventory.DataSlot)?.get() }
}
