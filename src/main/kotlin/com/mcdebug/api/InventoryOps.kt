package com.mcdebug.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.ServerContext
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
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
            JsonObject().apply { addProperty("size", inv.size()) }
        }

    private fun getSlot(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val inv = resolveInv(server, p)
            val slot = p.requireString("slot").toIntOrNull() ?: throw RpcException(RpcErrors.INVALID_PARAMS, "slot must be int")
            requireSlot(inv, slot)
            val stack = inv.getStack(slot)
            val max = if (stack.isEmpty) 64 else stack.maxCount
            JsonObject().apply {
                add("slot", ServerContext.itemStackToJson(stack))
                addProperty("maxCount", max)
            }
        }

    private fun setSlot(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val inv = resolveInv(server, p)
            val slot = p.requireString("slot").toIntOrNull() ?: throw RpcException(RpcErrors.INVALID_PARAMS, "slot must be int")
            requireSlot(inv, slot)
            val itemId = p.getStringOrNull("item") ?: "minecraft:air"
            val count = p.getIntOr("count", 0)
            val nbtJson = p.get("nbt")
            val stack = ServerContext.itemStackFromJson(server, itemId, count, nbtJson)
            inv.setStack(slot, stack)
            inv.markDirty()
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
            val targetSlot = if (p.has("slot") && !p.get("slot").isJsonNull) {
                val s = p.get("slot").asInt
                requireSlot(inv, s); s
            } else null

            val stack = ServerContext.itemStackFromJson(server, itemId, count, nbtJson)
            val original = stack.count
            var remaining = stack.count
            if (targetSlot != null) {
                val current = inv.getStack(targetSlot)
                if (current.isEmpty) {
                    if (!simulate) inv.setStack(targetSlot, stack)
                    remaining = 0
                } else if (ItemStack.canCombine(current, stack) && current.count < current.maxCount) {
                    val space = current.maxCount - current.count
                    val toAdd = minOf(space, remaining)
                    if (!simulate) {
                        val newStack = current.copy()
                        newStack.count = current.count + toAdd
                        inv.setStack(targetSlot, newStack)
                    }
                    remaining -= toAdd
                }
            } else {
                // First pass: stack onto existing same-item stacks
                for (i in 0 until inv.size()) {
                    if (remaining <= 0) break
                    val current = inv.getStack(i)
                    if (current.isEmpty || !ItemStack.canCombine(current, stack)) continue
                    if (current.count >= current.maxCount) continue
                    val space = current.maxCount - current.count
                    val toAdd = minOf(space, remaining)
                    if (!simulate) {
                        val newStack = current.copy()
                        newStack.count = current.count + toAdd
                        inv.setStack(i, newStack)
                    }
                    remaining -= toAdd
                }
                // Second pass: fill empty slots
                for (i in 0 until inv.size()) {
                    if (remaining <= 0) break
                    val current = inv.getStack(i)
                    if (!current.isEmpty) continue
                    val toAdd = minOf(stack.maxCount, remaining)
                    val newStack = stack.copy()
                    newStack.count = toAdd
                    if (!simulate) inv.setStack(i, newStack)
                    remaining -= toAdd
                }
            }
            if (!simulate) inv.markDirty()
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
            val targetSlot = if (p.has("slot") && !p.get("slot").isJsonNull) {
                val s = p.get("slot").asInt
                requireSlot(inv, s); s
            } else null

            val target = ServerContext.itemStackFromJson(server, itemId, 1, null)  // exemplar item for type check
            val maxExtract = count
            var remaining = maxExtract
            if (targetSlot != null) {
                val current = inv.getStack(targetSlot)
                if (!current.isEmpty && ItemStack.canCombine(current, target)) {
                    val take = minOf(current.count, remaining)
                    if (!simulate) {
                        val newStack = current.copy()
                        newStack.count = current.count - take
                        inv.setStack(targetSlot, newStack)
                    }
                    remaining -= take
                }
            } else {
                for (i in 0 until inv.size()) {
                    if (remaining <= 0) break
                    val current = inv.getStack(i)
                    if (current.isEmpty || !ItemStack.canCombine(current, target)) continue
                    val take = minOf(current.count, remaining)
                    if (!simulate) {
                        val newStack = current.copy()
                        newStack.count = current.count - take
                        inv.setStack(i, newStack)
                    }
                    remaining -= take
                }
            }
            if (!simulate) inv.markDirty()
            JsonObject().apply {
                addProperty("extracted", maxExtract - remaining)
                addProperty("remaining", remaining)
            }
        }

    // ---- helpers ----

    private fun resolveInv(server: MinecraftServer, p: JsonObject): Inventory {
        val pos = ServerContext.pos(p.getAsJsonArray("pos"))
        val world = ServerContext.world(server, p.getStringOrNull("dim"))
        return ServerContext.inventory(world, pos)
    }

    private fun requireSlot(inv: Inventory, slot: Int) {
        if (slot < 0 || slot >= inv.size()) {
            throw RpcException(RpcErrors.SLOT_OUT_OF_RANGE, "slot $slot out of range [0, ${inv.size()})")
        }
    }
}
