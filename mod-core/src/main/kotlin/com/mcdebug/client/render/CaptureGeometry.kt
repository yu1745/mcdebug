package com.mcdebug.client.render

import kotlin.math.ceil
import kotlin.math.floor

/** Pure geometry shared with no-GL unit tests; all GUI coordinates mean Screen units. */
object CaptureGeometry {
    data class Scale(val x: Double, val y: Double, val renderWidth: Double, val renderHeight: Double)
    data class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

    fun renderScale(targetWidth: Int, targetHeight: Int, windowFramebufferWidth: Int,
                    windowFramebufferHeight: Int, guiScale: Double): Scale {
        require(targetWidth > 0 && targetHeight > 0 && windowFramebufferWidth > 0 && windowFramebufferHeight > 0)
        require(guiScale.isFinite() && guiScale > 0)
        // Match the exact float ortho bounds passed by GameRenderer, not ceil layout sizes.
        val width = (windowFramebufferWidth / guiScale).toFloat().toDouble()
        val height = (windowFramebufferHeight / guiScale).toFloat().toDouble()
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        return Scale(targetWidth / width, targetHeight / height, width, height)
    }

    fun clipGuiRect(framebufferWidth: Int, framebufferHeight: Int, scaleX: Double, scaleY: Double,
                    x: Double, y: Double, width: Double, height: Double): Crop? {
        require(framebufferWidth > 0 && framebufferHeight > 0)
        require(scaleX.isFinite() && scaleY.isFinite() && scaleX > 0 && scaleY > 0)
        require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite())
        if (width <= 0 || height <= 0) return null
        val left = floor(snap(x * scaleX)).coerceIn(0.0, framebufferWidth.toDouble()).toInt()
        val top = floor(snap(y * scaleY)).coerceIn(0.0, framebufferHeight.toDouble()).toInt()
        val right = ceil(snap((x + width) * scaleX)).coerceIn(0.0, framebufferWidth.toDouble()).toInt()
        val bottom = ceil(snap((y + height) * scaleY)).coerceIn(0.0, framebufferHeight.toDouble()).toInt()
        if (right <= left || bottom <= top) return null
        return Crop(left, top, right - left, bottom - top)
    }

    /**
     * The float ortho bounds make the scale 6.0000001-ish; without snapping, ceil turns
     * 1056.0001 into an extra column of world pixels behind the GUI (unstable goldens).
     */
    private fun snap(pixel: Double): Double {
        val nearest = Math.rint(pixel)
        return if (kotlin.math.abs(pixel - nearest) < 1e-3) nearest else pixel
    }

    fun pngToGui(x: Double, y: Double, cropX: Double, cropY: Double,
                 scaleX: Double, scaleY: Double): Pair<Double, Double> =
        Pair((x + cropX) / scaleX, (y + cropY) / scaleY)

    fun guiToPng(x: Double, y: Double, cropX: Double, cropY: Double,
                 scaleX: Double, scaleY: Double): Pair<Double, Double> =
        Pair(x * scaleX - cropX, y * scaleY - cropY)

    /** Inverse of MouseHandler's mapping; intentionally differs from the render projection. */
    fun screenToWindow(x: Double, y: Double, windowWidth: Int, windowHeight: Int,
                       guiScaledWidth: Int, guiScaledHeight: Int): Pair<Double, Double> =
        Pair(x * windowWidth / guiScaledWidth, y * windowHeight / guiScaledHeight)
}
