package com.mcdebug.wait

import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.wait.PredicateExpr.Aggregate
import com.mcdebug.wait.PredicateExpr.Node
import com.mcdebug.wait.PredicateExpr.SourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Unit tests for the wait.until predicate DSL. PredicateExpr is deliberately
 * free of Minecraft types, so these run on the plain JVM without Loom/MC on
 * the classpath (only gson via RpcException's import chain).
 */
class PredicateExprTest {

    // ---- parse: structural assertions ----

    private fun parse(expr: String): Node = PredicateExpr.parse(expr)

    private fun assertIsComparison(node: Node, op: String) {
        val c = assertInstanceOf(Node.Comparison::class.java, node)
        assertEquals(op, c.op)
    }

    // ---- parse: v1 backward compatibility (single comparisons) ----

    @Test
    fun `v1 be comparison`() {
        val n = parse("""be[0,64,0].BurnTime > 0""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        assertEquals(">", c.op)
        val lhs = assertInstanceOf(SourceRef::class.java, c.left)
        assertEquals("be", lhs.source)
        assertEquals(Triple(0, 64, 0), lhs.pos)
        assertEquals("BurnTime", lhs.path)
        val rhs = assertInstanceOf(Node.Literal::class.java, c.right)
        assertEquals(JsonPrimitive(0L), rhs.value)
    }

    @Test
    fun `v1 tick comparison`() {
        val n = parse("""tick >= 100""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        assertEquals(">=", c.op)
        val lhs = assertInstanceOf(SourceRef::class.java, c.left)
        assertEquals("tick", lhs.source)
        assertEquals(null, lhs.pos)
        assertEquals("", lhs.path)
    }

    @Test
    fun `v1 block id string comparison`() {
        val n = parse("""block[0,64,0].id == "minecraft:water"""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        assertEquals("==", c.op)
        val rhs = assertInstanceOf(Node.Literal::class.java, c.right)
        assertEquals(JsonPrimitive("minecraft:water"), rhs.value)
    }

    @Test
    fun `v1 block prop bool comparison`() {
        val n = parse("""block[0,64,0].prop.lit == true""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        assertEquals("==", c.op)
        val rhs = assertInstanceOf(Node.Literal::class.java, c.right)
        assertEquals(JsonPrimitive(true), rhs.value)
    }

    @Test
    fun `v1 inv slot item comparison`() {
        val n = parse("""inv[0,64,0].0.item == "minecraft:iron_ingot"""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val lhs = assertInstanceOf(SourceRef::class.java, c.left)
        assertEquals("inv", lhs.source)
        assertEquals("0.item", lhs.path)
    }

    // ---- parse: boolean combinations ----

    @Test
    fun `AND of two comparisons`() {
        val n = parse("""be[0,64,0].charge > 100 AND inv[0,64,0].0.item == "minecraft:redstone"""")
        val and = assertInstanceOf(Node.And::class.java, n)
        assertIsComparison(and.left, ">")
        assertIsComparison(and.right, "==")
    }

    @Test
    fun `OR of two comparisons`() {
        val n = parse("""be[0,64,0].mode == "active" OR tick > 1000""")
        val or = assertInstanceOf(Node.Or::class.java, n)
        assertIsComparison(or.left, "==")
        assertIsComparison(or.right, ">")
    }

    @Test
    fun `NOT of a comparison`() {
        val n = parse("""NOT (tick > 1000)""")
        val not = assertInstanceOf(Node.Not::class.java, n)
        assertIsComparison(not.inner, ">")
    }

    @Test
    fun `NOT binds tighter than AND`() {
        // NOT a AND b  ==  (NOT a) AND b
        val n = parse("""NOT tick > 1000 AND be[0,64,0].x == 1""")
        val and = assertInstanceOf(Node.And::class.java, n)
        assertInstanceOf(Node.Not::class.java, and.left)
        assertInstanceOf(Node.Comparison::class.java, and.right)
    }

    @Test
    fun `parenthesized boolean subexpression`() {
        // (a OR b) AND c  — the parens group the OR
        val n = parse("""(tick > 1 OR tick > 2) AND be[0,64,0].x == 1""")
        val and = assertInstanceOf(Node.And::class.java, n)
        // left of AND is the parenthesised OR
        assertInstanceOf(Node.Or::class.java, and.left)
        assertInstanceOf(Node.Comparison::class.java, and.right)
    }

    @Test
    fun `nested parentheses`() {
        val n = parse("""((tick > 1))""")
        assertIsComparison(n, ">")
    }

    @Test
    fun `AND binds tighter than OR`() {
        // a AND b OR c  ==  (a AND b) OR c
        val n = parse("""tick > 1 AND tick > 2 OR tick > 3""")
        val or = assertInstanceOf(Node.Or::class.java, n)
        assertInstanceOf(Node.And::class.java, or.left)
        assertInstanceOf(Node.Comparison::class.java, or.right)
    }

    // ---- parse: arithmetic ----

    @Test
    fun `addition on comparison lhs`() {
        val n = parse("""be[0,64,0].a + be[0,64,0].b > 100""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val lhs = assertInstanceOf(Node.Arith::class.java, c.left)
        assertEquals("+", lhs.op)
        assertInstanceOf(SourceRef::class.java, lhs.left)
        assertInstanceOf(SourceRef::class.java, lhs.right)
    }

    @Test
    fun `multiplication on comparison lhs`() {
        val n = parse("""inv[0,64,0].0.count * 2 >= 64""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val lhs = assertInstanceOf(Node.Arith::class.java, c.left)
        assertEquals("*", lhs.op)
    }

    @Test
    fun `multiplication binds tighter than addition`() {
        // a + b * c  ==  a + (b * c)
        val n = parse("""be[0,64,0].a + be[0,64,0].b * be[0,64,0].c > 100""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val plus = assertInstanceOf(Node.Arith::class.java, c.left)
        assertEquals("+", plus.op)
        assertInstanceOf(SourceRef::class.java, plus.left)
        // right of + must be the multiplication
        val times = assertInstanceOf(Node.Arith::class.java, plus.right)
        assertEquals("*", times.op)
    }

    @Test
    fun `unary minus on literal`() {
        val n = parse("""be[0,64,0].a > -5""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val rhs = assertInstanceOf(Node.Negate::class.java, c.right)
        val inner = assertInstanceOf(Node.Literal::class.java, rhs.inner)
        assertEquals(JsonPrimitive(5L), inner.value)
    }

    // ---- parse: aggregates ----

    @Test
    fun `sum aggregate over inventory slots`() {
        val n = parse("""sum(inv[0,64,0].*.count) >= 64""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val agg = assertInstanceOf(Aggregate::class.java, c.left)
        assertEquals("sum", agg.fn)
        assertEquals(Triple(0, 64, 0), agg.pos)
        assertEquals("count", agg.field)
    }

    @Test
    fun `count aggregate over inventory item slots`() {
        val n = parse("""count(inv[0,64,0].*.item) > 0""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val agg = assertInstanceOf(Aggregate::class.java, c.left)
        assertEquals("count", agg.fn)
        assertEquals("item", agg.field)
    }

    @Test
    fun `aggregate with nbt path field`() {
        val n = parse("""sum(inv[0,64,0].*.nbt.charge) > 1000""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val agg = assertInstanceOf(Aggregate::class.java, c.left)
        assertEquals("nbt.charge", agg.field)
    }

    // ---- parse: literals & null ----

    @Test
    fun `null literal comparison`() {
        val n = parse("""be[0,64,0].X == null""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val rhs = assertInstanceOf(Node.Literal::class.java, c.right)
        assertEquals(JsonNull.INSTANCE, rhs.value)
    }

    @Test
    fun `fractional number literal`() {
        val n = parse("""be[0,64,0].X > 1.5""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val rhs = assertInstanceOf(Node.Literal::class.java, c.right)
        assertEquals(JsonPrimitive(1.5), rhs.value)
    }

    @Test
    fun `escaped quote in string literal`() {
        val n = parse("""be[0,64,0].X == "a\"b"""")
        val c = assertInstanceOf(Node.Comparison::class.java, n)
        val rhs = assertInstanceOf(Node.Literal::class.java, c.right)
        assertEquals(JsonPrimitive("a\"b"), rhs.value)
    }

    // ---- parse: rejection (negative cases) ----

    @Test
    fun `rejects bare source ref with no comparison`() {
        val e = assertThrows(RpcException::class.java) { parse("""be[0,64,0].BurnTime""") }
        assertEquals(RpcErrors.INVALID_PREDICATE, e.rpcCode)
    }

    @Test
    fun `rejects dangling AND`() {
        assertThrows(RpcException::class.java) { parse("""be[0,64,0] AND""") }
    }

    @Test
    fun `rejects aggregate without wildcard`() {
        // sum(inv[0,64,0].0.count) — aggregate must use * slot wildcard
        assertThrows(RpcException::class.java) { parse("""sum(inv[0,64,0].0.count)""") }
    }

    @Test
    fun `rejects unclosed parenthesis`() {
        assertThrows(RpcException::class.java) { parse("""(tick > 1""") }
    }

    @Test
    fun `rejects unclosed string`() {
        assertThrows(RpcException::class.java) { parse("""be[0,64,0].X == "oops""") }
    }

    @Test
    fun `rejects unknown identifier`() {
        assertThrows(RpcException::class.java) { parse("""foo[0,64,0].X > 1""") }
    }

    @Test
    fun `rejects trailing tokens after complete predicate`() {
        assertThrows(RpcException::class.java) { parse("""tick > 1 extra""") }
    }

    @Test
    fun `rejects malformed comparison operator`() {
        // single '=' is not a valid op (must be ==)
        assertThrows(RpcException::class.java) { parse("""tick = 1""") }
    }

    // ---- evaluate: boolean semantics with mock resolvers ----

    /**
     * Build a mock setup: leaf resolver returns a preset value per (source+pos+path),
     * aggregate resolver returns a preset value per (fn+pos+field). Lets us drive
     * the evaluator without any world state.
     */
    private class MockWorld {
        private val leaves = mutableMapOf<String, JsonPrimitive>()
        private val aggs = mutableMapOf<String, JsonPrimitive>()
        private val nullLeaves = mutableSetOf<String>()

        fun leaf(source: String, pos: Triple<Int, Int, Int>, path: String = "", value: Number) =
            apply { leaves[key(source, pos, path)] = JsonPrimitive(value) }

        fun leaf(source: String, pos: Triple<Int, Int, Int>, path: String = "", value: String) =
            apply { leaves[key(source, pos, path)] = JsonPrimitive(value) }

        fun leaf(source: String, pos: Triple<Int, Int, Int>, path: String = "", value: Boolean) =
            apply { leaves[key(source, pos, path)] = JsonPrimitive(value) }

        /** Register a pos-less leaf (e.g. `tick`, which has no coordinates). */
        fun leaf(source: String, path: String = "", value: Number) =
            apply { leaves[key(source, null, path)] = JsonPrimitive(value) }

        fun leafNull(source: String, pos: Triple<Int, Int, Int>, path: String = "") =
            apply { nullLeaves.add(key(source, pos, path)) }
        fun agg(fn: String, pos: Triple<Int, Int, Int>, field: String, value: Number) =
            apply { aggs[key(fn, pos, field)] = JsonPrimitive(value) }

        private fun key(source: String, pos: Triple<Int, Int, Int>?, path: String) =
            "$source$pos$path"

        fun eval(expr: String): Boolean {
            val node = PredicateExpr.parse(expr)
            return PredicateExpr.evaluate(
                node,
                resolveLeaf = { ref ->
                    val k = key(ref.source, ref.pos, ref.path)
                    if (k in nullLeaves) JsonNull.INSTANCE else leaves[k]
                },
                resolveAggregate = { a -> aggs[key(a.fn, a.pos, a.field)] ?: JsonPrimitive(0) },
            )
        }
    }

    private val pos = Triple(0, 64, 0)

    @Test
    fun `eval true when number comparison holds`() {
        assertTrue { MockWorld().leaf("be", pos, "BurnTime", 5).eval("""be[0,64,0].BurnTime > 0""") }
    }

    @Test
    fun `eval false when number comparison fails`() {
        assertFalse { MockWorld().leaf("be", pos, "BurnTime", 0).eval("""be[0,64,0].BurnTime > 0""") }
    }

    @Test
    fun `eval string equality`() {
        assertTrue { MockWorld().leaf("block", pos, "id", "minecraft:water").eval("""block[0,64,0].id == "minecraft:water"""") }
        assertFalse { MockWorld().leaf("block", pos, "id", "minecraft:stone").eval("""block[0,64,0].id == "minecraft:water"""") }
    }

    @Test
    fun `eval bool equality`() {
        assertTrue { MockWorld().leaf("block", pos, "prop.lit", true).eval("""block[0,64,0].prop.lit == true""") }
    }

    @Test
    fun `eval tick numeric`() {
        assertTrue { MockWorld().leaf("tick", "", 500).eval("""tick > 100""") }
    }

    @Test
    fun `eval AND requires both`() {
        val w = MockWorld()
            .leaf("be", pos, "charge", 200)
            .leaf("inv", pos, "0.item", "minecraft:redstone")
        assertTrue { w.eval("""be[0,64,0].charge > 100 AND inv[0,64,0].0.item == "minecraft:redstone"""") }
        // flip one side → false
        val w2 = MockWorld()
            .leaf("be", pos, "charge", 50)
            .leaf("inv", pos, "0.item", "minecraft:redstone")
        assertFalse { w2.eval("""be[0,64,0].charge > 100 AND inv[0,64,0].0.item == "minecraft:redstone"""") }
    }

    @Test
    fun `eval OR either side`() {
        val w = MockWorld()
            .leaf("be", pos, "mode", "active")
        assertTrue { w.eval("""be[0,64,0].mode == "active" OR tick > 1000""") }
    }

    @Test
    fun `eval NOT negates`() {
        val w = MockWorld().leaf("tick", pos, "", 5)
        assertTrue { w.eval("""NOT (tick > 1000)""") }
    }

    @Test
    fun `eval arithmetic addition`() {
        val w = MockWorld()
            .leaf("be", pos, "a", 30)
            .leaf("be", pos, "b", 80)
        assertTrue { w.eval("""be[0,64,0].a + be[0,64,0].b > 100""") }
    }

    @Test
    fun `eval arithmetic multiplication precedence`() {
        // a + b * c with a=1, b=2, c=3 → 1 + (2*3) = 7 > 6 → true
        val w = MockWorld()
            .leaf("be", pos, "a", 1)
            .leaf("be", pos, "b", 2)
            .leaf("be", pos, "c", 3)
        assertTrue { w.eval("""be[0,64,0].a + be[0,64,0].b * be[0,64,0].c > 6""") }
        // but NOT > 7 (since 7 is the result)
        assertFalse { w.eval("""be[0,64,0].a + be[0,64,0].b * be[0,64,0].c > 7""") }
    }

    @Test
    fun `eval aggregate sum`() {
        val w = MockWorld().agg("sum", pos, "count", 128)
        assertTrue { w.eval("""sum(inv[0,64,0].*.count) >= 64""") }
    }

    @Test
    fun `eval aggregate count`() {
        val w = MockWorld().agg("count", pos, "item", 3)
        assertTrue { w.eval("""count(inv[0,64,0].*.item) > 0""") }
        assertFalse { w.eval("""count(inv[0,64,0].*.item) > 5""") }
    }

    @Test
    fun `eval null equality with null leaf`() {
        val w = MockWorld().leafNull("be", pos, "X")
        assertTrue { w.eval("""be[0,64,0].X == null""") }
        assertFalse { w.eval("""be[0,64,0].X != null""") }
    }

    @Test
    fun `eval null inequality with non-null leaf`() {
        val w = MockWorld().leaf("be", pos, "X", 5)
        assertTrue { w.eval("""be[0,64,0].X != null""") }
        assertFalse { w.eval("""be[0,64,0].X == null""") }
    }

    @Test
    fun `eval comparison against missing leaf returns false for strict ops`() {
        // Leaf not registered → resolver returns null (Kotlin Map miss) → treated as json-null.
        // > 0 against null → false (null handling).
        val w = MockWorld()
        assertFalse { w.eval("""be[0,64,0].unset > 0""") }
    }

    @Test
    fun `eval type-mismatched comparison returns false for ==`() {
        // leaf is a string, compare against number
        val w = MockWorld().leaf("be", pos, "X", "hello")
        assertFalse { w.eval("""be[0,64,0].X == 5""") }
        assertTrue { w.eval("""be[0,64,0].X != 5""") }
    }

    @Test
    fun `eval complex nested expression`() {
        // (a > 1 OR b > 1) AND NOT (c == 0)
        val w = MockWorld()
            .leaf("be", pos, "a", 5)
            .leaf("be", pos, "b", 0)
            .leaf("be", pos, "c", 3)
        assertTrue { w.eval("""(be[0,64,0].a > 1 OR be[0,64,0].b > 1) AND NOT (be[0,64,0].c == 0)""") }
    }

    @Test
    fun `eval does not throw on unregistered aggregate`() {
        val w = MockWorld()  // no aggregates registered
        assertDoesNotThrow { w.eval("""sum(inv[0,64,0].*.count) >= 64""") }
    }
}
