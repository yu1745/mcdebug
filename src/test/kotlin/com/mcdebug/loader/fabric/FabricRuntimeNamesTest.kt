package com.mcdebug.loader.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The generated table must describe the Minecraft version this jar targets (the old
 * bundled 1.20.1 tables silently mapped 1.21.1 names to the wrong classes). Without a
 * Fabric loader the mapper assumes an intermediary runtime, so names resolve to
 * intermediary ones here.
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
    fun `Mojang simple and qualified names resolve to 1_21_1 intermediary`() {
        // class_2586 = BlockEntity, class_2246 = Blocks in every intermediary version.
        assertEquals("net.minecraft.class_2586", names.runtimeClassName("BlockEntity"))
        assertEquals("net.minecraft.class_2246", names.runtimeClassName("Blocks"))
        // A loadable name is returned untouched (dev classpath has Mojang names).
        assertEquals("net.minecraft.world.level.block.Blocks", names.runtimeClassName("net.minecraft.world.level.block.Blocks"))
    }

    @Test
    fun `unknown names pass through`() {
        assertEquals("com.example.Nope", names.runtimeClassName("com.example.Nope"))
    }
}
