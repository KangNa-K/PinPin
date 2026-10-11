package com.pinpin.app.overlay

import kotlin.math.max
import kotlin.math.min

/** Source-pixel to viewport transform; annotations use its inverse. */
internal object PinImageTransform {
    fun values(
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        rotation: Int,
        flipped: Boolean,
        crop: Boolean,
    ): FloatArray {
        require(sourceWidth > 0 && sourceHeight > 0 && viewportWidth > 0 && viewportHeight > 0)
        val angle = ((rotation % 360) + 360) % 360
        require(angle % 90 == 0)
        val (cos, sin) = when (angle) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        val rotatedW = if (angle % 180 == 0) sourceWidth else sourceHeight
        val rotatedH = if (angle % 180 == 0) sourceHeight else sourceWidth
        val sx = viewportWidth.toFloat() / rotatedW
        val sy = viewportHeight.toFloat() / rotatedH
        val scale = if (crop) max(sx, sy) else min(sx, sy)
        val flip = if (flipped) -1f else 1f
        val a = flip * scale * cos
        val b = -flip * scale * sin
        val c = scale * sin
        val d = scale * cos
        return floatArrayOf(
            a, b, viewportWidth / 2f - a * sourceWidth / 2f - b * sourceHeight / 2f,
            c, d, viewportHeight / 2f - c * sourceWidth / 2f - d * sourceHeight / 2f,
            0f, 0f, 1f,
        )
    }
}
