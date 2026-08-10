package com.mcdebug.runner

import com.mcdebug.cli.Pos

/**
 * wait.until 谓词构建器（对照 TS test-runner.ts 的 predicate builders）。
 * 服务端 grammar（WaitOps.kt / PredicateExpr.kt）：
 *   block[x,y,z].id|prop.<name>  <op> <value>
 *   be[x,y,z].<jsonPointer>      <op> <value>
 *   inv[x,y,z].<slot>.item|count <op> <value>
 *   tick <op> <value>
 * ops: == != < <= > >=    values: number | "string" | true | false | null
 */

private fun fmtLit(value: Any?): String = when (value) {
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    else -> value.toString()
}

private fun posOf(pos: Pos): String = "${pos[0]},${pos[1]},${pos[2]}"

// ---- block predicates ----

fun blockId(pos: Pos, id: String): String = "block[${posOf(pos)}].id == ${fmtLit(id)}"
fun blockNotId(pos: Pos, id: String): String = "block[${posOf(pos)}].id != ${fmtLit(id)}"
fun blockProp(pos: Pos, name: String, value: Any): String = "block[${posOf(pos)}].prop.$name == ${fmtLit(value)}"

// ---- block-entity predicates ----

fun beFieldEquals(pos: Pos, path: String, value: Any?): String = "be[${posOf(pos)}].$path == ${fmtLit(value)}"
fun beFieldNotEquals(pos: Pos, path: String, value: Any?): String = "be[${posOf(pos)}].$path != ${fmtLit(value)}"
fun beFieldGreaterThan(pos: Pos, path: String, value: Number): String = "be[${posOf(pos)}].$path > $value"
fun beFieldLessThan(pos: Pos, path: String, value: Number): String = "be[${posOf(pos)}].$path < $value"
fun beFieldGreaterOrEqual(pos: Pos, path: String, value: Number): String = "be[${posOf(pos)}].$path >= $value"
fun beFieldLessOrEqual(pos: Pos, path: String, value: Number): String = "be[${posOf(pos)}].$path <= $value"

// ---- inventory predicates ----

fun invItem(pos: Pos, slot: Int, itemId: String): String = "inv[${posOf(pos)}].$slot.item == ${fmtLit(itemId)}"
fun invItemNot(pos: Pos, slot: Int, itemId: String): String = "inv[${posOf(pos)}].$slot.item != ${fmtLit(itemId)}"
fun invCountEquals(pos: Pos, slot: Int, count: Int): String = "inv[${posOf(pos)}].$slot.count == $count"
fun invCountGreaterThan(pos: Pos, slot: Int, count: Int): String = "inv[${posOf(pos)}].$slot.count > $count"
fun invCountGreaterOrEqual(pos: Pos, slot: Int, count: Int): String = "inv[${posOf(pos)}].$slot.count >= $count"
fun invCountLessThan(pos: Pos, slot: Int, count: Int): String = "inv[${posOf(pos)}].$slot.count < $count"
fun invCountLessOrEqual(pos: Pos, slot: Int, count: Int): String = "inv[${posOf(pos)}].$slot.count <= $count"

// ---- tick predicates ----

fun tickEquals(tick: Long): String = "tick == $tick"
fun tickGreaterOrEqual(tick: Long): String = "tick >= $tick"
