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

/** A PNG-pixel rectangle excluded from comparison (cursor, animation, clock...). */
data class ScreenshotRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0) { "ignore region needs x,y >= 0 and width,height > 0" }
    }

    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px - x < width && py - y < height

    companion object {
        /** `x,y,w,h` in PNG pixels. */
        fun parse(spec: String): ScreenshotRegion {
            val parts = spec.split(',').map { it.trim().toIntOrNull() }
            require(parts.size == 4 && parts.all { it != null }) { "ignore region must be x,y,w,h integers, got: $spec" }
            return ScreenshotRegion(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
        }
    }
}

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
    val ignoredPixels: Long? = null,
    val ignore: List<ScreenshotRegion> = emptyList(),
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
        addProperty("ignoredPixels", ignoredPixels)
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
        if (ignore.isNotEmpty()) add("ignore", com.google.gson.JsonArray().apply {
            ignore.forEach { r -> add(com.google.gson.JsonArray().apply { add(r.x); add(r.y); add(r.width); add(r.height) }) }
        })
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
        ignore: List<ScreenshotRegion> = emptyList(),
    ): ScreenshotComparisonResult {
        validateThresholds(channelTolerance, maxDifferentRatio)
        val inputs = listOf(expected.toRealPath(), actual.toRealPath())
        val output = diff?.let { safeOutput(it, inputs) }
        return compareImages(PngCodec.read(inputs[0]), PngCodec.read(inputs[1]), channelTolerance, maxDifferentRatio, output, inputs, ignore)
    }

    /** For remote inline screenshots: the local baseline path remains protected against diff aliases. */
    fun compare(
        expected: Path,
        actualPng: ByteArray,
        channelTolerance: Int = 0,
        maxDifferentRatio: Double = 0.0,
        diff: Path? = null,
        ignore: List<ScreenshotRegion> = emptyList(),
    ): ScreenshotComparisonResult {
        validateThresholds(channelTolerance, maxDifferentRatio)
        val inputs = listOf(expected.toRealPath())
        val output = diff?.let { safeOutput(it, inputs) }
        return compareImages(PngCodec.read(inputs[0]), PngCodec.decode(actualPng), channelTolerance, maxDifferentRatio, output, inputs, ignore)
    }

    /** Bytes have no filesystem identity. Prefer a Path overload when a baseline file must be protected. */
    fun compare(
        expectedPng: ByteArray,
        actualPng: ByteArray,
        channelTolerance: Int = 0,
        maxDifferentRatio: Double = 0.0,
        diff: Path? = null,
        ignore: List<ScreenshotRegion> = emptyList(),
    ): ScreenshotComparisonResult {
        validateThresholds(channelTolerance, maxDifferentRatio)
        val output = diff?.let { safeOutput(it, emptyList()) }
        return compareImages(PngCodec.decode(expectedPng), PngCodec.decode(actualPng), channelTolerance, maxDifferentRatio, output, emptyList(), ignore)
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
        ignore: List<ScreenshotRegion>,
    ): ScreenshotComparisonResult {
        if (expected.width != actual.width || expected.height != actual.height) {
            // Even a 100% allowance cannot make unequal dimensions match. No large union canvas is allocated.
            return ScreenshotComparisonResult(false, expected.width, expected.height, actual.width, actual.height,
                null, null, null, null, tolerance, allowedRatio, null, null, ignore)
        }
        val total = expected.width.toLong() * expected.height
        var different = 0L
        var ignored = 0L
        var maxDifference = 0
        var left = expected.width
        var top = expected.height
        var right = -1
        var bottom = -1
        val diffPixels = output?.let { ByteArray(expected.rgba.size) }
        for (pixel in 0 until total.toInt()) {
            val offset = pixel * 4
            if (ignore.isNotEmpty() && ignore.any { it.contains(pixel % expected.width, pixel / expected.width) }) {
                ignored++
                if (diffPixels != null) {
                    // Ignored areas are drawn blue so a reviewer sees what the mask hid.
                    diffPixels[offset] = 0
                    diffPixels[offset + 1] = 0
                    diffPixels[offset + 2] = 160.toByte()
                    diffPixels[offset + 3] = 255.toByte()
                }
                continue
            }
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
        val compared = total - ignored
        val ratio = if (compared == 0L) 0.0 else different.toDouble() / compared
        val bounds = if (different == 0L) null else ScreenshotDifferenceBounds(left, top, right - left + 1, bottom - top + 1)
        if (output != null && diffPixels != null) {
            writeDiff(output, PngCodec.encode(PngCodec.Image(expected.width, expected.height, diffPixels)), inputs)
        }
        return ScreenshotComparisonResult(ratio <= allowedRatio, expected.width, expected.height, actual.width, actual.height,
            different, ratio, maxDifference, bounds, tolerance, allowedRatio, output, ignored, ignore)
    }

    internal fun safeOutput(diff: Path, inputs: List<Path>): Path {
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
