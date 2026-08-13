package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/** 从当前命令链找到根 McDebugCli，用 withClient 构造 DebugApi（批处理下复用共享连接）。 */
fun CliktCommand.withApi(block: (DebugApi) -> Unit) {
    val cli = rootCli()
    withClient(cli) { c -> block(DebugApi(c)) }
}

fun CliktCommand.rootCli(): McDebugCli {
    var ctx = currentContext
    while (ctx.parent != null) ctx = ctx.parent!!
    return requireNotNull(ctx.command as? McDebugCli)
}

/** "x,y,z" 三元组 → Pos。 */
fun parseTriplet(spec: String, what: String): Pos {
    val parts = spec.split(",").map { it.trim() }
    if (parts.size != 3) throw IllegalArgumentException("$what must be \"x,y,z\" with 3 integers, got: $spec")
    val out = parts.map { it.toIntOrNull() ?: throw IllegalArgumentException("$what axis is not an integer: $it") }
    return out
}

/** from/to 两个三元组 → Box。 */
fun parseBox(from: String, to: String): Box =
    mapOf("from" to parseTriplet(from, "from"), "to" to parseTriplet(to, "to"))

/** "k=v" 可重复选项 → state props。 */
fun parseStateProps(entries: List<String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (e in entries) {
        val idx = e.indexOf('=')
        if (idx < 0) throw IllegalArgumentException("state entry must be k=v, got: $e")
        out[e.substring(0, idx)] = e.substring(idx + 1)
    }
    return out
}

/** JSON 字面量或 "@file"。 */
fun parseJsonArg(s: String): JsonElement {
    val txt = if (s.startsWith("@")) Files.readString(Path.of(s.substring(1))) else s
    return JsonParser.parseString(txt)
}

/** 输出 JSON（pretty）。 */
fun printJson(el: JsonElement) {
    println(gson.toJson(el))
}
