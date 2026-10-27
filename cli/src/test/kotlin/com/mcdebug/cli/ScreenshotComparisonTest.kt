package com.mcdebug.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ScreenshotComparisonTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `identical pixels match regardless of RGB versus RGBA encoding and filters`() {
        val rgb = PngTestFixtures.rgba(11, 22, 33, 44, 55, 66)
        val rgba = PngTestFixtures.rgba(11, 22, 33, 255, 44, 55, 66, 255)
        val expected = PngTestFixtures.filtered(2, 1, 3, rgb, listOf(4))
        val actual = PngTestFixtures.encoded(2, 1, rgba)
        val result = ScreenshotComparison.compare(expected, actual)
        assertTrue(result.matches)
        assertTrue(result.sizeMatches)
        assertEquals(0L, result.differentPixels)
        assertEquals(0.0, result.differentRatio)
        assertEquals(0, result.maxChannelDifference)
        assertNull(result.bounds)
        assertNull(result.diff)
        assertSame(result, result.requireMatch())
        val json = result.toJson()
        assertEquals("matched", json["reason"].asString)
        assertEquals(2, json.getAsJsonObject("expected")["width"].asInt)
        assertEquals(1, json.getAsJsonObject("actual")["height"].asInt)
        assertEquals(2L, json["totalPixels"].asLong)
        assertTrue(json["bounds"].isJsonNull)
    }

    @Test
    fun `tolerance boundary is inclusive and ratio boundary permits exactly allowed pixels`() {
        val baseline = PngTestFixtures.rgba(10, 20, 30, 40, 50, 60, 70, 80)
        val modified = baseline.copyOf().apply { this[0] = 15; this[7] = 86 }
        val expected = PngTestFixtures.encoded(2, 1, baseline)
        val actual = PngTestFixtures.encoded(2, 1, modified)
        val exact = ScreenshotComparison.compare(expected, actual)
        assertFalse(exact.matches)
        assertEquals(2L, exact.differentPixels)
        assertEquals(6, exact.maxChannelDifference)
        val tolerated = ScreenshotComparison.compare(expected, actual, channelTolerance = 5, maxDifferentRatio = 0.5)
        assertTrue(tolerated.matches)
        assertEquals(1L, tolerated.differentPixels)
        assertEquals(0.5, tolerated.differentRatio)
        assertEquals(6, tolerated.maxChannelDifference)
        assertEquals(ScreenshotDifferenceBounds(1, 0, 1, 1), tolerated.bounds)
        assertFalse(ScreenshotComparison.compare(expected, actual, 5, Math.nextDown(0.5)).matches)
        assertTrue(ScreenshotComparison.compare(expected, actual, 6).matches)
        assertTrue(ScreenshotComparison.compare(expected, actual, maxDifferentRatio = 1.0).matches)
        assertTrue(ScreenshotComparison.compare(expected, actual, channelTolerance = 255).matches)
    }

    @Test
    fun `alpha and RGB hidden behind zero alpha are compared as raw channels`() {
        val baseline = PngTestFixtures.encoded(rgba = PngTestFixtures.rgba(7, 8, 9, 0))
        for (channel in 0..3) {
            val pixels = PngTestFixtures.rgba(7, 8, 9, 0).apply { this[channel] = (this[channel] + 1).toByte() }
            val result = ScreenshotComparison.compare(baseline, PngTestFixtures.encoded(rgba = pixels))
            assertFalse(result.matches, "channel=$channel")
            assertEquals(1L, result.differentPixels)
            assertEquals(1, result.maxChannelDifference)
        }
        val extremes = ScreenshotComparison.compare(
            PngTestFixtures.encoded(rgba = PngTestFixtures.rgba(0, 0, 0, 0)),
            PngTestFixtures.encoded(rgba = PngTestFixtures.rgba(255, 255, 255, 255)),
        )
        assertEquals(255, extremes.maxChannelDifference)
    }

    @Test
    fun `bounds include all and only pixels above tolerance`() {
        val baseline = ByteArray(4 * 3 * 4)
        val pixels = baseline.copyOf().apply {
            this[(1 * 4 + 1) * 4] = 20
            this[(2 * 4 + 3) * 4 + 3] = 30
            this[0] = 5 // Within tolerance; must not extend bounds.
        }
        val result = ScreenshotComparison.compare(PngTestFixtures.encoded(4, 3, baseline), PngTestFixtures.encoded(4, 3, pixels), 5)
        assertEquals(2L, result.differentPixels)
        assertEquals(2.0 / 12.0, result.differentRatio)
        assertEquals(30, result.maxChannelDifference)
        assertEquals(ScreenshotDifferenceBounds(1, 1, 3, 2), result.bounds)
        val bounds = result.toJson().getAsJsonObject("bounds")
        assertEquals(1, bounds["x"].asInt)
        assertEquals(1, bounds["y"].asInt)
        assertEquals(3, bounds["width"].asInt)
        assertEquals(2, bounds["height"].asInt)
    }

    @Test
    fun `size mismatch always fails with null pixel statistics and no diff allocation or write`() {
        val output = temp.resolve("existing-diff.png")
        val sentinel = byteArrayOf(42)
        Files.write(output, sentinel)
        val result = ScreenshotComparison.compare(PngTestFixtures.encoded(),
            PngTestFixtures.encoded(2, 1, ByteArray(8)), channelTolerance = 255, maxDifferentRatio = 1.0, diff = output)
        assertFalse(result.matches)
        assertFalse(result.sizeMatches)
        assertEquals(1, result.expectedWidth)
        assertEquals(1, result.expectedHeight)
        assertEquals(2, result.actualWidth)
        assertEquals(1, result.actualHeight)
        assertNull(result.differentPixels)
        assertNull(result.differentRatio)
        assertNull(result.maxChannelDifference)
        assertNull(result.bounds)
        assertNull(result.diff)
        val json = result.toJson()
        assertEquals("size_mismatch", json["reason"].asString)
        for (key in listOf("totalPixels", "differentPixels", "differentRatio", "maxChannelDifference", "bounds", "diff")) {
            assertTrue(json[key].isJsonNull, key)
        }
        assertArrayEquals(sentinel, Files.readAllBytes(output))
        assertThrows(AssertionError::class.java) { result.requireMatch() }
    }

    @Test
    fun `thresholds must be finite and in range before opening files`() {
        val png = PngTestFixtures.encoded()
        for (tolerance in listOf(-1, 256, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ScreenshotComparison.compare(png, png, tolerance) }
        }
        for (ratio in listOf(-0.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { ScreenshotComparison.compare(png, png, maxDifferentRatio = ratio) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenshotComparison.compare(temp.resolve("absent.png"), temp.resolve("also-absent.png"), channelTolerance = 256)
        }
    }

    @Test
    fun `diff is valid visible RGBA PNG with red differences and dimmed unchanged pixels`() {
        val expected = PngTestFixtures.encoded(2, 1, PngTestFixtures.rgba(100, 80, 60, 0, 8, 16, 24, 0))
        val actual = PngTestFixtures.encoded(2, 1, PngTestFixtures.rgba(100, 80, 60, 0, 8, 16, 25, 0))
        val output = temp.resolve("diff with space.png")
        Files.write(output, byteArrayOf(99)) // Existing unrelated output may be atomically replaced.
        val result = ScreenshotComparison.compare(expected, actual, diff = output)
        assertFalse(result.matches)
        assertEquals(output.toRealPath(), result.diff)
        val diff = PngCodec.read(output)
        assertEquals(2, diff.width)
        assertEquals(1, diff.height)
        assertArrayEquals(PngTestFixtures.rgba(25, 20, 15, 255, 255, 0, 0, 255), diff.rgba)
        assertEquals(output.toRealPath().toString(), result.toJson()["diff"].asString)
        Files.list(temp).use { files -> assertFalse(files.anyMatch { it.fileName.toString().startsWith(".mcdebug-diff-") }) }
        // The byte-array overload does not mutate caller buffers either.
        assertArrayEquals(PngTestFixtures.encoded(2, 1, PngTestFixtures.rgba(100, 80, 60, 0, 8, 16, 24, 0)), expected)
    }

    @Test
    fun `path and inline overloads leave both inputs byte-identical`() {
        val baseline = PngTestFixtures.encoded()
        val capture = PngTestFixtures.encoded(rgba = PngTestFixtures.rgba(200, 2, 3, 255))
        val expected = Files.write(temp.resolve("golden.png"), baseline)
        val actual = Files.write(temp.resolve("actual.png"), capture)
        assertFalse(ScreenshotComparison.compare(expected, actual, diff = temp.resolve("local.png")).matches)
        assertFalse(ScreenshotComparison.compare(expected, capture, diff = temp.resolve("inline.png")).matches)
        assertArrayEquals(baseline, Files.readAllBytes(expected))
        assertArrayEquals(capture, Files.readAllBytes(actual))
        val error = assertThrows(AssertionError::class.java) { ScreenshotComparison.compare(expected, actual).requireMatch() }
        assertTrue(error.message!!.contains("pixel_difference"))
        assertTrue(error.message!!.contains("differentPixels"))
    }

    @Test
    fun `diff cannot overwrite lexical normalized real symlink or hardlink input aliases`() {
        val baseline = PngTestFixtures.encoded()
        val expected = Files.write(temp.resolve("golden.png"), baseline)
        val actual = Files.write(temp.resolve("actual.png"), baseline)
        val symlink = Files.createSymbolicLink(temp.resolve("golden-link.png"), expected.fileName)
        val hardlink = Files.createLink(temp.resolve("actual-hardlink.png"), actual)
        for (output in listOf(expected, actual, temp.resolve("not-created/../golden.png"), symlink, hardlink)) {
            assertThrows(IllegalArgumentException::class.java) { ScreenshotComparison.compare(expected, actual, diff = output) }
        }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotComparison.compare(symlink, actual, diff = expected) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotComparison.compare(expected, baseline, diff = symlink) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotComparison.compare(actual, baseline, diff = hardlink) }
        assertArrayEquals(baseline, Files.readAllBytes(expected))
        assertArrayEquals(baseline, Files.readAllBytes(actual))
        assertTrue(Files.isSymbolicLink(symlink))
        assertTrue(Files.isSameFile(actual, hardlink))
    }

    @Test
    fun `symlinked output parent is resolved and cannot hide baseline alias`() {
        val directory = Files.createDirectory(temp.resolve("real"))
        val expected = Files.write(directory.resolve("golden.png"), PngTestFixtures.encoded())
        val actual = Files.write(temp.resolve("actual.png"), PngTestFixtures.encoded())
        val alias = Files.createSymbolicLink(temp.resolve("directory-link"), directory.fileName)
        assertThrows(IllegalArgumentException::class.java) {
            ScreenshotComparison.compare(expected, actual, diff = alias.resolve("golden.png"))
        }
        val result = ScreenshotComparison.compare(expected, actual, diff = alias.resolve("diff.png"))
        assertTrue(result.matches)
        assertEquals(directory.resolve("diff.png"), result.diff)
        val unrelated = Files.write(temp.resolve("unrelated.png"), byteArrayOf(5))
        val outputSymlink = Files.createSymbolicLink(temp.resolve("output-link.png"), unrelated.fileName)
        assertThrows(IllegalArgumentException::class.java) {
            ScreenshotComparison.compare(PngTestFixtures.encoded(), PngTestFixtures.encoded(), diff = outputSymlink)
        }
        assertArrayEquals(byteArrayOf(5), Files.readAllBytes(unrelated))
    }

    @Test
    fun `invalid PNG never writes diff or creates a baseline`() {
        val expected = Files.write(temp.resolve("golden.png"), PngTestFixtures.encoded())
        val output = temp.resolve("diff.png")
        assertThrows(IllegalArgumentException::class.java) { ScreenshotComparison.compare(expected, byteArrayOf(0), diff = output) }
        assertFalse(Files.exists(output))
        assertThrows(java.io.IOException::class.java) {
            ScreenshotComparison.compare(temp.resolve("missing-golden.png"), PngTestFixtures.encoded())
        }
        assertFalse(Files.exists(temp.resolve("missing-golden.png")))
        assertThrows(IllegalArgumentException::class.java) {
            ScreenshotComparison.compare(expected, PngTestFixtures.encoded(), diff = temp)
        }
    }
}
