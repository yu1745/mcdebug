package com.mcdebug.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The `mcdebugTest` task:
 *   1. Starts the loom `runServer` subprocess (so the mod is loaded with all
 *      `@McDebugTests`-annotated classes on the classpath).
 *   2. Waits for `<project>/run/mcdebug/port` to appear (max `timeoutSec`).
 *   3. Invokes the local `mcdebug-client/dist/cli.js runTests` subcommand
 *      (which talks JSON-RPC to the running mod).
 *   4. Always tears the server down in `finally`, even on test failure.
 *
 * Consumer-facing knobs live on [McDebugExtension.TestConfig].
 *
 * **v0.1 skeleton**: the @TaskAction body is a stub. The real orchestration
 * lands in commit 1.5 — see plans/mcdebug-block-tests.md.
 */
abstract class McDebugTestTask : DefaultTask() {
    @get:Input
    abstract val scanPackages: ListProperty<String>

    @get:Input
    abstract val cliPath: Property<String>

    @get:Input
    abstract val runServerTask: Property<String>

    @get:Input
    abstract val timeoutSec: Property<Int>

    @TaskAction
    fun run() {
        val resolvedScanPackages = resolveScanPackages()
        logger.lifecycle(
            "mcdebugTest: would start ${runServerTask.get()} and invoke " +
            "${cliPath.get()} runTests for packages $resolvedScanPackages " +
            "(timeout=${timeoutSec.get()}s)"
        )
        logger.lifecycle("mcdebugTest: skeleton — no real work yet (commit 2 lands the scanner)")
    }

    private fun resolveScanPackages(): List<String> {
        val configured = scanPackages.get()
        if (configured.isNotEmpty()) return configured
        // Auto-detect: archives_base_name (Fabric convention) > group > dir name
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
}
