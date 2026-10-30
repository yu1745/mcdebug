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
import com.mcdebug.loader.EnergyStorage
import com.mcdebug.loader.FluidStorage
import com.mcdebug.loader.ItemStorage
import com.mcdebug.loader.Loader
import com.mcdebug.util.ServerContext
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.InventoryCarrier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.Container
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
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
                "item" -> moveItemStackTo(server, target, side, handle, resource, amount, simulate)
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

    private fun moveItemStackTo(
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
                storage.insert(stack, amount, simulate)
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
                storage.extract(stack, amount, simulate)
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
        val fluid = resolveFluid(server, resource.requireString("fluid"))
        val storage = fluidStorageFor(target, side) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, target)
        val inserted = storage.insert(fluid, amount, simulate)
        return moveResult(HANDLE_FABRIC_FLUID, "fluid", amount, inserted, simulate, "inserted").apply {
            storage.itemAfter()?.let { add("targetAfter", itemAfterJson(it)) }
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
        val fluid = resolveFluid(server, resource.requireString("fluid"))
        val storage = fluidStorageFor(target, side) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, target)
        val extracted = storage.extract(fluid, amount, simulate)
        return moveResult(HANDLE_FABRIC_FLUID, "fluid", amount, extracted, simulate, "extracted").apply {
            storage.itemAfter()?.let { add("targetAfter", itemAfterJson(it)) }
        }
    }

    private fun insertEnergy(target: TargetRef, side: Direction?, amount: Long, simulate: Boolean): JsonElement {
        val storage = energyStorageFor(target, side) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, target)
        val inserted = storage.insert(amount, simulate)
        return moveResult(HANDLE_TEAM_REBORN_ENERGY, "energy", amount, inserted, simulate, "inserted").apply {
            storage.itemAfter()?.let { add("targetAfter", itemAfterJson(it)) }
        }
    }

    private fun extractEnergy(target: TargetRef, side: Direction?, amount: Long, simulate: Boolean): JsonElement {
        val storage = energyStorageFor(target, side) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, target)
        val extracted = storage.extract(amount, simulate)
        return moveResult(HANDLE_TEAM_REBORN_ENERGY, "energy", amount, extracted, simulate, "extracted").apply {
            storage.itemAfter()?.let { add("targetAfter", itemAfterJson(it)) }
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
        // Loader-native transaction first: on Fabric the whole move either happens or does not
        // (the Fabric-only pre-port code did this in one Transfer API Transaction). NeoForge
        // capabilities have no transaction, so it returns null and we fall back to the dry-run
        // path documented in moveBetween.
        val atomic = Loader.api.storage.moveItemsAtomically(fromStorage, toStorage, stack, amount, simulate)
        val moved = atomic ?: moveBetween(
            MoveEndpoint({ a, sim -> fromStorage.insert(stack, a, sim) }, { a, sim -> fromStorage.extract(stack, a, sim) }),
            MoveEndpoint({ a, sim -> toStorage.insert(stack, a, sim) }, { a, sim -> toStorage.extract(stack, a, sim) }),
            amount, simulate,
        )
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
        val fluid = resolveFluid(server, resource.requireString("fluid"))
        val fromStorage = fluidStorageFor(from, fromSide) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, from)
        val toStorage = fluidStorageFor(to, toSide) ?: throwStorageNotFound(HANDLE_FABRIC_FLUID, to)
        val atomic = Loader.api.storage.moveFluidsAtomically(fromStorage, toStorage, fluid, amount, simulate)
        val moved = atomic ?: moveBetween(
            MoveEndpoint({ a, sim -> fromStorage.insert(fluid, a, sim) }, { a, sim -> fromStorage.extract(fluid, a, sim) }),
            MoveEndpoint({ a, sim -> toStorage.insert(fluid, a, sim) }, { a, sim -> toStorage.extract(fluid, a, sim) }),
            amount, simulate,
        )
        return transferResult("fluid", moved, amount, simulate).apply {
            fromStorage.itemAfter()?.let { add("fromAfter", itemAfterJson(it)) }
            toStorage.itemAfter()?.let { add("toAfter", itemAfterJson(it)) }
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
        val fromStorage = energyStorageFor(from, fromSide) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, from)
        val toStorage = energyStorageFor(to, toSide) ?: throwStorageNotFound(HANDLE_TEAM_REBORN_ENERGY, to)
        val moved = moveBetween(
            MoveEndpoint({ a, sim -> fromStorage.insert(a, sim) }, { a, sim -> fromStorage.extract(a, sim) }),
            MoveEndpoint({ a, sim -> toStorage.insert(a, sim) }, { a, sim -> toStorage.extract(a, sim) }),
            amount, simulate,
        )
        return transferResult("energy", moved, amount, simulate).apply {
            fromStorage.itemAfter()?.let { add("fromAfter", itemAfterJson(it)) }
            toStorage.itemAfter()?.let { add("toAfter", itemAfterJson(it)) }
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

    /** One side of a transfer, bound to a concrete resource (item stack / fluid / energy). */
    private class MoveEndpoint(
        val insert: (Long, Boolean) -> Long,
        val extract: (Long, Boolean) -> Long,
    )

    /**
     * Move as much as both sides accept, without a cross-storage transaction: the amount is
     * decided by dry runs first, so an execute never half-moves more than the destination can
     * take. A racing change between the dry run and the execute is corrected by putting the
     * surplus back (best effort) — see the loader seam note about transaction-less capabilities.
     */
    private fun moveBetween(from: MoveEndpoint, to: MoveEndpoint, amount: Long, simulate: Boolean): Long {
        val canExtract = from.extract(amount, true)
        if (canExtract <= 0) return 0
        val canInsert = to.insert(canExtract, true)
        if (canInsert <= 0) return 0
        if (simulate) return canInsert
        val extracted = from.extract(canInsert, false)
        if (extracted <= 0) return 0
        val inserted = to.insert(extracted, false)
        if (inserted < extracted) from.insert(extracted - inserted, false)
        return inserted
    }

    /** `targetAfter` DTO of an item-hosted storage (bucket emptied, battery charged, …). */
    private fun itemAfterJson(stack: ItemStack): JsonObject =
        JsonObject().apply {
            addProperty("kind", "item")
            add("stack", ServerContext.itemStackToJson(stack))
        }

    private fun vanillaInsert(inv: Container, side: Direction?, stack: ItemStack, simulate: Boolean): Int {
        if (stack.isEmpty) return 0
        var remaining = stack.count
        val slots = slotsFor(inv, side)

        for (slot in slots) {
            if (remaining <= 0) break
            val current = inv.getItem(slot)
            if (current.isEmpty || !ItemStack.isSameItemSameComponents(current, stack)) continue
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
            if (current.isEmpty || !ItemStack.isSameItemSameComponents(current, exemplar)) continue
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

            val itemStorage = itemStorageFor(target, side)
            if (itemStorage != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_FABRIC_ITEM)
                    addProperty("kind", "item")
                    addProperty("slots", itemStorage.slotCount)
                })
            }

            val fluid = fluidStorageFor(target, side)
            if (fluid != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_FABRIC_FLUID)
                    addProperty("kind", "fluid")
                    addProperty("tanks", fluid.tankCount)
                })
            }

            val energy = energyStorageFor(target, side)
            if (energy != null) {
                add(JsonObject().apply {
                    addProperty("handle", HANDLE_TEAM_REBORN_ENERGY)
                    addProperty("kind", "energy")
                    addProperty("amount", energy.amount)
                    addProperty("capacity", energy.capacity)
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
                val storage = itemStorageFor(target, side) ?: throwStorageNotFound(handle, target)
                itemStorageToJson(storage, handle)
            }
            HANDLE_FABRIC_FLUID -> {
                val storage = fluidStorageFor(target, side) ?: throwStorageNotFound(handle, target)
                fluidStorageToJson(storage, handle)
            }
            HANDLE_TEAM_REBORN_ENERGY -> {
                val storage = energyStorageFor(target, side) ?: throwStorageNotFound(handle, target)
                energyStorageToJson(storage, handle)
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

    private fun itemStorageToJson(storage: ItemStorage, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "item")
            addProperty("supportsInsertion", storage.supportsInsertion)
            addProperty("supportsExtraction", storage.supportsExtraction)
            add("slots", JsonArray().apply {
                for (view in storage.views()) {
                    add(JsonObject().apply {
                        addProperty("index", view.index)
                        add("stack", ServerContext.itemStackToJson(view.stack))
                        addProperty("amount", view.amount)
                        addProperty("capacity", view.capacity)
                    })
                }
            })
        }

    private fun fluidStorageToJson(storage: FluidStorage, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "fluid")
            addProperty("supportsInsertion", storage.supportsInsertion)
            addProperty("supportsExtraction", storage.supportsExtraction)
            add("tanks", JsonArray().apply {
                for (view in storage.views()) {
                    add(JsonObject().apply {
                        addProperty("index", view.index)
                        if (view.fluidId == null) add("fluid", JsonNull.INSTANCE) else addProperty("fluid", view.fluidId)
                        view.fluidNbt?.let { add("nbt", it) }
                        addProperty("amount", view.amount)
                        addProperty("capacity", view.capacity)
                    })
                }
            })
        }

    private fun energyStorageToJson(storage: EnergyStorage, handle: String): JsonObject =
        JsonObject().apply {
            addProperty("handle", handle)
            addProperty("kind", "energy")
            addProperty("supportsInsertion", storage.supportsInsertion)
            addProperty("supportsExtraction", storage.supportsExtraction)
            addProperty("amount", storage.amount)
            addProperty("capacity", storage.capacity)
        }

    private fun itemStorageFor(target: TargetRef, side: Direction?): ItemStorage? =
        when (target) {
            is TargetRef.Block -> Loader.api.storage.itemStorage(target.world, target.pos, side)
            is TargetRef.Entity -> inventoryFor(target)?.let { Loader.api.storage.itemStorageOf(it, null) }
            is TargetRef.Item -> Loader.api.storage.itemStorageOfItem(target.stack)
        }

    private fun fluidStorageFor(target: TargetRef, side: Direction?): FluidStorage? =
        when (target) {
            is TargetRef.Block -> Loader.api.storage.fluidStorage(target.world, target.pos, side)
            is TargetRef.Entity -> null
            is TargetRef.Item -> Loader.api.storage.fluidStorageOfItem(target.stack)
        }

    private fun energyStorageFor(target: TargetRef, side: Direction?): EnergyStorage? =
        when (target) {
            is TargetRef.Block -> Loader.api.storage.energyStorage(target.world, target.pos, side)
            is TargetRef.Entity -> null
            is TargetRef.Item -> Loader.api.storage.energyStorageOfItem(target.stack)
        }

    /** The vanilla Container behind a target, if any (block entity, entity inventory). */
    private fun inventoryFor(target: TargetRef): Container? =
        when (target) {
            is TargetRef.Block -> target.world.getBlockEntity(target.pos) as? Container
            is TargetRef.Entity -> entityInventory(target.entity)
            is TargetRef.Item -> null
        }

    private fun entityInventory(entity: Entity): Container? =
        when (entity) {
            is Container -> entity
            is InventoryCarrier -> entity.inventory
            is Player -> entity.inventory
            else -> null
        }

    /** Sides only make sense for block targets; entity inventories are single-faced. */
    private fun sideForInventory(target: TargetRef, side: Direction?): Direction? =
        if (target is TargetRef.Block) side else null

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

    private fun kindOfHandle(handle: String): String? = when (handle) {
        HANDLE_VANILLA_INVENTORY, HANDLE_FABRIC_ITEM -> "item"
        HANDLE_FABRIC_FLUID -> "fluid"
        HANDLE_TEAM_REBORN_ENERGY -> "energy"
        else -> null
    }

    private fun resolveTarget(server: MinecraftServer, target: JsonObject): TargetRef =
        when (val kind = target.requireString("kind")) {
            "block" -> {
                val pos = target.requirePos("pos")
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
        val nested = p.getObjectOrNull("opts")?.getStringOrNull(name)
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
            is Entity -> "entity:${world.dimension().location()}:${entity.getStringUUID()}"
            is Item -> "item:${BuiltInRegistries.ITEM.getKey(stack.item)}:${stack.count}"
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

}


internal fun ensureChunksLoaded(world: Level, box: BoundingBox) {
    val minCx = box.minX() shr 4
    val maxCx = box.maxX() shr 4
    val minCz = box.minZ() shr 4
    val maxCz = box.maxZ() shr 4
    for (cx in minCx..maxCx) {
        for (cz in minCz..maxCz) {
            if (!world.chunkSource.hasChunk(cx, cz)) {
                throw RpcException(RpcErrors.CHUNK_NOT_LOADED, "chunk not loaded: ($cx, $cz)")
            }
        }
    }
}

internal fun screenHandlerProperties(handler: AbstractContainerMenu): List<Int> {
    val field = listOf("properties", "field_7765").firstNotNullOfOrNull { name ->
        runCatching {
            AbstractContainerMenu::class.java.getDeclaredField(name).apply { isAccessible = true }
        }.getOrNull()
    } ?: return emptyList()
    val properties = field.get(handler) as? List<*> ?: return emptyList()
    return properties.mapNotNull { prop ->
        runCatching { prop!!.javaClass.getMethod("get").invoke(prop) as Int }.getOrNull()
    }
}
