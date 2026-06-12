package com.mcdebug.test

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.McDebugMod
import java.util.concurrent.TimeUnit

/**
 * In-process DSL for writing [McDebugTest] bodies.
 *
 * Each method:
 *   1. Builds the same JSON payload the CLI would send over JSON-RPC
 *   2. Invokes [McDebugMod.dispatcher] (which hops to the MC server thread)
 *   3. Blocks the calling thread on `.get()` until the server completes
 *   4. Returns the result (or throws on assertion failure)
 *
 * **Caller must be on a non-server thread** (typically the
 * `mcdebug-test-runner` executor). Calling from the server thread
 * would deadlock: the server thread can't process the dispatched
 * work it's waiting on.
 *
 * Errors from the RPC layer (parse / method-not-found / etc.) surface
 * as raw [RuntimeException]s with the server's error message. Errors
 * raised inside the assertion helpers ([assertBlockId], [assertSlotHas],
 * [waitUntil]) surface as [AssertionError] for clear PASS/FAIL
 * reporting in the gradle task output.
 */
object McDebugTestApi {
    /** Place a block at [pos]. */
    fun place(pos: Pos, blockId: String) {
        call("world.setBlock", json {
            add("pos", pos.toJson())
            addProperty("block", blockId)
        })
    }

    /** Read the block at [pos]; returns the raw JSON `state` object. */
    fun getBlock(pos: Pos): JsonObject =
        call("world.getBlock", json { add("pos", pos.toJson()) })
            .asJsonObject
            .getAsJsonObject("state")

    /** Assert the block at [pos] is `expected` (e.g. `"ic2_120:iron_furnace"`). */
    fun assertBlockId(pos: Pos, expected: String) {
        val state = getBlock(pos)
        val actual = state.get("name").asString
        if (actual != expected) {
            throw AssertionError("expected block '$expected' at $pos, got '$actual'")
        }
    }

    /**
     * Insert [count] of [itemId] into the inventory at [pos]. If [slot]
     * is given, routes to a specific slot; otherwise uses the
     * inventory's first matching slot.
     */
    fun insertItem(pos: Pos, itemId: String, count: Int = 1, slot: Int? = null) {
        call("inv.insert", json {
            add("pos", pos.toJson())
            addProperty("item", itemId)
            addProperty("count", count)
            if (slot != null) addProperty("slot", slot)
        })
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
        return ItemStackView(
            item = if (itemEl == null || itemEl.isJsonNull) null else itemEl.asString,
            count = if (stack.has("count")) stack.get("count").asInt else 0,
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

    /**
     * Block until [predicate] matches or [timeoutTicks] elapse.
     * Predicate grammar is the same as the `wait.until` RPC; see
     * `WaitOps.kt` for the full syntax (`be[x,y,z].path <op> <value>`
     * / `inv[x,y,z].slot.field <op> <value>` / `block[x,y,z].id <op> <value>` /
     * `tick <op> <value>`, ops: `== != < <= > >=`).
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

    // ---- internals ----

    private inline fun json(build: JsonObject.() -> Unit): JsonObject =
        JsonObject().apply(build)

    private fun call(method: String, params: JsonObject): JsonElement {
        val d = McDebugMod.dispatcher
            ?: error("mcdebug dispatcher not ready (server not started?)")
        val s = McDebugMod.currentServer
            ?: error("mcdebug server not ready (server not started?)")
        return d.dispatch(method, params, s).get(120, TimeUnit.SECONDS)
    }
}

/** Minimal view of an inventory stack, returned by [McDebugTestApi.getSlot]. */
data class ItemStackView(
    /** Block / item id, or null if the slot is empty. */
    val item: String?,
    /** Stack count, 0 for an empty slot. */
    val count: Int,
)
