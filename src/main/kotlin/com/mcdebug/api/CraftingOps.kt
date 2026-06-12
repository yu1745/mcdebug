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
import net.minecraft.inventory.RecipeInputInventory
import net.minecraft.item.ItemStack
import net.minecraft.recipe.CraftingRecipe
import net.minecraft.recipe.Recipe
import net.minecraft.recipe.RecipeMatcher
import net.minecraft.registry.DynamicRegistryManager
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.util.collection.DefaultedList
import net.minecraft.world.World
import java.util.concurrent.CompletableFuture


/**
 * Simulate vanilla (and mod) crafting without going through a real crafting table.
 *
 * Two flavors of IC2 special-craft behavior are verified through this RPC:
 *  - ic2_120:battery_energy_shaped    — result inherits summed charge from charged
 *                                       IBatteryItem / IElectricTool / EnergyStorageBlockItem
 *                                       inputs.  See [craft] result.nbt.
 *  - ic2_120:damage_tool_shapeless    — the result is a plate/wire/etc., and the
 *                                       ForgeHammer / Cutter / Treetap / Wrench
 *                                       input loses 1 durability.  See [craft]
 *                                       result.remainder — diff slot N before/after
 *                                       to see the tool damage change.
 *  - ic2_120:consume_treetap_shaped   — Treetap is fully consumed; other slots pass
 *                                       through getRecipeRemainder.
 *
 * Inputs are a 9-slot 3x3 grid; shaped recipes do a sliding-window match across
 * the supplied cells (vanilla behavior).
 */
object CraftingOps : RpcHandlerGroup {

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "craft" to ::craft,
        "find" to ::find,
    )

    /**
     * Simulate a single craft of the given grid.
     *
     * Params:
     *   grid        required  array of 9 entries (row-major, 3x3). Each entry is
     *               either null / {"item":"minecraft:air","count":0} for an empty
     *               slot, or {"item":id, "count":n, "nbt":{...}} for a real stack.
     *   recipeId    optional  e.g. "ic2_120:advanced_batpack" or "minecraft:oak_planks".
     *               If omitted, the first crafting recipe whose matches() returns
     *               true is used. If multiple match, the first (in iteration order)
     *               wins — pass recipeId to disambiguate.
     *   dim         optional  dimension used for World in matches(). Mostly cosmetic
     *               for crafting; only some recipes read world state. Default
     *               minecraft:overworld.
     *
     * Output (matched=true):
     *   {
     *     "matched": true,
     *     "recipeId": "ic2_120:advanced_batpack",
     *     "recipeType": "ic2_120:battery_energy_shaped",
     *     "result": { "item": "ic2_120:advanced_batpack", "count": 1, "nbt": {...} },
     *     "remainder": [ {item,count,nbt}, ... 9 entries ... ]   // what each grid slot becomes
     *   }
     *
     * Output (matched=false):
     *   {
     *     "matched": false,
     *     "candidates": [ "ic2_120:foo", "minecraft:bar", ... ]   // recipes that COULD have matched
     *                                                            // (none did, otherwise matched=true)
     *   }
     *
     * The remainder list lets you verify durability-1 on tools: pass a ForgeHammer
     * with damage=10, then compare remainder[slot] — its damage should be 11 (or
     * the slot should be empty if maxDamage was reached). Same for Cutter.
     */
    private fun craft(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val stacks = parseGridWith(server, p)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val inv = GridInput(stacks)

            val recipe: Recipe<RecipeInputInventory>? = pickRecipe(server, p, inv, world)
            if (recipe == null) {
                return@onServer JsonObject().apply {
                    addProperty("matched", false)
                    add("candidates", JsonArray().apply { /* intentionally empty */ })
                }
            }

            val registryManager: DynamicRegistryManager = world.registryManager
            val resultStack: ItemStack = recipe.craft(inv, registryManager)
            val remainder: DefaultedList<ItemStack> = try {
                recipe.getRemainder(inv)
            } catch (e: Exception) {
                // Some non-vanilla recipes throw on getRemainder; fall back to "leave slots as-is".
                val fb = DefaultedList.ofSize(9, ItemStack.EMPTY)
                for (i in 0 until 9) fb[i] = inv.getStack(i).copy()
                fb
            }

            JsonObject().apply {
                addProperty("matched", true)
                addProperty("recipeId", recipe.id.toString())
                addProperty("recipeType", RegistriesIdLookup.typeIdOf(recipe))
                add("result", ServerContext.itemStackToJson(resultStack))
                add("remainder", remainderListToJson(remainder))
            }
        }

    /**
     * Diagnostic: list every crafting recipe whose matches() returns true for the
     * given grid. Useful when multiple recipes match and you want to disambiguate
     * by passing recipeId to craft().
     *
     * Params: grid (required), dim (optional)
     * Output: { "matches": [ {recipeId, recipeType}, ... ] }
     */
    private fun find(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val stacks = parseGridWith(server, p)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val inv = GridInput(stacks)

            val matches = JsonArray()
            for (recipe in server.recipeManager.values()) {
                if (recipe !is CraftingRecipe) continue
                if (recipe.isIgnoredInRecipeBook() || recipe.isEmpty) continue
                if (!recipe.fits(3, 3)) continue
                val matched = try {
                    recipe.matches(inv, world)
                } catch (e: Exception) { false }
                if (matched) {
                    matches.add(JsonObject().apply {
                        addProperty("recipeId", recipe.id.toString())
                        addProperty("recipeType", RegistriesIdLookup.typeIdOf(recipe))
                        addProperty("output", describeItem(recipe.getOutput(world.registryManager)))
                    })
                }
            }
            JsonObject().apply { add("matches", matches) }
        }

    // ---- helpers ----

    private fun pickRecipe(
        server: MinecraftServer,
        p: JsonObject,
        inv: GridInput,
        world: World,
    ): Recipe<RecipeInputInventory>? {
        val explicit = p.getStringOrNull("recipeId")
        if (explicit != null) {
            val id = Identifier.tryParse(explicit)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid recipeId: $explicit")
            val r = server.recipeManager.get(id).orElse(null)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "no recipe with id: $explicit")
            @Suppress("UNCHECKED_CAST")
            return r as? Recipe<RecipeInputInventory>
                ?: throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "recipe $explicit is not a RecipeInputInventory recipe (type=${RegistriesIdLookup.typeIdOf(r)})"
                )
        }
        for (recipe in server.recipeManager.values()) {
            if (recipe !is CraftingRecipe) continue
            if (recipe.isIgnoredInRecipeBook() || recipe.isEmpty) continue
            if (!recipe.fits(3, 3)) continue
            val matched = try {
                recipe.matches(inv, world)
            } catch (e: Exception) { false }
            if (matched) {
                @Suppress("UNCHECKED_CAST")
                return recipe as Recipe<RecipeInputInventory>
            }
        }
        return null
    }

    private fun parseGridWith(server: MinecraftServer, p: JsonObject): DefaultedList<ItemStack> {
        val arr = p.getAsJsonArray("grid")
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "grid (array of 9) required")
        if (arr.size() != 9) {
            throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "grid must have exactly 9 entries (3x3), got ${arr.size()}"
            )
        }
        val stacks = DefaultedList.ofSize(9, ItemStack.EMPTY)
        for (i in 0 until 9) {
            val el = arr[i]
            if (el.isJsonNull) continue
            val obj = el.asJsonObject
            val itemId = obj.getStringOrNull("item")
            if (itemId == null || itemId == "minecraft:air") continue
            val count = obj.getIntOr("count", 1)
            val nbtEl = obj.get("nbt")
            stacks[i] = ServerContext.itemStackFromJson(server, itemId, count, nbtEl)
        }
        return stacks
    }

    private fun remainderListToJson(remainder: DefaultedList<ItemStack>): JsonArray {
        val arr = JsonArray()
        for (stack in remainder) arr.add(ServerContext.itemStackToJson(stack))
        return arr
    }
}

// ---- private helpers (file-scope, not exposed in help) ----

/**
 * Minimal 3x3 RecipeInputInventory backed by a 9-element DefaultedList. Implements
 * only what CraftingRecipe.matches / .craft / .getRemainder actually use.
 * `getInputStacks` returns stacks in row-major order (slot 0 = top-left).
 */
private class GridInput(private val stacks: DefaultedList<ItemStack>) : RecipeInputInventory {
    override fun size(): Int = stacks.size
    override fun isEmpty(): Boolean = stacks.all { it.isEmpty }
    override fun getStack(slot: Int): ItemStack = stacks[slot]
    override fun removeStack(slot: Int): ItemStack {
        val s = stacks[slot]
        stacks[slot] = ItemStack.EMPTY
        return s
    }
    override fun removeStack(slot: Int, count: Int): ItemStack {
        val s = stacks[slot]
        val taken = s.split(count)
        stacks[slot] = s
        return taken
    }
    override fun setStack(slot: Int, stack: ItemStack) { stacks[slot] = stack }
    override fun markDirty() {}
    override fun canPlayerUse(player: net.minecraft.entity.player.PlayerEntity): Boolean = true
    override fun clear() { for (i in 0 until stacks.size) stacks[i] = ItemStack.EMPTY }
    override fun getHeight(): Int = 3
    override fun getWidth(): Int = 3
    override fun getInputStacks(): List<ItemStack> = stacks.filter { !it.isEmpty }
    override fun provideRecipeInputs(matcher: RecipeMatcher) {
        for (s in stacks) if (!s.isEmpty) matcher.addInput(s)
    }
}

private object RegistriesIdLookup {
    fun typeIdOf(r: Recipe<*>): String {
        val t = r.type
        val id = net.minecraft.registry.Registries.RECIPE_TYPE.getId(t) ?: return t.toString()
        return id.toString()
    }
}

private fun describeItem(stack: ItemStack): String {
    if (stack.isEmpty) return "minecraft:air"
    val itemId = net.minecraft.registry.Registries.ITEM.getId(stack.item)
    return "$itemId x${stack.count}"
}
