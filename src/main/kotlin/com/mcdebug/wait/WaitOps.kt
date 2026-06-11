package com.mcdebug.wait

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.api.getIntOr
import com.mcdebug.api.getStringOrNull
import com.mcdebug.api.requireString
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.minecraft.block.entity.BlockEntity
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WaitOps — passive observation only. Registers a ServerTickEvents.END_SERVER_TICK callback
 * that evaluates the predicate each natural server tick. Does NOT advance ticks.
 *
 * This follows the project's tick-design principle: the server drives ticks; mcdebug only watches.
 */
object WaitOps : RpcHandlerGroup {

    private val activeWaits = CopyOnWriteArrayList<WaitJob>()

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "until" to ::until
    )

    /** Install the tick listener. Called from McDebugMod's OnServerStarted hook. */
    fun install() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { srv ->
            tickSweep(srv)
        }
    }

    /** Called on server stopping. Cancels all pending waits. */
    fun uninstall() {
        activeWaits.forEach { job ->
            if (!job.future.isDone) {
                job.future.completeExceptionally(
                    RpcException(RpcErrors.INTERNAL_ERROR, "server shutting down")
                )
            }
        }
        activeWaits.clear()
    }

    private fun tickSweep(srv: MinecraftServer) {
        val toRemove = mutableListOf<WaitJob>()
        for (job in activeWaits) {
            if (job.future.isDone) { toRemove.add(job); continue }
            job.ticksElapsed++
            if (job.ticksElapsed % job.pollInterval != 0) continue
            val v = try { job.predicate.evaluate(srv) } catch (e: Exception) {
                job.future.completeExceptionally(
                    RpcException(RpcErrors.INTERNAL_ERROR, "predicate eval error: ${e.message}", null, e)
                )
                toRemove.add(job)
                continue
            }
            job.lastValue = v
            if (job.predicate.matches(v)) {
                job.future.complete(buildResult(true, job.ticksElapsed, v))
                toRemove.add(job)
            } else if (job.timeoutTicks > 0 && job.ticksElapsed >= job.timeoutTicks) {
                job.future.completeExceptionally(
                    RpcException(RpcErrors.TICK_TIMEOUT, "wait.until timeout after ${job.ticksElapsed} ticks",
                        JsonObject().apply { add("lastValue", v ?: com.google.gson.JsonNull.INSTANCE) })
                )
                toRemove.add(job)
            }
        }
        activeWaits.removeAll(toRemove)
    }

    /**
     * Handler: parse on the calling thread (safe — just regex), then register the job on the server thread,
     * and return the future immediately. The connection thread blocks on future.get() until the tick listener
     * completes the future with a match or timeout.
     */
    private fun until(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        val predicateStr = p.requireString("predicate")
        val timeoutTicks = p.getIntOr("timeoutTicks", 0)
        val pollInterval = p.getIntOr("pollIntervalTicks", 1).coerceAtLeast(1)
        val predicate = parsePredicate(predicateStr)
        val future = CompletableFuture<JsonElement>()
        val job = WaitJob(future, predicate, timeoutTicks, pollInterval)
        try {
            server.execute { activeWaits.add(job) }
        } catch (e: Exception) {
            future.completeExceptionally(RpcException(RpcErrors.INTERNAL_ERROR, "server shutting down"))
        }
        return future
    }

    private fun buildResult(matched: Boolean, ticks: Int, value: JsonElement?): JsonObject =
        JsonObject().apply {
            addProperty("matched", matched)
            addProperty("ranTicks", ticks)
            if (value != null) add("value", value)
        }

    // ---- predicate parsing ----

    private fun parsePredicate(s: String): ParsedPredicate {
        val m = PREDICATE_RE.matchEntire(s.trim())
            ?: throw RpcException(RpcErrors.INVALID_PREDICATE, "predicate must match grammar: be/inv/block[x,y,z].path <op> <value> or tick <op> <value>")
        // Group indices (1-based):
        //   1: source (be|inv|block|tick)
        //   2: full [x,y,z] (or "")
        //   3..5: x, y, z (or "")
        //   6: path (or "")
        //   7: op
        //   8: raw literal
        val source = m.groupValues[1]
        val x = m.groupValues[3].toIntOrNull()
        val y = m.groupValues[4].toIntOrNull()
        val z = m.groupValues[5].toIntOrNull()
        val path = m.groupValues[6].removePrefix(".")
        val op = m.groupValues[7]
        val raw = m.groupValues[8]
        val literal = parseLiteral(raw)
        val pos = if (x != null && y != null && z != null) BlockPos(x, y, z) else null
        val evaluator: (MinecraftServer) -> JsonElement? = when (source) {
            "tick" -> { srv -> com.google.gson.JsonPrimitive(srv.ticks) }
            "block" -> { srv ->
                val w = ServerContext.world(srv, null)
                val state = w.getBlockState(pos!!)
                when {
                    path.isEmpty() || path == "id" ->
                        com.google.gson.JsonPrimitive(Registries.BLOCK.getId(state.block).toString())
                    path.startsWith("prop.") -> {
                        val pname = path.removePrefix("prop.")
                        val prop = state.block.stateManager.getProperty(pname)
                        if (prop == null) com.google.gson.JsonNull.INSTANCE
                        else com.google.gson.JsonPrimitive(state.get(prop).toString())
                    }
                    else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "block path must be 'id' or 'prop.<name>'")
                }
            }
            "be" -> { srv ->
                val w = ServerContext.world(srv, null)
                val be: BlockEntity? = w.getBlockEntity(pos!!)
                if (be == null) com.google.gson.JsonNull.INSTANCE
                else NbtJson.getByPathAsJson(be.createNbt(), path)
            }
            "inv" -> { srv ->
                val w = ServerContext.world(srv, null)
                val be: BlockEntity? = w.getBlockEntity(pos!!)
                val inv: Inventory? = be as? Inventory
                if (inv == null) com.google.gson.JsonNull.INSTANCE
                else evaluateInvPath(inv, path)
            }
            else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "unknown source: $source")
        }
        return ParsedPredicate(evaluator, op, literal)
    }

    /** Path inside an Inventory. Accepts:
     *    "size" | "<slot>.item" | "<slot>.count" | "<slot>.maxCount" | "<slot>.nbt.<jsonPointer>"
     */
    private fun evaluateInvPath(inv: Inventory, path: String): JsonElement {
        if (path == "size") return com.google.gson.JsonPrimitive(inv.size())
        val parts = path.split(".", limit = 2)
        val slot = parts[0].toIntOrNull()
            ?: throw RpcException(RpcErrors.INVALID_PREDICATE, "inv path slot must be int: $path")
        if (slot < 0 || slot >= inv.size()) throw RpcException(RpcErrors.SLOT_OUT_OF_RANGE, "slot $slot out of range")
        val stack: ItemStack = inv.getStack(slot)
        if (parts.size == 1) return com.google.gson.JsonPrimitive(stack.count)
        val field = parts[1]
        return when {
            field == "item" ->
                if (stack.isEmpty) com.google.gson.JsonNull.INSTANCE
                else com.google.gson.JsonPrimitive(Registries.ITEM.getId(stack.item).toString())
            field == "count" -> com.google.gson.JsonPrimitive(stack.count)
            field == "maxCount" -> com.google.gson.JsonPrimitive(stack.maxCount)
            field.startsWith("nbt.") -> {
                if (stack.isEmpty || stack.nbt == null) com.google.gson.JsonNull.INSTANCE
                else NbtJson.getByPathAsJson(stack.nbt!!, field.removePrefix("nbt."))
            }
            else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "unknown inv field: $field")
        }
    }

    private fun parseLiteral(s: String): JsonElement? = when {
        s == "null" -> null
        s == "true" -> com.google.gson.JsonPrimitive(true)
        s == "false" -> com.google.gson.JsonPrimitive(false)
        s.startsWith("\"") && s.endsWith("\"") && s.length >= 2 ->
            com.google.gson.JsonPrimitive(s.substring(1, s.length - 1).replace("\\\"", "\""))
        s.toLongOrNull() != null -> com.google.gson.JsonPrimitive(s.toLong())
        s.toDoubleOrNull() != null -> com.google.gson.JsonPrimitive(s.toDouble())
        else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "invalid literal: $s")
    }

    /** Grammar:
     *   tick <op> <literal>
     *   (be|inv|block)(\[(-?\d+),(-?\d+),(-?\d+)\])?(\.[\w.\[\]]+)? <op> <literal>
     */
    private val PREDICATE_RE = Regex(
        """^(tick|be|inv|block)(\[(-?\d+),(-?\d+),(-?\d+)\])?(\.[\w.\[\]]+)?\s*(==|!=|<=|>=|<|>)\s*(\S+)$"""
    )

    private data class ParsedPredicate(
        val evaluator: (MinecraftServer) -> JsonElement?,
        val op: String,
        val literal: JsonElement?
    ) {
        fun matches(value: JsonElement?): Boolean {
            if (literal == null) {
                return when (op) {
                    "==" -> value == null || value.isJsonNull
                    "!=" -> value != null && !value.isJsonNull
                    else -> false
                }
            }
            if (value == null || value.isJsonNull) return op == "!="
            val l = literal
            if (l.isJsonPrimitive && l.asJsonPrimitive.isNumber && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                val a = value.asDouble
                val b = l.asDouble
                return when (op) {
                    "==" -> a == b; "!=" -> a != b
                    "<" -> a < b; "<=" -> a <= b
                    ">" -> a > b; ">=" -> a >= b
                    else -> false
                }
            }
            if (l.isJsonPrimitive && l.asJsonPrimitive.isBoolean && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                val a = value.asBoolean; val b = l.asBoolean
                return when (op) { "==" -> a == b; "!=" -> a != b; else -> false }
            }
            if (l.isJsonPrimitive && l.asJsonPrimitive.isString && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                val a = value.asString; val b = l.asString
                return when (op) { "==" -> a == b; "!=" -> a != b; else -> false }
            }
            return false
        }

        fun evaluate(server: MinecraftServer): JsonElement? = evaluator(server)
    }

    private data class WaitJob(
        val future: CompletableFuture<JsonElement>,
        val predicate: ParsedPredicate,
        val timeoutTicks: Int,
        val pollInterval: Int
    ) {
        var ticksElapsed: Int = 0
        var lastValue: JsonElement? = null
    }
}
