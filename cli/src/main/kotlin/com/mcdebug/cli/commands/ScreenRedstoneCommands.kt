package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

// ---- screen group ----

class ScreenCommands : CliktCommand(name = "screen", help = "open and drive real server ScreenHandlers") {
    override fun run() = Unit
}

class ScreenOpenBlockCmd : CliktCommand(name = "open-block", help = "open the screen of a block with a fake player") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")
    private val side by option("--side")

    override fun run() = withApi { api ->
        printJson(api.screen.openBlock(listOf(x, y, z), dim, player = "fake", side = side))
    }
}

class ScreenSnapshotCmd : CliktCommand(name = "snapshot", help = "read a screen snapshot") {
    private val screenId by option("--screen-id").required()

    override fun run() = withApi { api ->
        printJson(api.screen.snapshot(screenId))
    }
}

class ScreenSetPlayerSlotCmd : CliktCommand(name = "set-player-slot", help = "write a PLAYER INVENTORY slot of the fake player (--slot 0-35), NOT the cursor stack (the cursor is a separate screen field)") {
    private val screenId by option("--screen-id").required()
    private val slot by option("--slot", help = "player inventory slot 0-35 (not the cursor stack)").int().required()
    private val stack by option("--stack", help = "ItemStack JSON: {\"item\":\"...\",\"count\":N}").required()

    override fun run() = withApi { api ->
        printJson(api.screen.setPlayerSlot(screenId, slot, parseJsonArg(stack)))
    }
}

class ScreenClickSlotCmd : CliktCommand(name = "click-slot", help = "click a slot (actionType: pickup|quick_move|swap|clone|throw|quick_craft|pickup_all)") {
    private val screenId by option("--screen-id").required()
    private val slot by option("--slot").int().required()
    private val button by option("--button").int().default(0)
    private val actionType by option("--action-type").default("pickup")

    override fun run() = withApi { api ->
        printJson(api.screen.clickSlot(screenId, slot, button, actionType))
    }
}

class ScreenBatchCmd : CliktCommand(name = "batch", help = "run many screen operations in ONE call: --steps is a JSON array of {op,...} (open|setPlayerSlot|setSlot|clearPlayerSlots|clickSlot|clickButton|quickMove|snapshot|close, each with the parameters of that single command; open takes x,y,z/dim/side as pos), later steps use the screen opened by the last open; prints a compact result per step (snap:true adds the slots); stops at the first failing step") {
    private val steps by option("--steps", help = "JSON array of steps, or @file").required()
    private val screenId by option("--screen-id", help = "screen to use before any open step")
    private val closeAtEnd by option("--close-at-end", help = "close the current screen when done, also after a failure").flag()

    override fun run() = withApi { api ->
        printJson(api.screen.batch(parseJsonArg(steps), screenId, closeAtEnd))
    }
}

class ScreenQuickMoveCmd : CliktCommand(name = "quick-move", help = "shift-click a slot") {
    private val screenId by option("--screen-id").required()
    private val slot by option("--slot").int().required()

    override fun run() = withApi { api ->
        printJson(api.screen.quickMove(screenId, slot))
    }
}

class ScreenCloseCmd : CliktCommand(name = "close", help = "close a screen") {
    private val screenId by option("--screen-id").required()

    override fun run() = withApi { api ->
        printJson(api.screen.close(screenId))
    }
}

// ---- redstone group ----

class RedstoneCommands : CliktCommand(name = "redstone", help = "redstone power read / lever control / neighbor updates") {
    override fun run() = Unit
}

class RedstoneGetPowerCmd : CliktCommand(name = "get-power", help = "read redstone power at a position") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val side by option("--side")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.redstone.getPower(listOf(x, y, z), side, dim))
    }
}

class RedstoneIsPoweredCmd : CliktCommand(name = "is-powered", help = "is the block powered BY NEIGHBORS? (true when an adjacent block/redstone powers it; the block's own powered state — e.g. a switched-on lever itself — does NOT report true)") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.redstone.isPowered(listOf(x, y, z), dim))
    }
}

class RedstoneSetLeverCmd : CliktCommand(name = "set-lever", help = "set a lever on/off") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val powered by option("--powered", help = "--powered = on, --unpowered (or neither) = off").flag("--unpowered")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.redstone.setLever(listOf(x, y, z), powered, dim))
    }
}

class RedstonePulseCmd : CliktCommand(name = "pulse", help = "pulse a lever for N ticks") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val ticks by option("--ticks").int().default(2)
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.redstone.pulse(listOf(x, y, z), ticks, dim))
    }
}

class RedstoneNotifyNeighborsCmd : CliktCommand(name = "notify-neighbors", help = "trigger neighbor updates at a position") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.redstone.notifyNeighbors(listOf(x, y, z), dim))
    }
}

fun screenSubcommands() = listOf(
    ScreenOpenBlockCmd(), ScreenSnapshotCmd(), ScreenSetPlayerSlotCmd(),
    ScreenClickSlotCmd(), ScreenQuickMoveCmd(), ScreenBatchCmd(), ScreenCloseCmd(),
)

fun redstoneSubcommands() = listOf(
    RedstoneGetPowerCmd(), RedstoneIsPoweredCmd(), RedstoneSetLeverCmd(), RedstonePulseCmd(), RedstoneNotifyNeighborsCmd(),
)
