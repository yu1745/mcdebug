package com.mcdebug.runner

import com.mcdebug.cli.Box
import com.mcdebug.cli.DebugApi
import com.mcdebug.cli.Pos
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 测试上下文：每测试方法一个实例，origin 为网格分配坐标。 */
class TestContext(
    val api: DebugApi,
    val origin: Pos,
    val annotation: McDebugTest,
) {
    /** origin 偏移坐标（默认 origin 本身）。 */
    fun pos(dx: Int = 0, dy: Int = 0, dz: Int = 0): Pos =
        listOf(origin[0] + dx, origin[1] + dy, origin[2] + dz)
}

// ---- world helpers ----

fun place(ctx: TestContext, pos: Pos, block: String) {
    ctx.api.world.setBlock(pos, block)
}

fun placeAsPlayer(ctx: TestContext, pos: Pos, block: String, face: String, neighbor: Pos? = null, playerFacing: String? = null) {
    ctx.api.world.placeAsPlayer(pos, block, face, neighbor, playerFacing)
}

fun setBlocks(ctx: TestContext, ops: List<Pair<Pos, String>>, props: Map<String, String>? = null) {
    ctx.api.world.setBlocks(ops.map { (pos, block) ->
        mapOf("pos" to pos, "block" to block, "stateProps" to props)
    })
}

fun getBlockId(ctx: TestContext, pos: Pos): String =
    ctx.api.world.getBlock(pos).asJsonObject.get("state").asJsonObject.get("name").asString

fun getBlockProp(ctx: TestContext, pos: Pos, name: String): String? {
    val props = ctx.api.world.getBlock(pos).asJsonObject.get("state").asJsonObject.get("props")
    return if (props is JsonObject && props.has(name)) props.get(name).asString else null
}

fun assertBlockId(ctx: TestContext, pos: Pos, expected: String) {
    val actual = getBlockId(ctx, pos)
    if (actual != expected) throw AssertionError("expected block $expected at $pos, got $actual")
}

fun assertBlockNotId(ctx: TestContext, pos: Pos, unexpected: String) {
    val actual = getBlockId(ctx, pos)
    if (actual == unexpected) throw AssertionError("expected block at $pos to NOT be $unexpected, but it was")
}

// ---- block entity helpers ----

fun setBeField(ctx: TestContext, pos: Pos, path: String, value: Any) {
    ctx.api.be.setField(pos, path, value)
}

fun getBeField(ctx: TestContext, pos: Pos, path: String): JsonElement =
    ctx.api.be.getField(pos, path).asJsonObject.get("value")

fun getBeNumber(ctx: TestContext, pos: Pos, path: String): Double {
    val v = getBeField(ctx, pos, path)
    val n = v.asJsonPrimitive?.asDouble ?: throw AssertionError("expected numeric BE field $path, got $v")
    return n
}

// ---- inventory helpers ----

fun insertItem(ctx: TestContext, pos: Pos, item: String, count: Int, slot: Int) {
    ctx.api.inv.insert(pos, item, count, slot)
}

fun setSlot(ctx: TestContext, pos: Pos, slot: Int, item: String, count: Int, nbt: Any? = null) {
    ctx.api.inv.setSlot(pos, slot, item, count, nbt)
}

fun getSlot(ctx: TestContext, pos: Pos, slot: Int): JsonObject =
    ctx.api.inv.getSlot(pos, slot).asJsonObject.get("slot").asJsonObject

fun getSlotItem(ctx: TestContext, pos: Pos, slot: Int): String? {
    val el = getSlot(ctx, pos, slot).get("item")
    return if (el == null || el.isJsonNull) null else el.asString
}

fun getSlotCount(ctx: TestContext, pos: Pos, slot: Int): Int =
    getSlot(ctx, pos, slot).get("count").asInt

fun assertSlotHas(ctx: TestContext, pos: Pos, slot: Int, item: String) {
    val actual = getSlotItem(ctx, pos, slot)
    if (actual != item) throw AssertionError("expected slot $slot at $pos to contain $item, got ${actual ?: "empty"}")
}

fun assertSlotEmpty(ctx: TestContext, pos: Pos, slot: Int) {
    val stack = getSlot(ctx, pos, slot)
    if (stack.get("item")?.takeIf { !it.isJsonNull } != null || stack.get("count").asInt != 0) {
        throw AssertionError("expected slot $slot at $pos to be empty, got $stack")
    }
}

fun assertSlotCount(ctx: TestContext, pos: Pos, slot: Int, expectedCount: Int) {
    val actual = getSlotCount(ctx, pos, slot)
    if (actual != expectedCount) throw AssertionError("expected slot $slot at $pos count $expectedCount, got $actual")
}

// ---- wait helpers ----

fun waitUntil(ctx: TestContext, predicate: String, timeoutTicks: Int) {
    ctx.api.wait.until(predicate, timeoutTicks)
}

fun waitTicks(ctx: TestContext, ticks: Int) {
    val status = ctx.api.server.status().asJsonObject
    val now = status.get("tick").asLong
    ctx.api.wait.until("tick >= ${now + ticks}", ticks + 20)
}

// ---- trace helper ----

/** 包一层 trace：成功静默返回 trace；失败时把 trace 帧附加到异常消息再抛出。 */
fun <T> withTrace(ctx: TestContext, options: Map<String, Any?>, run: () -> T): Pair<T, JsonObject> {
    val started = ctx.api.trace.start(options).asJsonObject
    val traceId = started.get("traceId").asString
    try {
        val result = run()
        return result to ctx.api.trace.stop(traceId).asJsonObject
    } catch (e: Throwable) {
        val frames = try {
            ctx.api.trace.stop(traceId).asJsonObject
        } catch (_: Exception) {
            null
        }
        if (frames != null) {
            throw AssertionError("${e.message}\ntrace frames:\n$frames", e)
        }
        throw e
    }
}

fun traceBoxAround(pos: Pos, radius: Int = 1): Box = mapOf(
    "from" to listOf(pos[0] - radius, pos[1] - radius, pos[2] - radius),
    "to" to listOf(pos[0] + radius, pos[1] + radius, pos[2] + radius),
)
