package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

// ---- gui group ----
//
// 这些命令走**客户端平面**（`gui.*`，真实游戏客户端里的渲染/GUI 线程），
// 与 `screen.*`（服务端假玩家开容器、只有槽位 JSON）是两套东西：
//   screen.*  = 没有像素的服务端模型
//   gui.*     = 真客户端的截图 + 真 Screen 事件派发（无 GLFW、无窗口焦点要求）

/** 客户端平面连接（`--client-socket` / `--client-tcp` / `mcdebug/clientPort` 发现）。 */
fun CliktCommand.withGuiApi(block: (DebugApi) -> Unit) {
    val cli = rootCli()
    withClientPlane(cli) { c -> block(DebugApi(c)) }
}

class GuiCommands : CliktCommand(
    name = "gui",
    help = "drive a REAL game client: screenshots, Screen clicks/keys/text/drag and world disconnect " +
        "(needs a running client; compare is a local PNG regression command and needs no connection)",
    epilog = """
**What it needs**
- a running game client that has the mcdebug jar in its `mods/` directory
- its endpoint: `<gameDir>/mcdebug/clientSocket` (discovery file `mcdebug/clientPort`); TCP 25581 exists but is off by default
- run the CLI with the working directory set to that gameDir, or pass `--client-socket` / `--client-tcp` explicitly

**Two planes, one jar**
- the server plane (`world.*`, `be.*`, `inv.*`, `screen.*`) sets the world up; in single player the same process serves both planes
- the server plane can never render: `gui.*` is the only source of pixels

**Typical loop**
- `mcdebug raw world.setBlock '{"pos":[100,64,100],"block":"minecraft:chest"}'`
- `mcdebug raw inv.setSlot '{"pos":[100,64,100],"slot":"0","item":"minecraft:diamond","count":5}'`
- `mcdebug gui open-block --x 100 --y 64 --z 100`
- `mcdebug gui screenshot --mode gui --annotate` (numbered slots/widgets in `*.annotated.png`)
- `mcdebug gui click --slot 0 --shot gui` (click, settle, screenshot: one round trip)
- `mcdebug gui hover --slot 0` (the tooltips that frame really drew; also custom renderBg tooltips)
- `mcdebug gui problems` (WARN/ERROR log lines and render faults since a cursor)
- `mcdebug gui close`
- client-only Screens need no block: `mcdebug gui open-screen pkg.Fixtures#myScreen`

**Code iteration without restarting the client**
- edit, `./gradlew classes`, then `mcdebug gui hotswap --classes build/classes/kotlin/client` (method bodies; re-runs the open Screen's init)
- new fields/methods/lambdas in the open Screen's class: `mcdebug gui reload-screen --classes build/classes/kotlin/client` (fresh class loader, same menu)
- a render exception no longer crashes the client: the Screen stops drawing, `gui problems`/`screenFault` show the trace; fix, hotswap, `gui reinit`

**Reading the result**
- game state is authoritative: `gui screen`, the click report (`slotAfter`, `carried`, `changed`) or server-plane NBT
- screenshots show the rendered client, not server acknowledgement; lightweight modes use a focus-independent virtual Screen pointer
- `gui key` / `type` / `drag` dispatch Screen events, not desktop keys or global game bindings
- `gui disconnect` leaves/saves the world but keeps the client; `gui compare` checks local PNGs without a connection

Run `mcdebug gui <subcommand> --help` for options, examples and pitfalls of each one.
""".trimIndent(),
) {
    override fun run() = Unit
}

class GuiStatusCmd : CliktCommand(
    name = "status",
    help = "client state: screen, window/gui scale, framebuffer size, endpoint, loaded jar path, contract check",
    epilog = """
**Run this first.** It answers: is a client there, which jar did it load, what is on screen.

**Fields that matter**
- `ready` - the client plane is answering
- `inWorld` / `singleplayer` - a world (and an integrated server) is up
- `screen` / `screenType` - the current Screen; `none` while playing
- `world` / `player` / `pos` / `gamemode`
- `window` - window size, guiScale and GUI-unit size; `framebuffer` - raw pixels, what a screenshot captures
- `windowFocused` - see below
- `jarPath` - the jar the client really loaded; the answer to "my change is not there" is rebuild, copy into mods/, restart the client
- `contractMismatch` - non-empty when the loaded jar disagrees with the CLI method list (stale jar); entries read `missing:` / `undeclared:`
- `problems` - `next` cursor and error count of the WARN/ERROR log (`gui problems`)
- `screenGuard` - `fault` is present while the open Screen's render threw (it is then not drawn; `gui reinit` after fixing)
- `hotswap` - whether the agent is attached and enhanced redefinition is available
- `clientEndpoint` - where this CLI is connected

**Examples**
- `mcdebug gui status`
- `mcdebug gui status --brief`

**Window focus**
- the cursor warp done by `gui click` only changes what you see while the window is active, because Minecraft ignores mouse motion otherwise
- if a screenshot shows the carried stack somewhere unexpected, check `windowFocused`, activate the window (for example `xdotool windowactivate <id>`) and take the shot again
""".trimIndent(),
) {
    private val brief by option("--brief", help = "print a short human summary instead of full JSON").flag()

    override fun run() = withGuiApi { api ->
        val r = api.gui.status()
        if (!brief) {
            printJson(r)
            return@withGuiApi
        }
        val o = r.asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val window = o.getAsJsonObject("window")
        val fb = o.getAsJsonObject("framebuffer")
        println("player:      ${s("player")} @ ${o.get("pos")?.toString() ?: "-"} (${s("gamemode")})")
        println("world:       ${s("world")} server=${s("server")} singleplayer=${o.get("singleplayer")}")
        println("screen:      ${s("screenType")} ${s("screenTitle")} ${s("screen")}".trim())
        println("window:      ${window.get("width")}x${window.get("height")} guiScale=${window.get("guiScale")} gui=${window.get("guiScaledWidth")}x${window.get("guiScaledHeight")}")
        println("framebuffer: ${fb.get("width")}x${fb.get("height")}")
        println("endpoint:    ${s("clientEndpoint")}")
        println("jar:         ${s("jarPath")}")
        println("gameDir:     ${s("gameDir")}")
        val mismatch = o.getAsJsonArray("contractMismatch")
        if (mismatch != null && mismatch.size() > 0) println("CONTRACT MISMATCH: $mismatch")
    }
}

class GuiScreenCmd : CliktCommand(
    name = "screen",
    help = "the OPEN client screen: container slots (GUI-unit coords) + widgets (text/x/y/w/h), or open:false",
    epilog = """
**The structured view** of the open screen: prefer it over guessing from a PNG, and use it to get the coordinates and labels that `gui click` accepts.

**What comes back**
- `container` / `screenType` / `title` - the Screen class and its title
- `gui` - the container rectangle `left/top/imageWidth/imageHeight` in GUI units: exactly the rect that `screenshot --mode gui` crops
- `slots` - index, absolute `gui=(x,y)` of the slot's top-left corner (add 8,8 for its centre), item id and count
- `widgets` - type, text, x/y/width/height, active/visible, EditBox value
- `carried` - the stack on the cursor

**Examples**
- `mcdebug gui screen --brief`
- `mcdebug gui screen`
- `mcdebug gui screen --brief | grep -A 2 "^slots"`

**How it feeds click**
- `--slot N` - the index printed here
- `--text "Label"` - a widget text printed here (case-insensitive substring)
- `--gui X,Y` - any point in the container rect; `slot.gui + (8,8)` is a slot centre

**Pitfalls**
- slot coordinates are absolute GUI units: not container-relative, not pixels
- for pixels use the transform from `gui screenshot`: `pngX = (guiX - cropInGui[0]) * pixelsPerGui`
""".trimIndent(),
) {
    private val brief by option("--brief", help = "list widgets/slots as text lines instead of full JSON").flag()

    override fun run() = withGuiApi { api ->
        val r = api.gui.screen()
        if (!brief) {
            printJson(r)
            return@withGuiApi
        }
        val o = r.asJsonObject
        if (o.get("open")?.asBoolean != true) {
            println("no screen open (playerMenu=${o.get("playerMenu")?.asString ?: ""})")
            return@withGuiApi
        }
        println("${o.get("type")?.asString}: \"${o.get("title")?.asString}\" (${o.get("class")?.asString})")
        o.getAsJsonObject("gui")?.let {
            println("gui: left=${it.get("left")} top=${it.get("top")} size=${it.get("imageWidth")}x${it.get("imageHeight")}")
        }
        o.getAsJsonArray("slots")?.let { slots ->
            println("slots: ${slots.size()}")
            slots.forEach { s ->
                val so = s.asJsonObject
                val item = so.get("item")?.asString ?: ""
                if (item.isNotEmpty() && item != "air") {
                    println("  [${so.get("index")}] gui=(${so.get("guiX")},${so.get("guiY")}) $item x${so.get("count")}")
                }
            }
        }
        o.getAsJsonArray("widgets")?.let { widgets ->
            println("widgets: ${widgets.size()}")
            widgets.forEach { w ->
                val wo = w.asJsonObject
                val text = wo.get("text")?.asString ?: ""
                val value = wo.get("value")?.asString
                fun flag(key: String) = wo.get(key)?.asBoolean == true
                println(
                    "  [${wo.get("index")}] ${wo.get("class")?.asString}: \"$text\"" +
                        (value?.let { " value=\"$it\"" } ?: "") +
                        " at (${wo.get("x")},${wo.get("y")}) ${wo.get("width")}x${wo.get("height")}" +
                        (if (flag("active")) "" else " [inactive]") +
                        (if (wo.get("visible")?.asBoolean == false) " [hidden]" else "") +
                        (if (flag("clipped")) " [clipped]" else "") +
                        (if (flag("focused")) " [focused]" else ""),
                )
            }
        }
    }
}

class GuiScreenshotCmd : CliktCommand(
    name = "screenshot",
    help = "capture the client framebuffer as PNG (--mode gui crops to the open container GUI); " +
        "prints path/size/sha256 plus a transform mapping PNG pixels back to GUI units",
    epilog = """
**Captures** the client framebuffer as a real PNG (the vanilla F2 equivalent) and returns the mapping needed to turn picture coordinates back into clickable ones.

**Modes**
- `--mode window` - whole framebuffer (default)
- `--mode gui` - only the open container's GUI rectangle, so a machine panel fills the image (needs a container screen: `open-block` or a container widget click first)

**What comes back**
- `path` / `file` / `relPath` / `bytes` / `sha256` / `width` / `height`
- `frameId` / `clientTick` identify the newly rendered capture
- `transform`: `framebuffer`, `window`, `guiScale`, `guiScaled`, `pixelsPerGuiXY`, `crop` (framebuffer pixels), `cropInGui` (fractional GUI units), `pngSize`
- `png` - base64, only with `--inline`

**Coordinate math**
- `pngX = guiX * pixelsPerGuiXY[0] - crop[0]` (use axis 1 for Y)
- `guiX = (pngX + crop[0]) / pixelsPerGuiXY[0]`
- `gui click --png X,Y` does this for you, and only accepts pixels from the most recent screenshot of the same client process (otherwise `-32602 NO_SCREENSHOT`)

**Options**
- `--out PATH` - also copy the PNG there
- `--name NAME` - file name inside `<gameDir>/mcdebug/screenshots` (default: timestamped)
- `--delay-ticks N` - passive delay before requesting a fresh frame (default 1; 0 still waits for a new rendered frame)
- `--path-only` - print just the path, for shell pipelines
- `--inline` - embed base64 in the JSON (small images; the socket limit is 64 MiB)
- `--annotate` - also write `*.annotated.png`: numbered cyan boxes = slot indices, magenta boxes = widget indices (`gui click --slot N` / `--widget N`); the legend is in `annotations`
- `--draw-log` - include `drawLog`: every blit (texture, texel UV, sprite), fill, string, item and tooltip of that frame with its GUI-unit rect
- `--hash-only` - no PNG/file, only `pixelHash` + transform: cheap "did the picture change?" polling
- `--hide-cursor` - render the captured frame with the cursor parked off-screen (no hover highlight, tooltip or carried stack; the next frame is normal again): goldens that do not depend on where the last click left the cursor

**Pitfalls**
- readback happens after a new frame has rendered and flushed; this is not an acknowledgement of a previous server operation
- screenshots have a 30-second client-side cap including delay, render wait and encoding
- lightweight modes use a focus-independent virtual GUI pointer; continuous mode keeps the normal physical cursor behavior
""".trimIndent(),
) {
    private val mode by option("--mode", help = "window (whole framebuffer, default) | gui (container GUI rectangle)").default("window")
    private val out by option("--out", help = "also copy the PNG to this path (relative to CWD)")
    private val name by option("--name", help = "file name inside <gameDir>/mcdebug/screenshots (default: timestamped)")
    private val inline by option("--inline", help = "embed the PNG as base64 in the JSON (small images only)").flag()
    private val delayTicks by option("--delay-ticks", help = "passively wait N ticks before requesting a fresh frame (default 1; 0 also captures a new frame)").int()
    private val pathOnly by option("--path-only", help = "print only the PNG path (for shell pipelines)").flag()
    private val annotate by option("--annotate", help = "also write *.annotated.png with numbered slot/widget boxes").flag()
    private val drawLog by option("--draw-log", help = "include the frame's GuiGraphics draw calls (drawLog)").flag()
    private val hashOnly by option("--hash-only", help = "no PNG: only pixelHash + transform (change detection)").flag()
    private val hideCursor by option("--hide-cursor", help = "render this frame with the cursor off-screen: no hover highlight, tooltip or carried stack").flag()

    override fun run() = withGuiApi { api ->
        if (hashOnly && (annotate || inline || out != null || pathOnly)) {
            throw UsageError("--hash-only writes no PNG; it cannot be combined with --annotate/--inline/--out/--path-only")
        }
        val r = api.gui.screenshot(ScreenshotOptions(mode = mode, format = if (hashOnly) "none" else "png", annotate = annotate,
            drawLog = drawLog, inline = inline, name = name, delayTicks = delayTicks, cursor = if (hideCursor) "hide" else null))
        val o = r.asJsonObject
        var printed = o.get("path")?.asString ?: ""
        out?.let { target ->
            val from = Path.of(printed)
            val to = Path.of(target).toAbsolutePath().normalize()
            try {
                to.parent?.let { Files.createDirectories(it) }
                Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                throw UsageError("cannot copy $from to $to: ${e.message}")
            }
            o.addProperty("copiedTo", to.toString())
            // The annotated twin follows the same naming rule next to the copy: x.png -> x.annotated.png.
            o.get("annotatedPath")?.asString?.let { annotated ->
                val twin = to.resolveSibling(to.fileName.toString().removeSuffix(".png") + ".annotated.png")
                try {
                    Files.copy(Path.of(annotated), twin, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    throw UsageError("cannot copy $annotated to $twin: ${e.message}")
                }
                o.addProperty("annotatedCopiedTo", twin.toString())
            }
            printed = to.toString()
        }
        if (pathOnly) {
            println(printed)
        } else {
            printJson(o)
        }
    }
}

class GuiOpenBlockCmd : CliktCommand(
    name = "open-block",
    help = "walk the client's use-on-block path to open a block's GUI (tp the player next to it first; " +
        "sneak=false and an empty hand are the defaults because sneak-click inserts and a held tool acts instead of opening)",
    epilog = """
**Opens a block GUI the way a player does**: the client walks its own use-on-block path (reach, hand, clicked face) and the server answers with the screen. Defaults are chosen to open rather than interact: no sneak, held item untouched.

**Before you call it**
- the player must be in reach (about 4.5 blocks) - position it on the server plane: `mcdebug raw server.runCommand '{"command":"tp Player 100.5 64 -98.5"}'`
- the block must exist: `mcdebug raw world.setBlock '{"pos":[100,64,100],"block":"<mod>:<machine>"}'`
- some machines only open from a given side - that is what `--face` is for

**Options**
- `--x --y --z` - block position (required)
- `--face FACE` - `north|south|east|west|up|down` (default up): the clicked face
- `--dim DIM` - expected dimension; fails fast instead of clicking air
- `--hand HAND` - `main_hand` (default) or `off_hand`
- `--sneak` - sneak-click; most machines then insert/extract instead of opening
- `--clear-hand` - use an empty hotbar slot for the click, then switch back (wrenches and other tools act instead of opening)
- `--wait-ticks N` - how long to wait for the screen to change (default 40)

**What comes back**
- `changed`, `result` (SUCCESS/PASS/FAIL), `screenBefore` -> `screenAfter`, `screenType`, `slotCount` and the attempt detail, so a PASS tells you why nothing opened

**Pitfalls**
- `result PASS` usually means the block has no GUI, or the item in hand intercepted the click - retry with `--clear-hand`
- confirm the outcome with `gui screen`
""".trimIndent(),
) {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val face by option("--face", help = "north|south|east|west|up|down (default up)").default("up")
    private val dim by option("--dim", help = "expected dimension; fails fast on mismatch")
    private val hand by option("--hand", help = "main_hand (default) | off_hand").default("main_hand")
    private val sneak by option("--sneak", help = "sneak while clicking (most machines then insert/extract instead of opening)").flag()
    private val clearHand by option("--clear-hand", help = "switch to an empty hotbar slot for the click, then switch back").flag()
    private val waitTicks by option("--wait-ticks", help = "how long to wait for a screen/menu change (default 40)").int()

    override fun run() = withGuiApi { api ->
        printJson(
            api.gui.openBlock(
                pos = listOf(x, y, z),
                face = face,
                dim = dim,
                hand = hand,
                sneak = sneak,
                clearHand = clearHand,
                waitTicks = waitTicks,
            ),
        )
    }
}

class GuiClickCmd : CliktCommand(
    name = "click",
    help = "click in the open screen: --slot N (container slot) | --text \"Label\" | --gui X,Y | --png X,Y " +
        "(PNG pixels from the previous screenshot) | --x/--y (GUI units); " +
        "slot clicks go through handleInventoryMouseClick, other targets through Screen mouse events",
    epilog = """
**Clicks in the open screen.** Five ways to say where, all positional: no GLFW input synthesis and no window focus needed. Slot clicks go through the vanilla inventory-click path (the server still decides the outcome); other targets are dispatched as ordinary Screen mouse events.

**Where (pick one)**
- `--slot N` - container slot index from `gui screen` (cyan numbers in `screenshot --annotate`)
- `--widget N` - widget index from `gui screen` (magenta numbers in `screenshot --annotate`)
- `--text "Label"` - widget whose text contains the label (case-insensitive); `--index K` picks the K-th match (default 0)
- `--gui X,Y` - GUI-unit coordinates: canvas widgets, drag handles
- `--png X,Y` - PNG pixels from the most recent `gui screenshot` (converted for you)
- `--x N --y N` - GUI-unit coordinates as separate flags

**How (optional)**
- `--button 0|1` - 0 = left (default), 1 = right
- `--click-type TYPE` - `pickup|quick_move|swap|clone|throw|quick_craft|pickup_all` (slot clicks; default pickup)
- `--shift` - shorthand for `--click-type quick_move`
- `--wait-ticks N` - upper bound for settling (default 20): the click returns as soon as the server answered and the GUI stayed quiet for 2 ticks (`settle` in the result)
- `--no-settle` - wait exactly `--wait-ticks` instead (the previous fixed-wait behaviour)
- `--no-cursor-move` - do not warp the real cursor to the click point
- `--shot MODE` - `gui|window`: one round trip that also returns a screenshot (`gui.act`); add `--annotate` / `--draw-log` / `--screen`

**What comes back**
- `ok`, `mode`, `guiX`/`guiY` (the landing point; slot clicks report the slot centre), `before`, `slotAfter`, `carried`, `changed`, `pressed`/`released`, `screen`, `cursorMoved` - enough to assert the click worked without looking at pixels

**Examples**
- `mcdebug gui click --slot 0`
- `mcdebug gui click --slot 3 --shift`
- `mcdebug gui click --text "Start"`
- `mcdebug gui click --png 384,150`
- `mcdebug gui click --slot 2 --click-type throw --button 1`
- `mcdebug gui click --widget 3 --shot gui --annotate`

**The cursor warp**
- the carried stack, tooltips and hover highlight are drawn at the physical cursor, so by default the cursor is warped to the click point and screenshots show the result where you clicked
- Minecraft only follows that while its window is focused (`gui status` -> `windowFocused`); the game state changes either way
""".trimIndent(),
) {
    private val slot by option("--slot", help = "container slot index (from gui screen)").int()
    private val widget by option("--widget", help = "widget index (from gui screen / annotated screenshot)").int()
    private val text by option("--text", help = "widget label, case-insensitive substring")
    private val index by option("--index", help = "which match to use when --text matches several (default 0)").int()
    private val gui by option("--gui", help = "GUI-unit coordinates \"x,y\"")
    private val png by option("--png", help = "PNG-pixel coordinates \"x,y\" from the previous gui screenshot")
    private val x by option("--x").int()
    private val y by option("--y").int()
    private val button by option("--button", help = "0 = left (default), 1 = right").int().default(0)
    private val clickType by option("--click-type", help = "pickup|quick_move|swap|clone|throw|quick_craft|pickup_all (slot clicks only)")
    private val shift by option("--shift", help = "shift-click = quick_move (slot clicks only)").flag()
    private val waitTicks by option("--wait-ticks", help = "upper bound in client ticks for settling (default 20)").int()
    private val noSettle by option("--no-settle", help = "wait exactly --wait-ticks instead of returning once settled").flag()
    private val noCursorMove by option(
        "--no-cursor-move",
        help = "do not warp the real cursor to the click point (warping keeps screenshots honest: " +
            "the carried stack and tooltips are drawn at the physical cursor)",
    ).flag()

    private val follow by FollowUpOptions()

    override fun run() {
        follow.validate()
        val target: Map<String, Any?> = when {
            slot != null -> mapOf("slot" to slot, "clickType" to if (shift) "quick_move" else clickType)
            widget != null -> mapOf("widget" to widget)
            text != null -> mapOf("text" to text, "index" to index)
            gui != null -> mapOf("gui" to parsePair(gui!!, "--gui").toList())
            png != null -> mapOf("png" to parsePair(png!!, "--png").toList())
            x != null && y != null -> mapOf("gui" to listOf(x!!.toDouble(), y!!.toDouble()))
            else -> throw UsageError("one of --slot / --widget / --text / --gui / --png / --x+--y is required")
        }
        val params = target + mapOf("button" to button, "waitTicks" to waitTicks, "moveCursor" to !noCursorMove,
            "settle" to if (noSettle) false else null)
        withGuiApi { api ->
            printJson(follow.run(api, "click", params) ?: api.rpc.call("gui.click", gson.toJsonTree(params.filterValues { it != null })))
        }
    }
}

/** `--shot/--annotate/--draw-log/--screen`: turn an input command into one `gui.act` round trip. */
class FollowUpOptions : com.github.ajalt.clikt.parameters.groups.OptionGroup("Follow-up (one round trip via gui.act)") {
    val shot by option("--shot", metavar = "MODE", help = "after the action settles, return a screenshot: gui | window")
        .choice("gui", "window")
    val annotate by option("--annotate", help = "with --shot: also write *.annotated.png (numbered slots/widgets)").flag()
    val drawLog by option("--draw-log", help = "with --shot: include the frame's draw calls").flag()
    val screen by option("--screen", help = "also return the gui screen JSON after the action").flag()
    val hideCursor by option("--hide-cursor", help = "with --shot: render the shot without hover/tooltip/carried stack").flag()

    /** Call before connecting, so a usage error never opens an RPC connection. */
    fun validate() {
        if (shot == null && (annotate || drawLog || hideCursor)) throw UsageError("--annotate/--draw-log/--hide-cursor need --shot")
    }

    /** Null when no follow-up was requested: the caller then uses the plain method. */
    fun run(api: DebugApi, action: String, params: Map<String, Any?>): com.google.gson.JsonElement? {
        validate()
        if (shot == null && !screen) return null
        val options = shot?.let { ScreenshotOptions(mode = it, annotate = annotate, drawLog = drawLog, cursor = if (hideCursor) "hide" else null) }
        return api.gui.act(action, params, screen = screen, screenshot = options)
    }
}

private fun parsePair(spec: String, what: String): Pair<Double, Double> {
    val parts = spec.split(",").map { it.trim() }
    if (parts.size != 2) throw UsageError("$what must be \"x,y\", got: $spec")
    val a = parts[0].toDoubleOrNull() ?: throw UsageError("$what x is not a number: ${parts[0]}")
    val b = parts[1].toDoubleOrNull() ?: throw UsageError("$what y is not a number: ${parts[1]}")
    return Pair(a, b)
}

class GuiCloseCmd : CliktCommand(
    name = "close",
    help = "close the open screen (Esc equivalent) and wait until it is gone",
    epilog = """
**Closes the open screen** (the Esc equivalent) and waits until it is really gone, so the next command starts from a known state. Use it between test cases and before `gui connect`.

**Examples**
- `mcdebug gui close`
- `mcdebug gui close --wait-ticks 60` - more patient wait for slow screens

**Note**
- closing returns a cursor stack to the container exactly like Esc does; check with `gui screen` if that matters for your assertions
""".trimIndent(),
) {
    private val waitTicks by option("--wait-ticks", help = "wait N client ticks for the screen to disappear (default 20)").int()

    override fun run() = withGuiApi { api -> printJson(api.gui.close(waitTicks)) }
}

class GuiConnectCmd : CliktCommand(
    name = "connect",
    help = "make the client join a server (host[:port]); from the title screen or any non-world screen",
    epilog = """
**Makes the client join a server** through the vanilla connect screen, so the target needs no startup argument of its own.

**When it works**
- from the title screen or any other non-world screen
- it cannot be used while a world is open: `gui disconnect` first

**Examples**
- `mcdebug gui connect --host 127.0.0.1:25565`
- `mcdebug gui connect --host play.example.net --name "test server"`
- `mcdebug gui connect --host play.example.net --wait-ticks 200`

**What to expect**
- the normal "Connecting..." screen; once in, `gui status` shows `singleplayer=false` and `server=<host:port>`, and `screen` goes back to none
- the server needs no mcdebug - only the client does
""".trimIndent(),
) {
    private val host by option("--host", help = "host or host:port").required()
    private val name by option("--name", help = "display name in the server list (default: the host)")
    private val waitTicks by option("--wait-ticks", help = "wait up to N ticks for the player to appear (default 0 = do not wait)").int()

    override fun run() = withGuiApi { api -> printJson(api.gui.connect(host, name, waitTicks)) }
}

class GuiReloadResourcesCmd : CliktCommand(
    name = "reload-resources",
    help = "client-side F3+T: reload resource packs (textures/lang/models) without restarting the client — " +
        "use this after changing assets; for Java/Kotlin changes use gui hotswap",
    epilog = """
**Client-side F3+T**: reloads resource packs - textures, models, language files, sounds - without restarting the client. The fast path while iterating on assets.

**Example**
- `mcdebug gui reload-resources`

**Limits**
- Java/Kotlin changes are not resources: use `gui hotswap` (method bodies) or restart the client
- the reload blocks the client thread for a few seconds, so `gui.*` calls made right after it returns can be slow
""".trimIndent(),
) {
    override fun run() = withGuiApi { api -> printJson(api.gui.reloadResources()) }
}

fun guiSubcommands() = listOf(
    GuiStatusCmd(), GuiScreenCmd(), GuiScreenshotCmd(), GuiOpenBlockCmd(),
    GuiClickCmd(), GuiCloseCmd(), GuiConnectCmd(), GuiReloadResourcesCmd(),
    GuiKeyCmd(), GuiTypeCmd(), GuiDragCmd(), GuiDisconnectCmd(), GuiCompareCmd(),
    GuiActCmd(), GuiSettleCmd(), GuiReinitCmd(), GuiProblemsCmd(), GuiHotswapCmd(),
    GuiHoverCmd(), GuiScrollCmd(), GuiOpenScreenCmd(), GuiReloadScreenCmd(),
)

/** 供其他命令复用：把 `{"a":1}` 结果按键取字符串。 */
internal fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
