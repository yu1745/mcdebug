package com.mcdebug.loader.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The generated class index must describe this Minecraft version. Minecraft 26.3
 * ships official names, including in production; short names still resolve.
 *
 * This exercises the Fabric implementation directly: the shared `Mappings` facade only
 * delegates to whatever the installed loader seam provides.
 */
class FabricRuntimeNamesTest {
    private val names = FabricRuntimeNames()

    @Test
    fun `table is loaded from the generated resource`() {
        names.ensureLoaded()
        assertTrue(names.isAvailable())
    }

    @Test
    fun `Mojang simple and qualified names resolve to official names`() {
        assertEquals("net.minecraft.world.level.block.entity.BlockEntity", names.runtimeClassName("BlockEntity"))
        assertEquals("net.minecraft.world.level.block.Blocks", names.runtimeClassName("Blocks"))
        // A loadable name is returned untouched (dev classpath has Mojang names).
        assertEquals("net.minecraft.world.level.block.Blocks", names.runtimeClassName("net.minecraft.world.level.block.Blocks"))
    }

    @Test
    fun `unknown names pass through`() {
        assertEquals("com.example.Nope", names.runtimeClassName("com.example.Nope"))
    }
}
