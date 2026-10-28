package com.mcdebug.cli

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

// System.out capture is process-global; no other parallel test may print or replace it meanwhile.
@Isolated
class GuiCompareCommandTest {
    @TempDir lateinit var temp: Path

    private fun tree(): McDebugCli = McDebugCli().subcommands(GuiCommands().subcommands(GuiCompareCmd()))

    private fun inputs(): List<String> {
        val png = PngTestFixtures.encoded()
        return listOf("--expected", Files.write(temp.resolve("golden with space.png"), png).toString(),
            "--actual", Files.write(temp.resolve("actual.png"), png).toString())
    }

    @Test
    fun `compare is local even with nonexistent session and never resolves registry or RPC`() {
        val registry = temp.resolve("must-not-be-created")
        val output = capture {
            tree().parse(listOf("--session", "not-a-session", "--sessions-dir", registry.toString(), "gui", "compare") + inputs())
        }
        val json = json(output)
        assertTrue(json["matches"].asBoolean)
        assertEquals(0L, json["differentPixels"].asLong)
        assertFalse(Files.exists(registry))
        assertNull(batchClient)
        assertNull(batchClientPlane)
    }

    @Test
    fun `command also works standalone without any RPC root`() {
        val output = capture { GuiCompareCmd().parse(inputs()) }
        assertTrue(json(output)["matches"].asBoolean)
    }

    @Test
    fun `mismatch prints comparison JSON then raises local exit one`() {
        val flags = inputs()
        Files.write(temp.resolve("actual.png"), PngTestFixtures.encoded(rgba = PngTestFixtures.rgba(6, 2, 3, 255)))
        val output = capture {
            val error = assertThrows(CliExit::class.java) { GuiCompareCmd().parse(flags) }
            assertEquals(1, error.code)
        }
        val json = json(output)
        assertFalse(json["matches"].asBoolean)
        assertEquals("pixel_difference", json["reason"].asString)
        assertEquals(5, json["maxChannelDifference"].asInt)
    }

    @Test
    fun `threshold flags diff path and dimensions have expected local semantics`() {
        val flags = inputs()
        Files.write(temp.resolve("actual.png"), PngTestFixtures.encoded(rgba = PngTestFixtures.rgba(6, 2, 3, 255)))
        val diff = temp.resolve("diff.png")
        val tolerated = json(capture { GuiCompareCmd().parse(flags + listOf("--channel-tolerance", "5", "--diff", diff.toString())) })
        assertTrue(tolerated["matches"].asBoolean)
        assertEquals(0L, tolerated["differentPixels"].asLong)
        assertEquals(1, PngCodec.read(diff).width)
        val allowed = json(capture { GuiCompareCmd().parse(flags + listOf("--max-different-ratio", "1.0")) })
        assertTrue(allowed["matches"].asBoolean)
        Files.write(temp.resolve("actual.png"), PngTestFixtures.encoded(2, 1, ByteArray(8)))
        val mismatch = json(capture {
            assertEquals(1, assertThrows(CliExit::class.java) {
                GuiCompareCmd().parse(flags + listOf("--max-different-ratio", "1.0"))
            }.code)
        })
        assertEquals("size_mismatch", mismatch["reason"].asString)
        assertTrue(mismatch["differentPixels"].isJsonNull)
    }

    @Test
    fun `invalid PNG path threshold and baseline alias failures are local exit one with JSON`() {
        val flags = inputs()
        for (extra in listOf(listOf("--channel-tolerance", "-1"), listOf("--channel-tolerance", "256"),
            listOf("--max-different-ratio", "-0.01"), listOf("--max-different-ratio", "1.01"),
            listOf("--max-different-ratio", "NaN"), listOf("--max-different-ratio", "Infinity"),
            listOf("--diff", flags[1]))) {
            val output = capture {
                val error = assertThrows(CliExit::class.java) { GuiCompareCmd().parse(flags + extra) }
                assertEquals(1, error.code)
            }
            assertEquals("invalid_input", json(output)["reason"].asString)
        }
        Files.write(temp.resolve("actual.png"), byteArrayOf(0))
        val invalid = capture { assertEquals(1, assertThrows(CliExit::class.java) { GuiCompareCmd().parse(flags) }.code) }
        assertEquals("invalid_input", json(invalid)["reason"].asString)
        val missing = capture {
            assertEquals(1, assertThrows(CliExit::class.java) {
                GuiCompareCmd().parse(listOf("--expected", temp.resolve("missing.png").toString(), "--actual", flags[3]))
            }.code)
        }
        assertEquals("invalid_input", json(missing)["reason"].asString)
        assertFalse(Files.exists(temp.resolve("missing.png")))
    }

    @Test
    fun `help describes example baseline policy GPU caveat and no MC requirement without exiting worker`() {
        val request = assertThrows(PrintHelpMessage::class.java) {
            tree().parse(listOf("--session", "not-a-session", "gui", "compare", "--help"))
        }
        val command = checkNotNull(request.context).command
        assertEquals("compare", command.commandName)
        val help = checkNotNull(command.getFormattedHelp())
        for (text in listOf("Example:", "mcdebug gui compare", "baseline", "GPU", "--expected", "--actual", "--diff",
            "--channel-tolerance", "--max-different-ratio", "MC/RPC")) {
            assertTrue(help.contains(text), "$text missing from $help")
        }
    }

    @Test
    fun `required paths and numeric syntax are parsed without invoking command main`() {
        assertThrows(UsageError::class.java) { GuiCompareCmd().parse(emptyList()) }
        assertThrows(UsageError::class.java) { GuiCompareCmd().parse(listOf("--expected", "golden.png")) }
        assertThrows(UsageError::class.java) { GuiCompareCmd().parse(inputs() + listOf("--channel-tolerance", "not-int")) }
        assertThrows(UsageError::class.java) { GuiCompareCmd().parse(inputs() + listOf("--max-different-ratio", "not-number")) }
    }

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private fun capture(block: () -> Unit): String {
        val previous = System.out
        val buffer = ByteArrayOutputStream()
        try {
            PrintStream(buffer, true, Charsets.UTF_8).use { stream ->
                System.setOut(stream)
                block()
            }
        } finally {
            System.setOut(previous)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}

@Isolated
class GuiCompareReviewCommandTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `accept on a missing golden creates it and exits zero`() {
        val actual = Files.write(temp.resolve("actual.png"), PngTestFixtures.encoded())
        val golden = temp.resolve("golden.png")
        val out = captureOut { GuiCompareCmd().parse(listOf("--expected", golden.toString(), "--actual", actual.toString(), "--accept")) }
        assertEquals("created", JsonParser.parseString(out).asJsonObject["accepted"].asString)
        assertTrue(Files.exists(golden))
    }

    @Test
    fun `report with mismatch writes page and diff and still exits one without accept`() {
        val expected = Files.write(temp.resolve("e.png"), PngTestFixtures.encoded())
        val actual = Files.write(temp.resolve("a.png"), PngTestFixtures.encoded(rgba = PngTestFixtures.rgba(9, 9, 9, 255)))
        val report = temp.resolve("r.html")
        val args = listOf("--expected", expected.toString(), "--actual", actual.toString(), "--report", report.toString())
        val out = captureOut { assertEquals(1, assertThrows(CliExit::class.java) { GuiCompareCmd().parse(args) }.code) }
        assertTrue(Files.exists(report))
        assertTrue(Files.exists(temp.resolve("r.html.diff.png")))
        assertEquals(report.toString(), JsonParser.parseString(out).asJsonObject["report"].asString)
        val accepted = captureOut { GuiCompareCmd().parse(args + "--accept") }
        assertEquals("replaced", JsonParser.parseString(accepted).asJsonObject["accepted"].asString)
        assertArrayEquals(Files.readAllBytes(actual), Files.readAllBytes(expected))
    }

    @Test
    fun `ignore masks the only differing pixel`() {
        val expected = Files.write(temp.resolve("e.png"), PngTestFixtures.encoded())
        val actual = Files.write(temp.resolve("a.png"), PngTestFixtures.encoded(rgba = PngTestFixtures.rgba(9, 9, 9, 255)))
        val out = captureOut { GuiCompareCmd().parse(listOf("--expected", expected.toString(), "--actual", actual.toString(), "--ignore", "0,0,1,1")) }
        assertTrue(JsonParser.parseString(out).asJsonObject["matches"].asBoolean)
    }

    private fun captureOut(block: () -> Unit): String {
        val previous = System.out
        val buffer = ByteArrayOutputStream()
        try {
            PrintStream(buffer, true, Charsets.UTF_8).use { stream ->
                System.setOut(stream)
                block()
            }
        } finally {
            System.setOut(previous)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
