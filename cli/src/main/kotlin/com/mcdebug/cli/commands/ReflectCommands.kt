package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.options.required

// ---- reflect group ----

class ReflectCommands : CliktCommand(name = "reflect", help = "arbitrary runtime reflection (resolve/get/call/new/refs/release/mappings)") {
    override fun run() = Unit
}

class ReflectResolveCmd : CliktCommand(name = "resolve", help = "resolve a class: runtime/yarn name, superclass, fields, methods") {
    private val className by option("--class").required()
    private val members by option("--members").flag()

    override fun run() = withApi { api ->
        printJson(api.rpc.call("reflect.resolve", gson.toJsonTree(mapOf("class" to className, "members" to members))))
    }
}

class ReflectGetCmd : CliktCommand(name = "get", help = "read a field: --class --field, or --ref --field") {
    private val className by option("--class")
    private val ref by option("--ref").int()
    private val field by option("--field").required()

    override fun run() = withApi { api ->
        val obj = LinkedHashMap<String, Any?>()
        if (className != null) obj["class"] = className else obj["ref"] = ref
        obj["field"] = field
        printJson(api.rpc.call("reflect.get", gson.toJsonTree(obj)))
    }
}

class ReflectCallCmd : CliktCommand(name = "call", help = "invoke a method: --class --method --args JSON, or --ref --method") {
    private val className by option("--class")
    private val ref by option("--ref").int()
    private val method by option("--method").required()
    private val args by option("--args", help = "args array JSON or @file")

    override fun run() = withApi { api ->
        val obj = LinkedHashMap<String, Any?>()
        if (className != null) obj["class"] = className else obj["ref"] = ref
        obj["method"] = method
        obj["args"] = args?.let { parseJsonArg(it) }
        printJson(api.rpc.call("reflect.call", gson.toJsonTree(obj)))
    }
}

class ReflectNewCmd : CliktCommand(name = "new", help = "construct an object: --class --args JSON") {
    private val className by option("--class").required()
    private val args by option("--args", help = "constructor args array JSON or @file")

    override fun run() = withApi { api ->
        printJson(api.rpc.call("reflect.new", gson.toJsonTree(mapOf("class" to className, "args" to args?.let { parseJsonArg(it) }))))
    }
}

class ReflectRefsCmd : CliktCommand(name = "refs", help = "list live object references") {
    override fun run() = withApi { api ->
        printJson(api.rpc.call("reflect.refs", null))
    }
}

class ReflectReleaseCmd : CliktCommand(name = "release", help = "release an object reference") {
    private val ref by option("--ref").int().required()

    override fun run() = withApi { api ->
        printJson(api.rpc.call("reflect.release", gson.toJsonTree(mapOf<String, Any?>("ref" to ref))))
    }
}

class ReflectMappingsCmd : CliktCommand(name = "mappings", help = "show mapping info for a class or method") {
    private val className by option("--class")
    private val methodName by option("--method")

    override fun run() = withApi { api ->
        printJson(api.rpc.call("reflect.mappings", gson.toJsonTree(mapOf<String, Any?>("class" to className, "method" to methodName))))
    }
}

fun reflectSubcommands() = listOf(
    ReflectResolveCmd(), ReflectGetCmd(), ReflectCallCmd(), ReflectNewCmd(),
    ReflectRefsCmd(), ReflectReleaseCmd(), ReflectMappingsCmd(),
)
