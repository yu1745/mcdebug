package com.mcdebug.wait

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mcdebug.api.getIntOr
import com.mcdebug.api.getBoolOrFalse
import com.mcdebug.api.getStringOrNull
import com.mcdebug.api.requireString
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import com.mcdebug.loader.Loader

/**
 * WaitOps — passive observation only. Registers a ServerTickEvents.END_SERVER_TICK callback
 * that evaluates the predicate each natural server tick. Does NOT advance ticks.
 *
 * This follows the project's tick-design principle: the server drives ticks; mcdebug only watches.
 * The one pacing lever is `sprint:true` (wait.until): it asks the vanilla tick-rate manager for a
 * bounded /tick sprint budget, so the engine itself runs its own ticks without the 50 ms throttle.
 * mcdebug still never decides when a tick happens — same rule as AGENTS.md §1.
 */
object WaitOps : RpcHandlerGroup {

    private val log = LoggerFactory.getLogger("mcdebug-wait")
    private val activeWaits = CopyOnWriteArrayList<WaitJob>()

    /** 并发 wait --sprint 的油门所有权：只有最后一个持有者结束才停全局 sprint。只在 server 线程触碰。 */
    internal val sprintOwnership = SprintOwnership()

    /** sprint 预算归属的 server（endSprint/uninstall 收油门用；server 线程写）。 */
    private var sprintServer: MinecraftServer? = null
    private val jobIds = AtomicInteger()

    /** sprint 预算在 timeout 之上的余量：sweep 在 tick 域判超时，最多晚 pollInterval 拍发现。 */
    private const val SPRINT_SLACK_TICKS = 20

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "until" to ::until
    )

    /** Install the passive tick observer through the loader seam. */
    fun install() {
        Loader.api.onServerTick { srv ->
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
        // 收油门：sprint 状态随 server 实例销毁，这里只是礼貌性停止（停服期调用可能失败，忽略）
        if (sprintOwnership.clear()) {
            sprintServer?.let { srv -> runCatching { srv.tickRateManager().stopSprinting() } }
        }
        sprintServer = null
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
                // 断连也要收油门：跳回 server 线程停 sprint（停服期 execute 会被拒，交给 sweep 兑底）
                if (job.sprint) {
                    runCatching { job.server?.execute { endSprint(job) } }
                }
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
        toRemove.forEach { endSprint(it) }
    }

    /**
     * job 终止（命中/超时/求值异常/isDone 清理）后收油门：只有最后一个 sprint 持有者结束才停
     * 全局 sprint。只在 server 线程调用（cancelConnection 路径已用 server.execute 跳回来）。
     */
    private fun endSprint(job: WaitJob) {
        if (!job.sprint) return
        if (sprintOwnership.end(job.id)) {
            val srv = sprintServer ?: job.server ?: return
            runCatching { srv.tickRateManager().stopSprinting() }
                .onFailure { log.debug("stopSprinting after wait failed: {}", it.toString()) }
        }
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
        // 默认有限超时 1200 ticks（1 min @20tps）；0 表示无限等待（需显式传）。
        val timeoutTicks = p.getIntOr("timeoutTicks", 1200).coerceAtLeast(0)
        val pollInterval = p.getIntOr("pollIntervalTicks", 1).coerceAtLeast(1)
        // 快进：向原版 tick-rate manager 申请 sprint 预算（timeout + poll + 余量），服务器以极限速率
        // 推进自然 tick，谓词照常每拍求值。命中/超时/求值异常/断连时自动 stopSprinting()。
        // 这不是 tick 驱动：引擎照常决定何时 tick，只是去掉 50ms 步进（与原版 /tick sprint 同一机制）。
        // 不要与手动 /tick sprint 混用：后申请的预算会覆盖前者。
        val sprint = p.getBoolOrFalse("sprint")
        if (sprint && timeoutTicks == 0) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "sprint requires a finite timeoutTicks (0 means wait forever)")
        }
        val predicate = PredicateExpr.parse(predicateStr)
        val future = CompletableFuture<JsonElement>()
        val connId = RpcContext.currentConnectionId.get()
        val job = WaitJob(
            future, predicate, timeoutTicks, pollInterval, connId,
            sprint = sprint, server = server, id = jobIds.incrementAndGet(),
        )
        try {
            server.execute {
                activeWaits.add(job)
                if (job.sprint) {
                    sprintServer = server
                    sprintOwnership.begin(job.id)
                    runCatching {
                        server.tickRateManager()
                            .requestGameToSprint(job.timeoutTicks + job.pollInterval + SPRINT_SLACK_TICKS)
                    }.onFailure { log.warn("sprint request failed: {}", it.toString()) }
                }
            }
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
            "tick" -> JsonPrimitive(srv.tickCount)
            "block" -> {
                val w = ServerContext.world(srv, null)
                val state = w.getBlockState(pos!!)
                val path = ref.path
                when {
                    path.isEmpty() || path == "id" ->
                        com.google.gson.JsonPrimitive(BuiltInRegistries.BLOCK.getKey(state.block).toString())
                    path.startsWith("prop.") -> {
                        val pname = path.removePrefix("prop.")
                        val prop = state.block.stateDefinition.getProperty(pname)
                            ?: return JsonNull.INSTANCE
                        com.google.gson.JsonPrimitive(state.getValue(prop).toString())
                    }
                    else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "block path must be 'id' or 'prop.<name>'")
                }
            }
            "be" -> {
                val w = ServerContext.world(srv, null)
                val be: BlockEntity? = w.getBlockEntity(pos!!)
                if (be == null) JsonNull.INSTANCE
                else NbtJson.getByPathAsJson(be.saveWithoutMetadata(w.registryAccess()), ref.path)
            }
            "inv" -> {
                val w = ServerContext.world(srv, null)
                val be: BlockEntity? = w.getBlockEntity(pos!!)
                val inv: Container? = be as? Container
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
        val inv = be as? Container ?: return JsonPrimitive(0)
        var sum = 0.0
        var count = 0
        for (slot in 0 until inv.containerSize) {
            val stack = inv.getItem(slot)
            when {
                agg.field == "*" -> {
                    if (!stack.isEmpty) count++
                    sum += stack.count.toDouble()
                }
                agg.field == "count" -> sum += stack.count.toDouble()
                agg.field == "item" -> if (!stack.isEmpty) count++
                agg.field.startsWith("nbt.") -> {
                    val nbt = if (!stack.isEmpty) ServerContext.stackNbt(stack) else null
                    if (nbt != null) {
                        val v = NbtJson.getByPathAsJson(nbt, agg.field.removePrefix("nbt."))
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

    /** Path inside an Container slot. Accepts:
     *    "size" | "<slot>.item" | "<slot>.count" | "<slot>.maxStackSize" | "<slot>.nbt.<jsonPointer>"
     */
    private fun evaluateInvPath(inv: Container, path: String): JsonElement {
        if (path == "size") return com.google.gson.JsonPrimitive(inv.containerSize)
        val parts = path.split(".", limit = 2)
        val slot = parts[0].toIntOrNull()
            ?: throw RpcException(RpcErrors.INVALID_PREDICATE, "inv path slot must be int: $path")
        if (slot < 0 || slot >= inv.containerSize) throw RpcException(RpcErrors.SLOT_OUT_OF_RANGE, "slot $slot out of range")
        val stack: ItemStack = inv.getItem(slot)
        if (parts.size == 1) return com.google.gson.JsonPrimitive(stack.count)
        val field = parts[1]
        return when {
            field == "item" ->
                if (stack.isEmpty) com.google.gson.JsonNull.INSTANCE
                else com.google.gson.JsonPrimitive(BuiltInRegistries.ITEM.getKey(stack.item).toString())
            field == "count" -> com.google.gson.JsonPrimitive(stack.count)
            field == "maxCount" -> com.google.gson.JsonPrimitive(stack.maxStackSize)
            field.startsWith("nbt.") -> {
                val nbt = if (!stack.isEmpty) ServerContext.stackNbt(stack) else null
                if (nbt == null) com.google.gson.JsonNull.INSTANCE
                else NbtJson.getByPathAsJson(nbt, field.removePrefix("nbt."))
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
        /** 该 wait 是否持有 sprint 油门（wait.until sprint:true）。 */
        val sprint: Boolean = false,
        /** 注册时的 server：cancelConnection 从 RPC 线程跳回 server 线程收油门用。 */
        val server: MinecraftServer? = null,
        /** SprintOwnership 的 key（防重复 end）。 */
        val id: Int = -1,
    ) {
        var ticksElapsed: Int = 0
        var lastValue: JsonElement? = null
    }
}

/**
 * sprint 油门所有权计数（纯状态机，可单测）：记录哪些 wait job 持有 sprint。
 * [end] / [clear] 返回“是否应调用 stopSprinting()”（最后一个持有者结束 / 曾有持有者被整体消空）。
 * 只在 server 线程触碰；同一 id 重复 end 是 no-op。
 */
internal class SprintOwnership {
    private val active = mutableSetOf<Int>()

    fun begin(id: Int) {
        active.add(id)
    }

    fun end(id: Int): Boolean = active.remove(id) && active.isEmpty()

    fun clear(): Boolean = active.isNotEmpty().also { active.clear() }
}
