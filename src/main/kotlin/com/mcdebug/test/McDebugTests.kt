package com.mcdebug.test

import kotlin.reflect.KClass

/**
 * Mark a block (or any other) class as having mcdebug test cases.
 *
 * Usage:
 * ```
 * object IronFurnacePlaceTest : McDebugTest { ... }
 * object IronFurnaceSmeltTest : McDebugTest { ... }
 *
 * @McDebugTests(IronFurnacePlaceTest::class, IronFurnaceSmeltTest::class)
 * class IronFurnaceBlock : Block(...)
 * ```
 *
 * The annotation is read at server start by `TestDiscovery.discover(...)`
 * which scans the configured base packages. Each referenced test class
 * is instantiated (must be a Kotlin `object`) and registered with
 * `McDebugTestRegistry`.
 *
 * Retention is RUNTIME so kotlin-reflect can read it; target is CLASS so
 * it can sit on regular class declarations.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class McDebugTests(vararg val testClasses: KClass<out McDebugTest>)
