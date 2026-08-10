package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class InvCommands : CliktCommand(name = "inv", help = "vanilla inventory operations on a block entity") {
    override fun run() = Unit
}

class InvGetSizeCmd : CliktCommand(name = "get-size", help = "inventory slot count") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.inv.getSize(listOf(x, y, z), dim))
    }
}

class InvGetSlotCmd : CliktCommand(name = "get-slot", help = "read one slot") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val slot by option("--slot").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.inv.getSlot(listOf(x, y, z), slot, dim))
    }
}

class InvSetSlotCmd : CliktCommand(name = "set-slot", help = "write one slot (item=null to clear)") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val slot by option("--slot").int().required()
    private val item by option("--item", help = "item id, or 'null' to clear")
    private val count by option("--count").int().default(1)
    private val nbt by option("--nbt")
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.inv.setSlot(listOf(x, y, z), slot, item?.takeIf { it != "null" }, count, nbt?.let { parseJsonArg(it) }, dim))
    }
}

class InvInsertCmd : CliktCommand(name = "insert", help = "insert items into a machine inventory") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val item by option("--item").required()
    private val count by option("--count").int().required()
    private val slot by option("--slot").int()
    private val nbt by option("--nbt")
    private val simulate by option("--simulate").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.inv.insert(listOf(x, y, z), item, count, slot, nbt?.let { parseJsonArg(it) }, simulate, dim))
    }
}

class InvExtractCmd : CliktCommand(name = "extract", help = "extract items from a machine inventory") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val item by option("--item").required()
    private val count by option("--count").int().required()
    private val slot by option("--slot").int()
    private val simulate by option("--simulate").flag()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.inv.extract(listOf(x, y, z), item, count, slot, simulate, dim))
    }
}

fun invSubcommands() = listOf(InvGetSizeCmd(), InvGetSlotCmd(), InvSetSlotCmd(), InvInsertCmd(), InvExtractCmd())
