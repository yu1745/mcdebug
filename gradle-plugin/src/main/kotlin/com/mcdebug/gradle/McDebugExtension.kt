package com.mcdebug.gradle

import javax.inject.Inject

/**
 * Configuration block exposed to consumers as `mcdebug { test { ... } }`.
 *
 * Defaults are auto-detected from the consuming project (mod id / group), so
 * most consumers only need to `apply plugin: 'com.mcdebug'` and nothing else.
 */
open class McDebugExtension @Inject constructor() {
    val test: TestConfig = TestConfig()

    open class TestConfig {
        /**
         * Packages to scan for `@McDebugTests` annotated classes.
         * Empty list means "auto-detect" — the plugin will use
         * `archives_base_name` from gradle.properties, or `project.group`,
         * or the project directory name, in that order.
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
}
