package com.mcdebug.test

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mcdebug.McDebugMod
import java.util.concurrent.TimeUnit

/**
 * In-process DSL for writing [McDebugTest] bodies.
 *
 * Each method:
 *   1. Builds the same JSON payload the CLI would send over JSON-RPC
 *   2. Invokes [McDebugMod.dispatcher] (which hops to the MC server thread)
 *   3. Blocks the calling thread on `.get()` until the server completes
 *   4. Returns a typed result (or throws on assertion failure)
 *
 * **Caller must be on a non-server thread** (typically the
 * `mcdebug-test-runner` executor). Calling from the server thread
 * would deadlock: the server thread can't process the dispatched
 * work it's waiting on.
 *
 * Errors from the RPC layer (parse / method-not-found / etc.) surface
 * as raw [RuntimeException]s with the server's error message. Errors
 * raised inside the assertion helpers ([assertBlockId], [assertSlotHas],
 * [assertBeField], [waitUntil]) surface as [AssertionError] for clear
 * PASS/FAIL reporting in the gradle task output.
 */
object McDebugTestApi {
    private val currentContextThreadLocal = ThreadLocal<McDebugTestContext>()

    /**
     * The isolated area assigned to the currently running test.
     *
     * Falls back to a legacy single-position area when test code is executed
     * outside the mcdebug dispatcher, which keeps older ad-hoc tests usable.
     */
    val currentContext: McDebugTestContext
        get() = currentContextThreadLocal.get() ?: legacyContext

    /** Primary position for the currently running test. */
    val testOrigin: Pos
        get() = currentContext.origin

    /** Position relative to the current test origin. */
    fun pos(dx: Int = 0, dy: Int = 0, dz: Int = 0): Pos = currentContext.pos(dx, dy, dz)

    fun <T> withContext(context: McDebugTestContext, block: () -> T): T {
        val previous = currentContextThreadLocal.get()
        currentContextThreadLocal.set(context)
        try {
            return block()
        } finally {
            if (previous == null) currentContextThreadLocal.remove()
            else currentContextThreadLocal.set(previous)
        }
    }

    private val legacyContext = McDebugTestContext(
        testName = "legacy",
        index = 0,
        origin = Pos.TEST_ORIGIN,
        min = Pos.TEST_ORIGIN,
        max = Pos.TEST_ORIGIN,
    )

    // ================================================================
    //  world.*
    // ================================================================

    /** Place a block at [pos]. Equivalent to `world.setBlock`. */
    fun place(pos: Pos, blockId: String) {
        call("world.setBlock", json {
            add("pos", pos.toJson())
            addProperty("block", blockId)
        })
    }

    /**
     * Batch-set multiple blocks.
     *
     * @return number of blocks actually changed
     */
    fun setBlocks(ops: List<SetBlockOp>): Int {
        val result = call("world.setBlocks", json {
            add("ops", JsonArray().apply {
                ops.forEach { op ->
                    add(json {
                        add("pos", op.pos.toJson())
                        addProperty("block", op.blockId)
                        if (op.stateProps != null) add("stateProps", json {
                            op.stateProps.forEach { (k, v) -> addProperty(k, v) }
                        })
                    })
                }
            })
        }).asJsonObject
        return result.get("count").asInt
    }

    /**
     * Place a block as if a player clicked, going through the full BlockItem
     * placement pipeline (directional state, sounds, game events).
     *
     * @param face           which side of neighbor was clicked (up/down/north/south/east/west)
     * @param neighbor       block that was clicked; default = pos - face
     * @param playerFacing   which way the player was looking; default derived from face
     * @param nbt            optional ItemStack NBT (BlockEntityTag, BlockStateTag, etc.)
     * @return true if the placement succeeded
     */
    fun placeAsPlayer(
        pos: Pos, blockId: String, face: String,
        neighbor: Pos? = null, playerFacing: String? = null,
        nbt: JsonObject? = null
    ): Boolean {
        val result = call("world.placeAsPlayer", json {
            add("pos", pos.toJson())
            addProperty("block", blockId)
            addProperty("face", face)
            if (neighbor != null) add("neighbor", neighbor.toJson())
            if (playerFacing != null) addProperty("playerFacing", playerFacing)
            if (nbt != null) add("nbt", nbt)
        }).asJsonObject
        return result.get("ok").asBoolean
    }

    /** Read the block state at [pos]; returns the raw JSON `state` object. */
    fun getBlock(pos: Pos): JsonObject =
        call("world.getBlock", json { add("pos", pos.toJson()) })
            .asJsonObject.getAsJsonObject("state")

    /** Assert the block at [pos] is [expected] (e.g. "ic2_120:iron_furnace"). */
    fun assertBlockId(pos: Pos, expected: String) {
        val state = getBlock(pos)
        val actual = state.get("name").asString
        if (actual != expected) {
            throw AssertionError("expected block '$expected' at $pos, got '$actual'")
        }
    }

    /**
     * Simulate right-clicking with the held item without a block/entity target.
     * Triggers Item.use(world, player, hand).
     */
    fun useItem(
        itemId: String, count: Int = 1, nbt: JsonObject? = null,
        sneaking: Boolean = false
    ): UseResult {
        val result = call("world.useItem", json {
            addProperty("item", itemId)
            addProperty("count", count)
            if (nbt != null) add("nbt", nbt)
            if (sneaking) addProperty("sneaking", true)
        }).asJsonObject
        return UseResult(
            success = result.get("success").asBoolean,
            itemBefore = result.getAsJsonObject("itemBefore"),
            itemAfter = result.getAsJsonObject("itemAfter"),
        )
    }

    /**
     * Simulate right-clicking (using) a block with an item in hand.
     *
     * Mirrors the full interaction pipeline including Fabric API UseBlockCallback
     * that mod handlers (e.g. IC2 wrench rotation) register on.
     *
     * @param face           which face to click (up/down/north/south/east/west)
     * @param item           item id to hold (null for empty hand)
     * @param sneaking       player.isSneaking
     * @param playerFacing   which direction the player faces
     */
    fun useOnBlock(
        pos: Pos, face: String, item: String? = null, count: Int = 1,
        nbt: JsonObject? = null, sneaking: Boolean = false,
        playerFacing: String? = null
    ): UseOnBlockResult {
        val result = call("world.useOnBlock", json {
            add("pos", pos.toJson())
            addProperty("face", face)
            if (item != null) addProperty("item", item)
            addProperty("count", count)
            if (nbt != null) add("nbt", nbt)
            if (sneaking) addProperty("sneaking", true)
            if (playerFacing != null) addProperty("playerFacing", playerFacing)
        }).asJsonObject
        return UseOnBlockResult(
            success = result.get("success").asBoolean,
            eventConsumed = result.get("eventConsumed").asBoolean,
            blockConsumed = result.get("blockConsumed").asBoolean,
            itemConsumed = result.get("itemConsumed").asBoolean,
            blockState = result.getAsJsonObject("blockState"),
            itemBefore = result.getAsJsonObject("itemBefore"),
            itemAfter = result.getAsJsonObject("itemAfter"),
        )
    }

    /**
     * Simulate left-clicking (attacking) a block.
     *
     * Mirrors the full attack pipeline including Fabric API AttackBlockCallback
     * that mod handlers (e.g. IC2 wrench disassembly) register on.
     *
     * @param face  which face was hit
     * @param item  item id to hold (null for empty hand)
     */
    fun attackBlock(
        pos: Pos, face: String, item: String? = null, count: Int = 1,
        nbt: JsonObject? = null
    ): AttackBlockResult {
        val result = call("world.attackBlock", json {
            add("pos", pos.toJson())
            addProperty("face", face)
            if (item != null) addProperty("item", item)
            addProperty("count", count)
            if (nbt != null) add("nbt", nbt)
        }).asJsonObject
        return AttackBlockResult(
            broken = result.get("broken").asBoolean,
            eventConsumed = result.get("eventConsumed").asBoolean,
            blockState = result.getAsJsonObject("blockState"),
        )
    }

    /**
     * Simulate right-clicking (using) an entity.
     *
     * @param entityUuid  UUID of the target entity
     */
    fun interactEntity(
        entityUuid: String, item: String? = null, count: Int = 1,
        nbt: JsonObject? = null, sneaking: Boolean = false,
        playerFacing: String? = null
    ): EntityInteractResult {
        val result = call("world.interactEntity", json {
            addProperty("entityUuid", entityUuid)
            if (item != null) addProperty("item", item)
            addProperty("count", count)
            if (nbt != null) add("nbt", nbt)
            if (sneaking) addProperty("sneaking", true)
            if (playerFacing != null) addProperty("playerFacing", playerFacing)
        }).asJsonObject
        return EntityInteractResult(
            success = result.get("success").asBoolean,
            eventConsumed = result.get("eventConsumed").asBoolean,
            entityConsumed = result.get("entityConsumed").asBoolean,
            itemConsumed = result.get("itemConsumed").asBoolean,
            entityType = result.get("entityType").asString,
        )
    }

    /**
     * Simulate left-clicking (attacking) an entity.
     *
     * @param entityUuid  UUID of the target entity
     */
    fun attackEntity(
        entityUuid: String, item: String? = null, count: Int = 1,
        nbt: JsonObject? = null, playerFacing: String? = null
    ): EntityAttackResult {
        val result = call("world.attackEntity", json {
            addProperty("entityUuid", entityUuid)
            if (item != null) addProperty("item", item)
            addProperty("count", count)
            if (nbt != null) add("nbt", nbt)
            if (playerFacing != null) addProperty("playerFacing", playerFacing)
        }).asJsonObject
        return EntityAttackResult(
            success = result.get("success").asBoolean,
            eventConsumed = result.get("eventConsumed").asBoolean,
            entityType = result.get("entityType").asString,
            entityHealth = result.get("entityHealth").asDouble,
            entityDead = result.get("entityDead").asBoolean,
        )
    }

    /** Get all blocks in a box region. Returns raw JSON `{blocks: [...]}`. */
    fun getRegion(from: Pos, to: Pos, includeNbt: Boolean = false): JsonObject =
        call("world.getRegion", json {
            add("box", json {
                add("from", from.toJson())
                add("to", to.toJson())
            })
            if (includeNbt) addProperty("includeNbt", true)
        }).asJsonObject

    /**
     * Find blocks matching [blockId] in a box region.
     *
     * @return list of positions where the block was found
     */
    fun selectBlocks(from: Pos, to: Pos, blockId: String, includeNbt: Boolean = false): List<Pos> {
        val result = call("world.selectBlocks", json {
            add("box", json {
                add("from", from.toJson())
                add("to", to.toJson())
            })
            add("predicate", json { addProperty("block", blockId) })
            if (includeNbt) addProperty("includeNbt", true)
        }).asJsonObject
        return result.getAsJsonArray("matches").map { el ->
            val a = el.asJsonObject.getAsJsonArray("pos")
            Pos(a[0].asInt, a[1].asInt, a[2].asInt)
        }
    }

    /** Force-load a chunk so its block entities tick. Returns true if newly forced. */
    fun forceloadChunk(cx: Int, cz: Int): Boolean {
        val result = call("world.forceloadChunk", json {
            add("chunk", JsonArray().apply { add(cx); add(cz) })
        }).asJsonObject
        return result.get("changed").asBoolean
    }

    /** Stop force-loading a chunk. Returns true if it was previously forced. */
    fun unforceloadChunk(cx: Int, cz: Int): Boolean {
        val result = call("world.unforceloadChunk", json {
            add("chunk", JsonArray().apply { add(cx); add(cz) })
        }).asJsonObject
        return result.get("changed").asBoolean
    }

    // ================================================================
    //  be.* (BlockEntity)
    // ================================================================

    /** Read the full NBT of the block entity at [pos]. */
    fun getBeNbt(pos: Pos): JsonObject =
        call("be.getNbt", json { add("pos", pos.toJson()) })
            .asJsonObject.getAsJsonObject("nbt")

    /** Overwrite the block entity NBT at [pos] with [nbt]. */
    fun setBeNbt(pos: Pos, nbt: JsonObject) {
        call("be.setNbt", json {
            add("pos", pos.toJson())
            add("nbt", nbt)
        })
    }

    /**
     * Read a specific NBT field from the block entity at [pos].
     *
     * @param path dot-separated NBT path (e.g. "progress", "energy", "Items.0.id")
     */
    fun getBeField(pos: Pos, path: String): JsonElement =
        call("be.getField", json {
            add("pos", pos.toJson())
            addProperty("path", path)
        }).asJsonObject.get("value")

    /** Set a specific NBT field on the block entity at [pos]. */
    fun setBeField(pos: Pos, path: String, value: JsonElement) {
        call("be.setField", json {
            add("pos", pos.toJson())
            addProperty("path", path)
            add("value", value)
        })
    }

    /**
     * Set a specific NBT field to a number value.
     * Convenience overload of [setBeField].
     */
    fun setBeField(pos: Pos, path: String, value: Number) {
        setBeField(pos, path, JsonPrimitive(value))
    }

    /**
     * Set a specific NBT field to a string value.
     * Convenience overload of [setBeField].
     */
    fun setBeField(pos: Pos, path: String, value: String) {
        setBeField(pos, path, JsonPrimitive(value))
    }

    /**
     * Assert a block entity NBT field equals the expected value.
     *
     * For numeric comparisons with tolerance, use [assertBeFieldApprox].
     *
     * @param path     dot-separated NBT path
     * @param expected expected value (Number, String, Boolean, or JsonElement)
     */
    fun assertBeField(pos: Pos, path: String, expected: Any) {
        val actual = getBeField(pos, path)
        val matches = when (expected) {
            is Number -> !actual.isJsonNull &&
                actual.isJsonPrimitive && actual.asJsonPrimitive.isNumber &&
                actual.asDouble == expected.toDouble()
            is String -> !actual.isJsonNull &&
                actual.isJsonPrimitive && actual.asString == expected
            is Boolean -> !actual.isJsonNull &&
                actual.isJsonPrimitive && actual.asBoolean == expected
            is JsonElement -> actual == expected
            else -> actual.toString() == expected.toString()
        }
        if (!matches) {
            throw AssertionError(
                "expected be[$path] at $pos to be $expected, got $actual"
            )
        }
    }

    /**
     * Assert a block entity NBT field is approximately the expected number.
     * Useful for energy, progress, or other values that may not be exact.
     */
    fun assertBeFieldApprox(pos: Pos, path: String, expected: Number, tolerance: Double = 1.0) {
        val actual = getBeField(pos, path)
        if (actual.isJsonNull) {
            throw AssertionError("expected be[$path] at $pos ≈ $expected, got null")
        }
        val actualNum = actual.asDouble
        val expectedNum = expected.toDouble()
        if (kotlin.math.abs(actualNum - expectedNum) > tolerance) {
            throw AssertionError(
                "expected be[$path] at $pos ≈ $expected (±$tolerance), got $actualNum"
            )
        }
    }

    // ================================================================
    //  inv.* (Inventory)
    // ================================================================

    /** Get the size (number of slots) of the inventory at [pos]. */
    fun getInvSize(pos: Pos): Int =
        call("inv.getSize", json { add("pos", pos.toJson()) })
            .asJsonObject.get("size").asInt

    /**
     * Insert [count] of [itemId] into the inventory at [pos].
     *
     * @param slot     specific slot to target (null for first matching)
     * @param simulate if true, don't actually modify the inventory
     * @return how many were inserted and how many remain
     */
    fun insertItem(
        pos: Pos, itemId: String, count: Int = 1,
        slot: Int? = null, simulate: Boolean = false,
        nbt: JsonObject? = null
    ): InsertResult {
        val result = call("inv.insert", json {
            add("pos", pos.toJson())
            addProperty("item", itemId)
            addProperty("count", count)
            if (slot != null) addProperty("slot", slot)
            if (simulate) addProperty("simulate", true)
            if (nbt != null) add("nbt", nbt)
        }).asJsonObject
        return InsertResult(
            inserted = result.get("inserted").asInt,
            remaining = result.get("remaining").asInt,
        )
    }

    /**
     * Read [slot] of the inventory at [pos]. Returns an [ItemStackView]
     * (item id may be null for an empty slot).
     */
    fun getSlot(pos: Pos, slot: Int): ItemStackView {
        val result = call("inv.getSlot", json {
            add("pos", pos.toJson())
            addProperty("slot", slot)
        }).asJsonObject
        val stack = result.getAsJsonObject("slot")
        val itemEl = stack.get("item")
        val nbtEl = stack.get("nbt")
        return ItemStackView(
            item = if (itemEl == null || itemEl.isJsonNull) null else itemEl.asString,
            count = if (stack.has("count")) stack.get("count").asInt else 0,
            nbt = if (nbtEl != null && !nbtEl.isJsonNull && nbtEl.isJsonObject) nbtEl.asJsonObject else null,
        )
    }

    /**
     * Set the contents of [slot] at [pos].
     *
     * @param itemId  item id (default "minecraft:air" = clear slot)
     * @param count   stack size (default 0)
     * @param nbt     optional item NBT
     */
    fun setSlot(
        pos: Pos, slot: Int, itemId: String = "minecraft:air",
        count: Int = 0, nbt: JsonObject? = null
    ) {
        call("inv.setSlot", json {
            add("pos", pos.toJson())
            addProperty("slot", slot)
            addProperty("item", itemId)
            addProperty("count", count)
            if (nbt != null) add("nbt", nbt)
        })
    }

    /**
     * Extract [count] of [itemId] from the inventory at [pos].
     *
     * @param slot     specific slot to extract from (null for any)
     * @param simulate if true, don't actually modify the inventory
     * @return how many were extracted and how many remain to extract
     */
    fun extractItem(
        pos: Pos, itemId: String, count: Int = 1,
        slot: Int? = null, simulate: Boolean = false
    ): ExtractResult {
        val result = call("inv.extract", json {
            add("pos", pos.toJson())
            addProperty("item", itemId)
            addProperty("count", count)
            if (slot != null) addProperty("slot", slot)
            if (simulate) addProperty("simulate", true)
        }).asJsonObject
        return ExtractResult(
            extracted = result.get("extracted").asInt,
            remaining = result.get("remaining").asInt,
        )
    }

    /** Assert that [slot] at [pos] currently holds [expectedItemId]. */
    fun assertSlotHas(pos: Pos, slot: Int, expectedItemId: String) {
        val stack = getSlot(pos, slot)
        if (stack.item != expectedItemId) {
            throw AssertionError(
                "expected slot $slot at $pos to hold '$expectedItemId', " +
                "got '${stack.item ?: "<empty>"}' (count=${stack.count})"
            )
        }
    }

    /** Assert that [slot] at [pos] currently holds exactly [expectedCount] items. */
    fun assertSlotCount(pos: Pos, slot: Int, expectedCount: Int) {
        val stack = getSlot(pos, slot)
        if (stack.count != expectedCount) {
            throw AssertionError(
                "expected slot $slot at $pos to have count $expectedCount, got ${stack.count}"
            )
        }
    }

    /** Assert that [slot] at [pos] is empty. */
    fun assertSlotEmpty(pos: Pos, slot: Int) {
        val stack = getSlot(pos, slot)
        if (stack.item != null) {
            throw AssertionError(
                "expected slot $slot at $pos to be empty, " +
                "got '${stack.item}' (count=${stack.count})"
            )
        }
    }

    // ================================================================
    //  wait.*
    // ================================================================

    /**
     * Block until [predicate] matches or [timeoutTicks] elapse.
     *
     * Predicate grammar (same as `wait.until` RPC):
     *   - `be[x,y,z].path <op> <value>`      — block entity NBT field
     *   - `inv[x,y,z].slot.field <op> <value>` — inventory slot field
     *   - `block[x,y,z].id <op> <value>`      — block id or `prop.<name>`
     *   - `tick <op> <value>`                 — server tick count
     *
     * Operators: `==` `!=` `<` `<=` `>` `>=`
     *
     * Example: `waitUntil("""inv[100,64,100].2.item == "minecraft:iron_ingot"""")`
     */
    fun waitUntil(predicate: String, timeoutTicks: Int = 20 * 60) {
        val result = call("wait.until", json {
            addProperty("predicate", predicate)
            addProperty("timeoutTicks", timeoutTicks)
        }).asJsonObject
        if (!result.get("matched").asBoolean) {
            throw AssertionError(
                "wait.until did not match within $timeoutTicks ticks: $predicate"
            )
        }
    }

    // ================================================================
    //  scan.*
    // ================================================================

    /** Find all blocks of type [blockId] in the box [from]..[to]. */
    fun findBlocks(from: Pos, to: Pos, blockId: String): List<Pos> {
        val result = call("scan.findBlocks", json {
            add("box", json {
                add("from", from.toJson())
                add("to", to.toJson())
            })
            addProperty("block", blockId)
        }).asJsonObject
        return result.getAsJsonArray("positions").map { el ->
            val a = el.asJsonArray
            Pos(a[0].asInt, a[1].asInt, a[2].asInt)
        }
    }

    /**
     * Count all blocks by type in the box [from]..[to].
     *
     * @return map of block ID → count
     */
    fun countByBlock(from: Pos, to: Pos): Map<String, Int> {
        val result = call("scan.countByBlock", json {
            add("box", json {
                add("from", from.toJson())
                add("to", to.toJson())
            })
        }).asJsonObject.getAsJsonObject("counts")
        return result.entrySet().associate { it.key to it.value.asInt }
    }

    /**
     * Find entities in a box region.
     *
     * @param type       optional entity type filter (e.g. "minecraft:zombie")
     * @param includeNbt include full entity NBT
     * @return raw JSON `{entities: [...], count: N}`
     */
    fun findEntities(
        from: Pos, to: Pos, type: String? = null,
        includeNbt: Boolean = false
    ): JsonObject =
        call("scan.findEntities", json {
            add("box", json {
                add("from", from.toJson())
                add("to", to.toJson())
            })
            if (type != null) addProperty("type", type)
            if (includeNbt) addProperty("includeNbt", true)
        }).asJsonObject

    // ================================================================
    //  fluid.*
    // ================================================================

    /**
     * Get fluid storage info at [pos].
     *
     * @param side face to query (null for complete inventory)
     * @return raw JSON with type, supportsInsertion/Extraction, parts[]
     */
    fun fluidInfo(pos: Pos, side: String? = null): JsonObject =
        call("fluid.info", json {
            add("pos", pos.toJson())
            if (side != null) addProperty("side", side)
        }).asJsonObject

    /**
     * Read a specific fluid tank at [pos].
     *
     * @param index tank index (default 0 for single-tank storage)
     * @param side  face to query
     */
    fun fluidGet(pos: Pos, index: Int = 0, side: String? = null): FluidTankView {
        val result = call("fluid.get", json {
            add("pos", pos.toJson())
            addProperty("index", index)
            if (side != null) addProperty("side", side)
        }).asJsonObject
        val fluidEl = result.get("fluid")
        return FluidTankView(
            fluid = if (fluidEl == null || fluidEl.isJsonNull) null else fluidEl.asString,
            amount = result.get("amount").asLong,
            capacity = result.get("capacity").asLong,
        )
    }

    /**
     * Insert fluid into a tank at [pos].
     *
     * @return [TransferResult] with requested/transferred/remaining amounts
     */
    fun fluidInsert(
        pos: Pos, fluidId: String, amount: Long,
        index: Int = 0, side: String? = null
    ): TransferResult {
        val result = call("fluid.insert", json {
            add("pos", pos.toJson())
            addProperty("fluid", fluidId)
            addProperty("amount", amount)
            addProperty("index", index)
            if (side != null) addProperty("side", side)
        }).asJsonObject
        return TransferResult(
            requested = result.get("requested").asLong,
            transferred = result.get("inserted").asLong,
            remaining = result.get("remaining").asLong,
        )
    }

    /**
     * Extract fluid from a tank at [pos].
     *
     * @return [TransferResult] with requested/transferred/remaining amounts
     */
    fun fluidExtract(
        pos: Pos, amount: Long, index: Int = 0, side: String? = null
    ): TransferResult {
        val result = call("fluid.extract", json {
            add("pos", pos.toJson())
            addProperty("amount", amount)
            addProperty("index", index)
            if (side != null) addProperty("side", side)
        }).asJsonObject
        return TransferResult(
            requested = result.get("requested").asLong,
            transferred = result.get("extracted").asLong,
            remaining = result.get("remaining").asLong,
        )
    }

    /** Assert fluid tank amount at [pos] equals [expectedAmount] (±[tolerance]). */
    fun assertFluidAmount(
        pos: Pos, expectedAmount: Long, index: Int = 0,
        side: String? = null, tolerance: Long = 0
    ) {
        val tank = fluidGet(pos, index, side)
        if (kotlin.math.abs(tank.amount - expectedAmount) > tolerance) {
            throw AssertionError(
                "expected fluid amount at $pos to be $expectedAmount (±$tolerance), got ${tank.amount}"
            )
        }
    }

    // ================================================================
    //  craft.*
    // ================================================================

    /**
     * Simulate a crafting operation with a 3×3 grid.
     *
     * @param grid      9-element list of item IDs in row-major order (null = empty slot)
     * @param recipeId  optional specific recipe to use (disambiguates when multiple match)
     * @return [CraftResult] with match status and output details
     */
    fun craft(grid: List<String?>, recipeId: String? = null): CraftResult {
        val result = call("craft.craft", json {
            add("grid", gridToJson(grid))
            if (recipeId != null) addProperty("recipeId", recipeId)
        }).asJsonObject
        val matched = result.get("matched").asBoolean
        return if (matched) {
            CraftResult(
                matched = true,
                recipeId = result.get("recipeId")?.asString,
                recipeType = result.get("recipeType")?.asString,
                resultItem = result.getAsJsonObject("result"),
                remainder = result.getAsJsonArray("remainder"),
            )
        } else {
            CraftResult(matched = false)
        }
    }

    /**
     * Find recipes that match the given grid. Useful for disambiguating
     * before calling [craft] with an explicit [recipeId].
     *
     * @param grid  9-element list of item IDs in row-major order (null = empty)
     * @return list of matching recipe descriptors
     */
    fun findRecipes(grid: List<String?>): List<RecipeMatch> {
        val result = call("craft.find", json {
            add("grid", gridToJson(grid))
        }).asJsonObject
        return result.getAsJsonArray("matches").map { el ->
            val obj = el.asJsonObject
            RecipeMatch(
                recipeId = obj.get("recipeId").asString,
                recipeType = obj.get("recipeType").asString,
                output = obj.get("output").asString,
            )
        }
    }

    // ================================================================
    //  server.*
    // ================================================================

    /**
     * Run a Minecraft command as the server console.
     *
     * Example: `runCommand("/time set day")`
     */
    fun runCommand(command: String): CommandResult {
        val result = call("server.runCommand", json {
            addProperty("command", command)
        }).asJsonObject
        return CommandResult(
            success = result.get("success").asBoolean,
            result = result.get("result").asInt,
            output = result.get("output").asString,
        )
    }

    /** Get server status (mc version, tick, dimensions, etc.). */
    fun serverStatus(): JsonObject =
        call("server.status", json { }).asJsonObject

    /** List all loaded dimensions. */
    fun listDimensions(): List<String> {
        val result = call("server.listDimensions", json { }).asJsonObject
        return result.getAsJsonArray("dims").map { it.asString }
    }

    // ================================================================
    //  internals
    // ================================================================

    private inline fun json(build: JsonObject.() -> Unit): JsonObject =
        JsonObject().apply(build)

    private fun gridToJson(grid: List<String?>): JsonArray = JsonArray().apply {
        for (id in grid) {
            if (id == null) {
                add(JsonNull.INSTANCE)
            } else {
                add(json {
                    addProperty("item", id)
                    addProperty("count", 1)
                })
            }
        }
    }

    private fun call(method: String, params: JsonObject): JsonElement {
        val d = McDebugMod.dispatcher
            ?: error("mcdebug dispatcher not ready (server not started?)")
        val s = McDebugMod.currentServer
            ?: error("mcdebug server not ready (server not started?)")
        return d.dispatch(method, params, s).get(120, TimeUnit.SECONDS)
    }
}

// ================================================================
//  Data classes returned by DSL methods
// ================================================================

/** A block-set operation for [McDebugTestApi.setBlocks]. */
data class SetBlockOp(
    val pos: Pos,
    val blockId: String,
    val stateProps: Map<String, String>? = null,
)

/** Minimal view of an inventory stack, returned by [McDebugTestApi.getSlot]. */
data class ItemStackView(
    /** Block / item id, or null if the slot is empty. */
    val item: String?,
    /** Stack count, 0 for an empty slot. */
    val count: Int,
    /** Item NBT, or null if absent. */
    val nbt: JsonObject? = null,
)

/** Result of inserting items into an inventory. */
data class InsertResult(
    val inserted: Int,
    val remaining: Int,
)

/** Result of extracting items from an inventory. */
data class ExtractResult(
    val extracted: Int,
    val remaining: Int,
)

/** Result of using an item in air ([McDebugTestApi.useItem]). */
data class UseResult(
    val success: Boolean,
    val itemBefore: JsonObject,
    val itemAfter: JsonObject,
)

/** Result of right-clicking a block ([McDebugTestApi.useOnBlock]). */
data class UseOnBlockResult(
    val success: Boolean,
    val eventConsumed: Boolean,
    val blockConsumed: Boolean,
    val itemConsumed: Boolean,
    val blockState: JsonObject,
    val itemBefore: JsonObject,
    val itemAfter: JsonObject,
)

/** Result of left-clicking a block ([McDebugTestApi.attackBlock]). */
data class AttackBlockResult(
    val broken: Boolean,
    val eventConsumed: Boolean,
    val blockState: JsonObject,
)

/** Result of right-clicking an entity ([McDebugTestApi.interactEntity]). */
data class EntityInteractResult(
    val success: Boolean,
    val eventConsumed: Boolean,
    val entityConsumed: Boolean,
    val itemConsumed: Boolean,
    val entityType: String,
)

/** Result of left-clicking an entity ([McDebugTestApi.attackEntity]). */
data class EntityAttackResult(
    val success: Boolean,
    val eventConsumed: Boolean,
    val entityType: String,
    val entityHealth: Double,
    val entityDead: Boolean,
)

/** View of a fluid tank. */
data class FluidTankView(
    /** Fluid id (e.g. "minecraft:water"), or null if tank is empty. */
    val fluid: String?,
    /** Current amount in the tank (droplets / 1/81000 buckets). */
    val amount: Long,
    /** Maximum capacity. */
    val capacity: Long,
)

/** Result of a fluid transfer (insert or extract). */
data class TransferResult(
    val requested: Long,
    val transferred: Long,
    val remaining: Long,
)

/** Result of a crafting operation. */
data class CraftResult(
    val matched: Boolean,
    val recipeId: String? = null,
    val recipeType: String? = null,
    val resultItem: JsonObject? = null,
    val remainder: JsonArray? = null,
)

/** A matching recipe from [McDebugTestApi.findRecipes]. */
data class RecipeMatch(
    val recipeId: String,
    val recipeType: String,
    val output: String,
)

/** Result of running a server command. */
data class CommandResult(
    val success: Boolean,
    val result: Int,
    val output: String,
)
