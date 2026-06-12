package com.mcdebug.test

import com.google.gson.JsonArray

/**
 * Block position triple. Mirrors the JSON shape the RPC layer uses for
 * `pos` params (`[x, y, z]`). Used by [McDebugTestApi] methods.
 */
data class Pos(val x: Int, val y: Int, val z: Int) {
    fun toJson(): JsonArray = JsonArray().apply { add(x); add(y); add(z) }
    override fun toString(): String = "($x, $y, $z)"

    companion object {
        /** A safe per-test origin, far from world spawn (0,0) and any leftover state. */
        val TEST_ORIGIN: Pos = Pos(100, 64, 100)
    }
}
