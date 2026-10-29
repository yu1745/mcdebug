package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.GlUtil
import com.mcdebug.ServerPlane
import com.mcdebug.contract.Methods
import com.mcdebug.rpc.RpcEnv
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.server.IntegratedServer
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CompletableFuture

/**
 * Client plane (`gui.*`) — the GUI development loop for an agent: look
 * (`status` / `screen` / `screenshot`), act (`openBlock` / `click` / `close`),
 * and iterate without a human at the keyboard. This object holds the lifecycle
 * and world-entry methods; [GuiScreenOps] (inspection), [GuiClickOps],
 * [GuiPointerOps] (hover/scroll/openScreen), [GuiInputOps] (key/type/drag) and [GuiDevOps] (act/settle/reinit/problems/
 * hotswap) contribute the rest of the `gui.*` group.
 *
 * Two invariants:
 *  - every read of client state happens inside [ClientTasks.onClient], because a
 *    `thenApply` on an already-completed future would otherwise run on the RPC thread;
 *  - clicking dispatches ordinary Screen events instead of synthesising GLFW input,
 *    so no window focus, no `Robot`, no OS-level automation is involved.
 *
 * Java getters are called explicitly (`screen.getTitle()`, `widget.getX()`):
 * Kotlin would otherwise resolve `screen.title` to a protected Java field and fail.
 */
object GuiOps : ClientRpcGroup {

    override fun methods(): Map<String, ClientRpcHandler> = linkedMapOf(
        "status" to ::status,
        "connect" to ::connect,
        "openBlock" to ::openBlock,
        "screenshot" to ::screenshot,
        "close" to ::close,
        "reloadResources" to ::reloadResources,
        "stop" to ::stop,
        "disconnect" to ::disconnect,
    ) + GuiScreenOps.methods() + GuiClickOps.methods() + GuiPointerOps.methods() + GuiInputOps.methods() + GuiDevOps.methods()

    // ---------------------------------------------------------------- status

    private fun status(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> =
        ClientTasks.onClient(client) {
            val screen = client.screen
            val player = client.player
            val level = client.level
            val window = client.window
            val target = client.mainRenderTarget
            val gameDir = gameDir()
            JsonObject().apply {
                addProperty("ready", true)
                addProperty("gameLoadFinished", client.isGameLoadFinished)
                addProperty("overlay", client.getOverlay()?.javaClass?.name ?: "")
                add("graphics", JsonObject().apply {
                    addProperty("vendor", GlUtil.getVendor())
                    addProperty("renderer", GlUtil.getRenderer())
                    addProperty("version", GlUtil.getOpenGLVersion())
                })
                add("process", RpcEnv.processInfo())
                addProperty("clientTick", ClientTasks.currentTick())
                add("render", RenderControl.statusJson())
                addProperty("fps", client.getFps())
                addProperty("paused", client.isPaused)
                // 光标 warp 只在窗口"活跃"时才有视觉效果：MC 的 MouseHandler.onMove 被
                // isWindowActive() 守卫，窗口失焦时 OS 指针动了、MC 内部 mouseX/mouseY 不动，
                // 于是截图里手持物品/悬浮提示/槽位高亮会停在旧位置。agent 据此判断要不要先激活窗口。
                addProperty("windowFocused", client.isWindowActive())
                addProperty("inWorld", player != null && level != null)
                addProperty("singleplayer", client.isLocalServer)
                addProperty("screen", screen?.javaClass?.name ?: "")
                addProperty("screenType", screenType(screen))
                addProperty("screenTitle", screen?.let { it.getTitle().string } ?: "")
                addProperty("world", level?.dimension()?.location()?.toString() ?: "")
                addProperty(
                    "server",
                    if (client.isLocalServer) "integrated" else (client.getCurrentServer()?.ip ?: ""),
                )
                addProperty("player", player?.getGameProfile()?.getName() ?: "")
                if (player != null) {
                    add("pos", JsonArray().apply {
                        add(player.getX())
                        add(player.getY())
                        add(player.getZ())
                    })
                    addProperty("gamemode", client.gameMode?.let { it.getPlayerMode().getName() } ?: "")
                    addProperty("selectedSlot", player.getInventory().selected)
                }
                addProperty("gameDir", gameDir?.toAbsolutePath()?.toString() ?: "")
                addProperty("logPath", gameDir?.let { it.resolve("logs").resolve("latest.log").toString() } ?: "")
                addProperty("jarPath", ClientInfo.jarPath())
                addProperty("modVersion", ClientInfo.modVersion())
                addProperty("protocolVersion", ClientInfo.PROTOCOL_VERSION)
                addProperty("clientSocket", ClientInfo.endpoint() ?: "")
                addProperty("clientSocketBound", ClientInfo.socketBound())
                addProperty("clientTcpPort", ClientInfo.tcpPort() ?: -1)
                addProperty("clientTcpListening", ClientInfo.tcpPort() != null)
                addProperty("clientEndpoint", ClientInfo.endpointDescription())
                add("clientConfig", ClientInfo.configHints())
                add("serverPlane", ClientInfo.serverPlaneJson())
                add("window", JsonObject().apply {
                    addProperty("width", window.getScreenWidth())
                    addProperty("height", window.getScreenHeight())
                    addProperty("guiScale", window.getGuiScale())
                    addProperty("guiScaledWidth", window.getGuiScaledWidth())
                    addProperty("guiScaledHeight", window.getGuiScaledHeight())
                    addProperty("fullscreen", window.isFullscreen)
                })
                add("framebuffer", JsonObject().apply {
                    addProperty("width", target.width)
                    addProperty("height", target.height)
                })
                add("problems", ProblemLog.statusJson())
                add("screenGuard", ScreenGuard.statusJson(client))
                add("hotswap", Hotswap.statusJson())
                add("methods", JsonArray().apply { Methods.CLIENT_METHODS.sorted().forEach { add(it) } })
                add("contractMismatch", JsonArray().apply { ClientInfo.contractMismatch().forEach { add(it) } })
            }
        }

    // --------------------------------------------------------------- connect

    private fun connect(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(
            RpcErrors.INVALID_PARAMS,
            "params required: {\"host\": \"host[:port]\"}",
        )
        val host = p.string("host") ?: p.string("address")
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "host (host or host:port) is required")
        val name = p.string("name") ?: host
        val waitTicks = p.int("waitTicks", 0)
        val started = ClientTasks.onClient(client) {
            if (client.level != null) {
                throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "already in a world (${client.level?.dimension()?.location()}); leave it before connecting",
                )
            }
            if (!ServerAddress.isValidAddress(host)) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "not a valid server address: '$host'")
            }
            val address = ServerAddress.parseString(host)
            // `startConnecting` renders the vanilla "Connecting to the server..." screen
            // in place of the current one, and only reports progress through that screen.
            ConnectScreen.startConnecting(
                client.screen ?: TitleScreen(),
                client,
                address,
                ServerData(name, host, ServerData.Type.OTHER),
                false,
                null,
            )
            JsonObject().apply {
                addProperty("ok", true)
                addProperty("requested", host)
                addProperty("address", "${address.getHost()}:${address.getPort()}")
                addProperty("name", name)
            }
        }
        if (waitTicks <= 0) return started.asElement()
        return ClientTasks.thenOnClient(client, started) {
            ClientTasks.until(waitTicks) { client.player != null }
        }.thenCompose { waiting ->
            ClientTasks.thenOnClient(client, waiting) { joined ->
                started.join().apply {
                    addProperty("joined", joined)
                    addProperty("screen", client.screen?.javaClass?.name ?: "")
                    addProperty("player", client.player?.getGameProfile()?.getName() ?: "")
                }
            }.asElement()
        }
    }

    // ------------------------------------------------------------- openBlock

    private fun openBlock(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(
            RpcErrors.INVALID_PARAMS,
            "params required: pos [x,y,z] (+ optional face/hand/sneak/clearHand/waitTicks)",
        )
        val pos = p.pos()
        val faceName = (p.string("face") ?: "up").lowercase()
        val face = Direction.byName(faceName)
            ?: throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "unknown face '$faceName' (north/south/east/west/up/down)",
            )
        val handName = (p.string("hand") ?: "main_hand").lowercase()
        val hand = when (handName) {
            "main_hand", "main", "mainhand" -> InteractionHand.MAIN_HAND
            "off_hand", "off", "offhand" -> InteractionHand.OFF_HAND
            else -> throw RpcException(RpcErrors.INVALID_PARAMS, "unknown hand '$handName' (main_hand/off_hand)")
        }
        val sneak = p.bool("sneak", false)
        val clearHand = p.bool("clearHand", false)
        val waitTicks = p.int("waitTicks", 40)
        val dimension = p.string("dimension")

        val attempt = ClientTasks.onClient(client) {
            val player = client.player
                ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "not in a world")
            val gameMode = client.gameMode
                ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "no game mode (still connecting?)")
            val here = client.level?.dimension()?.location()?.toString()
            if (dimension != null && here != null && dimension != here) {
                throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "dimension mismatch: the player is in $here, the request asked for $dimension",
                    JsonObject().apply { addProperty("playerDimension", here) },
                )
            }
            // Most machines treat sneak-click as "insert/extract without opening a GUI",
            // and a held wrench-class tool turns the click into a tool action, so both
            // default to off.
            player.setShiftKeyDown(sneak)
            val restore = if (clearHand) selectEmptyHotbarSlot(client, player) else null
            val screenBefore = client.screen
            val menuBefore = player.containerMenu
            val result = try {
                gameMode.useItemOn(player, hand, BlockHitResult(Vec3.atCenterOf(pos), face, pos, false))
            } finally {
                if (restore != null) restoreHotbarSlot(client, player, restore)
            }
            // What the CLIENT sees there: a just-placed block may not have arrived yet, gravity blocks fall.
            val block = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).toString()
            OpenAttempt(face, result.toString(), screenBefore, menuBefore, block)
        }
        return ClientTasks.thenOnClient(client, attempt) { done ->
            ClientTasks.until(waitTicks) {
                client.screen !== done.screenBefore ||
                    client.player?.containerMenu !== done.menuBefore
            }
        }.thenCompose { changed ->
            ClientTasks.thenOnClient(client, changed) { changedNow ->
                // Both futures are complete here, so `join()` cannot block.
                val done = attempt.join()
                val screen = client.screen
                val menu = client.player?.containerMenu
                JsonObject().apply {
                    addProperty("ok", changedNow)
                    addProperty("changed", changedNow)
                    addProperty("result", done.result)
                    addProperty("reason", when {
                        changedNow -> ""
                        done.block == "minecraft:air" -> "NO_BLOCK"
                        else -> "NO_GUI_CHANGED"
                    })
                    addProperty("block", done.block)
                    addProperty("screen", screen?.javaClass?.name ?: "")
                    addProperty("screenType", screenType(screen))
                    addProperty("screenTitle", screen?.let { it.getTitle().string } ?: "")
                    addProperty("containerId", menu?.containerId ?: -1)
                    addProperty("slotCount", menu?.slots?.size ?: 0)
                    addProperty("face", done.face.getName())
                    addProperty("hand", handName)
                    addProperty("sneak", sneak)
                    addProperty("clearHand", clearHand)
                    add("pos", JsonArray().apply {
                        add(pos.x)
                        add(pos.y)
                        add(pos.z)
                    })
                }
            }
        }
    }

    private class OpenAttempt(
        val face: Direction,
        val result: String,
        val screenBefore: Screen?,
        val menuBefore: AbstractContainerMenu?,
        val block: String,
    )

    // ------------------------------------------------------------ screenshot

    private fun screenshot(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> =
        // Passive delay, client hops, render wait and encoding share ONE deadline
        // and settlement. A second wrapper timeout could report failure after the
        // capture had already published its PNG-click transform.
        Capture.screenshot(client, CaptureOptions.parse(params))

    // ----------------------------------------------------------------- close

    private fun close(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val waitTicks = (params ?: JsonObject()).int("waitTicks", 20)
        val closed = ClientTasks.onClient(client) {
            val screen = client.screen
            val player = client.player
            if (screen != null) {
                // AbstractContainerScreen#onClose -> LocalPlayer#closeContainer
                // (ServerboundContainerClosePacket + setScreen(null))
                screen.onClose()
            } else {
                player?.closeContainer()
            }
            screen?.javaClass?.name ?: ""
        }
        return ClientTasks.thenOnClient(client, closed) {
            ClientTasks.until(waitTicks) { client.screen == null }
        }.thenCompose { waited ->
            ClientTasks.thenOnClient(client, waited) { gone ->
                JsonObject().apply {
                    addProperty("ok", gone)
                    addProperty("closed", closed.join())
                    addProperty("screen", client.screen?.javaClass?.name ?: "")
                    addProperty("containerId", client.player?.containerMenu?.containerId ?: -1)
                }
            }
        }
    }

    // -------------------------------------------------------------- disconnect

    private data class DisconnectTarget(val level: ClientLevel?, val server: IntegratedServer?)
    private var disconnectOperation: Any? = null // client thread only

    private fun disconnect(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val waitTicks = (params ?: JsonObject()).int("waitTicks", 200)
        if (waitTicks !in 1..1200) throw RpcException(RpcErrors.INVALID_PARAMS, "waitTicks must be in 1..1200")
        val operation = Any()
        val started = ClientTasks.onClient(client) {
            if (disconnectOperation != null) throw RpcException(RpcErrors.CLIENT_NOT_READY, "disconnect already in progress")
            if (!client.isGameLoadFinished || client.getOverlay() != null || client.screen is ConnectScreen) {
                throw RpcException(RpcErrors.CLIENT_NOT_READY, "client is still loading/connecting; cancel that screen first")
            }
            disconnectOperation = operation
            val target = DisconnectTarget(client.level, client.getSingleplayerServer())
            if (target.server != null && target.server.isRunning) {
                // Request the normal integrated-server halt on its own thread.
                // Do not join it or pump runTick from this RPC. Vanilla network
                // teardown can still run its ordinary engine cleanup callbacks.
                target.server.execute { target.server.halt(false) }
                client.setScreen(GenericMessageScreen(Component.translatable("menu.savingLevel")))
            }
            target
        }
        val stopped = ClientTasks.thenOnClient(client, started) { target ->
            ClientTasks.until(waitTicks) { target.server == null || target.server.isShutdown }
        }.thenCompose { it }
        val ready = ClientTasks.thenOnClient(client, stopped) { complete ->
            if (!complete) throw RpcException(RpcErrors.TICK_TIMEOUT, "world is still disconnecting/saving; the stop request is not rolled back")
            // If vanilla connection cleanup observed server shutdown in a nested
            // event pump, let it finish before our final title-screen transition.
            ClientTasks.awaitTicks(1)
        }.thenCompose { it }
        return ClientTasks.thenOnClient(client, ready) {
            val target = started.join() // completed, never blocks
            if ((client.level != null && client.level !== target.level) ||
                (client.getSingleplayerServer() != null && client.getSingleplayerServer() !== target.server)) {
                throw RpcException(RpcErrors.CLIENT_NOT_READY, "world changed while disconnecting; refusing to disconnect a replacement")
            }
            client.level?.disconnect()
            client.disconnect()
            client.setScreen(TitleScreen())
            JsonObject().apply {
                addProperty("ok", client.level == null && client.player == null && !ServerPlane.running)
                addProperty("disconnected", client.level == null && client.player == null)
                addProperty("singleplayer", target.server != null)
                addProperty("serverStopped", target.server == null || target.server.isShutdown)
                addProperty("inWorld", client.level != null || client.player != null)
                addProperty("screen", client.screen?.javaClass?.name ?: "")
                add("process", RpcEnv.processInfo())
            }
        }.asElement().whenComplete { _, _ ->
            ClientTasks.onClient(client) { if (disconnectOperation === operation) disconnectOperation = null }
        }
    }

    // --------------------------------------------------------- reloadResources

    private fun reloadResources(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> =
        ClientTasks.onClient(client) { client.reloadResourcePacks() }
            .thenCompose { it }
            .thenCompose { ClientTasks.onClient(client) { JsonObject().apply { addProperty("ok", true) } } }

    // ------------------------------------------------------------------ stop

    private fun stop(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "instanceId and sessionToken are required")
        if (p.string("instanceId") != RpcEnv.instanceId || p.string("sessionToken") != RpcEnv.sessionToken) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "client instance identity changed; refusing to stop")
        }
        return ClientTasks.onClient(client) {
            client.stop()
            JsonObject().apply {
                addProperty("ok", true)
                addProperty("stopping", true)
                addProperty("instanceId", RpcEnv.instanceId)
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun gameDir(): java.nio.file.Path? = try {
        RpcEnv.gameDir()
    } catch (t: Throwable) {
        null
    }

    /**
     * Picks the first empty hotbar slot and tells the server about it, so that
     * `useItemOn` is a bare hand click both locally and server-side. Returns the
     * previously selected slot, or null when a hand swap was not needed.
     */
    private fun selectEmptyHotbarSlot(client: Minecraft, player: LocalPlayer): Int? {
        val inventory = player.getInventory()
        val occupied = inventory.getItem(inventory.selected)
        if (occupied.isEmpty) return null
        val empty = (0 until 9).firstOrNull { inventory.getItem(it).isEmpty }
            ?: throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "clearHand: all 9 hotbar slots are occupied",
            )
        val previous = inventory.selected
        inventory.selected = empty
        client.connection?.send(ServerboundSetCarriedItemPacket(empty))
        return previous
    }

    private fun restoreHotbarSlot(client: Minecraft, player: LocalPlayer, previous: Int) {
        player.getInventory().selected = previous
        client.connection?.send(ServerboundSetCarriedItemPacket(previous))
    }
}

/**
 * Widens a concrete `JsonElement` future to the handler's declared type —
 * `CompletableFuture` is invariant, so `CompletableFuture<JsonObject>` is not
 * a `CompletableFuture<JsonElement>`.
 */
internal fun <T : JsonElement> CompletableFuture<T>.asElement(): CompletableFuture<JsonElement> = thenApply { it }
