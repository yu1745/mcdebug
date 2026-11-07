package com.mcdebug.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mcdebug.rpc.RpcException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReflectCoreTest {
    @Suppress("unused")
    class Box {
        var count = 3
        val name = "box"
        var big = 0L
        var mode = Mode.A
        val items = mutableListOf(Inner(1), Inner(2))
        val numbers = listOf(1, 2, 3)
        val tags = mapOf("k" to Inner(9))
        fun getLabel() = "lbl"
        fun over(x: Int) = "int"
        fun over(x: Long) = "long"
        fun takesLong(x: Long) = x
        fun takesShort(x: Short) = x
        fun tie(x: java.io.Serializable) = "ser"
        fun tie(x: Comparable<*>) = "cmp"
    }

    class Inner(@JvmField val v: Int)
    enum class Mode { A, B }

    private val box = Box()
    private fun core(max: Int = 1024) = ReflectCore({ name, _ -> if (name == "box") box else null }, listOf("box"), max)
    private fun params(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `path walks fields, getters, indices and map keys`() {
        val c = core()
        assertEquals(3, c.target(params("""{"root":"box","path":"count"}""")).instance)
        assertEquals("lbl", c.target(params("""{"root":"box","path":"label"}""")).instance)
        assertEquals(2, c.target(params("""{"root":"box","path":"items[1].v"}""")).instance)
        assertEquals(9, c.target(params("""{"root":"box","path":"tags[\"k\"].v"}""")).instance)
        assertThrows<RpcException> { c.target(params("""{"root":"box","path":"items[5]"}""")) }
        assertThrows<RpcException> { c.target(params("""{"root":"box","path":"nope"}""")) }
    }

    @Test
    fun `same object keeps its ref and the table is bounded`() {
        val c = core(max = 2)
        val a = Inner(1)
        val id = c.refOf(a)
        assertEquals(id, c.refOf(a))
        c.refOf(Inner(2)); c.refOf(Inner(3))
        assertThrows<RpcException> { c.deref(id) }
        assertEquals(2, c.refsJson().get("count").asInt)
    }

    @Test
    fun `plain collections are inline, object collections are one ref with a summary`() {
        val c = core()
        assertTrue(c.toJson(box.numbers).isJsonArray)
        val items = c.toJson(box.items).asJsonObject
        assertEquals(2, items.get("size").asInt)
        assertTrue(items.has("ref"))
        assertEquals(1, c.refsJson().get("count").asInt)
    }

    @Test
    fun `a window pages through plain lists, object lists and maps`() {
        val c = core()
        val plain = (0 until 450).toList()
        val first = c.toJson(plain).asJsonArray
        assertEquals(201, first.size())
        assertEquals(250, first[200].asJsonObject.get("\$truncated").asInt)
        val page = c.toJson(plain, ReflectCore.Window(offset = 400, limit = 100)).asJsonArray
        assertEquals(50, page.size())
        assertEquals(400, page[0].asInt)
        val all = c.toJson(plain, ReflectCore.Window(limit = 1000)).asJsonArray
        assertEquals(450, all.size())

        val objs = (0 until 30).map(::Inner)
        val tail = c.toJson(objs, ReflectCore.Window(offset = 25, limit = 10)).asJsonObject
        assertEquals(5, tail.getAsJsonArray("items").size())
        assertEquals(25, tail.get("offset").asInt)
        assertFalse(tail.has("truncated"))
        val mid = c.toJson(objs, ReflectCore.Window(offset = 5, limit = 10)).asJsonObject
        assertTrue(mid.get("truncated").asBoolean)
        assertEquals(10, mid.get("returned").asInt)

        val map = (0 until 30).associate { "k$it" to it }
        val keys = c.toJson(map, ReflectCore.Window(offset = 28, limit = 5)).asJsonObject.getAsJsonObject("items")
        assertEquals(setOf("k28", "k29"), keys.keySet())
        assertThrows<RpcException> { ReflectCore.Window.of(params("""{"limit":0}""")) }
        assertThrows<RpcException> { ReflectCore.Window.of(params("""{"offset":-1}""")) }
    }

    @Test
    fun `both ref and dollar-ref name an object`() {
        val c = core()
        val inner = Inner(4)
        val id = c.refOf(inner)
        assertEquals(inner, c.target(params("""{"ref":""" + id + "}")).instance)
        assertEquals(inner, c.target(params("{\"\$ref\":" + id + "}")).instance)
    }

    @Test
    fun `overloads pick the best fit and ties need argTypes`() {
        val c = core()
        val one = listOf(c.fromJson(JsonParser.parseString("1")))
        assertEquals("over", c.findMethod(Box::class.java, "over", one, null).name)
        assertEquals("int", c.invoke(c.findMethod(Box::class.java, "over", one, null), box, arrayOf(1)))
        assertEquals("long", c.invoke(c.findMethod(Box::class.java, "over", one, listOf("long")), box, arrayOf(1)))
        val s = listOf(c.fromJson(JsonParser.parseString("\"x\"")))
        assertThrows<RpcException> { c.findMethod(Box::class.java, "tie", s, null) }
    }

    @Test
    fun `numbers widen, narrow when they fit, and keep long precision`() {
        val c = core()
        val big = c.fromJson(JsonParser.parseString("9007199254740993"))
        assertEquals(9007199254740993L, big.second)
        assertEquals(5L, c.invoke(c.findMethod(Box::class.java, "takesLong", listOf(c.fromJson(JsonParser.parseString("5"))), null), box, arrayOf(5)))
        assertEquals(7.toShort(), c.invoke(c.findMethod(Box::class.java, "takesShort", listOf(c.fromJson(JsonParser.parseString("7"))), null), box, arrayOf(7)))
        assertThrows<RpcException> { c.findMethod(Box::class.java, "takesShort", listOf(c.fromJson(JsonParser.parseString("70000"))), null) }
    }

    @Test
    fun `set coerces to the field type and refuses finals`() {
        val c = core()
        val big = c.findField(Box::class.java, "big")!!
        val (before, after) = c.writeField(big, box, "big", JsonParser.parseString("12"))
        assertEquals(0L, before); assertEquals(12L, after)
        c.writeField(c.findField(Box::class.java, "mode")!!, box, "mode", JsonParser.parseString("\"B\""))
        assertEquals(Mode.B, box.mode)
        assertThrows<RpcException> { c.writeField(c.findField(Box::class.java, "name")!!, box, "name", JsonParser.parseString("\"x\"")) }
        assertFalse(box.name != "box")
    }
}
