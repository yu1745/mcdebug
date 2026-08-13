package com.mcdebug.cli

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CliSupportTest {

    @Test
    fun `tokenizer splits on whitespace`() {
        assertEquals(
            listOf("world", "set-block", "--x", "1", "--y", "2", "--z", "3"),
            tokenizeCommandLine("world set-block --x 1 --y 2 --z 3"),
        )
    }

    @Test
    fun `tokenizer respects single and double quotes`() {
        assertEquals(
            listOf("raw", "world.setBlock", """{"pos":[1,2,3]}"""),
            tokenizeCommandLine("""raw world.setBlock '{"pos":[1,2,3]}'"""),
        )
        assertEquals(
            listOf("a", "b c", "d"),
            tokenizeCommandLine("""a "b c" d"""),
        )
    }

    @Test
    fun `tokenizer supports escaped chars outside quotes`() {
        assertEquals(listOf("a b"), tokenizeCommandLine("""a\ b"""))
    }

    @Test
    fun `tokenizer rejects unbalanced quote`() {
        assertThrows(IllegalArgumentException::class.java) {
            tokenizeCommandLine("""a "b""")
        }
    }

    @Test
    fun `exit code mapping`() {
        assertEquals(2, exitCodeFor(RpcException(-32006, "timeout")))
        assertEquals(2, exitCodeFor(RpcException(-32602, "invalid params")))
        assertEquals(2, exitCodeFor(RpcException(-32004, "no block entity")))
        assertEquals(3, exitCodeFor(RpcException(-32601, "method not found")))
        assertEquals(3, exitCodeFor(RpcException(-32601, "method not found: server.health")))
    }

    @Test
    fun `json path resolver handles dots and indexes`() {
        val el = JsonParser.parseString("""{"a":{"b":[10,20,{"c":"中文"}]},"d":5}""")
        assertEquals(10, jsonGetPath(el, "a.b[0]")!!.asInt)
        assertEquals("中文", jsonGetPath(el, "a.b[2].c")!!.asString)
        assertEquals(5, jsonGetPath(el, "d")!!.asInt)
        assertNull(jsonGetPath(el, "a.b[9]"))
        assertNull(jsonGetPath(el, "a.x.y"))
    }
}
