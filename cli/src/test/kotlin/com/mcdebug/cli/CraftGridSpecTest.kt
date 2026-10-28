package com.mcdebug.cli

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CraftGridSpecTest {
    private fun item(id: String, count: Int = 1) = mapOf("item" to id, "count" to count)

    @Test
    fun `positional args fill row-major and pad to nine`() {
        val grid = parseGridSpec(listOf("minecraft:oak_planks", "-", "minecraft:stick@2"))
        assertEquals(listOf(item("minecraft:oak_planks"), null, item("minecraft:stick", 2), null, null, null, null, null, null), grid)
    }

    @Test
    fun `slot prefix places the item and positional args continue after it`() {
        val grid = parseGridSpec(listOf("4=minecraft:coal", "minecraft:stick"))
        assertEquals(item("minecraft:coal"), grid[4])
        assertEquals(item("minecraft:stick"), grid[5])
        assertEquals(7, grid.count { it == null })
    }

    @Test
    fun `bad specs are usage errors`() {
        assertThrows<UsageError> { parseGridSpec(listOf("9=minecraft:coal")) }
        assertThrows<UsageError> { parseGridSpec(listOf("0=minecraft:coal", "0=minecraft:stick")) }
        assertThrows<UsageError> { parseGridSpec(List(10) { "minecraft:stick" }) }
        assertThrows<UsageError> { parseGridSpec(listOf("minecraft:stick@x")) }
    }
}
