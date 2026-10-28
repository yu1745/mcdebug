package com.mcdebug.client

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.client.Minecraft
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * The agent iteration loop on top of the primitive `gui.*` calls:
 *
 * - `gui.act`      one round trip for "do X, wait until the GUI is quiet, show me":
 *                  action + settle + optional `gui.screen` + optional screenshot +
 *                  problems logged meanwhile;
 * - `gui.settle`   the same wait on its own (e.g. after a server-plane NBT write);
 * - `gui.reinit`   re-run the open Screen's `init()` (after a hotswap; clears a render fault);
 * - `gui.problems` WARN/ERROR log entries and render faults since a cursor;
 * - `gui.hotswap`  redefine changed classes from compiled output directories;
 * - `gui.reloadScreen` rebuild the open Screen from freshly compiled classes in a new
 *                  class loader (any change to that class, same menu).
 */
object GuiDevOps : ClientRpcGroup {
    /** Actions `gui.act` may run; never act/stop/disconnect (a world exit is not an iteration step). */
    private val ACTIONS = setOf("click", "hover", "scroll", "key", "type", "drag", "close", "openBlock", "openScreen", "reinit", "reloadScreen", "none")

    override fun methods(): Map<String, ClientRpcHandler> = linkedMapOf(
        "act" to ::act,
        "settle" to ::settle,
        "reinit" to ::reinit,
        "problems" to ::problems,
        "hotswap" to ::hotswap,
        "reloadScreen" to ::reloadScreen,
    )

    // ------------------------------------------------------------------ act

    private fun act(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS,
            "params required: {action, params?, settle?, screen?, screenshot?}")
        val action = p.string("action") ?: "none"
        if (action !in ACTIONS) {
            throw RpcException(RpcErrors.INVALID_PARAMS, "action must be one of ${ACTIONS.sorted().joinToString("/")}")
        }
        val actionParams = p.obj("params") ?: JsonObject()
        // false = no settle step; an object tunes it; absent = defaults.
        val settleElement = p.get("settle")
        val settle = when {
            settleElement != null && settleElement.isJsonPrimitive && !settleElement.asBoolean -> null
            else -> GuiSettle.options(settleElement?.takeIf { it.isJsonObject }?.asJsonObject, defaultMax = 40)
        }
        val wantScreen = p.bool("screen", false)
        val shot = p.get("screenshot")?.takeIf { !it.isJsonNull }?.let {
            when {
                it.isJsonPrimitive && it.asJsonPrimitive.isString -> CaptureOptions.parse(JsonObject().apply { addProperty("mode", it.asString) }, 0)
                it.isJsonObject -> CaptureOptions.parse(it.asJsonObject, 0)
                else -> throw RpcException(RpcErrors.INVALID_PARAMS, "screenshot must be a mode string or an options object")
            }
        }
        // A click/scroll settles on its own (with its waitTicks as the bound) unless told not to.
        val actionSettles = (action == "click" || action == "scroll") && actionParams.bool("settle", true)
        val cursor = ProblemLog.buffer.cursor()
        val startTick = ClientTasks.currentTick()

        val performed: CompletableFuture<JsonElement> = try {
            when (action) {
                "none" -> CompletableFuture.completedFuture(JsonObject())
                "reinit" -> reinit(client, actionParams)
                "reloadScreen" -> reloadScreen(client, actionParams)
                else -> GuiOps.methods().getValue(action)(client, actionParams)
            }
        } catch (t: Throwable) {
            CompletableFuture.failedFuture(t)
        }
        val outcome = performed.handle { result, error -> result to error?.let(::unwrap) }
        val settled: CompletableFuture<JsonObject?> = outcome.thenCompose { (_, error) ->
            if (settle == null || (actionSettles && error == null)) CompletableFuture.completedFuture<JsonObject?>(null)
            else ClientTasks.onClient(client) { GuiSettle.settle(client, settle).thenApply<JsonObject?> { it } }.thenCompose { it }
        }
        // Screenshot first: it forces a fresh frame, so hover state in the screen view matches the image.
        val image: CompletableFuture<JsonElement?> = settled.thenCompose {
            if (shot == null) CompletableFuture.completedFuture(null)
            else Capture.screenshot(client, shot).thenApply<JsonElement?> { it }
        }
        val screen = ClientTasks.thenOnClient(client, image) { if (wantScreen) GuiScreenOps.snapshot(client) else null }
        return ClientTasks.thenOnClient(client, screen) { _ ->
            val picture = image.join()
            val (result, error) = outcome.join() // all stages above are complete
            JsonObject().apply {
                addProperty("ok", error == null)
                addProperty("action", action)
                if (error == null) add("result", result ?: JsonNull.INSTANCE) else add("error", errorJson(error))
                settled.join()?.let { add("settle", it) }
                screen.join()?.let { add("screen", it) }
                picture?.let { add("screenshot", it) }
                addProperty("ticks", ClientTasks.currentTick() - startTick)
                add("problems", ProblemLog.buffer.since(cursor, false, 50).toJson())
                ScreenGuard.currentFault(client)?.let { add("screenFault", it) }
            }
        }.asElement()
    }

    private fun unwrap(t: Throwable): Throwable {
        var cause = t
        while (cause is CompletionException && cause.cause != null) cause = cause.cause!!
        return cause
    }

    private fun errorJson(error: Throwable): JsonObject = JsonObject().apply {
        if (error is RpcException) {
            addProperty("code", error.rpcCode)
            error.data?.let { add("data", it) }
        } else {
            addProperty("code", RpcErrors.INTERNAL_ERROR)
        }
        addProperty("message", error.message ?: error.toString())
    }

    // --------------------------------------------------------------- settle

    private fun settle(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val options = GuiSettle.options(params, defaultMax = 40)
        return ClientTasks.onClient(client) { GuiSettle.settle(client, options) }.thenCompose { it }.asElement()
    }

    // --------------------------------------------------------------- reinit

    private fun reinit(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> =
        ClientTasks.onClient(client) {
            val screen = client.screen ?: throw noScreen()
            val hadFault = ScreenGuard.currentFault(client) != null
            ScreenGuard.clear()
            val window = client.window
            // Screen.resize -> repositionElements -> rebuildWidgets -> init(): new code, same Screen/menu.
            screen.resize(client, window.getGuiScaledWidth(), window.getGuiScaledHeight())
            JsonObject().apply {
                addProperty("ok", true)
                addProperty("screen", screen.javaClass.name)
                addProperty("clearedFault", hadFault)
                addProperty("widgets", GuiWidgets.collect(screen).size)
            }
        }.asElement()

    // ------------------------------------------------------------- problems

    private fun problems(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: JsonObject()
        val since = p.get("since")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 1L
        val level = (p.string("level") ?: "warn").lowercase()
        if (level != "warn" && level != "error") throw RpcException(RpcErrors.INVALID_PARAMS, "level must be warn or error")
        val limit = p.boundedInt("limit", 100, 1..500)
        return CompletableFuture.completedFuture<JsonElement>(
            ProblemLog.buffer.since(since, level == "error", limit).toJson().apply {
                addProperty("available", ProblemLog.available)
            },
        )
    }

    // --------------------------------------------------------- reloadScreen

    private fun reloadScreen(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "dirs (class output directories) are required")
        val dirs = classDirs(p)
        val also = p.get("also")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
        return ClientTasks.onClient(client) { ScreenReload.reload(client, dirs, also) }.asElement()
    }

    private fun classDirs(p: JsonObject): List<Path> {
        val dirs = p.get("dirs")?.takeIf { it.isJsonArray }?.asJsonArray?.map { Path.of(it.asString).toAbsolutePath().normalize() }
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "dirs must be an array of class output directories")
        if (dirs.isEmpty()) throw RpcException(RpcErrors.INVALID_PARAMS, "dirs must not be empty")
        return dirs
    }

    // -------------------------------------------------------------- hotswap

    private fun hotswap(client: Minecraft, params: JsonObject?): CompletableFuture<JsonElement> {
        val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "dirs (class output directories) are required")
        val dirs = classDirs(p)
        val since = when {
            p.bool("all", false) -> 0L
            else -> p.get("since")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        }
        val reinit = p.bool("reinit", true)
        return ClientTasks.onClient(client) {
            Hotswap.swap(dirs, since).apply {
                val screen = client.screen
                if (reinit && screen != null && get("redefined").asJsonArray.size() > 0) {
                    ScreenGuard.clear()
                    val window = client.window
                    screen.resize(client, window.getGuiScaledWidth(), window.getGuiScaledHeight())
                    addProperty("reinit", screen.javaClass.name)
                }
            }
        }.asElement()
    }
}
