package com.mcdebug.api

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** snapshot.diff --ignore-meta 的元数据过滤单测（0.6.0）。 */
class SnapshotOpsTest {

    @Test
    fun `stripMeta removes top-level tick and time only`() {
        val el = JsonParser.parseString(
            """{"tick":100,"time":5000,"dim":"minecraft:overworld","blocks":[{"state":{"id":"minecraft:stone"},"time":1}]}"""
        )
        val stripped = SnapshotOps.stripMeta(el).asJsonObject
        assertFalse(stripped.has("tick"))
        assertFalse(stripped.has("time"))
        assertTrue(stripped.has("dim"))
        assertTrue(stripped.has("blocks"))
        // 嵌套对象里的 time 键不受影响（只过滤顶层元数据）。
        assertEquals(1, stripped.getAsJsonArray("blocks")[0].asJsonObject.get("time").asInt)
        // 入参不被改动。
        assertTrue(el.asJsonObject.has("tick"))
    }

    @Test
    fun `stripMeta passes non-objects through`() {
        val el = JsonParser.parseString("[1,2,3]")
        val out = SnapshotOps.stripMeta(el)
        assertEquals(el, out)
    }
}
