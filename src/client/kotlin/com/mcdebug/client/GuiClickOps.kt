package com.mcdebug.client

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.client.mixin.AbstractContainerScreenAccessor
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ClickType
import java.util.concurrent.CompletableFuture

/**
 * `gui.click`: slot clicks go through the vanilla inventory-click path (the server
 * decides the outcome); every other target is an ordinary Screen mouse event at
 * a position. No GLFW input synthesis, no window focus.
 *
 * After the click the handler *settles* ([GuiSettle]) instead of sleeping a fixed
 * 20 ticks: it returns once the server has answered and the GUI is quiet, with
 * `waitTicks` only as the upper bound. `settle: false` restores the fixed wait.
 */
object GuiClickOps : ClientRpcGroup {
    override fun methods(): Map<String, ClientRpcHandler> = linkedMapOf("click" to ::click)

    private fun click(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(
            RpcErrors.INVALID_PARAMS,
            "params required: slot= | widget= | text= | gui=[x,y] | png=[x,y] | x&y (+ button/clickType/shift/waitTicks)",
        )
        val slotElement = p.get("slot")?.takeIf { !it.isJsonNull }
        val widgetIndex = p.get("widget")?.takeIf { !it.isJsonNull }
        val text = p.string("text")
        val guiElement = p.get("gui")?.takeIf { !it.isJsonNull }
        val pngElement = p.get("png")?.takeIf { !it.isJsonNull }
        val wait = WaitPolicy(p.boundedInt("waitTicks", 20, 0..1200), p.bool("settle", true), p.boundedInt("quietTicks", 2, 0..100))
        val moveCursor = p.bool("moveCursor", true)
        return when {
            slotElement != null -> clickSlot(client, slotIndex(slotElement), p, wait, moveCursor)
            widgetIndex != null -> {
                val index = slotIndex(widgetIndex)
                clickCoordinates(client, p, wait, moveCursor) { resolveWidgetTarget(client, index) }
            }
            text != null -> {
                val index = p.int("index", 0)
                clickCoordinates(client, p, wait, moveCursor) { resolveTextTarget(client, text, index) }
            }
            guiElement != null -> {
                val pair = p.pair("gui")
                clickCoordinates(client, p, wait, moveCursor) { Target(pair.first, pair.second, "gui", null, null) }
            }
            pngElement != null -> {
                val pair = p.pair("png")
                clickCoordinates(client, p, wait, moveCursor) {
                    val gui = Capture.pngToGui(pair.first, pair.second)
                    Target(gui.first, gui.second, "png", null, "png=${pair.first},${pair.second}")
                }
            }
            else -> {
                val x = p.num("x")
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "x is required when no slot/widget/text/gui/png is given")
                val y = p.num("y")
                    ?: throw RpcException(RpcErrors.INVALID_PARAMS, "y is required when no slot/widget/text/gui/png is given")
                clickCoordinates(client, p, wait, moveCursor) { Target(x, y, "gui", null, null) }
            }
        }
    }

    private class WaitPolicy(val maxTicks: Int, val settle: Boolean, val quietTicks: Int)

    private class Target(
        val x: Double,
        val y: Double,
        val mode: String,
        val widget: Pair<Int, AbstractWidget>?,
        val via: String?,
    )

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

    private fun clickSlot(
        client: Minecraft,
        index: Int,
        params: JsonObject,
        wait: WaitPolicy,
        moveCursor: Boolean,
    ): CompletableFuture<JsonElement> {
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
            val moved = moveCursor && GuiCursor.warp(client, guiX, guiY)
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
        return afterClick(client, clicked, wait)
    }

    private fun clickCoordinates(
        client: Minecraft,
        params: JsonObject,
        wait: WaitPolicy,
        moveCursor: Boolean,
        resolve: () -> Target,
    ): CompletableFuture<JsonElement> {
        val button = params.int("button", 0)
        val clicked = ClientTasks.onClient(client) {
            val screen = client.screen ?: throw noScreen()
            val target = resolve()
            // Snapshot before dispatch: the click may relabel/move the widget.
            val before = target.widget?.let { (i, w) -> GuiScreenOps.widgetJson(client, i, w) }
            val moved = moveCursor && GuiCursor.warp(client, target.x, target.y)
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
                before = before,
                beforeCount = null,
                x = target.x,
                y = target.y,
                via = target.via,
                pressed = pressed,
                released = released,
                cursorMoved = moved,
            )
        }
        return afterClick(client, clicked, wait)
    }

    private fun afterClick(client: Minecraft, clicked: CompletableFuture<ClickResult>, wait: WaitPolicy): CompletableFuture<JsonElement> {
        val waited: CompletableFuture<JsonObject?> = ClientTasks.thenOnClient(client, clicked) {
            val next: CompletableFuture<JsonObject?> =
                if (wait.settle) GuiSettle.settle(client, GuiSettle.Options(wait.maxTicks, wait.quietTicks)).thenApply { it }
                else ClientTasks.awaitTicks(wait.maxTicks).thenApply { null }
            next
        }.thenCompose { it }
        // `clicked` is already complete here, so `join()` cannot block; it just
        // hands the ClickResult to the next client-thread hop.
        return ClientTasks.thenOnClient(client, waited) { settle ->
            clickReport(client, clicked.join()).apply { settle?.let { add("settle", it) } }
        }.asElement()
    }

    /** Reports the post-click state. */
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

    private fun resolveWidgetTarget(client: Minecraft, index: Int): Target {
        val screen = client.screen ?: throw noScreen()
        val widgets = GuiWidgets.collect(screen)
        val widget = widgets.getOrNull(index)
            ?: throw RpcException(
                RpcErrors.GUI_ELEMENT_NOT_FOUND,
                "no widget $index on ${screen.javaClass.simpleName} (${widgets.size} widgets; indices from gui.screen)",
                JsonObject().apply {
                    addProperty("reason", "GUI_ELEMENT_NOT_FOUND")
                    addProperty("widgetCount", widgets.size)
                },
            )
        return Target(
            widget.getX() + widget.getWidth() / 2.0,
            widget.getY() + widget.getHeight() / 2.0,
            "widget",
            index to widget,
            "widget=$index",
        )
    }

    private fun resolveTextTarget(client: Minecraft, text: String, index: Int): Target {
        val screen = client.screen ?: throw noScreen()
        val all = GuiWidgets.collect(screen).withIndex().toList()
        val matches = all.filter { it.value.getMessage().string.contains(text, ignoreCase = true) }
        val clickable = matches.filter { it.value.isActive && it.value.visible }
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
                    add("available", stringArray(
                        all.map { it.value }.filter { it.visible && it.getMessage().string.isNotBlank() }
                            .take(60).map { it.getMessage().string },
                    ))
                },
            )
        return Target(
            widget.value.getX() + widget.value.getWidth() / 2.0,
            widget.value.getY() + widget.value.getHeight() / 2.0,
            "text",
            widget.index to widget.value,
            "text=\"$text\"",
        )
    }

    private fun slotIndex(element: JsonElement): Int = when {
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
        element.isJsonPrimitive -> element.asString.trim().toIntOrNull()
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "index must be a number, got '${element.asString}'")
        else -> throw RpcException(RpcErrors.INVALID_PARAMS, "index must be a number")
    }

    private fun parseClickType(name: String): ClickType {
        val normalized = name.trim().uppercase().replace('-', '_')
        return ClickType.entries.firstOrNull { it.name == normalized }
            ?: throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "unknown clickType '$name' (${ClickType.entries.joinToString("/") { it.name }})",
            )
    }
}
