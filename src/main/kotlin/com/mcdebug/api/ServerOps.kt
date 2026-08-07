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
import net.minecraft.world.World
import net.minecraft.util.Hand
import net.minecraft.item.ItemStack
import net.minecraft.util.math.BlockPos
import net.minecraft.world.GameMode
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.command.CommandOutput
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import java.util.concurrent.CompletableFuture

object ServerOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "status" to ::status,
        "listDimensions" to ::listDimensions,
        "runCommand" to ::runCommand,
        "forgeFluidCapability" to ::forgeFluidCapability,
        "mantleInteract" to ::mantleInteract,
    )

    /**
     * Directly invoke Mantle's FluidTransferHelper.interactWithContainer against a
     * block entity (the exact call TConstruct's casting basin makes), using a
     * survival fake player. Reveals which path the bucket drain actually takes and
     * what the player's hand looks like afterwards.
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
            var traceJson: JsonObject? = null
            var methodDesc = ""
            var setItemInHandTest = ""
            FakePlayerPool.withFakePlayer(server, world) { fp ->
                val oldGM = fp.interactionManager.gameMode
                val oldInv = fp.isInvulnerable
                fp.changeGameMode(GameMode.SURVIVAL)
                fp.abilities.creativeMode = false
                fp.abilities.invulnerable = false
                fp.setInvulnerable(false)
                fp.setStackInHand(Hand.MAIN_HAND, stack.copy())
                fp.refreshPositionAndAngles(pos.x + 0.5, pos.y + 1.5, pos.z + 0.5, 0f, 90f)
                try {
                    val iFluidHandlerClass = Class.forName("net.minecraftforge.fluids.capability.IFluidHandler")
                    val fth = Class.forName("slimeknights.mantle.fluid.FluidTransferHelper")
                    val method = fth.getMethod(
                        "interactWithContainer",
                        net.minecraft.world.World::class.java, BlockPos::class.java, iFluidHandlerClass,
                        PlayerEntity::class.java, Hand::class.java,
                    )

                    val be = world.getBlockEntity(pos)
                    if (be == null) error = "no block entity at $pos"
                    else {
                        // Mirror exactly what CastingBlockEntity.interact passes: the
                        // block entity's own tank field (lombok getTank()).
                        val getTank = be.javaClass.getMethod("getTank")
                        val handler = getTank.invoke(be)
                        // Manually trace Mantle's tryTransfer steps on a fresh copy
                        val steps = JsonObject()
                        try {
                            val heldStack = fp.getStackInHand(Hand.MAIN_HAND)
                            // Mantle uses ItemHandlerHelper.copyStackWithSize(stack, 1)
                            val ihhClass = Class.forName("net.minecraftforge.items.ItemHandlerHelper")
                            val csw = ihhClass.getMethod("copyStackWithSize", ItemStack::class.java, Int::class.javaPrimitiveType)
                            val copy = csw.invoke(null, heldStack, 1) as ItemStack
                            steps.addProperty("copyItem", copy.item.toString())
                            // Mantle's perspective: player.getItemInHand(hand) = intermediary method_5998
                            val getItemInHand = fp.javaClass.getMethod("method_5998", Hand::class.java)
                            val mantleStack = getItemInHand.invoke(fp, Hand.MAIN_HAND) as ItemStack
                            steps.addProperty("mantleStackItem", mantleStack.item.toString())
                            // Check Mantle's JSON transfer path activation
                            try {
                                val mgrClass = Class.forName("slimeknights.mantle.fluid.transfer.FluidContainerTransferManager")
                                val instanceField = mgrClass.getField("INSTANCE")
                                val mgr = instanceField.get(null)
                                val mayHave = mgr.javaClass.getMethod("mayHaveTransfer", ItemStack::class.java)
                                steps.addProperty("mayHaveTransfer", mayHave.invoke(mgr, mantleStack) as Boolean)
                                val fsClass2 = Class.forName("net.minecraftforge.fluids.FluidStack")
                                val getTransfer = mgr.javaClass.getMethod("getTransfer", ItemStack::class.java, fsClass2)
                                val emptyFs = fsClass2.getField("EMPTY").get(null)
                                val transfer = getTransfer.invoke(mgr, mantleStack, emptyFs)
                                steps.addProperty("jsonTransferFound", transfer != null)
                            } catch (t: Throwable) {
                                steps.addProperty("jsonTransferError", t.cause?.toString() ?: t.toString())
                            }
                            val mantleCopy = csw.invoke(null, mantleStack, 1) as ItemStack
                            val fcClass2 = Class.forName("net.minecraftforge.common.capabilities.ForgeCapabilities")
                            val cap2 = fcClass2.getField("FLUID_HANDLER_ITEM").get(null)
                            val getCap2 = mantleCopy.javaClass.getMethod("getCapability", cap2.javaClass)
                            val lazy2 = getCap2.invoke(mantleCopy, cap2)
                            val present2 = lazy2.javaClass.getMethod("isPresent").invoke(lazy2) as Boolean
                            steps.addProperty("mantleCopyCapabilityPresent", present2)
                            // Inspect the copy's CapabilityDispatcher contents
                            try {
                                val getCapDisp = mantleCopy.javaClass.getMethod("getCapabilities")
                                val disp = getCapDisp.invoke(mantleCopy)
                                if (disp == null) {
                                    steps.addProperty("dispatcher", "null")
                                } else {
                                    val capsField = disp.javaClass.getDeclaredField("caps")
                                    capsField.isAccessible = true
                                    val caps = capsField.get(disp) as Array<*>
                                    val names = caps.mapIndexed { i, c -> "$i:" + (c?.javaClass?.name ?: "null") }
                                    steps.addProperty("dispatcherCaps", names.joinToString(", "))
                                }
                            } catch (t: Throwable) {
                                steps.addProperty("dispatcherError", t.cause?.toString() ?: t.toString())
                            }
                            val fcClass = Class.forName("net.minecraftforge.common.capabilities.ForgeCapabilities")
                            val cap = fcClass.getField("FLUID_HANDLER_ITEM").get(null)
                            val getCap = copy.javaClass.getMethod("getCapability", cap.javaClass)
                            val lazy = getCap.invoke(copy, cap)
                            val present = lazy.javaClass.getMethod("isPresent").invoke(lazy) as Boolean
                            steps.addProperty("capabilityPresent", present)
                            if (present) {
                                val opt = lazy.javaClass.getMethod("resolve").invoke(lazy)
                                val itemHandler = opt.javaClass.getMethod("orElseThrow").invoke(opt)
                                steps.addProperty("handlerClass", itemHandler.javaClass.name)
                                val actionClass = Class.forName("net.minecraftforge.fluids.capability.IFluidHandler\$FluidAction")
                                val simulate = actionClass.getField("SIMULATE").get(null)
                                val execute = actionClass.getField("EXECUTE").get(null)
                                val fsClass = Class.forName("net.minecraftforge.fluids.FluidStack")
                                val drainInt = itemHandler.javaClass.getMethod("drain", Int::class.javaPrimitiveType, actionClass)
                                val simulateDrain = drainInt.invoke(itemHandler, Int.MAX_VALUE, simulate)
                                val amount1 = simulateDrain.javaClass.getMethod("getAmount").invoke(simulateDrain) as Int
                                steps.addProperty("simulateDrainAmount", amount1)
                                val fill = handler.javaClass.getMethod("fill", fsClass, actionClass)
                                val copyM = simulateDrain.javaClass.getMethod("copy")
                                val simulatedFill = fill.invoke(handler, copyM.invoke(simulateDrain), simulate) as Int
                                steps.addProperty("simulatedFill", simulatedFill)
                                val fsCtor = fsClass.getConstructor(fsClass, Int::class.javaPrimitiveType)
                                val drainFs = itemHandler.javaClass.getMethod("drain", fsClass, actionClass)
                                val exFs = fsCtor.newInstance(simulateDrain, simulatedFill)
                                steps.addProperty("exFsAmount", exFs.javaClass.getMethod("getAmount").invoke(exFs).toString())
                                val exDrain = drainFs.invoke(itemHandler, exFs, execute)
                                val exEmpty = exDrain.javaClass.getMethod("isEmpty").invoke(exDrain) as Boolean
                                steps.addProperty("executeDrainEmpty", exEmpty)
                                val exAmount = exDrain.javaClass.getMethod("getAmount").invoke(exDrain)
                                steps.addProperty("executeDrainAmount", exAmount.toString())
                                val drainInt2 = itemHandler.javaClass.getMethod("drain", Int::class.javaPrimitiveType, actionClass)
                                val afterDrain = drainInt2.invoke(itemHandler, Int.MAX_VALUE, simulate)
                                val afterDrainAmount = afterDrain.javaClass.getMethod("getAmount").invoke(afterDrain)
                                steps.addProperty("afterExecuteDrainSimulateAmount", afterDrainAmount.toString())
                                val getContainer = itemHandler.javaClass.getMethod("getContainer")
                                val container = getContainer.invoke(itemHandler) as ItemStack
                                steps.addProperty("getContainerEmpty", container.isEmpty)
                                steps.addProperty("getContainerItem", container.item.toString())

                                steps.addProperty("playerInstabuild", fp.abilities.creativeMode)
                                steps.addProperty("playerAbilitiesClass", fp.abilities.javaClass.name)
                                // Call the runtime (intermediary) ItemUtils.createFilledResult directly.
                                try {
                                    val iuClass = Class.forName("net.minecraft.class_5328")
                                    val createFilled = iuClass.getMethod(
                                        "method_30012",
                                        ItemStack::class.java, PlayerEntity::class.java, ItemStack::class.java,
                                    )
                                    val filledResult = createFilled.invoke(null, heldStack, fp, container) as ItemStack
                                    steps.addProperty("createFilledResultItem", filledResult.item.toString())
                                    steps.addProperty("createFilledResultCount", filledResult.count)
                                } catch (t: Throwable) {
                                    steps.addProperty("createFilledResultError", t.cause?.toString() ?: t.toString())
                                }
                            }
                        } catch (t: Throwable) {
                            steps.addProperty("traceError", t.cause?.toString() ?: t.toString())
                        }
                        traceJson = steps
                        methodDesc = method.toString()
                        val result = method.invoke(null, world, pos, handler, fp, Hand.MAIN_HAND)
                        resultName = result.toString()
                        // sanity: does fakePlayer.setItemInHand stick?
                        val oldHand = fp.getStackInHand(Hand.MAIN_HAND).copy()
                        val setItemInHand = fp.javaClass.getMethod("method_6122", Hand::class.java, ItemStack::class.java)
                        setItemInHand.invoke(fp, Hand.MAIN_HAND, ItemStack(net.minecraft.item.Items.BUCKET))
                        val afterSet = fp.getStackInHand(Hand.MAIN_HAND)
                        setItemInHandTest = afterSet.item.toString() + "|count=" + afterSet.count
                        setItemInHand.invoke(fp, Hand.MAIN_HAND, oldHand)
                        val after = fp.getStackInHand(Hand.MAIN_HAND)
                        afterStack = if (after.isEmpty) "EMPTY" else after.item.toString()


                    }
                } catch (t: Throwable) {
                    error = t.cause?.toString() ?: t.toString()
                } finally {
                    fp.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY)
                    fp.isSneaking = false
                    fp.setInvulnerable(oldInv)
                    fp.changeGameMode(oldGM)
                }
            }
            JsonObject().apply {
                addProperty("result", resultName)
                addProperty("afterStack", afterStack)
                addProperty("error", error)
                addProperty("method", methodDesc)
                addProperty("setItemInHandTest", setItemInHandTest)
                traceJson?.let { add("trace", it) }
            }
        }

    /**
     * Diagnose the Forge FLUID_HANDLER_ITEM capability of an item stack via
     * reflection (mcdebug is a Fabric mod but runs under Kilt, where Forge
     * capabilities are available). Used to verify which handler backs a
     * bucket: native FluidBucketWrapper vs Kilt's Fabric fluid bridge.
     *
     * Input:  { "item": "tconstruct:molten_diamond_bucket", "count": 1 }
     * Output: { "capabilityPresent": bool, "handlerClass": "..." or null }
     */
    private fun forgeFluidCapability(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val item = p.requireString("item")
            val count = p.getIntOr("count", 1)
            val stack = ServerContext.itemStackFromJson(server, item, count, p.get("nbt"))
            JsonObject().apply {
                try {
                    val fcClass = Class.forName("net.minecraftforge.common.capabilities.ForgeCapabilities")
                    val cap = fcClass.getField("FLUID_HANDLER_ITEM").get(null)
                    val getCap = stack.javaClass.getMethod("getCapability", cap.javaClass)
                    val lazy = getCap.invoke(stack, cap)
                    val isPresent = lazy.javaClass.getMethod("isPresent").invoke(lazy) as Boolean
                    addProperty("capabilityPresent", isPresent)
                    if (isPresent) {
                        val resolve = lazy.javaClass.getMethod("resolve")
                        val opt = resolve.invoke(lazy)
                        val handler = opt.javaClass.getMethod("orElseThrow").invoke(opt)
                        addProperty("handlerClass", handler.javaClass.name)
                        // drain(1000mb) and drain(900mb) behavior
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

    private fun probeDrain(handler: Any, amount: Int): String {
        return try {
            val actionClass = Class.forName("net.minecraftforge.fluids.capability.IFluidHandler\$FluidAction")
            val simulate = actionClass.getField("SIMULATE").get(null)
            val drain = handler.javaClass.getMethod("drain", Int::class.javaPrimitiveType, actionClass)
            val result = drain.invoke(handler, amount, simulate)
            val isEmp = result.javaClass.getMethod("isEmpty").invoke(result) as Boolean
            if (isEmp) "EMPTY" else "RETURNS"
        } catch (t: Throwable) {
            "EX: ${t.cause?.toString() ?: t}"
        }
    }

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
