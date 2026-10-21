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
import net.minecraft.commands.CommandSource
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.fluids.capability.IFluidHandler
import java.util.concurrent.CompletableFuture

object ServerOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "status" to ::status,
        "health" to ::health,
        "listDimensions" to ::listDimensions,
        "runCommand" to ::runCommand,
        "forgeFluidCapability" to ::forgeFluidCapability,
        "mantleInteract" to ::mantleInteract,
    )

    /** TPS/MSPT (average tick time), per-dimension entity and loaded-chunk counts. */
    private fun health(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val mspt = server.averageTickTime
            val tps = if (mspt > 0f) (1000.0 / mspt).coerceAtMost(20.0) else 20.0
            val dims = JsonArray()
            server.allLevels.forEach { w ->
                dims.add(JsonObject().apply {
                    addProperty("dim", w.dimension().location().toString())
                    addProperty("entities", w.allEntities.count())
                    addProperty("loadedChunks", w.chunkSource.loadedChunksCount)
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

    /**
     * Invoke Mantle's FluidTransferHelper.interactWithContainer against a block entity's tank
     * (the call TConstruct's casting basin makes) with a survival fake player. Reflective, so it
     * works only when Mantle is installed; otherwise `error` explains. (The Fabric/Kilt version also
     * dumps a multi-step diagnostic trace; that Kilt-bridge debugging aid is not carried over.)
     *
     * Input:  { "pos": [x,y,z], "item": "tconstruct:molten_diamond_bucket", "dim": "minecraft:overworld" }
     * Output: { "result": "...", "afterStack": "...", "error": "..." }
     */
    private fun mantleInteract(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val item = p.requireString("item")
            val stack = ServerContext.itemStackFromJson(server, item, 1, p.get("nbt"))

            var resultName = "?"
            var afterStack = "?"
            var error = ""
            var methodDesc = ""
            FakePlayerPool.withFakePlayer(server, world, FakePlayerPool.gameModeFrom(p)) { fp ->
                fp.setItemInHand(InteractionHand.MAIN_HAND, stack.copy())
                fp.moveTo(pos.x + 0.5, pos.y + 1.5, pos.z + 0.5, 0f, 90f)
                try {
                    val fth = Class.forName("slimeknights.mantle.fluid.FluidTransferHelper")
                    val method = fth.getMethod(
                        "interactWithContainer",
                        Level::class.java, BlockPos::class.java, IFluidHandler::class.java,
                        Player::class.java, InteractionHand::class.java,
                    )
                    val be = world.getBlockEntity(pos)
                    if (be == null) error = "no block entity at $pos"
                    else {
                        val handler = runCatching { be.javaClass.getMethod("getTank").invoke(be) as? IFluidHandler }.getOrNull()
                            ?: be.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElse(null)
                            ?: throw IllegalStateException("block entity exposes no fluid handler")
                        methodDesc = method.toString()
                        resultName = method.invoke(null, world, pos, handler, fp, InteractionHand.MAIN_HAND).toString()
                        val after = fp.getItemInHand(InteractionHand.MAIN_HAND)
                        afterStack = if (after.isEmpty) "EMPTY" else after.item.toString()
                    }
                } catch (t: Throwable) {
                    error = t.cause?.toString() ?: t.toString()
                } finally {
                    fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                    fp.isShiftKeyDown = false
                }
            }
            JsonObject().apply {
                addProperty("result", resultName)
                addProperty("afterStack", afterStack)
                addProperty("error", error)
                addProperty("method", methodDesc)
            }
        }

    /**
     * Diagnose the FLUID_HANDLER_ITEM capability of an item stack.
     *
     * Input:  { "item": "tconstruct:molten_diamond_bucket", "count": 1 }
     * Output: { "capabilityPresent": bool, "handlerClass": "..." or null, "drain1000": ..., "drain900": ... }
     */
    private fun forgeFluidCapability(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val item = p.requireString("item")
            val count = p.getIntOr("count", 1)
            val stack = ServerContext.itemStackFromJson(server, item, count, p.get("nbt"))
            JsonObject().apply {
                try {
                    val handler = stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).resolve().orElse(null)
                    addProperty("capabilityPresent", handler != null)
                    if (handler != null) {
                        addProperty("handlerClass", handler.javaClass.name)
                        addProperty("drain1000", probeDrain(handler, 1000))
                        addProperty("drain900", probeDrain(handler, 900))
                    } else {
                        add("handlerClass", com.google.gson.JsonNull.INSTANCE)
                    }
                } catch (t: Throwable) {
                    addProperty("error", t.toString())
                }
            }
        }

    private fun probeDrain(handler: IFluidHandler, amount: Int): String =
        try {
            if (handler.drain(amount, IFluidHandler.FluidAction.SIMULATE).isEmpty) "EMPTY" else "RETURNS"
        } catch (t: Throwable) {
            "EX: ${t.cause?.toString() ?: t}"
        }

    private fun status(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val dims = JsonArray()
            server.allLevels.forEach { dims.add(it.dimension().location().toString()) }
            val overworld = server.getLevel(Level.OVERWORLD)
            val dayTime = overworld?.dayTime ?: 0L
            JsonObject().apply {
                addProperty("mcVersion", server.serverVersion)
                addProperty("modVersion", McDebugMod.MOD_VERSION)
                addProperty("modLoader", "forge")
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
            server.allLevels.forEach { dims.add(it.dimension().location().toString()) }
            JsonObject().apply { add("dims", dims) }
        }

    /**
     * Run a Minecraft command as the server console.
     *
     * Input:  { "command": "/time set day", "dim": "minecraft:overworld" }   (dim optional)
     * Output: { "success": true, "result": 1, "output": "Set the time to 1000" }
     */
    private fun runCommand(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val command = p.requireString("command")
            val dim = p.getStringOrNull("dim")?.let { ServerContext.world(server, it) }
            val sourceWorld: ServerLevel = dim ?: server.getLevel(Level.OVERWORLD)!!
            val source = server.createCommandSourceStack().withLevel(sourceWorld)

            val output = StringBuilder()
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

            val result: Int = try {
                server.commands.performPrefixedCommand(sourceWithCapture, command)
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
