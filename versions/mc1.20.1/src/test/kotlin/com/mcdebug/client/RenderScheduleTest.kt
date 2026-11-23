package com.mcdebug.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RenderScheduleTest {
    @Test fun `continuous is unchanged even without requests`() {
        val schedule = RenderSchedule(RenderSettings())
        repeat(3) { assertEquals(RenderMode.CONTINUOUS, schedule.nextFrame(it.toLong(), true, false, false)) }
    }

    @Test fun `demand sleeps between heartbeat frames but captures bypass it`() {
        val schedule = RenderSchedule(RenderSettings(RenderMode.DEMAND, 20, 1))
        assertEquals(RenderMode.DEMAND, schedule.nextFrame(0, true, false, true))
        assertNull(schedule.nextFrame(50_000_000, true, false, false))
        assertEquals(RenderMode.DEMAND, schedule.nextFrame(100_000_000, true, true, false))
        assertNull(schedule.nextFrame(1_000_000_000, true, false, false))
        assertEquals(RenderMode.DEMAND, schedule.nextFrame(1_100_000_000, true, false, false))
    }

    @Test fun `zero idle fps means requests and screen changes only`() {
        val schedule = RenderSchedule(RenderSettings(RenderMode.DEMAND, 20, 0))
        assertNull(schedule.nextFrame(0, true, false, false))
        assertEquals(RenderMode.DEMAND, schedule.nextFrame(1, true, false, true))
        assertNull(schedule.nextFrame(Long.MAX_VALUE, true, false, false))
        assertEquals(RenderMode.DEMAND, schedule.nextFrame(Long.MAX_VALUE, true, true, false))
    }

    @Test fun `loading and non container screens always render fully`() {
        for (mode in listOf(RenderMode.DEMAND, RenderMode.GUI_ONLY)) {
            val schedule = RenderSchedule(RenderSettings(mode, 20, 0))
            assertEquals(RenderMode.CONTINUOUS, schedule.nextFrame(0, false, false, false))
            assertEquals(RenderMode.CONTINUOUS, schedule.nextFrame(1, false, true, false))
            assertNull(schedule.nextFrame(2, true, false, false))
            assertEquals(mode, schedule.nextFrame(3, true, true, false))
        }
    }

    @Test fun `gui only is never chosen for unsafe phases`() {
        val schedule = RenderSchedule(RenderSettings(RenderMode.GUI_ONLY))
        assertEquals(RenderMode.CONTINUOUS, schedule.nextFrame(0, false, false, true))
        assertEquals(RenderMode.GUI_ONLY, schedule.nextFrame(1, true, true, false))
    }

    @Test fun `new screen pointer survives its first rendered frame`() {
        val cursor = RenderCursor<Any>()
        val a = Any()
        val b = Any()
        cursor.observe(a)
        cursor.move(b, 42.9, 17.1) // RPC click before B has rendered once
        cursor.observe(b)
        assertTrue(cursor.active)
        assertEquals(42, cursor.x(-1))
        assertEquals(17, cursor.y(-1))
    }

    @Test fun `screen change clears only an old screen pointer`() {
        val cursor = RenderCursor<Any>()
        cursor.move(Any(), 10.0, 20.0)
        cursor.observe(Any())
        assertFalse(cursor.active)
        assertEquals(99, cursor.x(99))
        assertEquals(88, cursor.y(88))
    }

    @Test fun `closing screen releases synthetic pointer`() {
        val cursor = RenderCursor<Any>()
        cursor.move(Any(), 10.0, 20.0)
        cursor.observe(null)
        assertFalse(cursor.active)
    }

    @Test fun `settings validate tick loop and heartbeat limits`() {
        assertEquals(RenderMode.GUI_ONLY, RenderMode.parse(" GUI-ONLY "))
        assertThrows(IllegalArgumentException::class.java) { RenderMode.parse("headless") }
        assertThrows(IllegalArgumentException::class.java) { RenderSettings(loopFps = 1) }
        assertThrows(IllegalArgumentException::class.java) { RenderSettings(loopFps = 261) }
        assertThrows(IllegalArgumentException::class.java) { RenderSettings(idleFps = -1) }
        assertThrows(IllegalArgumentException::class.java) { RenderSettings(loopFps = 20, idleFps = 21) }
        assertEquals(0, RenderSettings(idleFps = 0).idleFps)
    }
}
