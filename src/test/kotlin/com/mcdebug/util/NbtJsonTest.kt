package com.mcdebug.util

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcErrors
import net.minecraft.nbt.NbtByte
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtDouble
import net.minecraft.nbt.NbtFloat
import net.minecraft.nbt.NbtInt
import net.minecraft.nbt.NbtLong
import net.minecraft.nbt.NbtShort
import net.minecraft.nbt.NbtString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for NbtJson, focusing on the JSON->NBT direction where the v2
 * `#nbt` type sentinel was added. These run on the Loom test classpath (MC
 * NBT classes are available via modImplementation propagation).
 */
class NbtJsonTest {

    private fun parse(json: String) = NbtJson.fromJson(JsonParser.parseString(json))

    private fun parseTyped(type: String, value: String) =
        NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"$type","value":$value}"""))

    // ---- backward compatibility: bare numbers -> NbtInt ----

    @Test
    fun `bare integer narrows to NbtInt`() {
        val n = parse("5")
        val i = assertInstanceOf(NbtInt::class.java, n)
        assertEquals(5, i.intValue())
    }

    @Test
    fun `bare object without nbt hint is a plain compound`() {
        val n = parse("""{"a":1,"b":"x"}""")
        val c = assertInstanceOf(NbtCompound::class.java, n)
        assertEquals(1, c.getInt("a"))
        assertEquals("x", c.getString("b"))
    }

    @Test
    fun `bare boolean becomes NbtByte`() {
        val n = parse("true")
        val b = assertInstanceOf(NbtByte::class.java, n)
        assertEquals(1, b.byteValue().toInt())
    }

    // ---- #nbt sentinel: scalar types ----

    @Test
    fun `nbt long sentinel`() {
        val n = parseTyped("long", "123456789012")
        val l = assertInstanceOf(NbtLong::class.java, n)
        assertEquals(123456789012L, l.longValue())
    }

    @Test
    fun `nbt byte sentinel`() {
        val n = parseTyped("byte", "7")
        val b = assertInstanceOf(NbtByte::class.java, n)
        assertEquals(7, b.byteValue().toInt())
    }

    @Test
    fun `nbt short sentinel`() {
        val n = parseTyped("short", "32000")
        val s = assertInstanceOf(NbtShort::class.java, n)
        assertEquals(32000, s.shortValue().toInt())
    }

    @Test
    fun `nbt int sentinel`() {
        val n = parseTyped("int", "42")
        val i = assertInstanceOf(NbtInt::class.java, n)
        assertEquals(42, i.intValue())
    }

    @Test
    fun `nbt float sentinel`() {
        val n = parseTyped("float", "1.5")
        val f = assertInstanceOf(NbtFloat::class.java, n)
        assertEquals(1.5f, f.floatValue(), 0.0001f)
    }

    @Test
    fun `nbt double sentinel`() {
        val n = parseTyped("double", "3.14159")
        val d = assertInstanceOf(NbtDouble::class.java, n)
        assertEquals(3.14159, d.doubleValue(), 0.0000001)
    }

    @Test
    fun `nbt string sentinel coerces number to string`() {
        val n = parseTyped("string", "123")
        val s = assertInstanceOf(NbtString::class.java, n)
        assertEquals("123", s.asString())
    }

    // ---- #nbt sentinel: array types ----

    @Test
    fun `nbt byteArray sentinel`() {
        val n = NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"byteArray","value":[1,2,3]}"""))
        val b = assertInstanceOf(net.minecraft.nbt.NbtByteArray::class.java, n)
        assertEquals(listOf<Byte>(1, 2, 3), b.byteArray.toList())
    }

    @Test
    fun `nbt intArray sentinel`() {
        val n = NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"intArray","value":[10,20,30]}"""))
        val i = assertInstanceOf(net.minecraft.nbt.NbtIntArray::class.java, n)
        assertEquals(listOf(10, 20, 30), i.intArray.toList())
    }

    @Test
    fun `nbt longArray sentinel`() {
        val n = NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"longArray","value":[100,200,300]}"""))
        val l = assertInstanceOf(net.minecraft.nbt.NbtLongArray::class.java, n)
        assertEquals(listOf<Long>(100L, 200L, 300L), l.longArray.toList())
    }

    // ---- #nbt sentinel nesting ----

    @Test
    fun `nbt sentinel nested inside compound`() {
        // A regular compound whose value at key "pos" uses the long sentinel.
        val n = parse("""{"pos":{"#nbt":"long","value":12345}}""")
        val c = assertInstanceOf(NbtCompound::class.java, n)
        val pos = assertInstanceOf(NbtLong::class.java, c.get("pos"))
        assertEquals(12345L, pos.longValue())
    }

    @Test
    fun `compound key literally named nbt without hash is not a sentinel`() {
        // A key "nbt" (no leading #) must be treated as a plain compound entry, not a sentinel.
        val n = parse("""{"nbt":"long","value":5}""")
        val c = assertInstanceOf(NbtCompound::class.java, n)
        // "nbt" and "value" are both ordinary keys.
        assertEquals("long", c.getString("nbt"))
        assertEquals(5, c.getInt("value"))
    }

    // ---- #nbt sentinel: rejection ----

    @Test
    fun `rejects unknown nbt type`() {
        val e = assertThrows(RpcException::class.java) {
            NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"quaternion","value":1}"""))
        }
        assertEquals(RpcErrors.NBT_PARSE_ERROR, e.rpcCode)
    }

    @Test
    fun `rejects nbt sentinel missing value`() {
        assertThrows(RpcException::class.java) {
            NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"long"}"""))
        }
    }

    @Test
    fun `rejects nbt long with string value`() {
        assertThrows(RpcException::class.java) {
            NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"long","value":"oops"}"""))
        }
    }

    @Test
    fun `rejects nbt byteArray with non-array value`() {
        assertThrows(RpcException::class.java) {
            NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"byteArray","value":5}"""))
        }
    }

    // ---- toJson: NBT -> JSON preserves type identity ----

    @Test
    fun `toJson preserves NbtLong`() {
        val n = NbtLong.of(9999999999L)
        val j = NbtJson.toJson(n)
        assertTrue(j.isJsonPrimitive && j.asJsonPrimitive.isNumber)
        assertEquals(9999999999L, j.asLong)
    }

    @Test
    fun `toJson preserves NbtDouble`() {
        val n = NbtDouble.of(2.5)
        val j = NbtJson.toJson(n)
        assertTrue(j.isJsonPrimitive && j.asJsonPrimitive.isNumber)
        assertEquals(2.5, j.asDouble, 0.0001)
    }

    @Test
    fun `toJson round-trips compound`() {
        val c = NbtCompound()
        c.putByte("flag", 1)
        c.putString("name", "x")
        c.putLong("pos", 42L)
        val j = NbtJson.toJson(c)
        // back to NBT
        val back = NbtJson.fromJson(j) as NbtCompound
        assertEquals(1, back.getByte("flag").toInt())
        assertEquals("x", back.getString("name"))
        assertEquals(42L, back.getLong("pos"))
    }

    @Test
    fun `toJson round-trips NbtByte via byteValue`() {
        // NbtByte toJson renders as int; round-trip through bare number gives NbtInt,
        // which is the documented lossiness. Verify the documented behavior so a future
        // change that silently "fixes" it is caught.
        val j = NbtJson.toJson(NbtByte.of(1))
        assertEquals(JsonPrimitive(1), j)
        val back = NbtJson.fromJson(j)
        assertInstanceOf(NbtInt::class.java, back)  // documented lossiness: byte -> int
    }

    // ---- null handling ----

    @Test
    fun `fromJson json-null becomes NbtEnd`() {
        val n = NbtJson.fromJson(JsonNull.INSTANCE)
        assertInstanceOf(net.minecraft.nbt.NbtEnd::class.java, n)
    }

    @Test
    fun `toJson NbtEnd becomes json-null`() {
        val j = NbtJson.toJson(net.minecraft.nbt.NbtEnd.INSTANCE)
        assertTrue(j.isJsonNull)
    }

    // ---- getByPath / setByPath (used by be.setField) ----

    @Test
    fun `getByPath resolves dotted path`() {
        val c = NbtCompound()
        c.putLong("Energy", 100L)
        val v = NbtJson.getByPath(c, "Energy")
        assertEquals(100L, (v as NbtLong).longValue())
    }

    @Test
    fun `getByPath returns null for missing path`() {
        val c = NbtCompound()
        assertEquals(null, NbtJson.getByPath(c, "missing"))
    }

    @Test
    fun `setByPath sets nested value`() {
        val c = NbtCompound()
        NbtJson.setByPath(c, "Energy", NbtLong.of(250L))
        assertEquals(250L, c.getLong("Energy"))
    }

    @Test
    fun `setByPath rejects empty path`() {
        val c = NbtCompound()
        assertThrows(RpcException::class.java) { NbtJson.setByPath(c, "", NbtInt.of(0)) }
    }
}
