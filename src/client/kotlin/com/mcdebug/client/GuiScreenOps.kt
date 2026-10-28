package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.client.mixin.AbstractContainerScreenAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import java.util.concurrent.CompletableFuture

/**
 * `gui.screen`: the structured view of the open Screen. Widget indices come from
 * the depth-first traversal in [GuiWidgets] and are what `gui.click {widget: N}`
 * and the annotated screenshot labels use.
 */
object GuiScreenOps : ClientRpcGroup {
    override fun methods(): Map<String, ClientRpcHandler> = linkedMapOf("screen" to ::screen)

    private fun screen(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> =
        ClientTasks.onClient(client) { snapshot(client) }

    /** Client thread only; shared with `gui.act`. */
    fun snapshot(client: Minecraft): JsonObject {
        val current = client.screen
            ?: return JsonObject().apply {
                addProperty("open", false)
                addProperty("inWorld", client.player != null)
                addProperty("playerMenu", client.player?.containerMenu?.javaClass?.name ?: "")
            }
        return JsonObject().apply {
            addProperty("open", true)
            addProperty("class", current.javaClass.name)
            addProperty("type", screenType(current))
            addProperty("title", current.getTitle().string)
            addProperty("width", current.width)
            addProperty("height", current.height)
            ScreenGuard.currentFault(client)?.let { add("fault", it) }
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
                val hovered = accessor.hoveredSlot
                if (hovered != null) {
                    addProperty("hoveredSlot", hovered.index)
                    if (hovered.hasItem()) {
                        add("hoveredTooltip", stringArray(
                            Screen.getTooltipFromItem(client, hovered.getItem()).map { it.string }))
                    }
                }
            }
            val widgets = GuiWidgets.collect(current)
            val focused = current.getFocused()
            add("widgets", JsonArray().apply {
                widgets.forEachIndexed { i, widget -> add(widgetJson(client, i, widget, focused)) }
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
            if (!slot.isActive) addProperty("active", false)
        }
    }

    fun widgetJson(client: Minecraft, index: Int, widget: AbstractWidget, focused: Any? = null): JsonObject =
        JsonObject().apply {
            addProperty("index", index)
            addProperty("class", widget.javaClass.name)
            addProperty("text", widget.getMessage().string)
            addProperty("x", widget.getX())
            addProperty("y", widget.getY())
            addProperty("width", widget.getWidth())
            addProperty("height", widget.getHeight())
            addProperty("active", widget.isActive)
            addProperty("visible", widget.visible)
            if (widget.isHovered()) addProperty("hovered", true)
            if (widget.isFocused || widget === focused) addProperty("focused", true)
            if (widget is EditBox) addProperty("value", widget.getValue())
            widget.getTooltip()?.let { tooltip ->
                add("tooltip", stringArray(tooltip.toCharSequence(client).map(DrawRecorder::plain)))
            }
        }
}
