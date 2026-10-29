package com.mcdebug.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Pure arithmetic: these tests never initialize Minecraft, NativeImage or an OpenGL context. */
class CaptureGeometryTest {
    @Test fun `render scale uses unrounded ortho width rather than ceil layout width`() {
        val scale = CaptureGeometry.renderScale(2560, 1440, 2560, 1440, 6.0)
        assertEquals(6.0, scale.x, 0.000001)
        assertEquals(6.0, scale.y, 0.000001)
        assertEquals((2560.0 / 6).toFloat().toDouble(), scale.renderWidth, 0.0)
        assertNotEquals(2560.0 / 427, scale.x, 0.001)
    }

    @Test fun `render target scaling is independent on both axes`() {
        val scale = CaptureGeometry.renderScale(1000, 500, 800, 600, 3.0)
        assertEquals(3.75, scale.x, 0.000001)
        assertEquals(2.5, scale.y, 0.000001)
        val gui = CaptureGeometry.pngToGui(75.0, 50.0, 0.0, 0.0, scale.x, scale.y)
        assertEquals(20.0, gui.first, 0.000001)
        assertEquals(20.0, gui.second, 0.000001)
    }

    @Test fun `negative origins clip endpoints without retaining offscreen width`() {
        val crop = CaptureGeometry.clipGuiRect(100, 80, 2.0, 3.0, -10.0, -5.0, 20.0, 10.0)
        assertEquals(CaptureGeometry.Crop(0, 0, 20, 15), crop)
    }

    @Test fun `right and bottom clipping cannot cross framebuffer boundaries`() {
        val crop = CaptureGeometry.clipGuiRect(100, 80, 2.0, 3.0, 40.0, 20.0, 30.0, 20.0)
        assertEquals(CaptureGeometry.Crop(80, 60, 20, 20), crop)
    }

    @Test fun `float noise in the ortho scale does not add a pixel column`() {
        // 2560x1440 @ guiScale 6: the real scale is 6.0000001, a 176-unit GUI at 125 is 1056 px.
        val scale = CaptureGeometry.renderScale(2560, 1440, 2560, 1440, 6.0)
        val crop = CaptureGeometry.clipGuiRect(2560, 1440, scale.x, scale.y, 125.0, 37.0, 176.0, 166.0)
        assertEquals(CaptureGeometry.Crop(750, 222, 1056, 996), crop)
    }

    @Test fun `fractional crop edges round outwards`() {
        val crop = CaptureGeometry.clipGuiRect(100, 100, 2.5, 3.0, 1.1, 2.2, 3.3, 4.4)
        assertEquals(CaptureGeometry.Crop(2, 6, 9, 14), crop)
    }

    @Test fun `fully outside and empty rectangles are rejected before allocation`() {
        val rectangles = listOf(
            doubleArrayOf(50.0, 0.0, 10.0, 10.0),
            doubleArrayOf(0.0, 40.0, 10.0, 10.0),
            doubleArrayOf(-20.0, 0.0, 10.0, 10.0),
            doubleArrayOf(0.0, -20.0, 10.0, 10.0),
            doubleArrayOf(0.0, 0.0, 0.0, 10.0),
            doubleArrayOf(0.0, 0.0, 10.0, -1.0),
        )
        for (r in rectangles) {
            assertNull(CaptureGeometry.clipGuiRect(100, 80, 2.0, 2.0, r[0], r[1], r[2], r[3]), r.contentToString())
        }
    }

    @Test fun `PNG Screen roundtrip preserves fractional clipped origins and independent scales`() {
        val crop = CaptureGeometry.clipGuiRect(1000, 700, 2.5, 3.0, 10.3, -1.0, 100.0, 100.0)!!
        val gui = CaptureGeometry.pngToGui(37.25, 88.5, crop.x.toDouble(), crop.y.toDouble(), 2.5, 3.0)
        val png = CaptureGeometry.guiToPng(gui.first, gui.second, crop.x.toDouble(), crop.y.toDouble(), 2.5, 3.0)
        assertEquals(37.25, png.first, 0.000000001)
        assertEquals(88.5, png.second, 0.000000001)
        assertEquals((37.25 + 25) / 2.5, gui.first, 0.000000001)
    }

    @Test fun `continuous cursor warp inverts vanilla mouse mapping not render projection`() {
        val guiX = 200.0
        val guiY = 73.0
        val cursor = CaptureGeometry.screenToWindow(guiX, guiY, 1280, 720, 427, 240)
        assertEquals(guiX, cursor.first * 427 / 1280, 0.000000001)
        assertEquals(guiY, cursor.second * 240 / 720, 0.000000001)
        // HiDPI framebuffer=2560, guiScale=6: x*6/2 is NOT the
        // correct inverse of vanilla's ceil(2560/6)=427 mouse mapping.
        assertNotEquals(guiX * 6 / 2, cursor.first, 0.01)
    }

    @Test fun `invalid dimensions and scales fail deterministically`() {
        assertThrows(IllegalArgumentException::class.java) { CaptureGeometry.renderScale(0, 100, 100, 100, 2.0) }
        assertThrows(IllegalArgumentException::class.java) { CaptureGeometry.renderScale(100, 100, 100, 100, 0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureGeometry.clipGuiRect(100, 100, Double.NaN, 2.0, 0.0, 0.0, 10.0, 10.0)
        }
    }
}
