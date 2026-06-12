package com.mcdebug.gradle

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The `mcdebugTest` task:
 *   1. Start the loom `runServer` subprocess (so the mod is loaded with all
 *      `@McDebugTests`-annotated classes on the classpath).
 *   2. Wait for `<project>/run/mcdebug/port` to appear (max `timeoutSec`).
 *   3. Open a TCP socket to the server, send a JSON-RPC `mcdebug.runAllTests`
 *      request, read the response.
 *   4. Print a GoogleTest-style report (PASS / FAIL with stack frame).
 *   5. Always tear the server down in `finally`, even on test failure.
 *
 * The JSON-RPC wire format mirrors what `mcdebug-client` does in TypeScript:
 * NDJSON, one object per line, no authentication, loopback only.
 */
abstract class McDebugTestTask : DefaultTask() {
    @get:Input
    abstract val scanPackages: ListProperty<String>

    @get:Input
    abstract val runServerTask: Property<String>

    @get:Input
    abstract val timeoutSec: Property<Int>

    @TaskAction
    fun run() {
        val resolvedScanPackages = resolveScanPackages()
        val portFile = project.layout.projectDirectory.file("run/mcdebug/port").asFile
        if (portFile.exists()) portFile.delete()

        logger.lifecycle("Starting MC server (${runServerTask.get()})...")
        val server = startServerProcess()
        try {
            val port = waitForPort(portFile, timeoutSec.get())
            logger.lifecycle("MC server on 127.0.0.1:$port; scanning $resolvedScanPackages for tests")

            val results = runAllTestsRpc(port, filter = "")
            printReport(results)

            val failed = results.count { it.asJsonObject.get("status").asString == "FAIL" }
            if (failed > 0) {
                throw GradleException("$failed/${results.size()} mcdebug tests FAILED")
            }
            logger.lifecycle("${results.size()}/${results.size()} mcdebug tests PASSED")
        } finally {
            logger.lifecycle("Stopping MC server...")
            server.destroy()
            if (!server.waitFor(10, TimeUnit.SECONDS)) {
                logger.warn("MC server did not stop in 10s, force-killing")
                server.destroyForcibly()
            }
        }
    }

    // --- helpers ---

    private fun resolveScanPackages(): List<String> {
        val configured = scanPackages.get()
        if (configured.isNotEmpty()) return configured
        val auto = listOfNotNull(
            project.findProperty("archives_base_name") as? String,
            project.group.toString().takeIf { it.isNotBlank() && it != "unspecified" },
            project.projectDir.name,
        )
        if (auto.isEmpty()) {
            throw GradleException(
                "mcdebug.test.scanPackages is empty and project has no " +
                "archives_base_name / group to auto-detect from. " +
                "Set `mcdebug { test { scanPackages = ['your.pkg'] } }`."
            )
        }
        return listOf(auto.first())
    }

    private fun startServerProcess(): Process {
        val gradlew = project.rootProject.file("gradlew")
        val logFile = project.layout.buildDirectory
            .file("mcdebugTest/server.log").get().asFile
        logFile.parentFile.mkdirs()
        val process = ProcessBuilder(gradlew.absolutePath, runServerTask.get(), "--no-daemon")
            .directory(project.rootDir)
            .redirectErrorStream(true)
            .start()
        // Drain stdout/stderr to a log file asynchronously — without this the
        // OS pipe buffer (~64 KiB) fills up within seconds of MC server
        // startup and the subprocess blocks. Log file is surfaced in the
        // timeout error so users can grep for the real cause.
        Thread({
            runCatching {
                logFile.outputStream().buffered().use { out ->
                    process.inputStream.copyTo(out)
                }
            }
        }, "mcdebug-server-log-drain").apply { isDaemon = true; start() }
        // Stash for the timeout error message
        serverLogFile = logFile
        return process
    }

    @Transient
    private var serverLogFile: File? = null

    private fun waitForPort(portFile: File, timeoutSec: Int): Int {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (portFile.exists()) {
                runCatching { portFile.readText().trim().toInt() }
                    .getOrNull()?.takeIf { it in 1..65535 }?.let { return it }
            }
            Thread.sleep(1000)
        }
        throw GradleException(
            "MC server didn't write $portFile within ${timeoutSec}s. " +
            "Inspect server log: ${serverLogFile ?: "(no log file captured)"}"
        )
    }

    private fun runAllTestsRpc(port: Int, filter: String): JsonArray {
        val gson = Gson()
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", 1)
            addProperty("method", "mcdebug.runAllTests")
            val params = JsonObject()
            if (filter.isNotEmpty()) params.addProperty("filter", filter)
            add("params", params)
        }
        val line = gson.toJson(request)

        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 60_000
            val writer = PrintWriter(sock.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            writer.println(line)

            // Drain the SERVER_READY notification line (we don't need it,
            // but the server emits it on connect per RpcServer.kt:122-126).
            val first = reader.readLine()
                ?: throw GradleException("MC server closed connection before responding")

            // The second line is the response to our request.
            val response = reader.readLine()
                ?: throw GradleException("MC server closed connection after SERVER_READY (got: $first)")

            val json = JsonParser.parseString(response).asJsonObject
            if (json.has("error")) {
                val errObj = json.getAsJsonObject("error")
                throw GradleException(
                    "mcdebug.runAllTests RPC error: ${errObj.get("message").asString} " +
                    "(code ${errObj.get("code").asInt})"
                )
            }
            val result = json.getAsJsonObject("result")
            return result.getAsJsonArray("results")
        }
    }

    private fun printReport(results: JsonArray) {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("[==========] Running ${results.size()} mcdebug test(s)")
        for (res in results) {
            val obj = res.asJsonObject
            val name = obj.get("testName").asString
            val status = obj.get("status").asString
            val dur = obj.get("durationMs").asLong
            when (status) {
                "PASS" -> sb.appendLine("[       OK ] $name (${dur}ms)")
                "FAIL" -> {
                    sb.appendLine("[  FAILED  ] $name (${dur}ms)")
                    obj.get("error")?.asString?.let { sb.appendLine("             error:  $it") }
                    obj.get("stack")?.asString?.takeIf { it.isNotEmpty() }
                        ?.let { sb.appendLine("             at:     $it") }
                }
                else -> sb.appendLine("[  ??????? ] $name (status=$status)")
            }
        }
        sb.appendLine("[==========] ${results.size()} test(s) reported")
        logger.lifecycle(sb.toString())
    }
}
