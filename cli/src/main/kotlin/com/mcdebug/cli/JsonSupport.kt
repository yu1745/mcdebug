package com.mcdebug.cli

import com.google.gson.JsonElement
import com.mcdebug.sdk.gson

/** Human or single-line command output belongs to the CLI front end. */
fun printJson(el: JsonElement, oneLine: Boolean = false) {
    println(if (oneLine) el.toString() else gson.toJson(el))
}
