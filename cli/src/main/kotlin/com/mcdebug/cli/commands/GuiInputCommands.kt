package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int

abstract class GuiModifiersCmd(name: String, help: String, epilog: String) :
    CliktCommand(name = name, help = help, epilog = epilog) {
    private val ctrl by option("--ctrl", help = "logical Control modifier (use --super for macOS Command)").flag()
    private val shift by option("--shift", help = "logical Shift modifier").flag()
    private val alt by option("--alt", help = "logical Alt modifier").flag()
    private val superKey by option("--super", help = "logical Super/Command modifier").flag()
    protected fun modifiers(): Int = (if (shift) 1 else 0) or (if (ctrl) 2 else 0) or
        (if (alt) 4 else 0) or (if (superKey) 8 else 0)
}

class GuiKeyCmd : GuiModifiersCmd(
    "key", "send one key press/release to the current Screen; does not inject system keys or game movement",
    "Example: mcdebug --session dev gui key --key A --ctrl\nUse gui type for characters; --key ENTER for submission. No held key survives the request.",
) {
    private val key by option("--key", help = "GLFW key name or character: ENTER, ESCAPE, TAB, A, 1, LEFT, F1...")
    private val code by option("--code", help = "numeric GLFW key code instead of --key").int()
    override fun run() {
        if ((key == null) == (code == null)) throw UsageError("choose exactly one of --key or --code")
        withGuiApi { api -> printJson(code?.let { api.gui.key(it, modifiers()) } ?: api.gui.key(key!!, modifiers())) }
    }
}

class GuiTypeCmd : GuiModifiersCmd(
    "type", "type Unicode characters into the current focused Screen widget (not a direct EditBox value assignment)",
    "Example: mcdebug --session dev gui type --text '机器 GUI' --clear\nFocus the widget first with gui click. Control characters/newlines use gui key, not text.",
) {
    private val text by option("--text", help = "text to dispatch, at most 4096 UTF-16 units").required()
    private val clear by option("--clear", help = "send logical select-all/backspace before typing").flag()
    override fun run() = withGuiApi { api -> printJson(api.gui.type(text, clear, modifiers())) }
}

class GuiDragCmd : GuiModifiersCmd(
    "drag", "press, passively drag through points, then release on the same Screen; aborts on screen/size change",
    "Example: mcdebug --session dev gui drag --from 100,80 --to 220,80 --steps 8 --step-ticks 1\nPNG points use the latest screenshot transform, frozen at gesture start. Partial actions are not rolled back.",
) {
    private val from by option("--from", metavar = "X,Y", help = "start coordinate").required()
    private val to by option("--to", metavar = "X,Y", help = "end coordinate").required()
    private val space by option("--space", help = "coordinate space").choice("gui", "png").default("gui")
    private val button by option("--button", help = "mouse button 0..2").int().default(0)
    private val steps by option("--steps", help = "drag segments 1..128").int().default(8)
    private val stepTicks by option("--step-ticks", help = "passive natural ticks per segment 0..20 (total <=400)").int().default(1)
    private val noCursor by option("--no-cursor-move", help = "do not update virtual/physical pointer position").flag()
    override fun run() {
        if (button !in 0..2 || steps !in 1..128 || stepTicks !in 0..20 || steps * stepTicks > 400) {
            throw UsageError("button must be 0..2, steps 1..128, step-ticks 0..20 and total ticks <=400")
        }
        val start = pair(from)
        val end = pair(to)
        withGuiApi { api -> printJson(api.gui.drag(start, end, space, button, steps, stepTicks, modifiers(), !noCursor)) }
    }
    private fun pair(value: String): List<Double> {
        val parts = value.split(',')
        val values = parts.map { it.trim().toDoubleOrNull() }
        if (values.size != 2 || values.any { it == null || !it.isFinite() }) throw UsageError("coordinates must be two finite numbers: X,Y")
        return values.map { it!! }
    }
}

class GuiDisconnectCmd : CliktCommand(
    name = "disconnect",
    help = "save/leave the current world and return to title; keep the MC process, resources and client RPC alive",
    epilog = "Example: mcdebug --session dev gui disconnect --wait-ticks 200\nDoes not stop the Session. Loading/connecting screens are rejected; cancellation of those screens is a separate action.",
) {
    private val waitTicks by option("--wait-ticks", help = "passive integrated-server shutdown wait, 1..1200 ticks").int().default(200)
    override fun run() {
        if (waitTicks !in 1..1200) throw UsageError("--wait-ticks must be in 1..1200")
        withGuiApi { api -> printJson(api.gui.disconnect(waitTicks)) }
    }
}
