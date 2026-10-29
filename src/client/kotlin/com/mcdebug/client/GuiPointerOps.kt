package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.client.mixin.AbstractContainerScreenAccessor
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture

/** A Screen-space point plus how it was named; shared by click/hover/scroll. */
internal class GuiTarget(
    val x: Double,
    val y: Double,
    val mode: String,
    val widget: Pair<Int, AbstractWidget>?,
    val via: String?,
    val slot: Int? = null,
)

/**
 * Resolves `slot= | widget= | text= (+index) | gui=[x,y] | png=[x,y] | x&y` to Screen
 * coordinates. [resolve] is client thread only (reads the open Screen).
 *
 * `text` first matches widget labels. Mod GUIs often draw their "buttons", list
 * entries and labels straight from render/renderBg, so when no widget matches,
 * [prepare] renders one frame with the draw log and matches the strings it drew.
 */
internal object GuiTargets {
    const val USAGE = "slot= | widget= | text= | gui=[x,y] | png=[x,y] | x&y"
    private const val DRAWN = "drawnTarget"

    /** Any thread. Resolves a `text` target that no widget carries against drawn strings. */
    fun prepare(client: Minecraft, p: JsonObject): CompletableFuture<JsonObject> {
        val text = p.string("text")
        // Scroll-list entries only have current x/y in a frame that drew them: draw one first.
        if (p.get("widget")?.isJsonNull == false || (text != null && p.get("slot")?.isJsonNull != false)) {
            val fresh = ClientTasks.onClient(client) { client.screen?.let(WidgetFrames::hasScrollList) == true }
            return fresh.thenCompose { needed ->
                if (needed) drawFrame(client).thenApply { textPrepare(client, p, text) }.thenCompose { it }
                else textPrepare(client, p, text)
            }
        }
        return textPrepare(client, p, text)
    }

    private fun textPrepare(client: Minecraft, p: JsonObject, text: String?): CompletableFuture<JsonObject> {
        if (text == null || listOf("slot", "widget").any { p.get(it)?.isJsonNull == false }) return CompletableFuture.completedFuture(p)
        val widgetMatches = ClientTasks.onClient(client) {
            val screen = client.screen ?: throw noScreen()
            val clipped = WidgetFrames.clipped(screen)
            GuiWidgets.collect(screen).any { it !in clipped && it.message.string.contains(text, ignoreCase = true) }
        }
        return widgetMatches.thenCompose { found ->
            if (found) CompletableFuture.completedFuture(p)
            else ClientTasks.thenOnClient(client, drawFrame(client)) { log -> withDrawnTarget(client, p, text, log) }
        }
    }

    /** Any thread: render one fresh frame with the draw log and return that log. */
    fun drawFrame(client: Minecraft): CompletableFuture<JsonObject?> = ClientTasks.onClient(client) {
        // gui mode only exists for container screens; the pixels are not needed either way.
        if (client.screen is AbstractContainerScreen<*>) "gui" else "window"
    }.thenCompose { mode ->
        Capture.screenshot(client, CaptureOptions(mode = mode, format = "none", drawLog = true, delayTicks = 0))
    }.thenApply { it.asJsonObject.getAsJsonObject("drawLog") }

    private fun withDrawnTarget(client: Minecraft, p: JsonObject, text: String, log: JsonObject?): JsonObject {
        val screen = client.screen ?: throw noScreen()
        val drawn = log?.getAsJsonArray("entries")?.map { it.asJsonObject }?.filter { it.get("op")?.asString == "text" }.orEmpty()
        val matches = drawn.filter { it.get("text").asString.contains(text, ignoreCase = true) }
        val index = p.int("index", 0)
        val clipped = WidgetFrames.clipped(screen)
        val clippedMatches = clipped.count { it.message.string.contains(text, ignoreCase = true) }
        val hit = matches.getOrNull(index)
            ?: throw RpcException(
                RpcErrors.GUI_ELEMENT_NOT_FOUND,
                when {
                    matches.isEmpty() && clippedMatches > 0 ->
                        "\"$text\" only matches $clippedMatches widget(s) scrolled out of their list; gui scroll it into view first"
                    matches.isEmpty() -> "no widget or drawn text contains \"$text\" on ${screen.javaClass.simpleName}"
                    else -> "no drawn text at index $index contains \"$text\" (${matches.size} match(es))"
                },
                reason(if (matches.isEmpty() && clippedMatches > 0) "WIDGET_CLIPPED" else "GUI_ELEMENT_NOT_FOUND").apply {
                    addProperty("query", text)
                    addProperty("matchCount", matches.size)
                    if (clippedMatches > 0) addProperty("clippedMatchCount", clippedMatches)
                    add("available", stringArray(
                        (GuiWidgets.collect(screen).filter { it.visible && it !in clipped }.map { it.message.string } + drawn.map { it.get("text").asString })
                            .filter { it.isNotBlank() }.distinct().take(60),
                    ))
                },
            )
        val rect = hit.getAsJsonArray("rect")
        return p.deepCopy().apply {
            add(DRAWN, JsonObject().apply {
                addProperty("x", rect[0].asDouble + rect[2].asDouble / 2)
                addProperty("y", rect[1].asDouble + rect[3].asDouble / 2)
                addProperty("text", hit.get("text").asString)
            })
        }
    }

    fun resolve(client: Minecraft, p: JsonObject): GuiTarget {
        val screen = client.screen ?: throw noScreen()
        p.obj(DRAWN)?.let { d ->
            return GuiTarget(d.get("x").asDouble, d.get("y").asDouble, "drawnText", null, "drawnText=\"${d.get("text").asString}\"")
        }
        p.get("slot")?.takeIf { !it.isJsonNull }?.let { return slotTarget(client, screen, index(it)) }
        p.get("widget")?.takeIf { !it.isJsonNull }?.let { return widgetTarget(screen, index(it)) }
        p.string("text")?.let { return textTarget(screen, it, p.int("index", 0)) }
        if (p.get("gui")?.isJsonNull == false) {
            val pair = p.pair("gui")
            return GuiTarget(pair.first, pair.second, "gui", null, null)
        }
        if (p.get("png")?.isJsonNull == false) {
            val pair = p.pair("png")
            val gui = Capture.pngToGui(pair.first, pair.second)
            return GuiTarget(gui.first, gui.second, "png", null, "png=${pair.first},${pair.second}")
        }
        val x = p.num("x") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "a target is required: $USAGE")
        val y = p.num("y") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "y is required with x")
        return GuiTarget(x, y, "gui", null, null)
    }

    /** Slot centre in absolute GUI units (the same point gui.click --slot reports). */
    fun slotTarget(client: Minecraft, screen: Screen, index: Int): GuiTarget {
        val container = screen as? AbstractContainerScreen<*>
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "slot targets need a container screen (open: ${screenType(screen)})", reason("NOT_A_CONTAINER"))
        val menu = container.getMenu()
        val slot = menu.slots.getOrNull(index)
            ?: throw RpcException(
                RpcErrors.SLOT_OUT_OF_RANGE,
                "slot $index out of range for ${menu.javaClass.simpleName} (${menu.slots.size} slots)",
                reason("SLOT_OUT_OF_RANGE").apply { addProperty("slotCount", menu.slots.size) },
            )
        val accessor = container as AbstractContainerScreenAccessor
        return GuiTarget(accessor.leftPos + slot.x + 8.0, accessor.topPos + slot.y + 8.0, "slot", null, "slot=$index", index)
    }

    fun widgetTarget(screen: Screen, index: Int): GuiTarget {
        val widgets = GuiWidgets.collect(screen)
        val widget = widgets.getOrNull(index)
            ?: throw RpcException(
                RpcErrors.GUI_ELEMENT_NOT_FOUND,
                "no widget $index on ${screen.javaClass.simpleName} (${widgets.size} widgets; indices from gui.screen)",
                reason("GUI_ELEMENT_NOT_FOUND").apply { addProperty("widgetCount", widgets.size) },
            )
        if (widget in WidgetFrames.clipped(screen)) throw RpcException(
            RpcErrors.GUI_ELEMENT_NOT_FOUND,
            "widget $index (\"${widget.message.string}\") is scrolled out of its list; gui scroll it into view first",
            reason("WIDGET_CLIPPED").apply { addProperty("widgetCount", widgets.size) },
        )
        return GuiTarget(widget.x + widget.width / 2.0, widget.y + widget.height / 2.0, "widget", index to widget, "widget=$index")
    }

    fun textTarget(screen: Screen, text: String, index: Int): GuiTarget {
        val clipped = WidgetFrames.clipped(screen)
        val all = GuiWidgets.collect(screen).withIndex().filter { it.value !in clipped }
        val matches = all.filter { it.value.message.string.contains(text, ignoreCase = true) }
        val clickable = matches.filter { it.value.isActive && it.value.visible }
        val pool = clickable.ifEmpty { matches }
        val widget = pool.getOrNull(index)
            ?: throw RpcException(
                RpcErrors.GUI_ELEMENT_NOT_FOUND,
                if (matches.isEmpty()) "no widget contains \"$text\" on ${screen.javaClass.simpleName}"
                else "no widget at index $index contains \"$text\" (${pool.size} match(es))",
                reason("GUI_ELEMENT_NOT_FOUND").apply {
                    addProperty("query", text)
                    addProperty("matchCount", matches.size)
                    addProperty("clickableMatchCount", clickable.size)
                    add("available", stringArray(
                        all.map { it.value }.filter { it.visible && it.message.string.isNotBlank() }.take(60).map { it.message.string },
                    ))
                },
            )
        val w = widget.value
        return GuiTarget(w.x + w.width / 2.0, w.y + w.height / 2.0, "text", widget.index to w, "text=\"$text\"")
    }

    fun index(element: JsonElement): Int = when {
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
        element.isJsonPrimitive -> element.asString.trim().toIntOrNull()
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "index must be a number, got '${element.asString}'")
        else -> throw RpcException(RpcErrors.INVALID_PARAMS, "index must be a number")
    }

    fun report(target: GuiTarget): JsonObject = JsonObject().apply {
        addProperty("mode", target.mode)
        addProperty("guiX", target.x)
        addProperty("guiY", target.y)
        target.via?.let { addProperty("via", it) }
        target.slot?.let { addProperty("slot", it) }
    }
}

/**
 * Pointer input besides clicks, and opening arbitrary Screens:
 *
 * - `gui.hover`      move the (virtual or real) cursor, render one fresh frame and
 *                    report what is hovered plus the tooltips that frame actually drew
 *                    (custom renderBg tooltips such as energy bars included);
 * - `gui.scroll`     `Screen.mouseScrolled` at a target, then settle;
 * - `gui.openScreen` `inventory`, `pkg.Class` (public constructor) or `pkg.Class#method`
 *                    (public static factory) — a client-side Screen fixture without a block.
 */
object GuiPointerOps : ClientRpcGroup {
    override fun methods(): Map<String, ClientRpcHandler> = linkedMapOf(
        "hover" to ::hover,
        "scroll" to ::scroll,
        "openScreen" to ::openScreen,
    )

    // ---------------------------------------------------------------- hover

    private fun hover(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required: ${GuiTargets.USAGE}")
        val moved = GuiTargets.prepare(client, p).thenCompose { q -> ClientTasks.onClient(client) {
            val screen = client.screen ?: throw noScreen()
            val target = GuiTargets.resolve(client, q)
            val cursorMoved = GuiCursor.warp(client, target.x, target.y)
            // What MouseHandler does on a real move; widgets/sliders may track it.
            screen.mouseMoved(target.x, target.y)
            target to cursorMoved
        } }
        // A fresh frame is the only place hover state and tooltips exist; its draw
        // log holds the tooltips that were really drawn this frame.
        val frame = moved.thenCompose { GuiTargets.drawFrame(client) }
        return ClientTasks.thenOnClient(client, frame) { drawLog ->
            val (target, cursorMoved) = moved.join()
            val view = GuiScreenOps.snapshot(client)
            GuiTargets.report(target).apply {
                addProperty("ok", true)
                addProperty("cursorMoved", cursorMoved)
                if (!cursorMoved) addProperty("warning", "cursor could not be moved: hover state reflects the old position")
                add("tooltips", drawnTooltips(drawLog))
                view.get("hoveredSlot")?.let { add("hoveredSlot", it) }
                view.get("hoveredTooltip")?.let { add("hoveredTooltip", it) }
                view.getAsJsonArray("widgets")?.firstOrNull { it.asJsonObject.get("hovered")?.asBoolean == true }
                    ?.let { add("hoveredWidget", it) }
                addProperty("screen", view.get("type")?.asString ?: "")
            }
        }.asElement()
    }

    private fun drawnTooltips(drawLog: JsonObject?): JsonArray = JsonArray().apply {
        drawLog?.getAsJsonArray("entries")?.forEach { entry ->
            val e = entry.asJsonObject
            if (e.get("op")?.asString == "tooltip") add(e.get("lines"))
        }
    }

    // --------------------------------------------------------------- scroll

    private fun scroll(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required: target + dy (+dx)")
        val dy = p.num("dy") ?: p.num("amount") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "dy is required (positive = wheel up)")
        val dx = p.num("dx") ?: 0.0
        if (!dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx) > 100 || kotlin.math.abs(dy) > 100) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "dx/dy must be within -100..100")
        }
        val settle = if (p.bool("settle", true)) GuiSettle.Options(p.boundedInt("waitTicks", 20, 0..1200), p.boundedInt("quietTicks", 2, 0..100)) else null
        val moveCursor = p.bool("moveCursor", true)
        val scrolled = GuiTargets.prepare(client, p).thenCompose { q -> ClientTasks.onClient(client) {
            val screen = client.screen ?: throw noScreen()
            val target = GuiTargets.resolve(client, q)
            val cursorMoved = moveCursor && GuiCursor.warp(client, target.x, target.y)
            val handled = screen.mouseScrolled(target.x, target.y, dx, dy)
            GuiTargets.report(target).apply {
                addProperty("ok", true)
                addProperty("handled", handled)
                addProperty("dx", dx)
                addProperty("dy", dy)
                addProperty("cursorMoved", cursorMoved)
            }
        } }
        val settled = ClientTasks.thenOnClient(client, scrolled) {
            if (settle == null) CompletableFuture.completedFuture<JsonObject?>(null)
            else GuiSettle.settle(client, settle).thenApply<JsonObject?> { it }
        }.thenCompose { it }
        return ClientTasks.thenOnClient(client, settled) { s ->
            scrolled.join().apply {
                s?.let { add("settle", it) }
                addProperty("screen", client.screen?.let(::screenType) ?: "none")
            }
        }.asElement()
    }

    // ----------------------------------------------------------- openScreen

    private fun openScreen(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val spec = params?.string("screen")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "screen is required: inventory | pkg.Class | pkg.Class#staticFactory")
        return ClientTasks.onClient(client) {
            val player = client.player
            val previous = client.screen
            val screen = try {
                ScreenFactory.create(spec, client, player, previous)
            } catch (e: RpcException) {
                throw e
            } catch (t: Throwable) {
                throw failure(spec, "constructing", t)
            }
            try {
                client.setScreen(screen)
            } catch (t: Throwable) {
                // A half-initialised Screen must not stay open; the world/title screen takes over.
                if (client.screen === screen) runCatching { client.setScreen(null) }
                throw failure(spec, "init()", t)
            }
            val open = client.screen
            JsonObject().apply {
                addProperty("ok", true)
                addProperty("requested", screen.javaClass.name)
                // InventoryScreen hands itself over to the creative screen in creative mode, for example.
                addProperty("screen", open?.let(::screenType) ?: "none")
                addProperty("replacedBy", open !== screen)
                addProperty("title", open?.title?.string ?: "")
                addProperty("widgets", open?.let { GuiWidgets.collect(it).size } ?: 0)
            }
        }.asElement()
    }

    private fun failure(spec: String, phase: String, t: Throwable): RpcException {
        val cause = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
        return RpcException(RpcErrors.INTERNAL_ERROR, "$spec failed in $phase: $cause", reason("SCREEN_FAILED").apply {
            addProperty("trace", ProblemLog.describe(cause))
        })
    }
}

/** What a Screen constructor/factory may ask for, by parameter type. */
internal class ScreenArgs(
    val client: Minecraft,
    val player: Player?,
    val parent: Screen?,
    val menu: AbstractContainerMenu? = null,
    val title: Component? = null,
) {
    /** The argument for a parameter of type [t] ([NONE] when nothing fits; a null parent is a valid argument). */
    fun value(t: Class<*>): Any? = when {
        t.isAssignableFrom(Minecraft::class.java) -> client
        t.isAssignableFrom(Options::class.java) -> client.options
        Screen::class.java.isAssignableFrom(t) -> if (parent == null || t.isInstance(parent)) parent else NONE
        AbstractContainerMenu::class.java.isAssignableFrom(t) -> menu?.takeIf { t.isInstance(it) } ?: NONE
        t.isAssignableFrom(Inventory::class.java) -> player?.inventory ?: NONE
        t.isAssignableFrom(Component::class.java) -> title ?: NONE
        player != null && t.isInstance(player) -> player
        else -> NONE
    }

    /**
     * Invoke the public constructor that uses the most of the available context
     * (so a `(parent)` constructor beats Kotlin's generated no-arg one).
     */
    fun construct(type: Class<*>): Any? {
        val constructor = type.constructors.filter { c -> c.parameterTypes.all { value(it) !== NONE } }
            .maxByOrNull { it.parameterCount } ?: return NONE
        return constructor.newInstance(*constructor.parameterTypes.map(::value).toTypedArray())
    }

    fun invoke(method: java.lang.reflect.Method): Any? =
        if (method.parameterTypes.any { value(it) === NONE }) NONE
        else method.invoke(null, *method.parameterTypes.map(::value).toTypedArray())

    companion object {
        val NONE = Any()
    }
}

/**
 * `inventory` | `pkg.Class` | `pkg.Class#method`. Constructors/factories may take any
 * combination of Screen (the open screen as parent, may be null), Player, Minecraft and
 * Options; constructors using the most of these win, factories the fewest. Loaded through the mod class loader, so any
 * mod's (or a consumer's dev-only) Screen is reachable.
 */
internal object ScreenFactory {
    private const val PARAMS = "Screen/Player/Minecraft/Options"

    fun create(spec: String, client: Minecraft, player: Player?, parent: Screen?): Screen {
        if (spec.equals("inventory", ignoreCase = true)) {
            val p = player ?: throw RpcException(RpcErrors.CLIENT_NOT_READY, "inventory needs a player in a world")
            return InventoryScreen(p)
        }
        val className = spec.substringBefore('#')
        val methodName = spec.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val type = try {
            Class.forName(className, true, ScreenFactory::class.java.classLoader)
        } catch (_: ClassNotFoundException) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "class not found: $className (runtime/Mojang name)", reason("CLASS_NOT_FOUND"))
        }
        val args = ScreenArgs(client, player, parent)
        val created: Any? = if (methodName != null) {
            type.methods.filter { it.name == methodName && Modifier.isStatic(it.modifiers) }.sortedBy { it.parameterCount }
                .asSequence().map { args.invoke(it) }.firstOrNull { it !== ScreenArgs.NONE }
                ?: throw RpcException(RpcErrors.INVALID_PARAMS,
                    "no public static $methodName(...) on $className taking only $PARAMS", reason("NO_FACTORY"))
        } else {
            if (!Screen::class.java.isAssignableFrom(type)) {
                throw RpcException(RpcErrors.INVALID_PARAMS, "$className is not a Screen; name a static factory with #method", reason("NOT_A_SCREEN"))
            }
            args.construct(type).takeIf { it !== ScreenArgs.NONE }
                ?: throw RpcException(RpcErrors.INVALID_PARAMS,
                    "$className has no public constructor taking only $PARAMS; use a static factory (#method)", reason("NO_FACTORY"))
        }
        return created as? Screen
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "$spec returned ${created?.javaClass?.name ?: "null"}, not a Screen", reason("NOT_A_SCREEN"))
    }
}
