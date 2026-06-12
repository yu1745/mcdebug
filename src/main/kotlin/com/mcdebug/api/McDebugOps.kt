package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.test.McDebugTest
import com.mcdebug.test.McDebugTestApi
import com.mcdebug.test.McDebugTestContext
import com.mcdebug.test.McDebugTestRegistry
import com.mcdebug.test.Pos
import com.mcdebug.test.SetBlockOp
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Self-test / introspection RPCs (namespace `mcdebug.*`).
 *
 * Currently exposes a single method, `mcdebug.runAllTests`, that drains
 * [McDebugTestRegistry] and returns per-test results. The Gradle
 * `mcdebugTest` task drives this.
 *
 * **Threading model**: the handler returns a [CompletableFuture]
 * immediately and submits the actual test orchestration to a single-thread
 * executor. Individual tests run on `mcdebug-test-*`, *not* the server
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
                val tests = McDebugTestRegistry.all()
                    .filter { filter == null || it.name.contains(filter, ignoreCase = true) }
                    .sortedBy { it.name }
                val results = runTestsInParallel(tests)
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

    private fun runTestsInParallel(tests: List<McDebugTest>): List<JsonElement> {
        if (tests.isEmpty()) return emptyList()

        val parallelism = minOf(tests.size, configuredParallelism())
        val pool = Executors.newFixedThreadPool(parallelism) { r ->
            Thread(r, "mcdebug-test-worker").apply { isDaemon = true }
        }
        try {
            val futures = tests.mapIndexed { index, test ->
                val context = allocateContext(index, test.name)
                pool.submit<JsonElement> { runOne(test, context) }
            }
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun configuredParallelism(): Int =
        System.getenv("MCDEBUG_TEST_PARALLELISM")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 64

    private fun allocateContext(index: Int, testName: String): McDebugTestContext {
        val origin = Pos(100 + index * 32, 64, 100)
        return McDebugTestContext(
            testName = testName,
            index = index,
            origin = origin,
            min = origin,
            max = Pos(origin.x + 15, origin.y + 7, origin.z + 15),
        )
    }

    private fun runOne(test: McDebugTest, context: McDebugTestContext): JsonElement {
        val start = System.nanoTime()
        val outcome = runCatching {
            try {
                prepareArea(context)
                McDebugTestApi.withContext(context) {
                    test.run(context)
                }
            } finally {
                cleanupArea(context)
            }
        }
        val durationMs = (System.nanoTime() - start) / 1_000_000
        return JsonObject().apply {
            addProperty("testName", test.name)
            addProperty("origin", context.origin.toString())
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

    private fun prepareArea(context: McDebugTestContext) {
        context.chunks().forEach { (cx, cz) ->
            McDebugTestApi.forceloadChunk(cx, cz)
        }
        clearArea(context)
    }

    private fun cleanupArea(context: McDebugTestContext) {
        clearArea(context)
        context.chunks().forEach { (cx, cz) ->
            McDebugTestApi.unforceloadChunk(cx, cz)
        }
    }

    private fun clearArea(context: McDebugTestContext) {
        val batchSize = 512
        context.positions()
            .map { SetBlockOp(it, "minecraft:air") }
            .chunked(batchSize)
            .forEach { McDebugTestApi.setBlocks(it) }
    }
}
