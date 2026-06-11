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
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandOutput
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.world.World
import java.util.concurrent.CompletableFuture

object ServerOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "status" to ::status,
        "listDimensions" to ::listDimensions,
        "runCommand" to ::runCommand,
    )

    private fun status(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val dims = JsonArray()
            server.worlds.forEach { dims.add(it.registryKey.value.toString()) }
            val overworld = server.getWorld(World.OVERWORLD)
            val dayTime = overworld?.timeOfDay ?: 0L
            JsonObject().apply {
                addProperty("mcVersion", server.version)
                addProperty("modVersion", McDebugMod.MOD_VERSION)
                addProperty("modLoader", "fabric")
                addProperty("protocolVersion", 1)
                add("dims", dims)
                addProperty("players", server.currentPlayerCount)
                addProperty("dayTime", dayTime)
                addProperty("tick", server.ticks)
            }
        }

    private fun listDimensions(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val dims = JsonArray()
            server.worlds.forEach { dims.add(it.registryKey.value.toString()) }
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
            val sourceWorld: net.minecraft.server.world.ServerWorld =
                dim ?: server.getWorld(World.OVERWORLD)!!
            val source: ServerCommandSource = server.commandSource.withWorld(sourceWorld)

            val output = StringBuilder()
            // Capture the textual output the command would print.
            val capturing = object : CommandOutput {
                override fun sendMessage(message: Text) {
                    if (output.isNotEmpty()) output.append('\n')
                    output.append(message.string)
                }
                override fun shouldReceiveFeedback(): Boolean = true
                override fun shouldTrackOutput(): Boolean = true
                override fun shouldBroadcastConsoleToOps(): Boolean = true
            }
            val sourceWithCapture = source.withOutput(capturing)

            val result: Int = try {
                server.commandManager.executeWithPrefix(sourceWithCapture, command)
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
