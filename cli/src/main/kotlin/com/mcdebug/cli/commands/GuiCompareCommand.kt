package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.google.gson.JsonObject
import java.io.IOException
import java.nio.file.Path

/** This command intentionally never resolves a session, creates DebugApi or opens an RPC connection. */
class GuiCompareCmd : CliktCommand(
    name = "compare",
    help = "compare two local PNG golden screenshots without MC/RPC; mismatch or invalid input exits 1",
    epilog = """
**Example:**
`mcdebug gui compare --expected golden.png --actual shot.png --diff diff.png`

- RGB8/RGBA8 non-interlaced PNG only; raw RGBA channels include alpha and hidden RGB.
- A pixel differs when its largest channel delta exceeds --channel-tolerance; the allowed pixel ratio is inclusive.
- Different dimensions always fail, without resizing; pixel statistics and diff are null for size mismatch.
- --diff writes an atomic RGBA8 visualization (red differences, dimmed expected elsewhere).
  Its parent directory must exist; input aliases and output symlinks are rejected.
- No automatic baseline update (没有自动更新baseline). Pixel equality is not a cross-GPU guarantee (像素非跨GPU保证).
  Fix GUI scale, resolution, render mode, cursor and animation state in the consumer test.
""".trimIndent(),
) {
    private val expected by option("--expected", metavar = "PATH", help = "existing local golden PNG (never modified)").required()
    private val actual by option("--actual", metavar = "PATH", help = "existing local captured PNG (never modified)").required()
    private val diff by option("--diff", metavar = "PATH", help = "optional local diff PNG; must not alias either input")
    private val channelTolerance by option("--channel-tolerance", help = "inclusive per-channel tolerance, 0..255 (default 0)").int().default(0)
    private val maxDifferentRatio by option("--max-different-ratio", help = "allowed different pixel ratio, finite 0..1 (default 0)").double().default(0.0)

    override fun run() {
        val result = try {
            ScreenshotComparison.compare(Path.of(expected), Path.of(actual), channelTolerance, maxDifferentRatio, diff?.let { Path.of(it) })
        } catch (e: IllegalArgumentException) {
            invalidInput(e)
        } catch (e: IOException) {
            invalidInput(e)
        } catch (e: SecurityException) {
            invalidInput(e)
        }
        printJson(result.toJson())
        if (!result.matches) throw CliExit(1, "Screenshot comparison failed (${if (result.sizeMatches) "pixel_difference" else "size_mismatch"})")
    }

    private fun invalidInput(error: Exception): Nothing {
        val message = error.message ?: "Invalid PNG comparison input"
        printJson(JsonObject().apply {
            addProperty("matches", false)
            addProperty("reason", "invalid_input")
            addProperty("error", message)
        })
        throw CliExit(1, message)
    }
}
