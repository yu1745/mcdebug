package com.mcdebug.client

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/** Screen-local input only. No global bindings, physical modifier mutation, active ticks or blocking waits. */
object GuiInputOps : ClientRpcGroup {
    private val lock = Any()
    private var active: Operation? = null
    @Volatile private var closed = false
    private const val TIMEOUT_SECONDS = 30L

    override fun methods(): Map<String, ClientRpcHandler> = linkedMapOf(
        "key" to ::key, "type" to ::type, "drag" to ::drag,
    )

    private fun key(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = parse { GuiInputParams.key(required(params)) }
        return submit(client, "key", p.modifiers) { op ->
            op.captureScreen()
            op.report.addProperty("key", p.key)
            keyPair(op, p.key, p.modifiers)
            op.finish()
        }
    }

    private fun type(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = parse { GuiInputParams.type(required(params)) }
        return submit(client, "type", p.modifiers) { op ->
            op.captureScreen()
            op.report.addProperty("requestedUtf16Units", p.text.length)
            op.report.addProperty("requestedCodePoints", p.text.codePointCount(0, p.text.length))
            op.report.addProperty("clear", p.clear)
            if (p.clear) {
                // Screen.isSelectAll explicitly requires !SHIFT and !ALT. Match vanilla's Mac control semantics.
                keyPair(op, 65, if (Minecraft.ON_OSX) GuiInputValidation.SUPER else GuiInputValidation.CONTROL)
                if (!op.changed() && !op.future.isDone) keyPair(op, 259, 0)
            }
            // KeyboardHandler.charTyped dispatches Character.toChars for supplementary code points.
            // A validated UTF-16 traversal is identical, while allowing stop after either callback.
            var dispatched = 0
            var handled = 0
            for (c in p.text) {
                if (op.changed() || op.future.isDone) break
                op.dispatch(p.modifiers) {
                    dispatched++
                    op.report.addProperty("dispatchedUtf16Units", dispatched)
                    if (op.screen!!.charTyped(c, p.modifiers)) handled++
                    op.report.addProperty("handledCharEvents", handled)
                }
                op.publish()
            }
            op.finish()
        }
    }

    private fun drag(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = parse { GuiInputParams.drag(required(params)) }
        return submit(client, "drag", p.modifiers) { op ->
            op.captureScreen()
            // Both lookups run on this one client hop: no screenshot publication can interleave.
            val from = if (p.space == "png") Capture.pngToGui(p.from.first, p.from.second) else p.from
            val to = if (p.space == "png") Capture.pngToGui(p.to.first, p.to.second) else p.to
            parse {
                GuiInputValidation.point(from.first, from.second)
                GuiInputValidation.point(to.first, to.second)
                // mouseDragged's delta must be finite too, including extreme finite GUI endpoints.
                GuiInputValidation.point(to.first - from.first, to.second - from.second)
            }
            op.x = from.first
            op.y = from.second
            op.report.addProperty("space", p.space)
            op.report.addProperty("button", p.button)
            op.report.addProperty("steps", p.steps)
            op.report.addProperty("stepTicks", p.stepTicks)
            op.publish()
            op.releaseAction = {
                op.gesture.finish {
                    op.report.addProperty("releaseDispatched", true)
                    val released = GuiInputModifiers.withMask(p.modifiers) {
                        op.screen!!.mouseReleased(op.x, op.y, p.button)
                    }
                    op.report.addProperty("released", released)
                }
            }
            op.dispatch(p.modifiers) {
                if (p.moveCursor) op.moveCursor()
                if (op.changed() || op.future.isDone) return@dispatch
                op.gesture.beginPress() // claim before Screen code, even if mouseClicked throws
                op.report.addProperty("pressDispatched", true)
                op.report.addProperty("pressed", op.screen!!.mouseClicked(op.x, op.y, p.button))
            }
            op.publish()
            if (op.changed() || op.future.isDone) {
                op.finish()
            } else {
                // Observe changes each natural tick, not only at a potentially 20-tick step boundary.
                op.dispatch(p.modifiers) {
                    op.guard = ClientTasks.until(401) { op.future.isDone || op.changed() }
                }
                val watched = ClientTasks.thenOnClient(client, op.guard!!) { changed ->
                    if (changed && !op.terminal && !op.future.isDone) op.finish()
                }
                watched.whenComplete { _, error -> if (error != null && !op.terminal) op.fail(error) }
                nextStep(op, p, from, to)
            }
        }
    }

    private fun nextStep(op: Operation, p: GuiInputParams.Drag, from: Pair<Double, Double>, to: Pair<Double, Double>) {
        if (op.terminal || op.future.isDone) return
        op.dispatch(p.modifiers) { op.waiter = ClientTasks.awaitTicks(p.stepTicks) }
        val advanced = ClientTasks.thenOnClient(op.client, op.waiter!!) {
            if (op.terminal || op.future.isDone) return@thenOnClient
            if (op.changed()) {
                op.finish()
                return@thenOnClient
            }
            val step = op.gesture.stepsCompleted + 1
            val point = GuiInputValidation.interpolate(from, to, step, p.steps)
            op.dispatch(p.modifiers) {
                val dx = point.first - op.x
                val dy = point.second - op.y
                op.x = point.first
                op.y = point.second
                if (p.moveCursor) op.moveCursor()
                if (op.changed() || op.future.isDone) return@dispatch
                op.gesture.step()
                op.report.addProperty("stepsCompleted", op.gesture.stepsCompleted)
                val handled = op.screen!!.mouseDragged(op.x, op.y, p.button, dx, dy)
                if (handled) op.dragHandled++
                op.report.addProperty("handledDragEvents", op.dragHandled)
            }
            op.publish()
            if (step == p.steps || op.changed() || op.future.isDone) op.finish()
            else nextStep(op, p, from, to)
        }
        advanced.whenComplete { _, error -> if (error != null && !op.terminal) op.fail(error) }
    }

    /** The release always targets the original Screen, including if press closes/replaces it or throws. */
    private fun keyPair(op: Operation, key: Int, mask: Int) {
        op.dispatch(mask) {
            op.releaseAction = {
                op.keyReleases++
                op.report.addProperty("keyReleaseEvents", op.keyReleases)
                op.report.addProperty("releaseDispatched", true)
                val released = GuiInputModifiers.withMask(mask) { op.screen!!.keyReleased(key, 0, mask) }
                if (released) op.keyReleaseHandled++
                op.report.addProperty("released", released)
                op.report.addProperty("handledKeyReleases", op.keyReleaseHandled)
            }
            var primary: Throwable? = null
            try {
                op.keyPresses++
                op.report.addProperty("keyPressEvents", op.keyPresses)
                op.report.addProperty("pressDispatched", true)
                val pressed = op.screen!!.keyPressed(key, 0, mask)
                if (pressed) op.keyPressHandled++
                op.report.addProperty("pressed", pressed)
                op.report.addProperty("handledKeyPresses", op.keyPressHandled)
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                op.changed() // latch a press-induced Screen switch even if release restores it
                try { op.release() } catch (releaseError: Throwable) {
                    if (primary == null) throw releaseError
                    if (primary !== releaseError) primary.addSuppressed(releaseError)
                }
                op.publish()
            }
        }
    }

    private class InputFuture : CompletableFuture<JsonElement>() {
        // Admission/dispatch and caller/timer settlement share a lock: an expired initial hop cannot press.
        override fun complete(value: JsonElement): Boolean = synchronized(lock) { super.complete(value) }
        override fun completeExceptionally(ex: Throwable): Boolean = synchronized(lock) { super.completeExceptionally(ex) }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = synchronized(lock) { super.cancel(mayInterruptIfRunning) }
    }

    private class Operation(val client: Minecraft, val kind: String, val modifiers: Int) {
        val future = InputFuture()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        val report = JsonObject().apply {
            addProperty("kind", kind)
            addProperty("modifiers", modifiers)
            addProperty("dispatchedUtf16Units", 0)
            addProperty("handledCharEvents", 0)
            addProperty("stepsCompleted", 0)
            addProperty("handledDragEvents", 0)
            addProperty("keyPressEvents", 0)
            addProperty("keyReleaseEvents", 0)
            addProperty("handledKeyPresses", 0)
            addProperty("handledKeyReleases", 0)
            addProperty("pressDispatched", false)
            addProperty("releaseDispatched", false)
            addProperty("pressed", false)
            addProperty("released", false)
            addProperty("cursorMoved", false)
            addProperty("screenChanged", false)
            addProperty("sizeChanged", false)
            addProperty("handled", false)
        }
        @Volatile var snapshot: JsonObject = report.deepCopy()
        @Volatile var waiter: CompletableFuture<Unit>? = null
        @Volatile var guard: CompletableFuture<Boolean>? = null
        @Volatile var terminal = false
        var screen: Screen? = null
        var width = 0
        var height = 0
        var windowWidth = 0
        var windowHeight = 0
        var framebufferWidth = 0
        var framebufferHeight = 0
        var guiScale = 0.0
        var x = 0.0
        var y = 0.0
        var dragHandled = 0
        var keyPresses = 0
        var keyReleases = 0
        var keyPressHandled = 0
        var keyReleaseHandled = 0
        val gesture = GuiInputGestureState()
        var releaseAction: (() -> Unit)? = null

        fun captureScreen() {
            screen = client.screen ?: throw problem("CLIENT_NOT_READY", "no Screen is open")
            width = screen!!.width
            height = screen!!.height
            val window = client.window
            windowWidth = window.getScreenWidth()
            windowHeight = window.getScreenHeight()
            framebufferWidth = window.getWidth()
            framebufferHeight = window.getHeight()
            guiScale = window.getGuiScale()
            report.addProperty("screen", screen!!.javaClass.name)
        }

        fun changed(): Boolean {
            val original = screen ?: return false
            val screenChanged = client.screen !== original
            val window = client.window
            val sizeChanged = original.width != width || original.height != height ||
                window.getScreenWidth() != windowWidth || window.getScreenHeight() != windowHeight ||
                window.getWidth() != framebufferWidth || window.getHeight() != framebufferHeight || window.getGuiScale() != guiScale
            // Latch observations; never resume if a later callback restores the old screen/size.
            if (screenChanged) report.addProperty("screenChanged", true)
            if (sizeChanged) report.addProperty("sizeChanged", true)
            return report.get("screenChanged").asBoolean || report.get("sizeChanged").asBoolean
        }

        fun dispatch(mask: Int, action: () -> Unit) = synchronized(lock) {
            if (closed) throw problem("CLIENT_STOPPING", "client is stopping")
            if (future.isDone || terminal) throw problem("INPUT_CANCELLED", "input request already settled")
            if (System.nanoTime() - deadline >= 0) throw problem("INPUT_TIMEOUT", "input exceeded 30s wall deadline")
            GuiInputModifiers.withMask(mask, action)
        }

        fun release() {
            val action = releaseAction ?: return
            releaseAction = null // claim before invoking arbitrary (possibly throwing/reentrant) Screen code
            action()
        }

        fun publish() {
            changed()
            if (kind == "drag") {
                report.addProperty("guiX", x)
                report.addProperty("guiY", y)
            }
            val handled = dragHandled > 0 || keyPressHandled > 0 || keyReleaseHandled > 0 ||
                report.get("handledCharEvents").asInt > 0 || report.get("pressed").asBoolean || report.get("released").asBoolean
            report.addProperty("handled", handled)
            snapshot = report.deepCopy()
        }

        fun moveCursor() {
            if (GuiCursor.warp(client, x, y)) report.addProperty("cursorMoved", true)
        }

        fun cleanup() {
            if (terminal) return
            terminal = true
            waiter?.cancel(false)
            guard?.cancel(false)
            try { release() } finally {
                publish()
                synchronized(lock) { if (active === this) active = null }
            }
        }

        fun finish() {
            try {
                cleanup()
                synchronized(lock) {
                    if (future.isDone) return
                    if (closed) throw problem("CLIENT_STOPPING", "client is stopping")
                    if (System.nanoTime() - deadline >= 0) throw problem("INPUT_TIMEOUT", "input exceeded 30s wall deadline")
                    val changed = changed()
                    // A key/type that itself navigates (Esc, Enter on a button) succeeded; only an
                    // interrupted drag gesture, or a change before our input landed, is a failure.
                    val causedByInput = kind != "drag" && report.get("handled").asBoolean
                    report.addProperty("ok", !changed || causedByInput)
                    report.addProperty("reason", when {
                        causedByInput && changed -> "CHANGED_BY_INPUT"
                        report.get("screenChanged").asBoolean -> "SCREEN_CHANGED"
                        report.get("sizeChanged").asBoolean -> "SCREEN_SIZE_CHANGED"
                        else -> ""
                    })
                    publish()
                    future.complete(report.deepCopy())
                }
            } catch (t: Throwable) { fail(t) }
        }

        fun fail(error: Throwable) {
            if (!client.isSameThread) {
                // Cancelling the true waiter may execute its dependent stages on the timer/caller thread.
                try { client.execute { fail(error) } } catch (_: Throwable) { future.completeExceptionally(error) }
                return
            }
            var cause = error
            while (cause is CompletionException && cause.cause != null) cause = cause.cause!!
            try { cleanup() } catch (releaseError: Throwable) {
                if (cause !== releaseError) cause.addSuppressed(releaseError)
            }
            val rpc = if (cause is RpcException) {
                val reason = cause.data?.takeIf { it.isJsonObject }?.asJsonObject?.get("reason")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: "INPUT_FAILED"
                RpcException(cause.rpcCode, cause.message ?: "input failed", snapshot.deepCopy().apply {
                    addProperty("reason", reason)
                }, cause)
            } else RpcException(RpcErrors.INTERNAL_ERROR, "Screen input failed: ${cause.message}", snapshot.deepCopy().apply {
                addProperty("reason", "INPUT_FAILED")
            }, cause)
            future.completeExceptionally(rpc)
        }

        fun problem(reason: String, message: String): RpcException = RpcException(
            RpcErrors.CLIENT_NOT_READY, message, snapshot.deepCopy().apply { addProperty("reason", reason) },
        )
    }

    private fun submit(client: Minecraft, kind: String, modifiers: Int, body: (Operation) -> Unit): CompletableFuture<JsonElement> {
        val op = Operation(client, kind, modifiers)
        synchronized(lock) {
            if (closed) return CompletableFuture.failedFuture(op.problem("CLIENT_STOPPING", "client is stopping"))
            if (active != null) return CompletableFuture.failedFuture(op.problem("INPUT_BUSY", "another Screen input request is active"))
            active = op
        }
        op.future.whenComplete { _, error ->
            if (error != null) {
                // CompletableFuture composition doesn't propagate cancellation to these real tick waiters.
                op.waiter?.cancel(false)
                op.guard?.cancel(false)
                // Bypass ClientTasks' stopping gate: release must still run during CLIENT_STOPPING.
                try { client.execute { try { op.cleanup() } catch (_: Throwable) { /* release already claimed */ } } }
                catch (_: Throwable) { /* close(client) drains before the event loop stops */ }
            }
        }
        val weak = WeakReference(op)
        try {
            CompletableFuture.delayedExecutor(TIMEOUT_SECONDS, TimeUnit.SECONDS).execute {
                weak.get()?.let { pending ->
                    pending.future.completeExceptionally(pending.problem("INPUT_TIMEOUT", "input exceeded 30s wall deadline"))
                }
            }
            ClientTasks.onClient(client) {
                if (!op.future.isDone) {
                    // Includes the initial hop; timeout/cancel can never leave a delayed press behind.
                    try {
                        op.dispatch(modifiers) { op.captureScreen() }
                        body(op)
                    } catch (t: Throwable) { op.fail(t) }
                } else op.cleanup()
            }.whenComplete { _, error ->
                if (error != null) {
                    op.future.completeExceptionally(error)
                }
            }
        } catch (t: Throwable) {
            op.future.completeExceptionally(t)
        }
        return op.future
    }

    /** Call on the client thread before ClientTasks.close(), while original Screens are still usable. */
    fun close(client: Minecraft) {
        val op = synchronized(lock) {
            closed = true
            active
        }
        if (op != null) {
            // Lifecycle callback is client-thread-only; direct execution avoids a rejected stopping hop.
            val cleanup = Runnable { op.fail(op.problem("CLIENT_STOPPING", "client is stopping")) }
            if (client.isSameThread) cleanup.run() else client.execute(cleanup)
        }
    }

    private fun required(params: JsonObject?): JsonObject = params ?: throw IllegalArgumentException("params required")
    private fun <T> parse(body: () -> T): T = try { body() } catch (t: IllegalArgumentException) {
        throw RpcException(RpcErrors.INVALID_PARAMS, t.message ?: "invalid input parameters")
    }
}
