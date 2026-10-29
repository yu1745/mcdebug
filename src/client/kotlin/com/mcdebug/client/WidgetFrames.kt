package com.mcdebug.client

import net.minecraft.client.gui.components.AbstractSelectionList
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import java.util.WeakHashMap

/**
 * Which widgets the latest completed frame actually drew (client thread only).
 * Scroll-list entries get their x/y assigned while rendering, so an entry scrolled out
 * of view keeps stale coordinates that may still lie inside the list: "not drawn in the
 * latest frame" is the only reliable clipped signal.
 */
object WidgetFrames {
    private val drawnIn = WeakHashMap<AbstractWidget, Long>()
    private var current = 0L
    private var completed = 0L
    private var completedScreen: Screen? = null

    @JvmStatic fun mark(widget: AbstractWidget) { drawnIn[widget] = current }

    fun beginFrame() { current++ }

    fun endFrame(screen: Screen?) {
        completed = current
        completedScreen = screen
    }

    /** True when [screen] holds a scroll list, i.e. widget positions depend on the last frame. */
    fun hasScrollList(screen: Screen): Boolean = screen.children().any { contains(it, 0) }

    private fun contains(listener: GuiEventListener, depth: Int): Boolean = depth <= 16 &&
        (listener is AbstractSelectionList<*> ||
            (listener is ContainerEventHandler && listener.children().any { contains(it, depth + 1) }))

    /**
     * Widgets inside a scroll list that the latest frame of [screen] did not draw.
     * Empty when no frame of this Screen completed yet (nothing is known).
     */
    fun clipped(screen: Screen): Set<AbstractWidget> {
        if (completedScreen !== screen) return emptySet()
        val out = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AbstractWidget, Boolean>())
        fun visit(listener: GuiEventListener, inList: Boolean, depth: Int) {
            if (depth > 16) return
            val list = inList || listener is AbstractSelectionList<*>
            if (inList && listener is AbstractWidget && drawnIn[listener] != completed) out.add(listener)
            if (listener is ContainerEventHandler) listener.children().forEach { visit(it, list, depth + 1) }
        }
        screen.children().forEach { visit(it, false, 0) }
        return out
    }
}
