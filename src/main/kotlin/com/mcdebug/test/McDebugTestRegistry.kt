package com.mcdebug.test

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of mcdebug test cases.
 *
 * Populated by `TestDiscovery` at server start; queried by the
 * `mcdebug.runAllTests` RPC handler. Thread-safe.
 */
object McDebugTestRegistry {
  private val byKey = ConcurrentHashMap<String, McDebugTest>()

  fun register(test: McDebugTest) {
    byKey[test.name] = test
  }

  fun all(): Collection<McDebugTest> = byKey.values

  fun clear() {
    byKey.clear()
  }

  fun count(): Int = byKey.size
}
