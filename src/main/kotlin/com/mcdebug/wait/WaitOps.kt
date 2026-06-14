package com.mcdebug.wait

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mcdebug.api.getIntOr
import com.mcdebug.api.getStringOrNull
import com.mcdebug.api.requireString
import com.mcdebug.rpc.RpcContext
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
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WaitOps — passive observation only. Registers a ServerTickEvents.END_SERVER_TICK callback
 * that evaluates the predicate each natural server tick. Does NOT advance ticks.
 *
 * This follows the project's tick-design principle: the server drives ticks; mcdebug only watches.
 */
object WaitOps : RpcHandlerGroup {

    private val log = LoggerFactory.getLogger("mcdebug-wait")
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

    /**
     * Cancel every wait owned by [connId]. Called by RpcServer when the client
     * disconnects, so a dropped connection doesn't leave predicate callbacks
     * running until their timeout. The actual list pruning happens on the next
     * tick sweep (it skips jobs whose future isDone).
     */
    fun cancelConnection(connId: Int) {
        var cancelled = 0
        for (job in activeWaits) {
            if (job.connId == connId && !job.future.isDone) {
                job.future.completeExceptionally(
                    RpcException(RpcErrors.INTERNAL_ERROR, "client disconnected")
                )
                cancelled++
            }
        }
        if (cancelled > 0) log.debug("cancelled {} wait(s) for connection {}", cancelled, connId)
    }

    private fun tickSweep(srv: MinecraftServer) {
        currentServer = srv
        try {
            tickSweepInner(srv)
        } finally {
            currentServer = null
        }
    }

    private fun tickSweepInner(srv: MinecraftServer) {
        val toRemove = mutableListOf<WaitJob>()
        for (job in activeWaits) {
            if (job.future.isDone) { toRemove.add(job); continue }
            job.ticksElapsed++
            if (job.ticksElapsed % job.pollInterval != 0) continue
            val matched = try {
                PredicateExpr.evaluate(job.predicate, ::resolveLeaf, ::resolveAggregate)
            } catch (e: Exception) {
                job.future.completeExceptionally(
                    RpcException(RpcErrors.INTERNAL_ERROR, "predicate eval error: ${e.message}", null, e)
                )
                toRemove.add(job)
                continue
            }
            job.lastValue = com.google.gson.JsonPrimitive(matched)
            if (matched) {
                job.future.complete(buildResult(true, job.ticksElapsed, job.lastValue))
                toRemove.add(job)
            } else if (job.timeoutTicks > 0 && job.ticksElapsed >= job.timeoutTicks) {
                job.future.completeExceptionally(
                    RpcException(RpcErrors.TICK_TIMEOUT, "wait.until timeout after ${job.ticksElapsed} ticks",
                        JsonObject().apply { add("lastValue", job.lastValue) })
                )
                toRemove.add(job)
            }
        }
        activeWaits.removeAll(toRemove)
    }

    /**
     * Handler: parse on the calling thread (safe — just lexer + recursive descent, no eval),
     * then register the job on the server thread, and return the future immediately. The
     * connection thread blocks on future.get() until the tick listener completes the future
     * with a match or timeout.
     */
    private fun until(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
        val predicateStr = p.requireString("predicate")
        val timeoutTicks = p.getIntOr("timeoutTicks", 0)
        val pollInterval = p.getIntOr("pollIntervalTicks", 1).coerceAtLeast(1)
        val predicate = PredicateExpr.parse(predicateStr)
        val future = CompletableFuture<JsonElement>()
        val connId = RpcContext.currentConnectionId.get()
        val job = WaitJob(future, predicate, timeoutTicks, pollInterval, connId)
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

    // ---- world-state resolvers (used by PredicateExpr.evaluator) ----

    /**
     * Resolve a SourceRef (be/inv/block/tick + optional pos + optional path) to its
     * current JSON value. Reuses the same per-source logic as v1; called once per
     * predicate evaluation per leaf node.
     */
    private fun resolveLeaf(ref: PredicateExpr.SourceRef): JsonElement? {
        val srv = currentServer ?: return JsonNull.INSTANCE
        val pos = ref.pos?.let { BlockPos(it.first, it.second, it.third) }
        return when (ref.source) {
            "tick" -> JsonPrimitive(srv.ticks)
            "block" -> {
                val w = ServerContext.world(srv, null)
                val state = w.getBlockState(pos!!)
                val path = ref.path
                when {
                    path.isEmpty() || path == "id" ->
                        com.google.gson.JsonPrimitive(Registries.BLOCK.getId(state.block).toString())
                    path.startsWith("prop.") -> {
                        val pname = path.removePrefix("prop.")
                        val prop = state.block.stateManager.getProperty(pname)
                            ?: return JsonNull.INSTANCE
                        com.google.gson.JsonPrimitive(state.get(prop).toString())
                    }
                    else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "block path must be 'id' or 'prop.<name>'")
                }
            }
            "be" -> {
                val w = ServerContext.world(srv, null)
                val be: BlockEntity? = w.getBlockEntity(pos!!)
                if (be == null) JsonNull.INSTANCE
                else NbtJson.getByPathAsJson(be.createNbt(), ref.path)
            }
            "inv" -> {
                val w = ServerContext.world(srv, null)
                val be: BlockEntity? = w.getBlockEntity(pos!!)
                val inv: Inventory? = be as? Inventory
                if (inv == null) JsonNull.INSTANCE
                else evaluateInvPath(inv, ref.path)
            }
            else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "unknown source: ${ref.source}")
        }
    }

    /**
     * Resolve an aggregate (sum/count) over every slot of an inventory.
     * field "*" → count of non-empty slots (count fn) or total item count (sum fn)
     * field "count" → sum of stack counts
     * field "item" → count of slots holding any item (sum/count identical)
     * field "nbt.<path>" → sum of the numeric nbt field across slots (sum only)
     */
    private fun resolveAggregate(agg: PredicateExpr.Aggregate): JsonElement {
        val srv = currentServer ?: return JsonPrimitive(0)
        val w = ServerContext.world(srv, null)
        val pos = BlockPos(agg.pos.first, agg.pos.second, agg.pos.third)
        val be = w.getBlockEntity(pos) ?: return JsonPrimitive(0)
        val inv = be as? Inventory ?: return JsonPrimitive(0)
        var sum = 0.0
        var count = 0
        for (slot in 0 until inv.size()) {
            val stack = inv.getStack(slot)
            when {
                agg.field == "*" -> {
                    if (!stack.isEmpty) count++
                    sum += stack.count.toDouble()
                }
                agg.field == "count" -> sum += stack.count.toDouble()
                agg.field == "item" -> if (!stack.isEmpty) count++
                agg.field.startsWith("nbt.") -> {
                    if (!stack.isEmpty && stack.nbt != null) {
                        val v = NbtJson.getByPathAsJson(stack.nbt!!, agg.field.removePrefix("nbt."))
                        if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) sum += v.asDouble
                    }
                }
                else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "unknown aggregate field: ${agg.field}")
            }
        }
        return when (agg.fn) {
            "sum" -> JsonPrimitive(sum)
            "count" -> JsonPrimitive(count.toDouble())
            else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "unknown aggregate fn: ${agg.fn}")
        }
    }

    /** Path inside an Inventory slot. Accepts:
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

    /** The server for which the current tick sweep is running. Set in tickSweep so
     * the resolvers above can read it without taking a server param. */
    @Volatile
    private var currentServer: MinecraftServer? = null

    private data class WaitJob(
        val future: CompletableFuture<JsonElement>,
        val predicate: PredicateExpr.Node,
        val timeoutTicks: Int,
        val pollInterval: Int,
        /** Owning RPC connection id, for cancel-on-disconnect. Null if invoked outside a connection. */
        val connId: Int? = null,
    ) {
        var ticksElapsed: Int = 0
        var lastValue: JsonElement? = null
    }
}
