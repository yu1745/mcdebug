package com.mcdebug.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Shared wire JSON configuration; neither application front end owns the codec. */
val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

fun parseJson(text: String): JsonElement = gson.fromJson(text, JsonElement::class.java)
fun jsonObj(block: JsonObject.() -> Unit): JsonObject = JsonObject().apply(block)
