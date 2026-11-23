package com.mcdebug.client.render

import com.mcdebug.client.ClientTasks
import com.mcdebug.client.bool
import com.mcdebug.client.int
import com.mcdebug.client.reason
import com.mcdebug.client.string

import com.mcdebug.client.input.GuiWidgets

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.McDebug
import com.mcdebug.client.mixin.AbstractContainerScreenAccessor
import com.mcdebug.client.mixin.NativeImageAccessor
import com.mcdebug.rpc.RpcEnv
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.util.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.lwjgl.system.MemoryUtil
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Requests only enter the render queue on the client thread. [afterFrame] must be
 * called after a real GameRenderer.render/GUI flush, before the next clear; ticks
 * and RenderSystem.recordRenderCall are deliberately not frame boundaries.
 *
 * A bounded reservation survives queueing, readback AND encoding. A queued image
 * belongs to this object, a claimed image exclusively to its IO worker. Shutdown
 * can close the former, but must never free memory underneath the latter.
 */
object Capture {
    internal const val REQUEST_TIMEOUT_SECONDS = 30L
    private const val MAX_OUTSTANDING = 8
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val COUNTER = AtomicInteger(0)
    private val lock = Any()

    private enum class Phase { PENDING, CAPTURING, QUEUED, ENCODING }

    // Settlement shares publication's lock: a timer/caller cannot win cancel()
    // between the final isDone check and publishing a successful transform.
    private class CaptureFuture : CompletableFuture<JsonElement>() {
        override fun complete(value: JsonElement): Boolean = synchronized(lock) { super.complete(value) }
        override fun completeExceptionally(ex: Throwable): Boolean = synchronized(lock) { super.completeExceptionally(ex) }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = synchronized(lock) { super.cancel(mayInterruptIfRunning) }
    }

    private class Request(
        val sequence: Long,
        val options: CaptureOptions,
        val deadline: Long,
        val future: CompletableFuture<JsonElement>,
    ) {
        // Guarded by lock; only QUEUED may hold an image here.
        var phase = Phase.PENDING
        var shot: Shot? = null
    }

    private class Shot(
        val image: NativeImage,
        val transform: JsonObject,
        val frameId: Long,
        val clientTick: Long,
        val drawLog: JsonObject?,
        val boxes: List<Annotator.Box>,
    )
    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    /** All fields are frozen at the afterFrame boundary, never read from an IO worker. */
    private class Frame(client: Minecraft, val id: Long) {
        val target: RenderTarget = client.gameRenderer.mainRenderTarget()
        val width = target.width
        val height = target.height
        private val window = client.window
        val windowWidth = window.getScreenWidth()
        val windowHeight = window.getScreenHeight()
        val framebufferWidth = window.getWidth()
        val framebufferHeight = window.getHeight()
        val guiWidth = window.getGuiScaledWidth()
        val guiHeight = window.getGuiScaledHeight()
        val guiScale = window.getGuiScale().toDouble()
        // GameRenderer's ortho bounds use width/guiScale converted to float, NOT
        // Window's ceil(width/guiScale) used for Screen layout and vanilla mouse input.
        private val geometry = CaptureGeometry.renderScale(width, height, framebufferWidth, framebufferHeight, guiScale)
        val renderGuiWidth = geometry.renderWidth
        val renderGuiHeight = geometry.renderHeight
        val scaleX = geometry.x
        val scaleY = geometry.y
        val tick = ClientTasks.currentTick()
        val overlay = client.gui.overlay()?.javaClass?.name
        val screen = client.gui.screen()?.javaClass?.name ?: "none"
        val container = (client.gui.screen() as? AbstractContainerScreen<*>)?.let {
            val a = it as AbstractContainerScreenAccessor
            Rect(a.leftPos, a.topPos, a.imageWidth, a.imageHeight)
        }
    }

    // Queue mutations happen on the client thread (including timeout cleanup).
    // lock also protects worker ownership, publication, and stopping concurrently.
    private val pending = ArrayDeque<Request>()
    private val active = HashSet<Request>()
    // Includes calls still waiting for their initial client-thread hop, so close
    // also cancels those if the event loop stops before executing the hop.
    private val promises = HashSet<CompletableFuture<JsonElement>>()
    private var requestSequence = 0L
    private var frameSequence = 0L
    private var publishedSequence = -1L
    @Volatile private var pendingCount = 0
    @Volatile private var closed = false

    /** Most recent successful capture in request order, not encode-completion order. */
    @Volatile var lastTransform: JsonObject? = null
        private set
    @Volatile var lastPath: Path? = null
        private set

    fun hasPendingRequests(): Boolean = pendingCount != 0

    /** Enqueue only; no framebuffer access occurs until [afterFrame]. */
    fun screenshot(client: Minecraft, options: CaptureOptions): CompletableFuture<JsonElement> {
        val future = CaptureFuture()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_TIMEOUT_SECONDS)
        synchronized(lock) {
            if (closed) {
                future.completeExceptionally(stopping())
                return future
            }
            // Include incoming client hops AND expired/cancelled encoders that
            // still own native memory. Admission itself touches no client state.
            if (promises.size + active.count { it.future.isDone } >= MAX_OUTSTANDING) {
                future.completeExceptionally(queueFull())
                return future
            }
            promises.add(future)
        }
        future.whenComplete { _, error ->
            synchronized(lock) { promises.remove(future) }
            if (error != null && !closed) {
                // Timer/cancellation threads never mutate the client queue or
                // touch an image that an encoder might currently be using.
                try { client.execute { cleanup() } } catch (_: Exception) { /* close drains on stopping */ }
            }
        }
        // A weak reference avoids retaining completed inline PNGs until the timer
        // fires. The active set/client hop retain any genuinely pending request.
        val weak = WeakReference(future)
        try {
            CompletableFuture.delayedExecutor(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute {
                weak.get()?.completeExceptionally(timedOut())
            }
            val waiting = ClientTasks.onClient(client) {
                if (future.isDone) CompletableFuture.completedFuture(Unit) else ClientTasks.awaitTicks(options.delayTicks).also { waiter ->
                    // CompletableFuture cancellation does not propagate through
                    // thenCompose; cancel the actual tick waiter explicitly.
                    future.whenComplete { _, error -> if (error != null) waiter.cancel(false) }
                }
            }.thenCompose { it }
            ClientTasks.thenOnClient(client, waiting) {
                cleanup()
                synchronized(lock) {
                    if (!future.isDone) {
                        when {
                            closed -> future.completeExceptionally(stopping())
                            expired(deadline) -> future.completeExceptionally(timedOut())
                            options.mode != "window" && options.mode != "gui" -> future.completeExceptionally(
                                RpcException(RpcErrors.INVALID_PARAMS, "unknown mode '${options.mode}' (window/gui)"),
                            )
                            active.size >= MAX_OUTSTANDING -> future.completeExceptionally(queueFull())
                            else -> {
                                val request = Request(++requestSequence, options, deadline, future)
                                active.add(request)
                                pending.addLast(request)
                                pendingCount = pending.size
                            }
                        }
                    }
                }
            }.whenComplete { _, error -> if (error != null) future.completeExceptionally(error) }
        } catch (t: Throwable) {
            future.completeExceptionally(t)
        }
        return future
    }

    /**
     * Client thread: the cursor policy of the next captured frame (the oldest
     * pending request decides; requests with another policy wait for a later
     * frame), or null when nothing is pending.
     */
    fun nextFrameCursor(): String? = if (pendingCount == 0) null else synchronized(lock) { pending.firstOrNull()?.options?.cursor }

    /** Client thread: should the next frame (with that cursor policy) record its GuiGraphicsExtractor calls? */
    fun wantsDrawLog(cursor: String?): Boolean =
        cursor != null && synchronized(lock) { pending.any { it.options.drawLog && it.options.cursor == cursor } }

    /** Called on the client/render thread only, after real rendering and its flush. */
    fun afterFrame(client: Minecraft, drawLog: JsonObject? = null, cursor: String = "keep") {
        check(client.isSameThread()) { "Capture.afterFrame must run on the client thread" }
        cleanup()
        val batch = synchronized(lock) {
            if (closed || pending.isEmpty()) return
            // Snapshot first: reentrant requests from future callbacks must wait
            // for another real frame, never piggyback onto this completed one.
            // Only requests whose cursor policy this frame was rendered with.
            val requests = pending.filter { it.options.cursor == cursor }
            if (requests.isEmpty()) return
            pending.removeAll(requests.toSet())
            pendingCount = pending.size
            requests.forEach { it.phase = Phase.CAPTURING }
            requests
        }
        val frame = try {
            Frame(client, ++frameSequence)
        } catch (t: Throwable) {
            batch.forEach { release(it); it.future.completeExceptionally(captureError(t)) }
            return
        }
        // Annotation targets come from the Screen that produced these pixels, on this thread.
        val boxes = if (batch.any { it.options.annotate }) annotationBoxes(client) else emptyList()
        for (request in batch) {
            if (!isLive(request)) {
                release(request)
                continue
            }
            val captured = try {
                capture(frame, request.options, if (request.options.drawLog) drawLog else null,
                    if (request.options.annotate) boxes else emptyList())
            } catch (t: Throwable) {
                release(request)
                request.future.completeExceptionally(if (t is RpcException) t else captureError(t))
                continue
            }
            captured.whenComplete { shot, error ->
                if (error != null) {
                    release(request)
                    request.future.completeExceptionally(captureError(error))
                } else enqueue(request, shot)
            }
        }
    }

    private fun enqueue(request: Request, shot: Shot) {
        val transferred = synchronized(lock) {
            if (closed || request.future.isDone || expired(request.deadline)) false else {
                request.shot = shot
                request.phase = Phase.QUEUED
                true
            }
        }
        if (!transferred) {
            try { shot.image.close() } finally { release(request) }
            isLive(request)
            return
        }
        try {
            Util.ioPool().execute { encode(request) }
        } catch (t: Throwable) {
            val unclaimed = synchronized(lock) {
                if (request.phase == Phase.QUEUED) request.shot.also { request.shot = null } else null
            }
            if (unclaimed != null) {
                try { unclaimed.image.close() } finally { release(request) }
            }
            request.future.completeExceptionally(captureError(t))
        }
    }

    /** CLIENT_STOPPING hook. Running encoders close their own images in finally. */
    fun close() {
        val images = ArrayList<NativeImage>()
        val futures = synchronized(lock) {
            closed = true
            pending.clear()
            pendingCount = 0
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val request = iterator.next()
                if (request.phase == Phase.PENDING || request.phase == Phase.QUEUED) {
                    request.shot?.let { images.add(it.image) }
                    request.shot = null
                    iterator.remove()
                }
                // CAPTURING and ENCODING have exclusive local owners. They notice
                // closed before publishing and release their reservation themselves.
            }
            lastTransform = null
            lastPath = null
            promises.toList().also { promises.clear() }
        }
        futures.forEach { it.completeExceptionally(stopping()) }
        images.forEach { it.close() }
    }

    /** Client-thread cleanup; expired running workers retain their reservation. */
    private fun cleanup() {
        val images = ArrayList<NativeImage>()
        val timeouts = ArrayList<CompletableFuture<JsonElement>>()
        synchronized(lock) {
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val request = iterator.next()
                if (expired(request.deadline) && !request.future.isDone) timeouts.add(request.future)
                if (request.future.isDone || expired(request.deadline) || closed) {
                    if (request.phase == Phase.PENDING || request.phase == Phase.QUEUED) {
                        request.shot?.let { images.add(it.image) }
                        request.shot = null
                        iterator.remove()
                    }
                }
            }
            pending.removeIf { it !in active }
            pendingCount = pending.size
        }
        images.forEach { it.close() }
        timeouts.forEach { it.completeExceptionally(timedOut()) }
    }

    private fun capture(frame: Frame, options: CaptureOptions, drawLog: JsonObject?, boxes: List<Annotator.Box>): CompletableFuture<Shot> {
        val mode = options.mode
        if (frame.width <= 0 || frame.height <= 0 || frame.framebufferWidth <= 0 || frame.framebufferHeight <= 0 ||
            !frame.scaleX.isFinite() || !frame.scaleY.isFinite() || frame.scaleX <= 0 || frame.scaleY <= 0) {
            throw RpcException(RpcErrors.CLIENT_NOT_READY, "framebuffer is not ready", reason("FRAMEBUFFER_NOT_READY"))
        }
        // Validate and clip the complete rectangle BEFORE allocating/readback.
        // Clip both endpoints rather than moving a negative origin but retaining
        // its old width. floor/ceil contain every pixel touched by fractional edges.
        val crop = if (mode == "gui") {
            if (frame.overlay != null) throw RpcException(RpcErrors.CLIENT_NOT_READY,
                "GUI is hidden by ${frame.overlay}; wait for loading/reload to finish", reason("GUI_OBSCURED"))
            // Not "no Screen": there may be a perfectly good non-container Screen (options,
            // video settings). mode=gui crops to a container's GUI rectangle, so say that.
            val gui = frame.container ?: throw RpcException(RpcErrors.SCREEN_NOT_FOUND,
                "mode=gui crops to a container screen's GUI rectangle; the open screen " +
                    "(${frame.screen}) has none — use --mode window (or --mode gui on a chest/furnace screen)",
                reason("NO_CONTAINER_SCREEN"))
            if (gui.width <= 0 || gui.height <= 0) throw RpcException(RpcErrors.INVALID_PARAMS,
                "container has an empty GUI rectangle", reason("EMPTY_GUI_CROP"))
            val clipped = CaptureGeometry.clipGuiRect(frame.width, frame.height, frame.scaleX, frame.scaleY,
                gui.x.toDouble(), gui.y.toDouble(), gui.width.toDouble(), gui.height.toDouble())
                ?: throw RpcException(RpcErrors.INVALID_PARAMS,
                    "container GUI rectangle is outside the framebuffer", reason("EMPTY_GUI_CROP"))
            Rect(clipped.x, clipped.y, clipped.width, clipped.height)
        } else Rect(0, 0, frame.width, frame.height)
        val transform = JsonObject().apply {
            add("framebuffer", ints(frame.width, frame.height))
            add("window", ints(frame.windowWidth, frame.windowHeight))
            add("windowFramebuffer", ints(frame.framebufferWidth, frame.framebufferHeight))
            addProperty("guiScale", frame.guiScale)
            add("guiScaled", ints(frame.guiWidth, frame.guiHeight))
            add("renderGuiSize", doubles(frame.renderGuiWidth, frame.renderGuiHeight))
            addProperty("coordinateSpace", "screen")
            addProperty("pixelsPerGui", frame.scaleX)
            addProperty("pixelsPerGuiX", frame.scaleX)
            addProperty("pixelsPerGuiY", frame.scaleY)
            add("pixelsPerGuiXY", doubles(frame.scaleX, frame.scaleY))
            add("crop", ints(crop.x, crop.y, crop.width, crop.height))
            add("cropInGui", doubles(crop.x / frame.scaleX, crop.y / frame.scaleY,
                crop.width / frame.scaleX, crop.height / frame.scaleY))
            add("pngSize", ints(crop.width, crop.height))
            addProperty("frameId", frame.id)
            addProperty("clientTick", frame.tick)
        }
        return readCrop(frame, crop).thenApply { image ->
            Shot(image, transform, frame.id, frame.tick, drawLog, boxes)
        }
    }

    /** Vanilla's backend-independent GPU readback (OpenGL or Vulkan), followed by an owned crop. */
    private fun readCrop(frame: Frame, crop: Rect): CompletableFuture<NativeImage> {
        val future = CompletableFuture<NativeImage>()
        net.minecraft.client.Screenshot.takeScreenshot(frame.target) { full ->
            var owned: NativeImage? = full
            var cropped: NativeImage? = null
            try {
                if (crop.x == 0 && crop.y == 0 && crop.width == frame.width && crop.height == frame.height) {
                    if (future.complete(full)) owned = null
                } else {
                    val image = NativeImage(crop.width, crop.height, false)
                    cropped = image
                    full.copyRect(image, crop.x, crop.y, 0, 0, crop.width, crop.height, false, false)
                    if (future.complete(image)) cropped = null
                }
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            } finally {
                try { cropped?.close() } finally { owned?.close() }
            }
        }
        return future
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun png(image: NativeImage): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.nio.channels.Channels.newChannel(output).use { channel ->
            check((image as NativeImageAccessor).`mcdebug$writeToChannel`(channel)) { "PNG encoding failed" }
        }
        return output.toByteArray()
    }

    /** Slots and widgets of the current Screen, in GUI units. Client thread only. */
    private fun annotationBoxes(client: Minecraft): List<Annotator.Box> {
        val screen = client.gui.screen() ?: return emptyList()
        val boxes = ArrayList<Annotator.Box>()
        (screen as? AbstractContainerScreen<*>)?.let { container ->
            val accessor = container as AbstractContainerScreenAccessor
            container.getMenu().slots.forEachIndexed { i, slot ->
                if (slot.isActive) boxes.add(Annotator.Box("slot", i, (accessor.leftPos + slot.x).toDouble(),
                    (accessor.topPos + slot.y).toDouble(), 16.0, 16.0, null))
            }
        }
        GuiWidgets.collect(screen).forEachIndexed { i, widget ->
            if (widget.visible) boxes.add(Annotator.Box("widget", i, widget.getX().toDouble(), widget.getY().toDouble(),
                widget.getWidth().toDouble(), widget.getHeight().toDouble(), widget.getMessage().string))
        }
        return boxes
    }

    @Suppress("CAST_NEVER_SUCCEEDS") // NativeImageAccessor is mixed into NativeImage at runtime
    private fun encode(request: Request) {
        val shot = synchronized(lock) {
            val image = request.shot ?: return // cleanup/close already reclaimed it
            request.shot = null
            request.phase = Phase.ENCODING
            image
        }
        var path: Path? = null
        val options = request.options
        val result: JsonObject
        try {
            result = shot.image.use { image ->
                if (!isLive(request)) return
                val pointer = (image as NativeImageAccessor).`mcdebug$getPixels`()
                val pixelHash = sha256(MemoryUtil.memByteBuffer(pointer, image.width * image.height * 4))
                val json = JsonObject().apply {
                    addProperty("format", options.format)
                    addProperty("pixelHash", pixelHash)
                    val size = shot.transform.getAsJsonArray("pngSize")
                    addProperty("width", size[0].asInt)
                    addProperty("height", size[1].asInt)
                    addProperty("frameId", shot.frameId)
                    addProperty("clientTick", shot.clientTick)
                    add("transform", shot.transform.deepCopy())
                    shot.drawLog?.let { add("drawLog", it) }
                }
                if (options.format == "none") return@use json
                val bytes = png(image) // exactly one PNG encode; no disk readback
                if (!isLive(request)) return
                val output = shotPath(options.name)
                path = output
                writeAtomically(output, bytes) { isLive(request) } ?: return
                json.apply {
                    addProperty("path", output.toAbsolutePath().toString())
                    addProperty("file", output.fileName.toString())
                    addProperty("relPath", relativeToGameDir(output))
                    addProperty("bytes", bytes.size)
                    addProperty("sha256", sha256(bytes))
                    if (options.inline) addProperty("png", Base64.getEncoder().encodeToString(bytes))
                }
                if (options.annotate) {
                    // After the clean encode: the overlay never reaches the main PNG.
                    val crop = shot.transform.getAsJsonArray("crop")
                    val legend = Annotator.draw(NativeImageCanvas(image), shot.boxes,
                        shot.transform.get("pixelsPerGuiX").asDouble, shot.transform.get("pixelsPerGuiY").asDouble,
                        crop[0].asDouble, crop[1].asDouble)
                    val annotatedBytes = png(image)
                    val annotated = output.resolveSibling(output.fileName.toString().removeSuffix(".png") + ".annotated.png")
                    writeAtomically(annotated, annotatedBytes) { isLive(request) } ?: return
                    json.addProperty("annotatedPath", annotated.toAbsolutePath().toString())
                    json.add("annotations", legend)
                    if (options.inline) json.addProperty("annotatedPng", Base64.getEncoder().encodeToString(annotatedBytes))
                }
                json
            }
        } catch (t: Throwable) {
            request.future.completeExceptionally(RpcException(RpcErrors.INTERNAL_ERROR,
                "failed to encode/write screenshot${path?.let { " to $it" } ?: ""}: ${t.message}", null, t))
            return
        } finally {
            // use has closed the image before releasing its bounded reservation.
            release(request)
        }
        synchronized(lock) {
            if (!isLive(request)) return
            // A hash-only capture has no PNG, so it must not become the base for `--png` clicks.
            if (options.format == "png" && request.sequence > publishedSequence) {
                publishedSequence = request.sequence
                lastPath = path
                // Do not share the mutable JSON result object with publication.
                lastTransform = shot.transform.deepCopy()
            }
            request.future.complete(result)
        }
    }

    /** Screen.mouseClicked semantics, NOT vanilla's ceil-sized mouse projection. */
    fun pngToGui(x: Double, y: Double): Pair<Double, Double> {
        val transform = lastTransform ?: throw RpcException(RpcErrors.INVALID_PARAMS,
            "--png coordinates need a previous gui.screenshot (no capture transform known yet)", reason("NO_SCREENSHOT"))
        val size = transform.getAsJsonArray("pngSize")
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= size[0].asDouble || y >= size[1].asDouble) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "PNG coordinates must be finite and inside the captured image",
                reason("PNG_COORDINATES_OUT_OF_BOUNDS"))
        }
        val crop = transform.getAsJsonArray("crop")
        return CaptureGeometry.pngToGui(x, y, crop[0].asDouble, crop[1].asDouble,
            transform.get("pixelsPerGuiX").asDouble, transform.get("pixelsPerGuiY").asDouble)
    }

    private fun isLive(request: Request): Boolean {
        if (closed || request.future.isDone) return false
        if (!expired(request.deadline)) return true
        request.future.completeExceptionally(timedOut())
        return false
    }

    private fun release(request: Request) { synchronized(lock) { active.remove(request) } }
    private fun expired(deadline: Long) = System.nanoTime() - deadline >= 0
    private fun reason(value: String) = JsonObject().apply { addProperty("reason", value) }
    private fun timedOut() = RpcException(RpcErrors.CLIENT_NOT_READY,
        "screenshot did not complete within ${REQUEST_TIMEOUT_SECONDS}s (wall clock, including passive delay)", reason("SCREENSHOT_TIMEOUT"))
    private fun stopping() = RpcException(RpcErrors.CLIENT_NOT_READY, "client is stopping", reason("CLIENT_STOPPING"))
    private fun queueFull() = RpcException(RpcErrors.CLIENT_NOT_READY,
        "screenshot queue is full ($MAX_OUTSTANDING including incoming hops and encoders)", reason("SCREENSHOT_QUEUE_FULL"))
    private fun captureError(t: Throwable) = RpcException(RpcErrors.INTERNAL_ERROR,
        "failed to capture screenshot: ${t.message}", null, t)

    private fun shotPath(name: String?): Path {
        val dir = RpcEnv.gameDir().resolve("mcdebug").resolve("screenshots")
        Files.createDirectories(dir)
        val file = if (name.isNullOrBlank()) {
            "mcdebug-${LocalDateTime.now().format(STAMP)}-${COUNTER.incrementAndGet()}.png"
        } else {
            val slug = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(120).ifEmpty { "shot" }
            if (slug.endsWith(".png")) slug else "$slug.png"
        }
        return dir.resolve(file)
    }

    private fun relativeToGameDir(path: Path): String = try {
        RpcEnv.gameDir().toAbsolutePath().relativize(path.toAbsolutePath()).toString()
    } catch (_: Exception) { path.toString() }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun sha256(buffer: ByteBuffer): String = hex(MessageDigest.getInstance("SHA-256").apply { update(buffer) }.digest())
    private fun hex(digest: ByteArray) = digest.joinToString("") { "%02x".format(it) }
    private const val OPAQUE = 0xFF shl 24

    /** Only a complete file replaces the destination; returns null when [live] turned false. */
    private fun writeAtomically(output: Path, bytes: ByteArray, live: () -> Boolean): Path? {
        val temporary = Files.createTempFile(output.toAbsolutePath().parent, ".mcdebug-", ".png.part")
        try {
            Files.write(temporary, bytes)
            if (!live()) return null
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            return output
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** NativeImage stores ABGR; the annotator speaks ARGB. */
    private class NativeImageCanvas(private val image: NativeImage) : PixelCanvas {
        override val width get() = image.width
        override val height get() = image.height
        override fun get(x: Int, y: Int): Int = swap(net.minecraft.util.ARGB.toABGR(image.getPixel(x, y)))
        override fun set(x: Int, y: Int, argb: Int) = image.setPixelABGR(x, y, swap(argb))
        private fun swap(c: Int) = (c and 0xFF00FF00.toInt()) or (c shr 16 and 0xFF) or (c and 0xFF shl 16)
    }
    private fun ints(vararg values: Int) = JsonArray().apply { values.forEach { add(it) } }
    private fun doubles(vararg values: Double) = JsonArray().apply { values.forEach { add(it) } }
}

/** What one `gui.screenshot` (or the screenshot step of `gui.act`) asks for. */
data class CaptureOptions(
    val mode: String = "window",
    val inline: Boolean = false,
    val name: String? = null,
    /** `png` (default) or `none`: no file/PNG, only the pixel hash + transform (cheap change detection). */
    val format: String = "png",
    /** Also write `*.annotated.png` with numbered slot/widget boxes plus a legend. */
    val annotate: Boolean = false,
    /** Include the GuiGraphicsExtractor draw log of the captured frame. */
    val drawLog: Boolean = false,
    val delayTicks: Int = 1,
    /**
     * `keep` (default): the frame shows hover/tooltip/carried stack at the cursor.
     * `hide`: that one frame is rendered with the cursor parked off-screen — no
     * hover highlight, tooltip or carried stack — for deterministic goldens.
     */
    val cursor: String = "keep",
) {
    init {
        require(mode == "window" || mode == "gui") { "unknown mode '$mode' (window/gui)" }
        require(format == "png" || format == "none") { "format must be png or none" }
        require(!(annotate && format == "none")) { "annotate needs format png" }
        require(!(inline && format == "none")) { "inline needs format png" }
        require(delayTicks in 0..1200) { "delayTicks must be in 0..1200" }
        require(cursor == "keep" || cursor == "hide") { "cursor must be keep or hide" }
    }

    companion object {
        fun parse(params: JsonObject?, defaultDelayTicks: Int = 1): CaptureOptions {
            val p = params ?: JsonObject()
            return try {
                CaptureOptions(
                    mode = (p.string("mode") ?: "window").lowercase(),
                    inline = p.bool("inline", false),
                    name = p.string("name"),
                    format = (p.string("format") ?: "png").lowercase(),
                    annotate = p.bool("annotate", false),
                    drawLog = p.bool("drawLog", false),
                    delayTicks = p.int("delayTicks", defaultDelayTicks),
                    cursor = (p.string("cursor") ?: "keep").lowercase(),
                )
            } catch (e: IllegalArgumentException) {
                throw RpcException(RpcErrors.INVALID_PARAMS, e.message ?: "invalid screenshot parameters")
            }
        }
    }
}
