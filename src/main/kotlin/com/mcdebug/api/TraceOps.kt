package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.McDebugMod
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.ServerContext
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockBox
import java.util.UUID
import java.util.concurrent.CompletableFuture

object TraceOps : RpcHandlerGroup {
    private val traces = LinkedHashMap<String, ActiveTrace>()

    /** 已自动停止（maxTicks 到期）或手动 stop 的 trace，供 trace.get 读取。容量上限，超出淘汰最旧。 */
    private val finished = LinkedHashMap<String, ActiveTrace>()
    private var installed = false

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "start" to ::start,
        "stop" to ::stop,
        "get" to ::get,
    )

    fun install() {
        if (installed) return
        installed = true
        ServerTickEvents.END_SERVER_TICK.register { server -> tick(server) }
    }

    fun uninstall() {
        traces.clear()
        finished.clear()
    }

    private fun start(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val box = boxFromParams(p)
            val include = SnapshotOps.includeKinds(p)
            val interval = p.getIntOr("intervalTicks", 1).coerceAtLeast(1)
            // maxTicks: 达到该时长（相对 start）后自动停止；0 / 缺省 = 不自动停止（直到手动 trace stop）。
            val maxTicks = p.getIntOr("maxTicks", 0).coerceAtLeast(0)
            ensureChunksLoaded(world, box)
            val id = UUID.randomUUID().toString()
            val trace = ActiveTrace(id, world, box, include, interval, maxTicks, server.ticks)
            traces[id] = trace
            captureFrame(server, trace)
            JsonObject().apply {
                addProperty("traceId", id)
                addProperty("startedTick", trace.startedTick)
                addProperty("intervalTicks", interval)
                addProperty("maxTicks", maxTicks)
                addProperty("frames", trace.frames.size)
            }
        }

    private fun stop(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val id = p.requireString("traceId")
            val trace = traces.remove(id) ?: finished.remove(id) ?: throwTraceNotFound(id)
            traceToJson(trace, active = false, autoStopped = trace.autoStopped)
        }

    private fun get(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val id = p.requireString("traceId")
            val trace = traces[id] ?: finished[id] ?: throwTraceNotFound(id)
            traceToJson(trace, active = traces.containsKey(id), autoStopped = trace.autoStopped)
        }

    private fun tick(server: MinecraftServer) {
        if (traces.isEmpty()) return
        val snapshot = traces.values.toList()
        for (trace in snapshot) {
            // maxTicks 到期：抓最后一帧后自动停止，移入 finished 供 trace.get 继续读取。
            if (trace.maxTicks > 0 && server.ticks - trace.startedTick >= trace.maxTicks) {
                try {
                    captureFrame(server, trace)
                } catch (e: Exception) {
                    McDebugMod.LOGGER.warn("trace {} final capture failed", trace.id, e)
                }
                trace.autoStopped = true
                traces.remove(trace.id)
                archiveFinished(trace)
                McDebugMod.LOGGER.info("trace {} auto-stopped after {} ticks (maxTicks={})", trace.id, server.ticks - trace.startedTick, trace.maxTicks)
                continue
            }
            if (server.ticks - trace.lastCaptureTick < trace.intervalTicks) continue
            try {
                captureFrame(server, trace)
            } catch (e: Exception) {
                McDebugMod.LOGGER.warn("trace {} capture failed; stopping trace", trace.id, e)
                traces.remove(trace.id)
                archiveFinished(trace)
            }
        }
    }

    /** 移入 finished 表并限制容量（防自动停止的 trace 无限堆积）。 */
    private fun archiveFinished(trace: ActiveTrace) {
        finished.remove(trace.id)
        finished[trace.id] = trace
        while (finished.size > MAX_FINISHED_TRACES) {
            finished.remove(finished.keys.first())
        }
    }

    private fun captureFrame(server: MinecraftServer, trace: ActiveTrace) {
        val snapshot = SnapshotOps.captureSnapshot(server, trace.world, trace.box, trace.include)
        trace.frames.add(JsonObject().apply {
            addProperty("tick", server.ticks)
            add("snapshot", snapshot)
        })
        trace.lastCaptureTick = server.ticks
    }

    private fun traceToJson(trace: ActiveTrace, active: Boolean, autoStopped: Boolean = false): JsonObject =
        JsonObject().apply {
            addProperty("traceId", trace.id)
            addProperty("active", active)
            addProperty("autoStopped", autoStopped)
            addProperty("dim", trace.world.registryKey.value.toString())
            addProperty("startedTick", trace.startedTick)
            addProperty("intervalTicks", trace.intervalTicks)
            addProperty("maxTicks", trace.maxTicks)
            add("include", JsonArray().apply { trace.include.forEach { add(it.wireName) } })
            add("frames", JsonArray().apply { trace.frames.forEach { add(it) } })
        }

    private fun throwTraceNotFound(id: String): Nothing {
        val data = JsonObject().apply {
            addProperty("reason", "TRACE_NOT_FOUND")
            addProperty("traceId", id)
        }
        throw RpcException(RpcErrors.TRACE_NOT_FOUND, "trace not found: $id", data)
    }

    private data class ActiveTrace(
        val id: String,
        val world: ServerWorld,
        val box: BlockBox,
        val include: Set<SnapshotKind>,
        val intervalTicks: Int,
        val maxTicks: Int,
        val startedTick: Int,
        val frames: MutableList<JsonObject> = mutableListOf(),
        var lastCaptureTick: Int = Int.MIN_VALUE,
        var autoStopped: Boolean = false,
    )

    private const val MAX_FINISHED_TRACES = 64
}
