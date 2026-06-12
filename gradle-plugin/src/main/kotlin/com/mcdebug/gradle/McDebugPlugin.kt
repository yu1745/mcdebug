package com.mcdebug.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `id "com.mcdebug"` — provides the `mcdebug` extension and the
 * `mcdebugTest` task. Idempotent; safe to apply in any subproject.
 */
class McDebugPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("mcdebug", McDebugExtension::class.java)
        project.tasks.register("mcdebugTest", McDebugTestTask::class.java) { task ->
            task.group = "verification"
            task.description = "Run mcdebug-driven block tests against a dev MC server"
            task.scanPackages.set(ext.test.scanPackages)
            task.cliPath.set(ext.test.cliPath)
            task.runServerTask.set(ext.test.runServerTask)
            task.timeoutSec.set(ext.test.timeoutSec)
            // Build the mod first so the JAR (with @McDebugTests classes) is fresh
            task.dependsOn("build")
        }
    }
}
