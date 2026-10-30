package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.options.required

// ---- reflect group ----

class ReflectCommands : CliktCommand(
    name = "reflect",
    help = "arbitrary runtime reflection on the server plane. Start from --root (server|level|be|entity|player), " +
        "--ref N or --class (statics), then walk --path: dot segments, `name` = field, else no-arg method, else " +
        "getName/isName; `name()` = method; `[3]` index; `[\"key\"]` map key; no arguments or expressions. " +
        "Mojang and intermediary names both work. Objects come back as {\"ref\":N}; refs are kept " +
        "(LRU, max 1024) across commands until released or the server stops",
) {
    override fun run() = Unit
}

/** Shared base + path options: the object a reflect command works on. */
class ReflectTarget : OptionGroup("Target (one of --root/--ref/--class, then --path)") {
    val root by option("--root", help = "server | level | be | entity | player (server plane); minecraft | screen | player | level | server (--client)")
    val ref by option("--ref", help = "object ref from an earlier result").long()
    val className by option("--class", help = "class for static members (Mojang, intermediary or mod name)")
    val path by option("--path", help = "e.g. 'overworld.dayTime' or 'items[0]'")
    val pos by option("--pos", help = "x,y,z for --root be")
    val dim by option("--dim", help = "dimension for --root level/be (default overworld)")
    val uuid by option("--uuid", help = "entity uuid for --root entity")
    val name by option("--name", help = "player name for --root player (default: first player)")

    /** `--client` sends the call to `gui.reflect.*` (same options, client JVM, client RPC thread). */
    val client by option("--client", help = "run on the client plane (roots: minecraft|screen|player|level|server)").flag()

    fun params(): LinkedHashMap<String, Any?> {
        val bases = listOfNotNull(root, ref, className)
        if (bases.size != 1) throw UsageError("give exactly one of --root, --ref, --class")
        val out = LinkedHashMap<String, Any?>()
        root?.let { out["root"] = it }
        ref?.let { out["ref"] = it }
        className?.let { out["class"] = it }
        pos?.let { p ->
            val xyz = p.split(",").map { it.trim().toIntOrNull() ?: throw UsageError("--pos must be x,y,z") }
            if (xyz.size != 3) throw UsageError("--pos must be x,y,z")
            out["pos"] = xyz
        }
        dim?.let { out["dim"] = it }
        uuid?.let { out["uuid"] = it }
        name?.let { out["name"] = it }
        path?.let { out["path"] = it }
        return out
    }
}

/** `reflect.*` (server plane) or `gui.reflect.*` (--client, same core in the client JVM). */
private fun CliktCommand.reflectCall(target: ReflectTarget, suffix: String, obj: MutableMap<String, Any?>) {
    val method = if (target.client) "gui.reflect.$suffix" else "reflect.$suffix"
    withPlaneFor(rootCli(), method) { c -> printJson(c.call(method, gson.toJsonTree(obj))) }
}

class ReflectResolveCmd : CliktCommand(name = "resolve", help = "class of the target: runtime/Mojang name, superclass, fields, methods (overloads listed separately)") {
    private val target by ReflectTarget()
    private val members by option("--members").flag()

    override fun run() {
        val obj = target.params()
        obj["members"] = members
        reflectCall(target, "resolve", obj)
    }
}

class ReflectGetCmd : CliktCommand(name = "get", help = "read the value at --path (and/or --field); e.g. reflect get --root server --path tickCount") {
    private val target by ReflectTarget()
    private val field by option("--field", help = "last segment, kept for compatibility: same as appending .field to --path")

    override fun run() {
        val obj = target.params()
        field?.let { obj["field"] = it }
        reflectCall(target, "get", obj)
    }
}

class ReflectSetCmd : CliktCommand(name = "set", help = "write a non-final field of the target; numbers take the field's type (range-checked), enum fields take the constant name; prints before/after") {
    private val target by ReflectTarget()
    private val field by option("--field").required()
    private val value by option("--value", help = "JSON value: 5, true, \"text\", {\"ref\":N}, or {\"enum\":..,\"name\":..}").required()

    override fun run() {
        val obj = target.params()
        obj["field"] = field
        obj["value"] = parseJsonArg(value)
        reflectCall(target, "set", obj)
    }
}

class ReflectCallCmd : CliktCommand(name = "call", help = "invoke a method on the target; the best-fitting overload is chosen, ties need --arg-types") {
    private val target by ReflectTarget()
    private val method by option("--method").required()
    private val args by option("--args", help = "args array JSON or @file; an arg may be {\"ref\":N} or {\"root\":..,\"path\":..}")
    private val argTypes by option("--arg-types", help = "comma-separated parameter types to pick an overload, e.g. int,long")

    override fun run() {
        val obj = target.params()
        obj["method"] = method
        obj["args"] = args?.let { parseJsonArg(it) }
        argTypes?.let { t -> obj["argTypes"] = t.split(",").map(String::trim) }
        reflectCall(target, "call", obj)
    }
}

class ReflectNewCmd : CliktCommand(name = "new", help = "construct an object: --class --args JSON") {
    private val className by option("--class").required()
    private val args by option("--args", help = "constructor args array JSON or @file")
    private val argTypes by option("--arg-types", help = "comma-separated parameter types to pick a constructor")

    private val client by option("--client", help = "client plane (gui.reflect)").flag()

    override fun run() {
        val method = if (client) "gui.reflect.new" else "reflect.new"
        val obj = linkedMapOf<String, Any?>("class" to className, "args" to args?.let { parseJsonArg(it) })
        argTypes?.let { t -> obj["argTypes"] = t.split(",").map(String::trim) }
        withPlaneFor(rootCli(), method) { c -> printJson(c.call(method, gson.toJsonTree(obj))) }
    }
}

class ReflectRefsCmd : CliktCommand(name = "refs", help = "list live object references") {
    private val client by option("--client", help = "client plane (gui.reflect)").flag()

    override fun run() {
        val method = if (client) "gui.reflect.refs" else "reflect.refs"
        withPlaneFor(rootCli(), method) { c -> printJson(c.call(method, null)) }
    }
}

class ReflectReleaseCmd : CliktCommand(name = "release", help = "release an object reference (--all: every ref)") {
    private val ref by option("--ref").long()
    private val all by option("--all").flag()

    private val client by option("--client", help = "client plane (gui.reflect)").flag()

    override fun run() {
        if ((ref == null) == !all) throw UsageError("give --ref N or --all")
        val method = if (client) "gui.reflect.release" else "reflect.release"
        val obj = if (all) mapOf<String, Any?>("all" to true) else mapOf<String, Any?>("ref" to ref)
        withPlaneFor(rootCli(), method) { c -> printJson(c.call(method, gson.toJsonTree(obj))) }
    }
}

class ReflectMappingsCmd : CliktCommand(name = "mappings", help = "whether the mapping table is loaded (resolve shows the names of a class)") {
    private val className by option("--class")
    private val methodName by option("--method")

    override fun run() = withApi { api ->
        printJson(api.rpc.call("reflect.mappings", gson.toJsonTree(mapOf<String, Any?>("class" to className, "method" to methodName))))
    }
}

fun reflectSubcommands() = listOf(
    ReflectResolveCmd(), ReflectGetCmd(), ReflectSetCmd(), ReflectCallCmd(), ReflectNewCmd(),
    ReflectRefsCmd(), ReflectReleaseCmd(), ReflectMappingsCmd(),
)
