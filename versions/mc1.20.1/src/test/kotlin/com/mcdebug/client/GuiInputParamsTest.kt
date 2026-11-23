package com.mcdebug.client

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuiInputParamsTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test fun `key accepts names or exact integer only`() {
        assertEquals(GuiInputParams.Key(65, 0), GuiInputParams.key(json("""{"key":"a"}""")))
        assertEquals(GuiInputParams.Key(257, 3), GuiInputParams.key(json("""{"key":257,"modifiers":3}""")))
        for (s in listOf("{}", """{"key":null}""", """{"key":true}""", """{"key":[]}""",
            """{"key":257.5}""", """{"key":2147483648}""", """{"key":1e100}""",
            """{"key":"257"}""", """{"key":"A","modifiers":"2"}""", """{"key":"A","modifiers":null}""",
            """{"key":"A","modifiers":0.1}""", """{"key":"A","modifiers":16}""")) {
            assertThrows(IllegalArgumentException::class.java, { GuiInputParams.key(json(s)) }, s)
        }
    }

    @Test fun `type is strict about string booleans and UTF16 bound`() {
        assertEquals(GuiInputParams.Type("中文😀", false, 0), GuiInputParams.type(json("""{"text":"中文😀"}""")))
        assertEquals(GuiInputParams.Type("", true, 8), GuiInputParams.type(json("""{"text":"","clear":true,"modifiers":8}""")))
        for (s in listOf("{}", """{"text":null}""", """{"text":123}""", """{"text":"ok","clear":"true"}""",
            """{"text":"ok","clear":0}""", """{"text":"ok","clear":null}""", """{"text":"a\nb"}""",
            """{"text":"\uD800"}""", """{"text":"\uDC00"}""")) {
            assertThrows(IllegalArgumentException::class.java, { GuiInputParams.type(json(s)) }, s)
        }
    }

    @Test fun `drag defaults are bounded and coordinates do not coerce strings`() {
        val p = GuiInputParams.drag(json("""{"from":[1,2],"to":[3,4]}"""))
        assertEquals(1.0 to 2.0, p.from)
        assertEquals(3.0 to 4.0, p.to)
        assertEquals("gui", p.space)
        assertEquals(0, p.button)
        assertEquals(8, p.steps)
        assertEquals(1, p.stepTicks)
        assertEquals(0, p.modifiers)
        assertTrue(p.moveCursor)
        val png = GuiInputParams.drag(json("""{"from":[1,2],"to":[3,4],"space":"png","button":7,"steps":20,"stepTicks":20,"modifiers":15,"moveCursor":false}"""))
        assertEquals("png", png.space)
        assertEquals(400, png.steps * png.stepTicks)
        assertFalse(png.moveCursor)
        for (field in listOf("\"space\":null", "\"space\":\"window\"", "\"space\":1", "\"button\":8",
            "\"button\":-1", "\"button\":0.5", "\"steps\":0", "\"steps\":129", "\"steps\":2.5",
            "\"stepTicks\":21", "\"stepTicks\":-1", "\"steps\":21,\"stepTicks\":20", "\"moveCursor\":\"false\"")) {
            val s = "{\"from\":[1,2],\"to\":[3,4],$field}"
            assertThrows(IllegalArgumentException::class.java, { GuiInputParams.drag(json(s)) }, s)
        }
        for (point in listOf("null", "{}", "[]", "[1]", "[1,2,3]", "[\"1\",2]", "[true,2]", "[1e999,2]")) {
            assertThrows(IllegalArgumentException::class.java) { GuiInputParams.drag(json("{\"from\":$point,\"to\":[3,4]}")) }
            assertThrows(IllegalArgumentException::class.java) { GuiInputParams.drag(json("{\"from\":[1,2],\"to\":$point}")) }
        }
    }
}
