package com.mcdebug.api

import com.google.gson.JsonParser
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Missing or mistyped params must be INVALID_PARAMS naming the param, never an INTERNAL_ERROR. */
class ParamsTest {
    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    private fun code(block: () -> Unit): Pair<Int, String> {
        val e = assertThrows<RpcException> { block() }
        return e.rpcCode to (e.message ?: "")
    }

    @Test
    fun `pos parses and rejects`() {
        assertEquals(BlockPos(1, -2, 3), obj("""{"pos":[1,-2,3]}""").requirePos("pos"))
        assertNull(obj("{}").posOrNull("pos"))
        val (missing, message) = code { obj("{}").requirePos("pos") }
        assertEquals(RpcErrors.INVALID_PARAMS, missing)
        assertTrue("pos" in message)
        assertEquals(RpcErrors.INVALID_POSITION, code { obj("""{"pos":[1,2]}""").requirePos("pos") }.first)
        assertEquals(RpcErrors.INVALID_POSITION, code { obj("""{"pos":[1,"a",3]}""").requirePos("pos") }.first)
        assertEquals(RpcErrors.INVALID_PARAMS, code { obj("""{"pos":"1,2,3"}""").requirePos("pos") }.first)
    }

    @Test
    fun `box needs both corners`() {
        val box = obj("""{"box":{"from":[3,3,3],"to":[0,0,0]}}""").requireBox()
        assertEquals(0, box.minX()); assertEquals(3, box.maxZ())
        assertEquals(RpcErrors.INVALID_PARAMS, code { obj("""{"box":{"from":[0,0,0]}}""").requireBox() }.first)
        assertEquals(RpcErrors.INVALID_PARAMS, code { obj("""{"box":[0,0,0]}""").requireBox() }.first)
    }

    @Test
    fun `scalars are typed`() {
        assertEquals(5, obj("""{"n":5}""").getIntOr("n", 1))
        assertEquals(5, obj("""{"n":"5"}""").getIntOr("n", 1))
        assertEquals(1, obj("""{"n":null}""").getIntOr("n", 1))
        assertEquals(RpcErrors.INVALID_PARAMS, code { obj("""{"n":"x"}""").getIntOr("n", 1) }.first)
        assertEquals(RpcErrors.INVALID_PARAMS, code { obj("""{"n":{}}""").requireLong("n") }.first)
        assertEquals(RpcErrors.INVALID_PARAMS, code { obj("""{"s":[1]}""").requireString("s") }.first)
        assertTrue(obj("""{"b":"true"}""").getBoolOrFalse("b"))
        assertFalse(obj("{}").getBoolOrFalse("b"))
        assertEquals(RpcErrors.INVALID_PARAMS, code { obj("""{"b":"yes"}""").getBoolOrFalse("b") }.first)
    }

    @Test
    fun `internal errors keep the exception type`() {
        val e = RpcErrors.internal(NullPointerException())
        assertEquals(RpcErrors.INTERNAL_ERROR, e.rpcCode)
        assertEquals("NullPointerException: (no message)", e.message)
        assertEquals("java.lang.NullPointerException", e.data!!.asJsonObject["exception"].asString)
    }
}
