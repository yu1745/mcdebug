package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.client.mixin.AbstractContainerScreenAccessor
import com.mcdebug.contract.Methods
import com.mcdebug.rpc.RpcEnv
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.ServerContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CompletableFuture

/**
 * Client plane (`gui.*`) — the GUI development loop for an agent: look
 * (`status` / `screen` / `screenshot`), act (`openBlock` / `click` / `close`),
 * and iterate without a human at the keyboard.
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
        "screen" to ::screen,
        "click" to ::click,
        "close" to ::close,
        "reloadResources" to ::reloadResources,
        "stop" to ::stop,
    )

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
                addProperty("clientSocket", ClientInfo.socketPath()?.toString() ?: "")
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
            OpenAttempt(face, result.toString(), screenBefore, menuBefore)
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
                    addProperty("reason", if (changedNow) "" else "NO_GUI_CHANGED")
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
    )

    // ------------------------------------------------------------ screenshot

    private fun screenshot(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: JsonObject()
        val mode = (p.string("mode") ?: "window").lowercase()
        if (mode != "window" && mode != "gui") {
            throw RpcException(RpcErrors.INVALID_PARAMS, "unknown mode '$mode' (window/gui)")
        }
        val inline = p.bool("inline", false)
        val name = p.string("name")
        val delayTicks = p.int("delayTicks", 1)
        // Passive delay, client hops, render wait and encoding share ONE deadline
        // and settlement. A second wrapper timeout could report failure after the
        // capture had already published its PNG-click transform.
        return Capture.screenshot(client, mode, inline, name, delayTicks)
    }

    // ---------------------------------------------------------------- screen

    private fun screen(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> =
        ClientTasks.onClient(client) {
            val current = client.screen
            if (current == null) {
                return@onClient JsonObject().apply {
                    addProperty("open", false)
                    addProperty("inWorld", client.player != null)
                    addProperty("playerMenu", client.player?.containerMenu?.javaClass?.name ?: "")
                }
            }
            JsonObject().apply {
                addProperty("open", true)
                addProperty("class", current.javaClass.name)
                addProperty("type", screenType(current))
                addProperty("title", current.getTitle().string)
                addProperty("width", current.width)
                addProperty("height", current.height)
                val container = current as? AbstractContainerScreen<*>
                if (container != null) {
                    val accessor = container as AbstractContainerScreenAccessor
                    val left = accessor.leftPos
                    val top = accessor.topPos
                    add("gui", JsonObject().apply {
                        addProperty("left", left)
                        addProperty("top", top)
                        addProperty("imageWidth", accessor.imageWidth)
                        addProperty("imageHeight", accessor.imageHeight)
                    })
                    addProperty("containerId", container.getMenu().containerId)
                    add("carried", itemJson(container.getMenu().getCarried()))
                    add("slots", JsonArray().apply {
                        container.getMenu().slots.forEach { slot -> add(slotJson(slot, left, top)) }
                    })
                }
                add("widgets", JsonArray().apply {
                    current.children().filterIsInstance<AbstractWidget>().forEach { add(widgetJson(it)) }
                })
            }
        }

    private fun slotJson(slot: Slot, left: Int, top: Int): JsonObject {
        val stack = slot.getItem()
        return JsonObject().apply {
            addProperty("index", slot.index)
            addProperty("x", slot.x)
            addProperty("y", slot.y)
            addProperty("guiX", left + slot.x)
            addProperty("guiY", top + slot.y)
            addProperty("item", stack.item.toString())
            addProperty("count", stack.count)
            add("stack", itemJson(stack))
        }
    }

    private fun widgetJson(widget: AbstractWidget): JsonObject = JsonObject().apply {
        addProperty("class", widget.javaClass.name)
        addProperty("text", widget.getMessage().string)
        addProperty("x", widget.getX())
        addProperty("y", widget.getY())
        addProperty("width", widget.getWidth())
        addProperty("height", widget.getHeight())
        addProperty("active", widget.isActive)
        addProperty("visible", widget.visible)
        if (widget is EditBox) addProperty("value", widget.getValue())
    }

    // ----------------------------------------------------------------- click

    private fun click(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(
            RpcErrors.INVALID_PARAMS,
            "params required: slot= | text= | gui=[x,y] | png=[x,y] | x&y (+ button/clickType/shift/waitTicks)",
        )
        val slotElement = p.get("slot")?.takeIf { !it.isJsonNull }
        val text = p.string("text")
        val guiElement = p.get("gui")?.takeIf { !it.isJsonNull }
        val pngElement = p.get("png")?.takeIf { !it.isJsonNull }
        val waitTicks = p.int("waitTicks", 20)
        val moveCursor = p.bool("moveCursor", true)
        return when {
            slotElement != null -> clickSlot(client, slotElement, p, waitTicks, moveCursor)
            text != null -> {
                val index = p.int("index", 0)
                clickCoordinates(client, p, waitTicks, moveCursor) { resolveTextTarget(client, text, index) }
            }
            guiElement != null -> {
                val pair = p.pair("gui")
                clickCoordinates(client, p, waitTicks, moveCursor) { Target(pair.first, pair.second, "gui", null, null) }
            }
            pngElement != null -> {
                val pair = p.pair("png")
                clickCoordinates(client, p, waitTicks, moveCursor) {
                    val gui = Capture.pngToGui(pair.first, pair.second)
                    Target(gui.first, gui.second, "png", null, "png=${pair.first},${pair.second}")
                }
            }
            else -> {
                val x = p.num("x")
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "x is required when no slot/text/gui/png is given")
                val y = p.num("y")
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "y is required when no slot/text/gui/png is given")
                clickCoordinates(client, p, waitTicks, moveCursor) { Target(x, y, "gui", null, null) }
            }
        }
    }

    private class Target(
        val x: Double,
        val y: Double,
        val mode: String,
        val widget: AbstractWidget?,
        val via: String?,
    )

    private fun clickSlot(
        client: Minecraft,
        slotElement: JsonElement,
        params: JsonObject,
        waitTicks: Int,
        moveCursor: Boolean,
    ): CompletableFuture<JsonElement> {
        val index = slotIndex(slotElement)
        val button = params.int("button", 0)
        val clickTypeName = params.string("clickType")
        val shift = params.bool("shift", false)
        val clicked = ClientTasks.onClient(client) {
            val player = client.player
                ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "not in a world")
            val gameMode = client.gameMode
                ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "no game mode (still connecting?)")
            val menu = (client.screen as? AbstractContainerScreen<*>)?.getMenu() ?: player.containerMenu
            val slot = menu.slots.getOrNull(index)
                ?: throw RpcException(
                    RpcErrors.SLOT_OUT_OF_RANGE,
                    "slot $index out of range for ${menu.javaClass.simpleName} (${menu.slots.size} slots)",
                    JsonObject().apply {
                        addProperty("reason", "SLOT_OUT_OF_RANGE")
                        addProperty("slotCount", menu.slots.size)
                    },
                )
            val clickType = when {
                clickTypeName != null -> parseClickType(clickTypeName)
                shift -> ClickType.QUICK_MOVE
                else -> ClickType.PICKUP
            }
            val before = itemJson(slot.getItem())
            val accessor = client.screen as? AbstractContainerScreenAccessor
            // 槽位中心（绝对 GUI 单位）：既是 warp 目标，也回报给 agent——有了它 agent 才能把
            // 这次点击对应到 gui.screenshot 的 PNG 像素（PNG 坐标 = (gui - gui.left/top) * pixelsPerGui）。
            val guiX = (accessor?.leftPos ?: 0) + slot.x + 8.0
            val guiY = (accessor?.topPos ?: 0) + slot.y + 8.0
            val moved = moveCursor && warpCursor(client, guiX, guiY)
            // The vanilla body of AbstractContainerScreen#slotClicked (which is
            // protected): the client only sends the intent, the server owns the result.
            gameMode.handleInventoryMouseClick(menu.containerId, slot.index, button, clickType, player)
            ClickResult(
                mode = "slot",
                slotIndex = index,
                button = button,
                clickType = clickType.name,
                before = before,
                beforeCount = before.get("count")?.asInt ?: 0,
                x = guiX,
                y = guiY,
                cursorMoved = moved,
            )
        }
        return ClientTasks.thenOnClient(client, clicked) {
            ClientTasks.awaitTicks(waitTicks.coerceAtLeast(0))
        }.thenCompose { waited ->
            // `clicked` is already complete here, so `join()` cannot block; it just
            // hands the ClickResult to the next client-thread hop.
            ClientTasks.thenOnClient(client, waited) { clickReport(client, clicked.join()) }
        }
    }

    private fun clickCoordinates(
        client: Minecraft,
        params: JsonObject,
        waitTicks: Int,
        moveCursor: Boolean,
        resolve: () -> Target,
    ): CompletableFuture<JsonElement> {
        val button = params.int("button", 0)
        val clicked = ClientTasks.onClient(client) {
            val screen = client.screen ?: throw noScreen()
            val target = resolve()
            val moved = moveCursor && warpCursor(client, target.x, target.y)
            // Ordinary Screen event dispatch — no GLFW input synthesis, no focus needed.
            // Widgets and container slots both resolve positionally from (x, y), so the
            // synthetic click lands exactly where the coordinates say.
            val pressed = screen.mouseClicked(target.x, target.y, button)
            val released = screen.mouseReleased(target.x, target.y, button)
            ClickResult(
                mode = target.mode,
                slotIndex = null,
                button = button,
                clickType = null,
                before = target.widget?.let { widgetJson(it) },
                beforeCount = null,
                x = target.x,
                y = target.y,
                via = target.via,
                pressed = pressed,
                released = released,
                cursorMoved = moved,
            )
        }
        return ClientTasks.thenOnClient(client, clicked) {
            ClientTasks.awaitTicks(waitTicks.coerceAtLeast(0))
        }.thenCompose { waited ->
            ClientTasks.thenOnClient(client, waited) { clickReport(client, clicked.join()) }
        }
    }

    private class ClickResult(
        val mode: String,
        val slotIndex: Int?,
        val button: Int,
        val clickType: String?,
        val before: JsonObject?,
        val beforeCount: Int?,
        val x: Double? = null,
        val y: Double? = null,
        val via: String? = null,
        val pressed: Boolean = false,
        val released: Boolean = false,
        val cursorMoved: Boolean = false,
    )

    /** Reports the post-click state; the server needs a tick or two to answer. */
    private fun clickReport(client: Minecraft, result: ClickResult): JsonObject {
        val screen = client.screen
        val player = client.player
        val menu = (screen as? AbstractContainerScreen<*>)?.getMenu() ?: player?.containerMenu
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("mode", result.mode)
            result.x?.let { addProperty("guiX", it) }
            result.y?.let { addProperty("guiY", it) }
            result.via?.let { addProperty("via", it) }
            result.slotIndex?.let { addProperty("slot", it) }
            addProperty("button", result.button)
            addProperty("cursorMoved", result.cursorMoved)
            result.clickType?.let { addProperty("clickType", it) }
            result.before?.let { add("before", it) }
            if (result.mode != "slot") {
                addProperty("pressed", result.pressed)
                addProperty("released", result.released)
            }
            addProperty("screen", screen?.javaClass?.name ?: "")
            addProperty("screenTitle", screen?.let { it.getTitle().string } ?: "")
            val index = result.slotIndex
            if (index != null && menu != null) {
                val slot = menu.slots.getOrNull(index)
                add("slotAfter", slot?.let { itemJson(it.getItem()) } ?: JsonObject())
                val after = slot?.let { itemJson(it.getItem()) }
                if (after != null && result.beforeCount != null) {
                    addProperty("changed", (after.get("count")?.asInt ?: 0) != result.beforeCount)
                }
            }
            add("carried", menu?.let { itemJson(it.getCarried()) } ?: JsonObject())
        }
    }

    private fun resolveTextTarget(client: Minecraft, text: String, index: Int): Target {
        val screen = client.screen ?: throw noScreen()
        val all = screen.children().filterIsInstance<AbstractWidget>()
        val matches = all.filter { it.getMessage().string.contains(text, ignoreCase = true) }
        val clickable = matches.filter { it.isActive && it.visible }
        val pool = if (clickable.isNotEmpty()) clickable else matches
        val widget = pool.getOrNull(index)
            ?: throw RpcException(
                RpcErrors.GUI_ELEMENT_NOT_FOUND,
                if (matches.isEmpty()) {
                    "no widget contains \"$text\" on ${screen.javaClass.simpleName}"
                } else {
                    "no widget at index $index contains \"$text\" (${pool.size} match(es))"
                },
                JsonObject().apply {
                    addProperty("reason", "GUI_ELEMENT_NOT_FOUND")
                    addProperty("query", text)
                    addProperty("matchCount", matches.size)
                    addProperty("clickableMatchCount", clickable.size)
                    add("available", JsonArray().apply {
                        all.filter { it.visible && it.getMessage().string.isNotBlank() }
                            .take(60)
                            .forEach { add(it.getMessage().string) }
                    })
                },
            )
        return Target(
            widget.getX() + widget.getWidth() / 2.0,
            widget.getY() + widget.getHeight() / 2.0,
            "text",
            widget,
            "text=\"$text\"",
        )
    }

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

    /**
     * Widens a concrete `JsonElement` future to the handler's declared type —
     * `CompletableFuture` is invariant, so `CompletableFuture<JsonObject>` is not
     * a `CompletableFuture<JsonElement>`.
     */
    private fun <T : JsonElement> CompletableFuture<T>.asElement(): CompletableFuture<JsonElement> = thenApply { it }

    /**
     * Keeps hover/tooltips/carried stacks at the synthetic Screen click position.
     * Lite mode uses the render gate's virtual Screen-space cursor, without focus
     * or OS pointer movement. Continuous mode retains the real cursor path.
     *
     * The latter deliberately inverts MouseHandler's ceil-sized guiScaled mapping,
     * NOT GameRenderer's unrounded width/guiScale projection. PNG coordinates are
     * converted to Screen semantics by Capture first; applying render guiScale here
     * instead would make the click correct but leave hover at a different position.
     */
    private fun warpCursor(
        client: Minecraft,
        x: Double,
        y: Double,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): Boolean {
        if (client.screen == null) return false
        if (RenderControl.moveCursor(x + offsetX, y + offsetY)) return true
        val window = client.window
        val scaledWidth = window.getGuiScaledWidth()
        val scaledHeight = window.getGuiScaledHeight()
        if (scaledWidth <= 0 || scaledHeight <= 0 || window.getWindow() == 0L) return false
        val cursor = CaptureGeometry.screenToWindow(x + offsetX, y + offsetY,
            window.getScreenWidth(), window.getScreenHeight(), scaledWidth, scaledHeight)
        return try {
            GLFW.glfwSetCursorPos(window.getWindow(), cursor.first, cursor.second)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun noScreen(): RpcException = RpcException(
        RpcErrors.SCREEN_NOT_FOUND,
        "no screen is open; use gui.openBlock or gui.connect first",
        JsonObject().apply { addProperty("reason", "SCREEN_NOT_FOUND") },
    )

    private fun gameDir(): java.nio.file.Path? = try {
        RpcEnv.gameDir()
    } catch (t: Throwable) {
        null
    }

    private fun screenType(screen: Screen?): String = when {
        screen == null -> "none"
        screen is AbstractContainerScreen<*> -> "container"
        else -> screen.javaClass.simpleName
    }

    private fun itemJson(stack: ItemStack): JsonObject = ServerContext.itemStackToJson(stack)

    private fun slotIndex(element: JsonElement): Int = when {
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
        element.isJsonPrimitive -> element.asString.trim().toIntOrNull()
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "slot must be a number, got '${element.asString}'")
        else -> throw RpcException(RpcErrors.INVALID_PARAMS, "slot must be a number")
    }

    private fun parseClickType(name: String): ClickType {
        val normalized = name.trim().uppercase().replace('-', '_')
        return ClickType.entries.firstOrNull { it.name == normalized }
            ?: throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "unknown clickType '$name' (${ClickType.entries.joinToString("/") { it.name }})",
            )
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

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    private fun JsonObject.num(name: String): Double? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun JsonObject.int(name: String, default: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: default

    private fun JsonObject.bool(name: String, default: Boolean): Boolean =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: default

    /** `[x, y]` in GUI units, or PNG pixels for `png`. Falls back to `x` + `y`. */
    private fun JsonObject.pair(name: String): Pair<Double, Double> {
        val element = get(name)
        if (element != null && element.isJsonArray) {
            val array = element.asJsonArray
            if (array.size() >= 2) return Pair(array[0].asDouble, array[1].asDouble)
        }
        val x = num("x")
        val y = num("y")
        if (x != null && y != null) return Pair(x, y)
        throw RpcException(RpcErrors.INVALID_PARAMS, "$name must be [x, y], or accompanied by x and y")
    }

    /** `pos: [x,y,z]` (the server-plane shape) or separate `x`/`y`/`z` fields. */
    private fun JsonObject.pos(): BlockPos {
        val element = get("pos")
        if (element != null && element.isJsonArray) {
            val array = element.asJsonArray
            if (array.size() >= 3) return BlockPos(array[0].asInt, array[1].asInt, array[2].asInt)
        }
        val x = get("x")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        val y = get("y")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        val z = get("z")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        if (x != null && y != null && z != null) return BlockPos(x, y, z)
        throw RpcException(RpcErrors.INVALID_PARAMS, "pos [x,y,z] (or x/y/z) is required")
    }
}
