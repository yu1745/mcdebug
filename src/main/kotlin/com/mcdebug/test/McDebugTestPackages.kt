package com.mcdebug.test

/**
 * Mark a class as owning all mcdebug tests in one or more packages.
 *
 * Each listed package is scanned for top-level Kotlin `object`s implementing
 * [McDebugTest]. This keeps block classes concise when a block has many tests:
 *
 * ```
 * @McDebugTestPackages("my.mod.tests.compressor")
 * class CompressorBlock : Block(...)
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class McDebugTestPackages(vararg val packages: String)
