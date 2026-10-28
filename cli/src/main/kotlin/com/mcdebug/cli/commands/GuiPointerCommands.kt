package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int

/** `--slot | --widget | --text [--index] | --gui X,Y | --png X,Y`, shared by hover and scroll. */
class TargetOptions : OptionGroup("Target (pick one)") {
    val slot by option("--slot", help = "container slot index (cyan numbers in screenshot --annotate)").int()
    val widget by option("--widget", help = "widget index (magenta numbers in screenshot --annotate)").int()
    val text by option("--text", help = "widget label, case-insensitive substring")
    val index by option("--index", help = "which --text match (default 0)").int()
    val gui by option("--gui", help = "GUI-unit coordinates \"x,y\"")
    val png by option("--png", help = "PNG pixels \"x,y\" from the previous gui screenshot")

    /** Validates and builds the params map; call before connecting. */
    fun params(): Map<String, Any?> {
        val given = listOfNotNull(slot, widget, text, gui, png).size
        if (given != 1) throw UsageError("exactly one of --slot / --widget / --text / --gui / --png is required")
        return when {
            slot != null -> mapOf("slot" to slot)
            widget != null -> mapOf("widget" to widget)
            text != null -> mapOf("text" to text, "index" to index)
            gui != null -> mapOf("gui" to pair(gui!!, "--gui"))
            else -> mapOf("png" to pair(png!!, "--png"))
        }
    }

    private fun pair(spec: String, what: String): List<Double> {
        val parts = spec.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) throw UsageError("$what must be \"x,y\", got: $spec")
        return parts.map { it!! }
    }
}

class GuiHoverCmd : CliktCommand(
    name = "hover",
    help = "move the cursor onto a slot/widget/point, render one frame and report the tooltips actually drawn",
    epilog = """
**Hover without clicking** (`gui.hover`): the cursor moves (virtual in lite render modes, warped in continuous mode), one fresh frame is rendered, and the result lists what that frame really drew.

**What comes back**
- `tooltips` - every tooltip drawn in that frame, as lines of text: item tooltips *and* custom ones a Screen draws from `renderBg`/`render` (energy bars, tank levels, progress hints)
- `hoveredSlot` / `hoveredTooltip` - the container slot under the cursor and its item tooltip
- `hoveredWidget` - the hovered widget as in `gui screen`
- `guiX`/`guiY`, `cursorMoved` (false = hover state still reflects the old cursor position)

**Examples**
- `mcdebug gui hover --slot 0`
- `mcdebug gui hover --gui 160,30` - over an energy bar drawn in renderBg
- `mcdebug gui hover --widget 2 --shot gui` - plus a screenshot (via `gui.act`)

**Note**
- the cursor stays there: later screenshots show the hover until something moves it; use `gui screenshot --hide-cursor` for a hover-free picture
""".trimIndent(),
) {
    private val target by TargetOptions()
    private val follow by FollowUpOptions()

    override fun run() {
        val params = target.params()
        follow.validate()
        withGuiApi { api -> printJson(follow.run(api, "hover", params) ?: api.gui.hover(params)) }
    }
}

class GuiScrollCmd : CliktCommand(
    name = "scroll",
    help = "mouse wheel at a slot/widget/point (positive --dy = wheel up), then settle",
    epilog = """
**Scrolls** (`gui.scroll`): `Screen.mouseScrolled` at the target, the same event a real wheel produces; then waits until the GUI settled (like a click).

**Options**
- `--dy N` - wheel steps, positive = up (required); `--dx N` - horizontal (default 0)
- `--wait-ticks N` - settle upper bound (default 20); `--no-settle` - do not wait
- `--no-cursor-move` - scroll at the point without moving the cursor there

**What comes back**
- `handled` - whether any listener consumed the event (false = nothing scrollable there), `guiX`/`guiY`, `settle`, `screen`

**Examples**
- `mcdebug gui scroll --gui 100,80 --dy -3` - three steps down in a list
- `mcdebug gui scroll --widget 4 --dy 1 --shot gui`
""".trimIndent(),
) {
    private val target by TargetOptions()
    private val dy by option("--dy", help = "wheel steps, positive = up").double()
    private val dx by option("--dx", help = "horizontal wheel steps").double()
    private val waitTicks by option("--wait-ticks", help = "settle upper bound in client ticks (default 20)").int()
    private val noSettle by option("--no-settle", help = "return without waiting for the GUI to settle").flag()
    private val noCursorMove by option("--no-cursor-move", help = "do not move the cursor to the scroll point").flag()
    private val follow by FollowUpOptions()

    override fun run() {
        val amount = dy ?: throw UsageError("--dy is required")
        val params = target.params() + mapOf("dy" to amount, "dx" to dx, "waitTicks" to waitTicks,
            "settle" to if (noSettle) false else null, "moveCursor" to if (noCursorMove) false else null)
        follow.validate()
        withGuiApi { api ->
            printJson(follow.run(api, "scroll", params) ?: api.rpc.call("gui.scroll", gson.toJsonTree(params.filterValues { it != null })))
        }
    }
}

class GuiOpenScreenCmd : CliktCommand(
    name = "open-screen",
    help = "open a Screen by class: inventory | pkg.Class (public constructor) | pkg.Class#staticFactory",
    epilog = """
**Opens any client Screen** (`gui.openScreen`) - a Screen fixture without placing a block. Iterate on a config screen, a guide book or your own Screen directly.

**Forms**
- `inventory` - the player inventory (creative mode hands over to the creative inventory, as vanilla does)
- `pkg.Class` - a Screen whose public constructor takes only `Screen` (the parent: the currently open screen), `Player`, `Minecraft` and/or `Options`
- `pkg.Class#method` - a public static factory with such parameters that returns a Screen: put dev-only fixtures (a Screen filled with test data) in your mod's dev source set

**Notes**
- runtime class names: Mojang names in a Loom dev client with official mappings
- Kotlin: a factory in an `object` needs `@JvmStatic`; a top-level function lives in class `<File>Kt`
- container Screens need a server-side menu: open those with `gui open-block` (or the in-game action), not here
- a constructor/`init()` exception is returned with its stack trace (`data.trace`) and the half-built screen is closed

**Examples**
- `mcdebug gui open-screen inventory`
- `mcdebug gui open-screen net.minecraft.client.gui.screens.options.OptionsScreen`
- `mcdebug gui open-screen com.example.dev.Fixtures#machineScreen --shot gui --annotate`
""".trimIndent(),
) {
    private val screen by argument(help = "inventory | pkg.Class | pkg.Class#staticFactory")
    private val follow by FollowUpOptions()

    override fun run() {
        follow.validate()
        val params = mapOf("screen" to screen)
        withGuiApi { api -> printJson(follow.run(api, "openScreen", params) ?: api.gui.openScreen(screen)) }
    }
}
