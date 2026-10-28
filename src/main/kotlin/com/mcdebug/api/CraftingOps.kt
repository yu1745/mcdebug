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
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.server.MinecraftServer
import net.minecraft.resources.ResourceLocation
import net.minecraft.core.NonNullList
import net.minecraft.world.level.Level
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
     *   dim         optional  dimension used for Level in matches(). Mostly cosmetic
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
            //create() trims to the occupied bounding box and ignores empty cells, exactly like a real 3x3 grid
            val input = CraftingInput.of(3, 3, stacks)

            val entry: RecipeHolder<Recipe<CraftingInput>>? = pickRecipe(server, p, input, world)
            if (entry == null) {
                return@onServer JsonObject().apply {
                    addProperty("matched", false)
                    add("candidates", JsonArray().apply { /* intentionally empty */ })
                }
            }
            val recipe = entry.value

            val resultStack: ItemStack = recipe.assemble(input, world.registryAccess())
            val remainder: NonNullList<ItemStack> = try {
                recipe.getRemainingItems(input)
            } catch (e: Exception) {
                // Some non-vanilla recipes throw on getRemainder; fall back to "leave slots as-is".
                val fb = NonNullList.withSize(9, ItemStack.EMPTY)
                for (i in 0 until 9) fb[i] = input.getItem(i).copy()
                fb
            }

            JsonObject().apply {
                addProperty("matched", true)
                addProperty("recipeId", entry.id.toString())
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
            val input = CraftingInput.of(3, 3, stacks)

            val matches = JsonArray()
            for (entry in server.recipeManager.getRecipes()) {
                val recipe = entry.value
                if (recipe !is CraftingRecipe) continue
                if (recipe.isSpecial() || recipe.isIncomplete) continue
                if (!recipe.canCraftInDimensions(3, 3)) continue
                val matched = try {
                    recipe.matches(input, world)
                } catch (e: Exception) { false }
                if (matched) {
                    matches.add(JsonObject().apply {
                        addProperty("recipeId", entry.id.toString())
                        addProperty("recipeType", RegistriesIdLookup.typeIdOf(recipe))
                        addProperty("output", describeItem(recipe.getResultItem(world.registryAccess())))
                    })
                }
            }
            JsonObject().apply { add("matches", matches) }
        }

    // ---- helpers ----

    private fun pickRecipe(
        server: MinecraftServer,
        p: JsonObject,
        input: CraftingInput,
        world: Level,
    ): RecipeHolder<Recipe<CraftingInput>>? {
        val explicit = p.getStringOrNull("recipeId")
        if (explicit != null) {
            val id = ResourceLocation.tryParse(explicit)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "invalid recipeId: $explicit")
            val found = server.recipeManager.byKey(id).orElse(null)
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "no recipe with id: $explicit")
            @Suppress("UNCHECKED_CAST")
            val r = found.value as? Recipe<CraftingInput>
                ?: throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "recipe $explicit is not a crafting-grid recipe (type=${RegistriesIdLookup.typeIdOf(found.value)})"
                )
            return RecipeHolder(found.id, r)
        }
        for (entry in server.recipeManager.getRecipes()) {
            val recipe = entry.value
            if (recipe !is CraftingRecipe) continue
            if (recipe.isSpecial() || recipe.isIncomplete) continue
            if (!recipe.canCraftInDimensions(3, 3)) continue
            val matched = try {
                recipe.matches(input, world)
            } catch (e: Exception) { false }
            if (matched) {
                @Suppress("UNCHECKED_CAST")
                return entry as RecipeHolder<Recipe<CraftingInput>>
            }
        }
        return null
    }

    private fun parseGridWith(server: MinecraftServer, p: JsonObject): NonNullList<ItemStack> {
        val arr = p.getArrayOrNull("grid")
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "grid (array of 9) required")
        if (arr.size() != 9) {
            throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "grid must have exactly 9 entries (3x3), got ${arr.size()}"
            )
        }
        val stacks = NonNullList.withSize(9, ItemStack.EMPTY)
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

    private fun remainderListToJson(remainder: NonNullList<ItemStack>): JsonArray {
        val arr = JsonArray()
        for (stack in remainder) arr.add(ServerContext.itemStackToJson(stack))
        return arr
    }
}

// ---- private helpers (file-scope, not exposed in help) ----

private object RegistriesIdLookup {
    fun typeIdOf(r: Recipe<*>): String {
        val t = r.type
        val id = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(t) ?: return t.toString()
        return id.toString()
    }
}

private fun describeItem(stack: ItemStack): String {
    if (stack.isEmpty) return "minecraft:air"
    val itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)
    return "$itemId x${stack.count}"
}
