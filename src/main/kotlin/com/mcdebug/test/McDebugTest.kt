package com.mcdebug.test

/**
 * A test case that runs against a live mcdebug server.
 *
 * Consumers implement this as a Kotlin `object` (singleton) and reference it
 * via `::class` in `@McDebugTests`. The `name` is used in reports and for
 * `--filter` matching on the CLI side.
 *
 * Inside `run()` use the `McDebugTestApi` DSL to call RPC operations —
 * every method is a thin wrapper around `RpcDispatcher.dispatch(...)`, so
 * the test code runs on the MC server thread automatically.
 */
interface McDebugTest {
  /**
   * Human-readable test name. Defaults to the implementing `object`'s
   * simple class name. Override for a more descriptive label.
   */
  val name: String get() = this::class.simpleName ?: "unnamed"

  /**
   * Execute the test. Throw on failure (any exception type works — the
   * registry captures the message). Return normally on success.
   *
   * Tests run sequentially on the MC server thread; do not block waiting
   * for external resources.
   */
  fun run()
}
