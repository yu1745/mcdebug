package com.mcdebug.cli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 与服务器一致的 JSON 配置：不转义 HTML、输出 null 字段。 */
val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

fun parseJson(text: String): JsonElement = gson.fromJson(text, JsonElement::class.java)

fun jsonObj(block: JsonObject.() -> Unit): JsonObject = JsonObject().apply(block)

/** 输出 JSON：默认 pretty（人读），单行模式给脚本。 */
fun printJson(el: JsonElement, oneLine: Boolean = false) {
    println(if (oneLine) el.toString() else gson.toJson(el))
}
