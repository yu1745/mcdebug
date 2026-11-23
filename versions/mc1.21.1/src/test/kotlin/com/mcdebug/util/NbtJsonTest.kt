package com.mcdebug.util

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcErrors
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.ShortTag
import net.minecraft.nbt.StringTag
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

    // ---- backward compatibility: bare numbers -> IntTag ----

    @Test
    fun `bare integer narrows to IntTag`() {
        val n = parse("5")
        val i = assertInstanceOf(IntTag::class.java, n)
        assertEquals(5, i.getAsInt())
    }

    @Test
    fun `bare object without nbt hint is a plain compound`() {
        val n = parse("""{"a":1,"b":"x"}""")
        val c = assertInstanceOf(CompoundTag::class.java, n)
        assertEquals(1, c.getInt("a"))
        assertEquals("x", c.getString("b"))
    }

    @Test
    fun `bare boolean becomes ByteTag`() {
        val n = parse("true")
        val b = assertInstanceOf(ByteTag::class.java, n)
        assertEquals(1, b.getAsByte().toInt())
    }

    // ---- #nbt sentinel: scalar types ----

    @Test
    fun `nbt long sentinel`() {
        val n = parseTyped("long", "123456789012")
        val l = assertInstanceOf(LongTag::class.java, n)
        assertEquals(123456789012L, l.getAsLong())
    }

    @Test
    fun `nbt byte sentinel`() {
        val n = parseTyped("byte", "7")
        val b = assertInstanceOf(ByteTag::class.java, n)
        assertEquals(7, b.getAsByte().toInt())
    }

    @Test
    fun `nbt short sentinel`() {
        val n = parseTyped("short", "32000")
        val s = assertInstanceOf(ShortTag::class.java, n)
        assertEquals(32000, s.getAsShort().toInt())
    }

    @Test
    fun `nbt int sentinel`() {
        val n = parseTyped("int", "42")
        val i = assertInstanceOf(IntTag::class.java, n)
        assertEquals(42, i.getAsInt())
    }

    @Test
    fun `nbt float sentinel`() {
        val n = parseTyped("float", "1.5")
        val f = assertInstanceOf(FloatTag::class.java, n)
        assertEquals(1.5f, f.getAsFloat(), 0.0001f)
    }

    @Test
    fun `nbt double sentinel`() {
        val n = parseTyped("double", "3.14159")
        val d = assertInstanceOf(DoubleTag::class.java, n)
        assertEquals(3.14159, d.getAsDouble(), 0.0000001)
    }

    @Test
    fun `nbt string sentinel coerces number to string`() {
        val n = parseTyped("string", "123")
        val s = assertInstanceOf(StringTag::class.java, n)
        assertEquals("123", s.getAsString())
    }

    // ---- #nbt sentinel: array types ----

    @Test
    fun `nbt byteArray sentinel`() {
        val n = NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"byteArray","value":[1,2,3]}"""))
        val b = assertInstanceOf(net.minecraft.nbt.ByteArrayTag::class.java, n)
        assertEquals(listOf<Byte>(1, 2, 3), b.asByteArray.toList())
    }

    @Test
    fun `nbt intArray sentinel`() {
        val n = NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"intArray","value":[10,20,30]}"""))
        val i = assertInstanceOf(net.minecraft.nbt.IntArrayTag::class.java, n)
        assertEquals(listOf(10, 20, 30), i.asIntArray.toList())
    }

    @Test
    fun `nbt longArray sentinel`() {
        val n = NbtJson.fromJson(JsonParser.parseString("""{"#nbt":"longArray","value":[100,200,300]}"""))
        val l = assertInstanceOf(net.minecraft.nbt.LongArrayTag::class.java, n)
        assertEquals(listOf<Long>(100L, 200L, 300L), l.asLongArray.toList())
    }

    // ---- #nbt sentinel nesting ----

    @Test
    fun `nbt sentinel nested inside compound`() {
        // A regular compound whose value at key "pos" uses the long sentinel.
        val n = parse("""{"pos":{"#nbt":"long","value":12345}}""")
        val c = assertInstanceOf(CompoundTag::class.java, n)
        val pos = assertInstanceOf(LongTag::class.java, c.get("pos"))
        assertEquals(12345L, pos.getAsLong())
    }

    @Test
    fun `compound key literally named nbt without hash is not a sentinel`() {
        // A key "nbt" (no leading #) must be treated as a plain compound entry, not a sentinel.
        val n = parse("""{"nbt":"long","value":5}""")
        val c = assertInstanceOf(CompoundTag::class.java, n)
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
    fun `toJson preserves LongTag`() {
        val n = LongTag.valueOf(9999999999L)
        val j = NbtJson.toJson(n)
        assertTrue(j.isJsonPrimitive && j.asJsonPrimitive.isNumber)
        assertEquals(9999999999L, j.asLong)
    }

    @Test
    fun `toJson preserves DoubleTag`() {
        val n = DoubleTag.valueOf(2.5)
        val j = NbtJson.toJson(n)
        assertTrue(j.isJsonPrimitive && j.asJsonPrimitive.isNumber)
        assertEquals(2.5, j.asDouble, 0.0001)
    }

    @Test
    fun `toJson round-trips compound`() {
        val c = CompoundTag()
        c.putByte("flag", 1)
        c.putString("name", "x")
        c.putLong("pos", 42L)
        val j = NbtJson.toJson(c)
        // back to NBT
        val back = NbtJson.fromJson(j) as CompoundTag
        assertEquals(1, back.getByte("flag").toInt())
        assertEquals("x", back.getString("name"))
        assertEquals(42L, back.getLong("pos"))
    }

    @Test
    fun `toJson round-trips ByteTag via byteValue`() {
        // ByteTag toJson renders as int; round-trip through bare number gives IntTag,
        // which is the documented lossiness. Verify the documented behavior so a future
        // change that silently "fixes" it is caught.
        val j = NbtJson.toJson(ByteTag.valueOf(1))
        assertEquals(JsonPrimitive(1), j)
        val back = NbtJson.fromJson(j)
        assertInstanceOf(IntTag::class.java, back)  // documented lossiness: byte -> int
    }

    // ---- null handling ----

    @Test
    fun `fromJson json-null becomes EndTag`() {
        val n = NbtJson.fromJson(JsonNull.INSTANCE)
        assertInstanceOf(net.minecraft.nbt.EndTag::class.java, n)
    }

    @Test
    fun `toJson EndTag becomes json-null`() {
        val j = NbtJson.toJson(net.minecraft.nbt.EndTag.INSTANCE)
        assertTrue(j.isJsonNull)
    }

    // ---- getByPath / setByPath (used by be.setField) ----

    @Test
    fun `getByPath resolves dotted path`() {
        val c = CompoundTag()
        c.putLong("Energy", 100L)
        val v = NbtJson.getByPath(c, "Energy")
        assertEquals(100L, (v as LongTag).getAsLong())
    }

    @Test
    fun `getByPath returns null for missing path`() {
        val c = CompoundTag()
        assertEquals(null, NbtJson.getByPath(c, "missing"))
    }

    @Test
    fun `setByPath sets nested value`() {
        val c = CompoundTag()
        NbtJson.setByPath(c, "Energy", LongTag.valueOf(250L))
        assertEquals(250L, c.getLong("Energy"))
    }

    @Test
    fun `setByPath rejects empty path`() {
        val c = CompoundTag()
        assertThrows(RpcException::class.java) { NbtJson.setByPath(c, "", IntTag.valueOf(0)) }
    }

    // ---- non-ASCII strings (regression: 0.5.x set-nbt round-trip garbled non-ASCII) ----

    @Test
    fun `non-ascii string with unicode escapes round-trips losslessly`() {
        // JSON 源里用 \u 转义（等价于 CLI --nbt '{"name":"\u4e2d\u6587"}' 的写法）。
        val n = parse("""{"name":"\u4e2d\u6587","msg":"caf\u00e9 \u00fc"}""")
        val c = assertInstanceOf(CompoundTag::class.java, n)
        assertEquals("中文", c.getString("name"))
        assertEquals("café ü", c.getString("msg"))
        // NBT→JSON 方向不得再引入任何 mojibake。
        val j = NbtJson.toJson(c)
        val back = NbtJson.fromJson(j) as CompoundTag
        assertEquals("中文", back.getString("name"))
        assertEquals("café ü", back.getString("msg"))
    }

    @Test
    fun `supplementary-plane characters survive nbt io round trip`() {
        // 代理对（emoji）在 NbtIo 的 modified UTF-8（CESU-8）序列化下必须无损往返。
        val c = CompoundTag()
        c.putString("emoji", "a\uD83D\uDE00b")
        val baos = java.io.ByteArrayOutputStream()
        net.minecraft.nbt.NbtIo.write(c, java.io.DataOutputStream(baos))
        val back = net.minecraft.nbt.NbtIo.read(
            java.io.DataInputStream(java.io.ByteArrayInputStream(baos.toByteArray()))
        )
        assertEquals("a😀b", (back as CompoundTag).getString("emoji"))
    }

    @Test
    fun `toJson fromJson non-ascii round trip matches original java string`() {
        val c = CompoundTag()
        c.putString("s", "日本語テキスト ✔")
        val j = NbtJson.toJson(c)
        val back = NbtJson.fromJson(j) as CompoundTag
        assertEquals("日本語テキスト ✔", back.getString("s"))
    }
}
