package com.mcdebug.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.client.mixin.AbstractContainerScreenAccessor
import com.mcdebug.rpc.RpcEnv
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.Util
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Real client-side pixel capture.
 *
 * `Screenshot.takeScreenshot(renderTarget)` downloads the main render target's
 * colour texture — i.e. the frame that was fully rendered last — into a
 * [NativeImage]. It is called **inline on the client thread**, at a point in the
 * tick where no rendering is in flight, instead of going through
 * `RenderSystem.recordRenderCall` (that API only appends to a queue drained from
 * `flipFrame`, so a capture could be deferred by a frame or never run at all).
 *
 * Only the PNG encode/flush is off-loaded to `Util.ioPool()`.
 */
object Capture {

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val COUNTER = AtomicInteger(0)

    /** Transform of the most recent successful capture, used to map PNG pixels back to GUI units. */
    @Volatile
    var lastTransform: JsonObject? = null
        private set

    @Volatile
    var lastPath: Path? = null
        private set

    /**
     * Capture on the client thread, then encode off-thread.
     *
     * @param mode `window` = whole framebuffer, `gui` = cropped to the open
     *   container screen's GUI rectangle.
     */
    fun screenshot(
        client: Minecraft,
        mode: String,
        inline: Boolean,
        name: String?,
    ): CompletableFuture<JsonElement> =
        ClientTasks.onClient(client) { capture(client, mode) }
            .thenCompose { shot -> encode(shot, inline, name) }

    private class Shot(val image: NativeImage, val transform: JsonObject)

    private fun capture(client: Minecraft, mode: String): Shot {
        val target: RenderTarget = client.mainRenderTarget
        val window = client.window
        val guiScaledWidth = window.guiScaledWidth
        val guiScaledHeight = window.guiScaledHeight
        // GUI units -> framebuffer pixels. MC renders the GUI into a
        // guiScaledWidth x guiScaledHeight space stretched over the whole render target.
        val pixelsPerGui = target.width.toDouble() / guiScaledWidth.toDouble()

        val full: NativeImage = Screenshot.takeScreenshot(target)
        var image = full
        var cropX = 0
        var cropY = 0
        var cropW = target.width
        var cropH = target.height

        if (mode == "gui") {
            val screen = client.screen as? AbstractContainerScreen<*>
                ?: throw RpcException(
                    RpcErrors.SCREEN_NOT_FOUND,
                    "mode=gui needs an open container screen (current screen: ${client.screen?.javaClass?.name ?: "none"})",
                    JsonObject().apply { addProperty("reason", "SCREEN_NOT_FOUND") },
                )
            val accessor = screen as AbstractContainerScreenAccessor
            cropX = (accessor.leftPos * pixelsPerGui).roundToInt().coerceIn(0, target.width)
            cropY = (accessor.topPos * pixelsPerGui).roundToInt().coerceIn(0, target.height)
            cropW = (accessor.imageWidth * pixelsPerGui).roundToInt().coerceIn(1, target.width - cropX)
            cropH = (accessor.imageHeight * pixelsPerGui).roundToInt().coerceIn(1, target.height - cropY)
            val cropped = NativeImage(cropW, cropH, false)
            try {
                full.resizeSubRectTo(cropX, cropY, cropW, cropH, cropped)
            } catch (t: Throwable) {
                cropped.close()
                full.close()
                throw RpcException(RpcErrors.INTERNAL_ERROR, "failed to crop screenshot: ${t.message}", null, t)
            }
            full.close()
            image = cropped
        }

        val transform = JsonObject().apply {
            add("framebuffer", ints(target.width, target.height))
            add("window", ints(window.screenWidth, window.screenHeight))
            addProperty("guiScale", window.guiScale)
            add("guiScaled", ints(guiScaledWidth, guiScaledHeight))
            addProperty("pixelsPerGui", pixelsPerGui)
            add("crop", ints(cropX, cropY, cropW, cropH))
            add("cropInGui", ints(
                (cropX / pixelsPerGui).roundToInt(),
                (cropY / pixelsPerGui).roundToInt(),
                (cropW / pixelsPerGui).roundToInt(),
                (cropH / pixelsPerGui).roundToInt(),
            ))
            add("pngSize", ints(image.width, image.height))
        }
        return Shot(image, transform)
    }

    /**
     * PNG encode + flush on `Util.ioPool()`. The [NativeImage] is closed in every
     * path: success, write failure, and "the task could not even be scheduled".
     */
    private fun encode(
        shot: Shot,
        inline: Boolean,
        name: String?,
    ): CompletableFuture<JsonElement> {
        val future = CompletableFuture<JsonElement>()
        val image = shot.image
        try {
            Util.ioPool().execute {
                var path: Path? = null
                try {
                    path = shotPath(name)
                    val width = image.width
                    val height = image.height
                    image.writeToFile(path)
                    val bytes = Files.readAllBytes(path)
                    val result = JsonObject().apply {
                        addProperty("path", path.toAbsolutePath().toString())
                        addProperty("file", path.fileName.toString())
                        addProperty("relPath", relativeToGameDir(path))
                        addProperty("bytes", bytes.size)
                        addProperty("sha256", sha256(bytes))
                        addProperty("width", width)
                        addProperty("height", height)
                        add("transform", shot.transform)
                        if (inline) addProperty("png", Base64.getEncoder().encodeToString(bytes))
                    }
                    lastPath = path
                    lastTransform = shot.transform
                    future.complete(result)
                } catch (t: Throwable) {
                    future.completeExceptionally(
                        RpcException(
                            RpcErrors.INTERNAL_ERROR,
                            "failed to write screenshot${path?.let { " to $it" } ?: ""}: ${t.message}",
                            null,
                            t,
                        ),
                    )
                } finally {
                    image.close()
                }
            }
        } catch (t: Throwable) {
            image.close()
            future.completeExceptionally(
                RpcException(RpcErrors.INTERNAL_ERROR, "cannot schedule the PNG encode task: ${t.message}", null, t),
            )
        }
        return future
    }

    /** Maps a PNG pixel offset from the last capture back to GUI units. */
    fun pngToGui(x: Double, y: Double): Pair<Double, Double> {
        val transform = lastTransform ?: throw RpcException(
            RpcErrors.INVALID_PARAMS,
            "--png coordinates need a previous gui.screenshot (no capture transform known yet)",
            JsonObject().apply { addProperty("reason", "NO_SCREENSHOT") },
        )
        val scale = transform.get("pixelsPerGui").asDouble
        val crop = transform.getAsJsonArray("crop")
        return Pair(
            (x + crop[0].asDouble) / scale,
            (y + crop[1].asDouble) / scale,
        )
    }

    private fun shotPath(name: String?): Path {
        val dir = RpcEnv.gameDir().resolve("mcdebug").resolve("screenshots")
        Files.createDirectories(dir)
        val file = if (name.isNullOrBlank()) {
            "mcdebug-${LocalDateTime.now().format(STAMP)}-${COUNTER.incrementAndGet()}.png"
        } else {
            val slug = name.trim()
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('.', '_')
                .take(120)
                .ifEmpty { "shot" }
            val base = if (slug.endsWith(".png")) slug else "$slug.png"
            "$base"
        }
        return dir.resolve(file)
    }

    private fun relativeToGameDir(path: Path): String = try {
        RpcEnv.gameDir().toAbsolutePath().relativize(path.toAbsolutePath()).toString()
    } catch (t: Throwable) {
        path.toString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ints(vararg values: Int): JsonArray = JsonArray().apply { values.forEach { add(it) } }
}
