package com.mcdebug.wait

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * wait.until sprint:true 的油门所有权计数：多个并发 wait 只有**最后一个**结束才允许
 * stopSprinting()，且重复 end（sweep 清理与 cancelConnection 各走一遍）不能二次触发。
 */
class SprintOwnershipTest {

    @Test
    fun `single job stops exactly once`() {
        val o = SprintOwnership()
        o.begin(1)
        assertTrue(o.end(1), "唯一的持有者结束 -> 应停 sprint")
        assertFalse(o.end(1), "重复 end 不能二次触发")
    }

    @Test
    fun `two jobs stop on the last one`() {
        val o = SprintOwnership()
        o.begin(1)
        o.begin(2)
        assertFalse(o.end(1), "还有另一个 wait 在等，不能停")
        assertTrue(o.end(2), "最后一个结束 -> 应停")
    }

    @Test
    fun `end of unknown id is a no-op`() {
        val o = SprintOwnership()
        assertFalse(o.end(99))
        o.begin(1)
        assertFalse(o.end(99), "别人的 id 不影响在册持有者")
        assertTrue(o.end(1))
    }

    @Test
    fun `clear reports whether anything was active`() {
        val o = SprintOwnership()
        assertFalse(o.clear(), "空表 clear 不触发")
        o.begin(1)
        assertTrue(o.clear(), "有持有者被整体清空 -> 应停（server stopping 路径）")
        assertFalse(o.clear())
        assertFalse(o.end(1), "clear 之后残留的 end 不再触发")
    }

    @Test
    fun `begin again after end re-arms`() {
        val o = SprintOwnership()
        o.begin(1)
        assertTrue(o.end(1))
        o.begin(2)
        assertTrue(o.end(2), "新一轮持有者结束仍应停（状态机可复用）")
    }
}
