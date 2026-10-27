package com.mcdebug.cli

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Difference rectangle in PNG pixels; x/y inclusive, width/height extents. */
data class ScreenshotDifferenceBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** Pixel statistics are null on size mismatch: no invented padding/resizing/union-canvas statistics. */
data class ScreenshotComparisonResult(
    val matches: Boolean,
    val expectedWidth: Int,
    val expectedHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int,
    val differentPixels: Long?,
    val differentRatio: Double?,
    val maxChannelDifference: Int?,
    val bounds: ScreenshotDifferenceBounds?,
    val channelTolerance: Int,
    val maxDifferentRatio: Double,
    val diff: Path?,
) {
    val sizeMatches: Boolean get() = expectedWidth == actualWidth && expectedHeight == actualHeight

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("matches", matches)
        addProperty("sizeMatches", sizeMatches)
        addProperty("reason", if (!sizeMatches) "size_mismatch" else if (matches) "matched" else "pixel_difference")
        add("expected", JsonObject().apply { addProperty("width", expectedWidth); addProperty("height", expectedHeight) })
        add("actual", JsonObject().apply { addProperty("width", actualWidth); addProperty("height", actualHeight) })
        if (sizeMatches) addProperty("totalPixels", expectedWidth.toLong() * expectedHeight) else add("totalPixels", JsonNull.INSTANCE)
        addProperty("differentPixels", differentPixels)
        addProperty("differentRatio", differentRatio)
        addProperty("maxChannelDifference", maxChannelDifference)
        add("bounds", bounds?.let { b ->
            JsonObject().apply {
                addProperty("x", b.x); addProperty("y", b.y)
                addProperty("width", b.width); addProperty("height", b.height)
            }
        } ?: JsonNull.INSTANCE)
        addProperty("channelTolerance", channelTolerance)
        addProperty("maxDifferentRatio", maxDifferentRatio)
        addProperty("diff", diff?.toString())
    }

    /** Never accepts or replaces a golden baseline. */
    fun requireMatch(): ScreenshotComparisonResult {
        if (!matches) throw AssertionError("Screenshot comparison failed: ${toJson()}")
        return this
    }
}

/** Pure local RGBA comparison; no MC, GL, AWT, ImageIO, resizing or automatic baseline updates. */
object ScreenshotComparison {
    fun compare(
        expected: Path,
        actual: Path,
        channelTolerance: Int = 0,
        maxDifferentRatio: Double = 0.0,
        diff: Path? = null,
    ): ScreenshotComparisonResult {
        validateThresholds(channelTolerance, maxDifferentRatio)
        val inputs = listOf(expected.toRealPath(), actual.toRealPath())
        val output = diff?.let { safeOutput(it, inputs) }
        return compareImages(PngCodec.read(inputs[0]), PngCodec.read(inputs[1]), channelTolerance, maxDifferentRatio, output, inputs)
    }

    /** For remote inline screenshots: the local baseline path remains protected against diff aliases. */
    fun compare(
        expected: Path,
        actualPng: ByteArray,
        channelTolerance: Int = 0,
        maxDifferentRatio: Double = 0.0,
        diff: Path? = null,
    ): ScreenshotComparisonResult {
        validateThresholds(channelTolerance, maxDifferentRatio)
        val inputs = listOf(expected.toRealPath())
        val output = diff?.let { safeOutput(it, inputs) }
        return compareImages(PngCodec.read(inputs[0]), PngCodec.decode(actualPng), channelTolerance, maxDifferentRatio, output, inputs)
    }

    /** Bytes have no filesystem identity. Prefer a Path overload when a baseline file must be protected. */
    fun compare(
        expectedPng: ByteArray,
        actualPng: ByteArray,
        channelTolerance: Int = 0,
        maxDifferentRatio: Double = 0.0,
        diff: Path? = null,
    ): ScreenshotComparisonResult {
        validateThresholds(channelTolerance, maxDifferentRatio)
        val output = diff?.let { safeOutput(it, emptyList()) }
        return compareImages(PngCodec.decode(expectedPng), PngCodec.decode(actualPng), channelTolerance, maxDifferentRatio, output, emptyList())
    }

    private fun validateThresholds(channelTolerance: Int, maxDifferentRatio: Double) {
        require(channelTolerance in 0..255) { "channelTolerance must be 0..255" }
        require(maxDifferentRatio.isFinite() && maxDifferentRatio in 0.0..1.0) { "maxDifferentRatio must be finite and 0..1" }
    }

    private fun compareImages(
        expected: PngCodec.Image,
        actual: PngCodec.Image,
        tolerance: Int,
        allowedRatio: Double,
        output: Path?,
        inputs: List<Path>,
    ): ScreenshotComparisonResult {
        if (expected.width != actual.width || expected.height != actual.height) {
            // Even a 100% allowance cannot make unequal dimensions match. No large union canvas is allocated.
            return ScreenshotComparisonResult(false, expected.width, expected.height, actual.width, actual.height,
                null, null, null, null, tolerance, allowedRatio, null)
        }
        val total = expected.width.toLong() * expected.height
        var different = 0L
        var maxDifference = 0
        var left = expected.width
        var top = expected.height
        var right = -1
        var bottom = -1
        val diffPixels = output?.let { ByteArray(expected.rgba.size) }
        for (pixel in 0 until total.toInt()) {
            val offset = pixel * 4
            var delta = 0
            for (c in 0..3) {
                delta = max(delta, abs((expected.rgba[offset + c].toInt() and 255) - (actual.rgba[offset + c].toInt() and 255)))
            }
            maxDifference = max(maxDifference, delta)
            val isDifferent = delta > tolerance
            if (isDifferent) {
                different++
                val x = pixel % expected.width
                val y = pixel / expected.width
                left = min(left, x); top = min(top, y)
                right = max(right, x); bottom = max(bottom, y)
            }
            if (diffPixels != null) {
                if (isDifferent) {
                    diffPixels[offset] = 255.toByte()
                    diffPixels[offset + 1] = 0
                    diffPixels[offset + 2] = 0
                } else {
                    for (c in 0..2) diffPixels[offset + c] = ((expected.rgba[offset + c].toInt() and 255) / 4).toByte()
                }
                diffPixels[offset + 3] = 255.toByte() // Visible even for differences in transparent/hidden RGB.
            }
        }
        val ratio = different.toDouble() / total
        val bounds = if (different == 0L) null else ScreenshotDifferenceBounds(left, top, right - left + 1, bottom - top + 1)
        if (output != null && diffPixels != null) {
            writeDiff(output, PngCodec.encode(PngCodec.Image(expected.width, expected.height, diffPixels)), inputs)
        }
        return ScreenshotComparisonResult(ratio <= allowedRatio, expected.width, expected.height, actual.width, actual.height,
            different, ratio, maxDifference, bounds, tolerance, allowedRatio, output)
    }

    private fun safeOutput(diff: Path, inputs: List<Path>): Path {
        val normalized = diff.toAbsolutePath().normalize()
        require(normalized.fileName != null) { "diff must name a file" }
        // Resolve the parent once; do not follow a final symlink or create directories implicitly.
        val output = normalized.parent.toRealPath().resolve(normalized.fileName)
        require(!Files.isSymbolicLink(output)) { "diff must not be a symbolic link: $diff" }
        require(!Files.exists(output, NOFOLLOW_LINKS) || Files.isRegularFile(output, NOFOLLOW_LINKS)) {
            "diff must be a regular file: $diff"
        }
        for (input in inputs) {
            require(output != input && (!Files.exists(output, NOFOLLOW_LINKS) || !Files.isSameFile(output, input))) {
                "diff must not overwrite an input PNG (including aliases): $diff"
            }
        }
        return output
    }

    private fun writeDiff(output: Path, bytes: ByteArray, inputs: List<Path>) {
        safeOutput(output, inputs)
        val temporary = Files.createTempFile(output.parent, ".mcdebug-diff-", ".png.tmp")
        try {
            Files.write(temporary, bytes)
            safeOutput(output, inputs) // Recheck after encoding/writing; the rename itself never follows a final symlink.
            Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
