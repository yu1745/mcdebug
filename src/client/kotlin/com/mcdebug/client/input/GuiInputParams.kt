package com.mcdebug.client

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Strict JSON parsing, deliberately independent of Minecraft and LWJGL. */
object GuiInputParams {
    data class Key(val key: Int, val modifiers: Int)
    data class Type(val text: String, val clear: Boolean, val modifiers: Int)
    data class Drag(
        val from: Pair<Double, Double>, val to: Pair<Double, Double>, val space: String,
        val button: Int, val steps: Int, val stepTicks: Int, val modifiers: Int, val moveCursor: Boolean,
    )

    fun key(p: JsonObject): Key {
        val key = required(p, "key")
        require(key.isJsonPrimitive) { "key must be a string or integer" }
        val code = when {
            key.asJsonPrimitive.isString -> GuiInputValidation.key(key.asString)
            key.asJsonPrimitive.isNumber -> GuiInputValidation.key(integer(key, "key"))
            else -> throw IllegalArgumentException("key must be a string or integer")
        }
        return Key(code, modifiers(p))
    }

    fun type(p: JsonObject): Type = Type(
        GuiInputValidation.text(string(required(p, "text"), "text")),
        bool(p, "clear", false), modifiers(p),
    )

    fun drag(p: JsonObject): Drag {
        val steps = int(p, "steps", 8)
        val ticks = int(p, "stepTicks", 1)
        GuiInputValidation.timing(steps, ticks)
        val space = p.get("space")?.let { string(it, "space") } ?: "gui"
        require(space == "gui" || space == "png") { "space must be gui or png" }
        return Drag(
            pair(p, "from"), pair(p, "to"), space,
            GuiInputValidation.button(int(p, "button", 0)), steps, ticks,
            modifiers(p), bool(p, "moveCursor", true),
        )
    }

    private fun modifiers(p: JsonObject): Int = GuiInputValidation.modifiers(int(p, "modifiers", 0))
    private fun required(p: JsonObject, name: String): JsonElement = p.get(name)
        ?: throw IllegalArgumentException("$name is required")

    private fun string(e: JsonElement, name: String): String {
        require(e.isJsonPrimitive && e.asJsonPrimitive.isString) { "$name must be a string" }
        return e.asString
    }

    private fun integer(e: JsonElement, name: String): Int {
        require(e.isJsonPrimitive && e.asJsonPrimitive.isNumber) { "$name must be an integer" }
        return try {
            e.asBigDecimal.intValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("$name must be a 32-bit integer")
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("$name must be an integer")
        }
    }

    private fun int(p: JsonObject, name: String, default: Int): Int = p.get(name)?.let { integer(it, name) } ?: default
    private fun bool(p: JsonObject, name: String, default: Boolean): Boolean {
        val e = p.get(name) ?: return default
        require(e.isJsonPrimitive && e.asJsonPrimitive.isBoolean) { "$name must be a boolean" }
        return e.asBoolean
    }

    private fun pair(p: JsonObject, name: String): Pair<Double, Double> {
        val e = required(p, name)
        require(e.isJsonArray && e.asJsonArray.size() == 2) { "$name must be [x,y]" }
        val values = e.asJsonArray
        require(values.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }) { "$name must contain numbers" }
        return GuiInputValidation.point(values[0].asDouble, values[1].asDouble)
    }
}
