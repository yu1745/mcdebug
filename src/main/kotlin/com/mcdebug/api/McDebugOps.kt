package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.test.McDebugTestRegistry
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture

/**
 * Self-test / introspection RPCs (namespace `mcdebug.*`).
 *
 * Currently exposes a single method, `mcdebug.runAllTests`, that drains
 * [McDebugTestRegistry] and returns per-test results. The CLI's
 * `mcdebug runTests` subcommand drives this.
 *
 * The handler runs on the MC server thread (via [RpcContext.onServer]),
 * so test bodies execute in the same thread that ticks the world.
 */
object McDebugOps : RpcHandlerGroup {
    override fun methods(): Map<String, RpcHandler> = mapOf(
        "runAllTests" to ::runAllTests,
    )

    private fun runAllTests(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val filter = params?.get("filter")?.takeIf { !it.isJsonNull }?.asString

            val results = JsonArray()
            for (test in McDebugTestRegistry.all()) {
                if (filter != null && !test.name.contains(filter, ignoreCase = true)) continue
                results.add(runOne(test))
            }
            JsonObject().apply {
                add("results", results)
                addProperty("count", results.size())
            }
        }

    private fun runOne(test: com.mcdebug.test.McDebugTest): JsonElement {
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
                    // First frame of the stack trace — enough to locate the
                    // failing assertion without bloating the response.
                    addProperty(
                        "stack",
                        e.stackTrace.firstOrNull()?.let {
                            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                        } ?: ""
                    )
                },
            )
        }
    }
}
