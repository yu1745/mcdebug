package com.mcdebug.test

/**
 * Per-test world allocation supplied by the mcdebug dispatcher.
 *
 * [origin] is the primary position tests should treat as their local (0,0,0).
 * [min] and [max] bound the cleaned/force-loaded area reserved for the test.
 */
data class McDebugTestContext(
    val testName: String,
    val index: Int,
    val origin: Pos,
    val min: Pos,
    val max: Pos,
) {
    fun pos(dx: Int = 0, dy: Int = 0, dz: Int = 0): Pos =
        Pos(origin.x + dx, origin.y + dy, origin.z + dz)

    fun chunks(): Set<Pair<Int, Int>> {
        val minCx = Math.floorDiv(min.x, 16)
        val maxCx = Math.floorDiv(max.x, 16)
        val minCz = Math.floorDiv(min.z, 16)
        val maxCz = Math.floorDiv(max.z, 16)
        val out = linkedSetOf<Pair<Int, Int>>()
        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                out.add(cx to cz)
            }
        }
        return out
    }

    fun positions(): Sequence<Pos> = sequence {
        for (x in min.x..max.x) {
            for (y in min.y..max.y) {
                for (z in min.z..max.z) {
                    yield(Pos(x, y, z))
                }
            }
        }
    }
}
