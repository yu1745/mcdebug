package com.mcdebug.runner

import com.mcdebug.cli.DebugApi
import com.mcdebug.cli.Pos
import com.mcdebug.cli.RpcClient
import com.mcdebug.cli.RpcClientOptions
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestWatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 标注测试类：每个 @Test 方法获得独立的 TestContext（网格 origin、清理、forceload）。
 *
 * 用法（consumer 侧）：
 * ```
 * @McDebugTest
 * class MaceratorTest {
 *     @Test fun grindsOre(ctx: TestContext) {
 *         place(ctx, ctx.origin, "ic2_120:macerator")
 *         waitUntil(ctx, invItem(ctx.origin, 1, "minecraft:coal"), 600)
 *     }
 * }
 * ```
 *
 * 连接发现与 CLI 一致：--socket / MCDEBUG_SOCKET / mcdebug/port 文件。
 * 并行：junit-platform.properties 已启用方法级并发（见 resources），
 * origin 分配是原子计数器，线程安全。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(McDebugExtension::class)
annotation class McDebugTest(
    val originX: Int = 100,
    val originY: Int = 64,
    val originZ: Int = 100,
    val stride: Int = 32,
    val gridColumns: Int = 16,
    val clearMinOffset: IntArray = [-8, -1, -8],
    val clearMaxOffset: IntArray = [9, 6, 9],
    val batchSize: Int = 512,
)

class McDebugExtension : BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver, TestWatcher {

    companion object {
        private val NS = ExtensionContext.Namespace.create(McDebugExtension::class)
        private val originCounter = AtomicInteger(0)
        private const val CTX = "ctx"
        private const val CHUNKS = "chunks"
    }

    override fun beforeAll(context: ExtensionContext) {}

    override fun afterAll(context: ExtensionContext) {}

    override fun beforeEach(context: ExtensionContext) {
        val ann = context.requiredTestClass.getAnnotation(McDebugTest::class.java)
            ?: return
        val api = DebugApi(RpcClient(RpcClientOptions()))
        val idx = originCounter.getAndIncrement()
        val col = idx % ann.gridColumns
        val row = idx / ann.gridColumns
        val origin: Pos = listOf(
            ann.originX + col * ann.stride,
            ann.originY,
            ann.originZ + row * ann.stride,
        )
        val ctx = TestContext(api, origin, ann)
        context.getStore(NS).put(CTX, ctx)

        // 准备：forceload 相关 chunk + 清空区域（对齐 TS runner 的 prepareArea）
        val chunks = prepareArea(api, origin, ann)
        context.getStore(NS).put(CHUNKS, chunks)
    }

    override fun afterEach(context: ExtensionContext) {
        val store = context.getStore(NS)
        val ctx = store.get(CTX, TestContext::class.java) ?: return
        @Suppress("UNCHECKED_CAST")
        val chunks = (store.get(CHUNKS, List::class.java) as? List<Pair<Int, Int>>) ?: emptyList()
        try {
            clearArea(ctx.api, areaMin(ctx.origin, ctx.annotation), areaMax(ctx.origin, ctx.annotation), ctx.annotation.batchSize)
            chunks.forEach { (cx, cz) -> runCatching { ctx.api.world.unforceloadChunk(cx, cz) } }
        } finally {
            runCatching { ctx.api.rpc.close() }
        }
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type == TestContext::class.java

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any =
        extensionContext.getStore(NS).get(CTX, TestContext::class.java)
            ?: error("McDebugExtension.beforeEach did not run for this test")

    override fun testFailed(context: ExtensionContext, cause: Throwable) {
        // withTrace 已把 trace 帧附加到异常上；这里无需额外处理。
    }
}

// ---- area preparation (mirrors TS test-runner.ts) ----

internal fun areaMin(origin: Pos, ann: McDebugTest): Pos = listOf(
    origin[0] + ann.clearMinOffset[0],
    origin[1] + ann.clearMinOffset[1],
    origin[2] + ann.clearMinOffset[2],
)

internal fun areaMax(origin: Pos, ann: McDebugTest): Pos = listOf(
    origin[0] + ann.clearMaxOffset[0],
    origin[1] + ann.clearMaxOffset[1],
    origin[2] + ann.clearMaxOffset[2],
)

internal fun chunksForBox(min: Pos, max: Pos): List<Pair<Int, Int>> {
    val chunks = LinkedHashSet<Pair<Int, Int>>()
    for (x in min[0]..max[0]) {
        for (z in min[2]..max[2]) {
            chunks.add(Math.floorDiv(x, 16) to Math.floorDiv(z, 16))
        }
    }
    return chunks.toList()
}

internal fun clearArea(api: DebugApi, min: Pos, max: Pos, batchSize: Int = 512) {
    val ops = ArrayList<Map<String, Any?>>()
    for (x in min[0]..max[0]) {
        for (y in min[1]..max[1]) {
            for (z in min[2]..max[2]) {
                ops.add(mapOf("pos" to listOf(x, y, z), "block" to "minecraft:air"))
            }
        }
    }
    for (i in ops.indices step batchSize) {
        api.world.setBlocks(ops.subList(i, min(i + batchSize, ops.size)))
    }
}

internal fun prepareArea(api: DebugApi, origin: Pos, ann: McDebugTest): List<Pair<Int, Int>> {
    val min = areaMin(origin, ann)
    val max = areaMax(origin, ann)
    val chunks = chunksForBox(min, max)
    chunks.forEach { (cx, cz) -> api.world.forceloadChunk(cx, cz) }
    clearArea(api, min, max, ann.batchSize)
    return chunks
}
