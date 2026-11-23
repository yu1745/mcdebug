package com.mcdebug.cli

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `mcdebug jar` embeds one jar per `<mc, loader>` (`META-INF/mcdebug/<mc>/<loader>.jar`). The test
 * classpath carries a single fake `1.20.1/forge.jar`, so the 1.20.1 pair is "embedded" here and
 * every other combination exercises the "not embedded" path.
 */
class JarCmdTest {
    @TempDir lateinit var temp: Path

    private val fakeJar = EmbeddedMod(ModJarMc.MC_1_20_1, ModJarLoader.FORGE)

    @Test
    fun `every jar name scheme is recognised as a stale mcdebug jar`() {
        val stale = listOf(
            "mcdebug-0.8.0.jar", "mcdebug-0.6.3.jar", "mcdebug-1.0.jar",
            "mcdebug-neoforge-0.8.0.jar",
            "mcdebug-forge-0.8.0.jar",
            "mcdebug-fabric-26.3-0.8.0.jar", "mcdebug-neoforge-1.21.1-0.8.0.jar", "mcdebug-forge-1.20.1-0.8.0.jar",
        )
        val kept = listOf(
            "mcdebug-0.8.0-sources.jar", "mcdebug-fabric-26.3-0.8.0-sources.jar",
            "mcdebug-config-1.0.jar", "other-mod-0.8.0.jar", "somemod-mcdebug-1.0.jar", "mcdebug-cli.jar",
        )
        stale.forEach { assertTrue(isMcDebugModJar(it), it) }
        kept.forEach { assertFalse(isMcDebugModJar(it), it) }
    }

    @Test
    fun `written file names carry loader and minecraft line so the five jars never clash`() {
        val names = EmbeddedMod.ALL.map { it.fileName("1.2.3") }.toSet()
        assertEquals(5, names.size)
        assertEquals(
            setOf(
                "mcdebug-forge-1.20.1-1.2.3.jar",
                "mcdebug-fabric-1.21.1-1.2.3.jar", "mcdebug-neoforge-1.21.1-1.2.3.jar",
                "mcdebug-fabric-26.3-1.2.3.jar", "mcdebug-neoforge-26.3-1.2.3.jar",
            ),
            names,
        )
        assertEquals("META-INF/mcdebug/26.3/neoforge.jar", EmbeddedMod(ModJarMc.MC_26_3, ModJarLoader.NEOFORGE).resource)
    }

    @Test
    fun `writing into a mods directory removes every other mcdebug jar and keeps unrelated files`() {
        val mods = Files.createDirectories(temp.resolve("run/mods"))
        val stale = listOf("mcdebug-0.8.0.jar", "mcdebug-neoforge-0.8.0.jar", "mcdebug-forge-0.8.0.jar",
            "mcdebug-fabric-26.3-0.8.0.jar").map { Files.writeString(mods.resolve(it), "stale") }
        val kept = listOf("mcdebug-0.8.0-sources.jar", "other-mod-1.0.jar", "mcdebug-cli.jar")
            .map { Files.writeString(mods.resolve(it), "keep") }

        val written = writeEmbeddedModJar(mods, fakeJar, version = "9.9.9")

        assertEquals(mods.resolve("mcdebug-forge-1.20.1-9.9.9.jar"), written.path)
        assertTrue(Files.isRegularFile(written.path))
        assertEquals(64, written.sha256.length)
        assertEquals(stale.toSet(), written.removed.toSet())
        stale.forEach { assertTrue(Files.notExists(it), "$it should be removed") }
        kept.forEach { assertTrue(Files.exists(it), "$it must survive") }
        assertTrue(Files.list(mods).use { files -> files.count() == kept.size + 1L })

        // Writing again (same version) replaces the file and removes nothing.
        val again = writeEmbeddedModJar(mods, fakeJar, version = "9.9.9")
        assertTrue(again.removed.isEmpty())
    }

    @Test
    fun `an explicit file output is written verbatim and nothing else is touched`() {
        val target = temp.resolve("out/renamed.jar")
        val written = writeEmbeddedModJar(target, fakeJar, version = "9.9.9")
        assertEquals(target, written.path)
        assertTrue(written.removed.isEmpty())
        assertEquals(fakeJar.resourceBytes()!!.toList(), Files.readAllBytes(target).toList())
    }

    @Test
    fun `loader without a build for that line and unbuilt combinations are usage errors`() {
        val wrongLoader = assertThrows(UsageError::class.java) {
            writeEmbeddedModJar(null, EmbeddedMod(ModJarMc.MC_1_20_1, ModJarLoader.FABRIC), version = "9.9.9")
        }
        assertTrue(wrongLoader.message!!.contains("only built for: forge"), wrongLoader.message)

        val notBuilt = assertThrows(UsageError::class.java) {
            writeEmbeddedModJar(null, EmbeddedMod(ModJarMc.MC_26_3, ModJarLoader.FABRIC), version = "9.9.9")
        }
        val message = notBuilt.message!!
        assertTrue(message.contains("no embedded Minecraft 26.3 fabric jar"), message)
        assertTrue(message.contains("embedded: 1.20.1/forge"), message)
        assertTrue(message.contains("versions/mc26.3"), message)
        assertTrue(Files.notExists(temp.resolve("mcdebug-fabric-26.3-9.9.9.jar")))
    }
}
