package com.mcdebug.testkit

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.sdk.DebugApi
import com.mcdebug.sdk.GuiApi
import com.mcdebug.sdk.ScreenshotOptions
import com.mcdebug.sdk.gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.function.BooleanSupplier

/** A JUnit test's view of both planes; no CLI commands or Minecraft classes are loaded here. */
class ClientTestContext internal constructor(
    private val runtime: ClientTestRuntime,
    val artifactsDir: Path,
) {
    val server: DebugApi get() = runtime.connection().server
    val client: DebugApi get() = runtime.connection().client
    val gui: GuiApi get() = runtime.connection().gui
    private var problemCursor = cursor()
    internal var failureCaptured = false

    init { Files.createDirectories(artifactsDir) }

    @JvmOverloads
    fun call(method: String, params: Map<String, Any?> = emptyMap()): JsonObject {
        val event = linkedMapOf<String, Any?>("time" to Instant.now().toString(), "method" to method, "params" to params)
        var failure: Throwable? = null
        try {
            val result = runtime.connection().call(method, gson.toJsonTree(params)).asJsonObject
            event["result"] = result.deepCopy().apply { remove("png"); remove("annotatedPng") }
            return result
        } catch (error: Throwable) {
            failure = error
            event["error"] = error.toString()
            throw error
        } finally {
            try {
                Files.writeString(artifactsDir.resolve("rpc.jsonl"), gson.toJson(event) + "\n", CREATE, APPEND)
            } catch (diagnostic: Throwable) {
                if (failure == null) throw diagnostic
                failure.addSuppressed(diagnostic)
            }
        }
    }

    /** Actions reject explicit business failures; raw call() remains available for negative cases. */
    @JvmOverloads
    fun act(method: String, params: Map<String, Any?> = emptyMap()): JsonObject =
        call(method, params).also { requireSuccess(method, it) }

    /** Poll a condition with a monotonic wall-clock deadline, including ordinary assertion retries. */
    @JvmOverloads
    fun await(description: String, timeout: Duration = Duration.ofSeconds(10), condition: BooleanSupplier) {
        require(!timeout.isNegative && !timeout.isZero) { "condition timeout must be positive" }
        val deadline = System.nanoTime() + timeout.toNanos()
        var lastAssertion: AssertionError? = null
        while (true) {
            try {
                if (condition.asBoolean) return
            } catch (error: AssertionError) {
                lastAssertion = error
            }
            if (System.nanoTime() >= deadline) throw AssertionError("timed out waiting for $description after $timeout", lastAssertion)
            Thread.sleep(50)
        }
    }

    /** Store a fresh client frame and draw log on the test host, including when the game is remote. */
    @JvmOverloads
    fun capture(name: String, hideCursor: Boolean = true): JsonObject {
        require(Regex("[A-Za-z0-9_.-]+").matches(name)) { "invalid artifact name" }
        val result = gui.screenshot(ScreenshotOptions(
            mode = "window", inline = true, annotate = true, drawLog = true,
            cursor = if (hideCursor) "hide" else "keep",
        )).asJsonObject
        requireSuccess("gui.screenshot", result)
        Files.write(artifactsDir.resolve("$name.png"), Base64.getDecoder().decode(result["png"].asString))
        result.remove("png")
        result.remove("annotatedPng")?.let {
            Files.write(artifactsDir.resolve("$name.annotated.png"), Base64.getDecoder().decode(it.asString))
        }
        writeJson("$name.json", result)
        return result
    }

    fun assertNoNewErrors() {
        val result = gui.problems(since = problemCursor, level = "error", limit = 500).asJsonObject
        writeJson("problems.json", result)
        check(result["available"]?.asBoolean != false) { "client problem log is unavailable" }
        if (result["truncated"]?.asBoolean == true || !result.getAsJsonArray("entries").isEmpty) {
            throw AssertionError("new client errors or incomplete error log: $result")
        }
        problemCursor = result["next"].asLong
    }

    fun restartClient() {
        runtime.restart()
        problemCursor = cursor()
    }

    /** Wait on the server's own tick loop; this never calls a machine ticker or drives the world. */
    fun waitServerTicks(ticks: Int) {
        require(ticks in 1..1200) { "ticks must be between 1 and 1200" }
        val now = server.server.status().asJsonObject["tick"].asLong
        server.wait.until("tick >= ${now + ticks}", ticks + 40)
    }

    internal fun collectFailure(error: Throwable) {
        if (failureCaptured) return
        failureCaptured = true
        runCatching { Files.writeString(artifactsDir.resolve("failure.txt"), error.stackTraceToString()) }
            .exceptionOrNull()?.let(error::addSuppressed)
        val captures = linkedMapOf<String, () -> JsonElement>(
            "client-status.json" to { gui.status() },
            "screen.json" to { gui.screen() },
            "server-status.json" to { server.server.status() },
            "failure-problems.json" to { gui.problems(since = problemCursor, limit = 500) },
            "failure-frame.json" to { capture("failure", hideCursor = false) },
        )
        for ((name, capture) in captures) {
            try { writeJson(name, capture()) } catch (diagnostic: Throwable) {
                error.addSuppressed(diagnostic)
                runCatching { Files.writeString(artifactsDir.resolve("$name.error.txt"), diagnostic.stackTraceToString()) }
                    .exceptionOrNull()?.let(error::addSuppressed)
            }
        }
    }

    private fun cursor(): Long = gui.problems(limit = 1).asJsonObject["next"].asLong
    private fun writeJson(name: String, value: JsonElement) {
        Files.writeString(artifactsDir.resolve(name), gson.toJson(value))
    }
}

/** JSON-RPC success is separate from a method's explicit business result. */
internal fun requireSuccess(method: String, value: JsonObject) {
    for (field in listOf("ok", "success")) {
        if (value[field]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == false) {
            throw AssertionError("$method returned $field=false: $value")
        }
    }
}
