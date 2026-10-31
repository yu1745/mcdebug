package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.McDebug
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.ServerContext
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import kotlin.math.max
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture
import com.mcdebug.loader.Loader

object ServerOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "status" to ::status,
        "health" to ::health,
        "listDimensions" to ::listDimensions,
        "runCommand" to ::runCommand,
    )

    /**
     * 聚合健康信息（0.6.0+）：TPS/MSPT（MinecraftServer.tickTime 的平均 tick 耗时）、
     * 各维度实体数与已加载区块数。旧服务端（<0.6.0）无此方法（method not found）。
     */
    private fun health(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val mspt = server.currentSmoothedTickTime
            // 加速（原版 /tick rate、/tick sprint）下 1000/mspt 可以远大于 20：上限跟随目标速率。
            // sprint 期间引擎不再按 50ms 步进（l=0），上限即原版命令的最大速率 10000。
            val trm = server.tickRateManager()
            val tpsCap = if (trm.isSprinting()) 10000.0 else max(20.0, trm.tickrate().toDouble())
            val tps = if (mspt > 0f) (1000.0 / mspt).coerceAtMost(tpsCap) else tpsCap
            val dims = JsonArray()
            server.getAllLevels().forEach { w ->
                dims.add(JsonObject().apply {
                    addProperty("dim", w.dimension().location().toString())
                    addProperty("entities", w.getAllEntities().count())
                    addProperty("loadedChunks", w.chunkSource.getLoadedChunksCount())
                })
            }
            JsonObject().apply {
                addProperty("tps", round2(tps))
                addProperty("mspt", round2(mspt.toDouble()))
                addProperty("tick", server.tickCount)
                addProperty("players", server.playerCount)
                add("dims", dims)
            }
        }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun status(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val dims = JsonArray()
            server.getAllLevels().forEach { dims.add(it.dimension().location().toString()) }
            val overworld = server.getLevel(Level.OVERWORLD)
            val dayTime = overworld?.getDayTime() ?: 0L
            JsonObject().apply {
                addProperty("mcVersion", server.getServerVersion())
                addProperty("modVersion", McDebug.MOD_VERSION)
                addProperty("modLoader", Loader.api.name)
                addProperty("fluidUnit", Loader.api.fluidUnit)
                addProperty("energyUnit", Loader.api.energyUnit)
                addProperty("protocolVersion", 1)
                add("dims", dims)
                addProperty("players", server.playerCount)
                addProperty("dayTime", dayTime)
                addProperty("tick", server.tickCount)
            }
        }

    private fun listDimensions(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val dims = JsonArray()
            server.getAllLevels().forEach { dims.add(it.dimension().location().toString()) }
            JsonObject().apply { add("dims", dims) }
        }

    /**
     * Run a Minecraft command as the server console.
     *
     * Input:  { "command": "/time set day", "dim": "minecraft:overworld" }   (dim optional)
     * Output: { "success": true, "result": 1, "output": "Set the time to 1000" }
     *
     * Notes:
     *  - The command string should include the leading "/" (e.g. "/time set day").
     *  - The "dim" param only changes the executor's current dimension; commands like
     *    /time set operate globally regardless.
     *  - This is equivalent to running the command at the server console; no player context.
     */
    private fun runCommand(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val command = p.requireString("command")
            val dim = p.getStringOrNull("dim")?.let {
                ServerContext.world(server, it)  // validate dim
            }
            // Build a command source rooted in the requested dimension (or overworld as default).
            val sourceWorld: net.minecraft.server.level.ServerLevel =
                dim ?: server.getLevel(Level.OVERWORLD)!!
            val source: CommandSourceStack = server.createCommandSourceStack().withLevel(sourceWorld)

            val output = StringBuilder()
            // Capture the textual output the command would print.
            val capturing = object : CommandSource {
                override fun sendSystemMessage(message: Component) {
                    if (output.isNotEmpty()) output.append('\n')
                    output.append(message.string)
                }
                override fun acceptsSuccess(): Boolean = true
                override fun acceptsFailure(): Boolean = true
                override fun shouldInformAdmins(): Boolean = true
            }
            val sourceWithCapture = source.withSource(capturing)

            //executeWithPrefix returns void in 1.21; parse+execute on the dispatcher keeps the int result
            val result: Int = try {
                val commands = server.getCommands()
                commands.dispatcher.execute(commands.dispatcher.parse(command.removePrefix("/"), sourceWithCapture))
            } catch (e: Exception) {
                throw RpcException(
                    RpcErrors.INTERNAL_ERROR,
                    "command execution threw: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
            JsonObject().apply {
                addProperty("success", result > 0)
                addProperty("result", result)
                addProperty("output", output.toString())
            }
        }
}
