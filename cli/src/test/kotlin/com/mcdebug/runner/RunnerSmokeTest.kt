package com.mcdebug.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JUnit runner 冒烟测试（需要 mcdebug server 在跑，socket 发现走 MCDEBUG_SOCKET / port 文件）。
 * 验证：extension 注入 TestContext、origin 网格分配、区域清理、并行执行、断言/谓词 helper。
 */
@McDebugTest
class RunnerSmokeTest {

    @Test
    fun placeAndInspect(ctx: TestContext) {
        place(ctx, ctx.origin, "minecraft:chest")
        assertBlockId(ctx, ctx.origin, "minecraft:chest")
        setSlot(ctx, ctx.origin, 0, "minecraft:coal", 5)
        assertSlotCount(ctx, ctx.origin, 0, 5)
        assertSlotHas(ctx, ctx.origin, 0, "minecraft:coal")
        waitUntil(ctx, invCountEquals(ctx.origin, 0, 5), 200)
    }

    @Test
    fun beFieldRoundTrip(ctx: TestContext) {
        place(ctx, ctx.origin, "minecraft:furnace")
        // CustomName 不随时间变化（BurnTime 每 tick 递减，不适合断言精确值）。
        // MC 会把 CustomName 规范化成 JSON 文本组件（{"text":...}），所以只断言往返可读。
        setBeField(ctx, ctx.origin, "CustomName", "\"mcdebug-test\"")
        assertTrue(getBeField(ctx, ctx.origin, "CustomName").toString().contains("mcdebug-test"))
    }

    @Test
    fun areaCleanupBetweenTests(ctx: TestContext) {
        // 上一个测试的箱子/熔炉应已被 afterEach 清掉：origin 处应为 air。
        assertEquals("minecraft:air", getBlockId(ctx, ctx.origin))
    }

    @Test
    fun waitTimeoutThrows(ctx: TestContext) {
        val e = try {
            ctx.api.wait.until("inv[${ctx.origin[0]},${ctx.origin[1]},${ctx.origin[2]}].0.count == 999", 10)
            null
        } catch (ex: Exception) {
            ex
        }
        assertNotNull(e, "wait.until with unsatisfiable predicate must time out")
    }

    @Test
    fun originIsGridAllocated(ctx: TestContext) {
        // origin 从 (100,64,100) 起按 32 步长分配；pos() 偏移正确。
        assertEquals(4, ctx.origin[0] % 32)  // 100 % 32 == 4，网格原点不变（并行下每个测试 col 不同）
        assertEquals(ctx.origin, ctx.pos(0, 0, 0))
        assertEquals(ctx.origin[0] + 1, ctx.pos(1, 0, 0)[0])
    }
}
