package com.mcdebug.client

import com.google.gson.JsonObject
import com.mcdebug.client.mixin.AbstractContainerScreenAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket
import net.minecraft.world.item.ItemStack
import java.util.concurrent.CompletableFuture

/**
 * Pure quiescence bookkeeping: settled once the server barrier has answered and
 * the observed fingerprint stayed identical for [quietTicks] consecutive checks.
 */
class QuietTracker(private val quietTicks: Int) {
    private var last: Long? = null
    private var stable = 0

    init {
        require(quietTicks >= 0) { "quietTicks must be >= 0" }
    }

    /** Feed one observation; returns true once settled. Observations before the barrier never count. */
    fun observe(fingerprint: Long, barrierDone: Boolean): Boolean {
        if (!barrierDone) {
            last = fingerprint
            stable = 0
            return false
        }
        stable = if (last == fingerprint) stable + 1 else 0
        last = fingerprint
        return stable >= quietTicks
    }
}

/**
 * "Wait until the GUI has caught up" without a fixed sleep.
 *
 * 1. **Server barrier**: a command-suggestion request. The server handles it on its
 *    main thread *after* every earlier packet from this connection (a container
 *    click, a menu button), and the answer arrives after any packets those earlier
 *    requests sent back. A ping would not do: the server answers pings on the
 *    network thread, possibly before the click was processed.
 * 2. **Quiescence**: slots, carried stack, Screen identity and widgets unchanged for
 *    `quietTicks` natural client ticks, which also covers state the server only
 *    syncs on its next tick. Container data slots (progress bars, energy) are
 *    deliberately excluded, so a running machine still settles.
 *
 * Passive like every other wait here: it observes client ticks and never drives one.
 */
object GuiSettle {
    /** Never collides with ClientSuggestionProvider ids, which count up from 0 (idle = -1). */
    private const val FIRST_ID = Int.MIN_VALUE
    private const val BARRIER_INPUT = "mcdebug-settle-barrier"
    private var nextId = FIRST_ID
    private val barriers = HashMap<Int, CompletableFuture<Unit>>() // client thread only

    data class Options(val maxTicks: Int, val quietTicks: Int)

    fun options(params: JsonObject?, defaultMax: Int, defaultQuiet: Int = 2): Options {
        val p = params ?: JsonObject()
        return Options(p.boundedInt("maxTicks", defaultMax, 0..1200), p.boundedInt("quietTicks", defaultQuiet, 0..100))
    }

    /** Called by the ClientPacketListener mixin on the client thread; true = our barrier, swallow it. */
    @JvmStatic
    fun onSuggestions(id: Int): Boolean {
        val barrier = barriers.remove(id) ?: return false
        barrier.complete(Unit)
        return true
    }

    /** Client thread only. Completes with the settle report; never fails on timeout. */
    fun settle(client: Minecraft, options: Options): CompletableFuture<JsonObject> {
        val start = ClientTasks.currentTick()
        val connection = client.connection
        val barrierId = if (connection != null) nextBarrierId() else null
        val barrier = if (barrierId != null) {
            CompletableFuture<Unit>().also { barriers[barrierId] = it }
        } else CompletableFuture.completedFuture(Unit)
        if (barrierId != null) connection!!.send(ServerboundCommandSuggestionPacket(barrierId, BARRIER_INPUT))
        val tracker = QuietTracker(options.quietTicks)
        val waiting = ClientTasks.until(options.maxTicks) { tracker.observe(fingerprint(client), barrier.isDone) }
        return ClientTasks.thenOnClient(client, waiting) { settled ->
            if (barrierId != null) barriers.remove(barrierId)
            JsonObject().apply {
                addProperty("settled", settled)
                addProperty("ticks", ClientTasks.currentTick() - start)
                addProperty("barrier", when {
                    barrierId == null -> "no-connection"
                    barrier.isDone -> "acknowledged"
                    else -> "timeout"
                })
                addProperty("maxTicks", options.maxTicks)
                addProperty("quietTicks", options.quietTicks)
            }
        }
    }

    fun close() {
        barriers.values.forEach { it.cancel(false) }
        barriers.clear()
    }

    private fun nextBarrierId(): Int {
        val id = nextId
        nextId = if (nextId == -1_000_001) FIRST_ID else nextId + 1
        return id
    }

    /** Cheap hash of what a GUI assertion usually looks at. */
    fun fingerprint(client: Minecraft): Long {
        var h = 1125899906842597L
        fun mix(v: Long) { h = 31 * h + v }
        fun mixStack(stack: ItemStack) {
            mix(if (stack.isEmpty) 0 else ItemStack.hashItemAndComponents(stack).toLong())
            mix(stack.count.toLong())
        }
        val screen = client.screen
        mix(System.identityHashCode(screen).toLong())
        val player = client.player
        val menu = (screen as? AbstractContainerScreen<*>)?.getMenu() ?: player?.containerMenu
        if (menu != null) {
            mix(menu.containerId.toLong())
            menu.slots.forEach { mixStack(it.getItem()) }
            mixStack(menu.getCarried())
        }
        if (screen != null) {
            mix(screen.width.toLong())
            mix(screen.height.toLong())
            (screen as? AbstractContainerScreenAccessor)?.let { mix(it.leftPos.toLong()); mix(it.topPos.toLong()) }
            GuiWidgets.collect(screen).forEach { w ->
                mix(w.getMessage().string.hashCode().toLong())
                mix(w.getX().toLong() shl 20 or w.getY().toLong())
                mix(if (w.isActive) 1 else 0)
                mix(if (w.visible) 1 else 0)
            }
        }
        return h
    }
}

/** Depth-first widget traversal shared by `gui.screen`, `--text`/`--widget` clicks and annotation. */
object GuiWidgets {
    fun collect(screen: net.minecraft.client.gui.screens.Screen): List<AbstractWidget> {
        val out = ArrayList<AbstractWidget>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun visit(listener: net.minecraft.client.gui.components.events.GuiEventListener, depth: Int) {
            if (depth > 16 || !seen.add(listener)) return
            if (listener is AbstractWidget) out.add(listener)
            if (listener is net.minecraft.client.gui.components.events.ContainerEventHandler) {
                listener.children().forEach { visit(it, depth + 1) }
            }
        }
        screen.children().forEach { visit(it, 0) }
        return out
    }
}
