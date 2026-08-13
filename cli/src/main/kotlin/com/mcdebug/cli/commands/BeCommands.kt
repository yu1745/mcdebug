package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class BeCommands : CliktCommand(name = "be", help = "block entity NBT and field operations") {
    override fun run() = Unit
}

class BeGetNbtCmd : CliktCommand(name = "get-nbt", help = "read block entity NBT") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.be.getNbt(listOf(x, y, z), dim))
    }
}

class BeSetNbtCmd : CliktCommand(name = "set-nbt", help = "write block entity NBT; non-ASCII text (中文/emoji/\\u escapes) round-trips correctly since 0.6.0 (UTF-8 fix) — server < 0.6.0 still garbles non-ASCII responses") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val nbt by option("--nbt", help = "NBT JSON or @file").required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.be.setNbt(listOf(x, y, z), parseJsonArg(nbt), dim))
    }
}

class BeGetFieldCmd : CliktCommand(name = "get-field", help = "read one NBT path inside a block entity") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val path by option("--path").required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.be.getField(listOf(x, y, z), path, dim))
    }
}

class BeSetFieldCmd : CliktCommand(name = "set-field", help = "write one NBT path inside a block entity; WARNING: if the JSON value type does not match the field's NBT type, the response is still ok:true but the field is silently NOT written") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val path by option("--path").required()
    private val value by option("--value", help = "value JSON or @file").required()
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.be.setField(listOf(x, y, z), path, parseJsonArg(value), dim))
    }
}

class BeTickCmd : CliktCommand(name = "tick", help = "tick the block entity N times (same path as natural ticks); requires server-side support: added in mcdebug mod >= 0.5.0 (0.4.x servers return method not found)") {
    private val x by option("--x").int().required()
    private val y by option("--y").int().required()
    private val z by option("--z").int().required()
    private val ticks by option("--ticks").int().default(1)
    private val dim by option("--dim")

    override fun run() = withApi { api ->
        printJson(api.be.tick(listOf(x, y, z), ticks, dim))
    }
}

fun beSubcommands() = listOf(BeGetNbtCmd(), BeSetNbtCmd(), BeGetFieldCmd(), BeSetFieldCmd(), BeTickCmd())
