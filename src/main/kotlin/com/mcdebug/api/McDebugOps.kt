package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.test.McDebugTest
import com.mcdebug.test.McDebugTestRegistry
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Self-test / introspection RPCs (namespace `mcdebug.*`).
 *
 * Currently exposes a single method, `mcdebug.runAllTests`, that drains
 * [McDebugTestRegistry] and returns per-test results. The Gradle
 * `mcdebugTest` task drives this.
 *
 * **Threading model**: the handler returns a [CompletableFuture]
 * immediately and submits the actual test work to a single-thread
 * executor. Tests run on `mcdebug-test-runner`, *not* the server
 * thread. This is critical — `McDebugTestApi` methods internally call
 * back into the dispatcher, which hops to the server thread; if tests
 * ran on the server thread, the dispatcher hop would deadlock.
 */
object McDebugOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "runAllTests" to ::runAllTests,
    )

    private val testExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcdebug-test-runner").apply { isDaemon = true }
    }

    private fun runAllTests(
        server: MinecraftServer,
        params: JsonObject?,
    ): CompletableFuture<JsonElement> {
        // Note: we do NOT use RpcContext.onServer here. The future is
        // completed on the test executor; the server thread is never
        // blocked waiting for tests.
        val future = CompletableFuture<JsonElement>()
        testExecutor.execute {
            try {
                val filter = params?.get("filter")?.takeIf { !it.isJsonNull }?.asString
                val results = McDebugTestRegistry.all()
                    .filter { filter == null || it.name.contains(filter, ignoreCase = true) }
                    .map { runOne(it) }
                future.complete(JsonObject().apply {
                    val arr = JsonArray()
                    results.forEach { arr.add(it) }
                    add("results", arr)
                    addProperty("count", results.size)
                })
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    private fun runOne(test: McDebugTest): JsonElement {
        val start = System.nanoTime()
        val outcome = runCatching { test.run() }
        val durationMs = (System.nanoTime() - start) / 1_000_000
        return JsonObject().apply {
            addProperty("testName", test.name)
            addProperty("durationMs", durationMs)
            outcome.fold(
                onSuccess = { addProperty("status", "PASS") },
                onFailure = { e ->
                    addProperty("status", "FAIL")
                    addProperty("error", e.message ?: e::class.simpleName ?: "error")
                    addProperty(
                        "stack",
                        e.stackTrace.firstOrNull()?.let {
                            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                        } ?: "",
                    )
                },
            )
        }
    }
}
