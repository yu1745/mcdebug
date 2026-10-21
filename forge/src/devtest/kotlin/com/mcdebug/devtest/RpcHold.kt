package com.mcdebug.devtest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Dev-only (not part of the shipped jar). The GameTest server ticks unthrottled, so the timeout is effectively infinite: keeps the GameTest server alive until the file named by
 * -Dmcdebug.devtest.flag appears, so the RPC server can be exercised from outside against a real
 * Forge server + world without a dedicated-server EULA.
 */
@GameTestHolder("mcdebug")
object RpcHold {
    @JvmStatic
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 100_000_000)
    fun hold(helper: GameTestHelper) {
        val flag = System.getProperty("mcdebug.devtest.flag") ?: "mcdebug-devtest.done"
        helper.succeedWhen {
            if (!Files.exists(Paths.get(flag))) helper.fail("waiting for $flag")
        }
    }
}
