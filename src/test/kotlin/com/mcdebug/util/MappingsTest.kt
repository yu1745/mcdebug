package com.mcdebug.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The generated table must describe the Minecraft version this jar targets (the old
 * bundled 1.20.1 tables silently mapped 1.21.1 names to the wrong classes). Without a
 * Fabric loader the mapper assumes an intermediary runtime, so names resolve to
 * intermediary ones here.
 */
class MappingsTest {
    @Test
    fun `table is loaded from the generated resource`() {
        Mappings.ensureLoaded()
        assertTrue(Mappings.isAvailable())
    }

    @Test
    fun `Mojang simple and qualified names resolve to 1_21_1 intermediary`() {
        // class_2586 = BlockEntity, class_2246 = Blocks in every intermediary version.
        assertEquals("net.minecraft.class_2586", Mappings.runtimeClassName("BlockEntity"))
        assertEquals("net.minecraft.class_2246", Mappings.runtimeClassName("Blocks"))
        // A loadable name is returned untouched (dev classpath has Mojang names).
        assertEquals("net.minecraft.world.level.block.Blocks", Mappings.runtimeClassName("net.minecraft.world.level.block.Blocks"))
    }

    @Test
    fun `unknown names pass through`() {
        assertEquals("com.example.Nope", Mappings.runtimeClassName("com.example.Nope"))
    }
}
