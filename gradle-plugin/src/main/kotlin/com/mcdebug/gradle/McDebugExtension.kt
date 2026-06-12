package com.mcdebug.gradle

import javax.inject.Inject

/**
 * Configuration block exposed to consumers as `mcdebug { ... }`.
 *
 * Properties are flat (not nested under a `test` sub-block) because
 * `test` is the name of Gradle's standard Test task and shadowing it
 * breaks the Groovy DSL — `mcdebug { test { ... } }` would resolve
 * `test` to `:core:test` instead of the intended sub-config.
 */
open class McDebugExtension @Inject constructor() {
    /**
     * Packages to scan for `@McDebugTests` annotated classes.
     *
     * **Required.** The task fails fast with a GradleException if this
     * is left empty — no auto-detection. The package list directly
     * scopes what the classpath scanner will enumerate, so getting it
     * wrong means either a silent miss (test classes never found) or
     * a slow scan (entire classpath walked). Better to make the
     * consumer write it explicitly.
     */
    var scanPackages: List<String> = emptyList()

    /**
     * Gradle path of the loom `runServer` task to invoke.
     * Multi-project consumers (e.g. `:core:runServer`) override this.
     */
    var runServerTask: String = "runServer"

    /**
     * Max seconds to wait for the MC server to write the port file
     * before failing the task. Loom cold start can be 60–120s.
     */
    var timeoutSec: Int = 180
}

