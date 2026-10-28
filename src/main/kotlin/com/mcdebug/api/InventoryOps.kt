package com.mcdebug.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.ServerContext
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture

object InventoryOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "getSize" to ::getSize,
        "getSlot" to ::getSlot,
        "setSlot" to ::setSlot,
        "insert" to ::insert,
        "extract" to ::extract
    )

    private fun getSize(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val inv = resolveInv(server, p)
            JsonObject().apply { addProperty("size", inv.containerSize) }
        }

    private fun getSlot(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val inv = resolveInv(server, p)
            val slot = p.requireInt("slot")
            requireSlot(inv, slot)
            val stack = inv.getItem(slot)
            val max = if (stack.isEmpty) 64 else stack.maxStackSize
            JsonObject().apply {
                add("slot", ServerContext.itemStackToJson(stack))
                addProperty("maxCount", max)
            }
        }

    private fun setSlot(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val inv = resolveInv(server, p)
            val slot = p.requireInt("slot")
            requireSlot(inv, slot)
            val itemId = p.getStringOrNull("item") ?: "minecraft:air"
            val count = p.getIntOr("count", 0)
            val nbtJson = p.get("nbt")
            val stack = ServerContext.itemStackFromJson(server, itemId, count, nbtJson)
            inv.setItem(slot, stack)
            inv.setChanged()
            JsonObject().apply { addProperty("ok", true) }
        }

    private fun insert(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val inv = resolveInv(server, p)
            val itemId = p.requireString("item")
            val count = p.getIntOr("count", 1)
            val nbtJson = p.get("nbt")
            val simulate = p.getBoolOrFalse("simulate")
            val targetSlot = p.getIntOrNull("slot")?.also { requireSlot(inv, it) }

            val stack = ServerContext.itemStackFromJson(server, itemId, count, nbtJson)
            val original = stack.count
            var remaining = stack.count
            if (targetSlot != null) {
                val current = inv.getItem(targetSlot)
                if (current.isEmpty) {
                    if (!simulate) inv.setItem(targetSlot, stack)
                    remaining = 0
                } else if (ItemStack.isSameItemSameComponents(current, stack) && current.count < current.maxStackSize) {
                    val space = current.maxStackSize - current.count
                    val toAdd = minOf(space, remaining)
                    if (!simulate) {
                        val newStack = current.copy()
                        newStack.count = current.count + toAdd
                        inv.setItem(targetSlot, newStack)
                    }
                    remaining -= toAdd
                }
            } else {
                // First pass: stack onto existing same-item stacks
                for (i in 0 until inv.containerSize) {
                    if (remaining <= 0) break
                    val current = inv.getItem(i)
                    if (current.isEmpty || !ItemStack.isSameItemSameComponents(current, stack)) continue
                    if (current.count >= current.maxStackSize) continue
                    val space = current.maxStackSize - current.count
                    val toAdd = minOf(space, remaining)
                    if (!simulate) {
                        val newStack = current.copy()
                        newStack.count = current.count + toAdd
                        inv.setItem(i, newStack)
                    }
                    remaining -= toAdd
                }
                // Second pass: fill empty slots
                for (i in 0 until inv.containerSize) {
                    if (remaining <= 0) break
                    val current = inv.getItem(i)
                    if (!current.isEmpty) continue
                    val toAdd = minOf(stack.maxStackSize, remaining)
                    val newStack = stack.copy()
                    newStack.count = toAdd
                    if (!simulate) inv.setItem(i, newStack)
                    remaining -= toAdd
                }
            }
            if (!simulate) inv.setChanged()
            JsonObject().apply {
                addProperty("inserted", original - remaining)
                addProperty("remaining", remaining)
            }
        }

    private fun extract(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val inv = resolveInv(server, p)
            val itemId = p.requireString("item")
            val count = p.getIntOr("count", 1)
            val simulate = p.getBoolOrFalse("simulate")
            val targetSlot = p.getIntOrNull("slot")?.also { requireSlot(inv, it) }

            val target = ServerContext.itemStackFromJson(server, itemId, 1, null)  // exemplar item for type check
            val maxExtract = count
            var remaining = maxExtract
            if (targetSlot != null) {
                val current = inv.getItem(targetSlot)
                if (!current.isEmpty && ItemStack.isSameItemSameComponents(current, target)) {
                    val take = minOf(current.count, remaining)
                    if (!simulate) {
                        val newStack = current.copy()
                        newStack.count = current.count - take
                        inv.setItem(targetSlot, newStack)
                    }
                    remaining -= take
                }
            } else {
                for (i in 0 until inv.containerSize) {
                    if (remaining <= 0) break
                    val current = inv.getItem(i)
                    if (current.isEmpty || !ItemStack.isSameItemSameComponents(current, target)) continue
                    val take = minOf(current.count, remaining)
                    if (!simulate) {
                        val newStack = current.copy()
                        newStack.count = current.count - take
                        inv.setItem(i, newStack)
                    }
                    remaining -= take
                }
            }
            if (!simulate) inv.setChanged()
            JsonObject().apply {
                addProperty("extracted", maxExtract - remaining)
                addProperty("remaining", remaining)
            }
        }

    // ---- helpers ----

    private fun resolveInv(server: MinecraftServer, p: JsonObject): Container {
        val pos = p.requirePos("pos")
        val world = ServerContext.world(server, p.getStringOrNull("dim"))
        return ServerContext.inventory(world, pos)
    }

    private fun requireSlot(inv: Container, slot: Int) {
        if (slot < 0 || slot >= inv.containerSize) {
            throw RpcException(RpcErrors.SLOT_OUT_OF_RANGE, "slot $slot out of range [0, ${inv.containerSize})")
        }
    }
}
