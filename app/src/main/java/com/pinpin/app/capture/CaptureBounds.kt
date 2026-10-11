package com.pinpin.app.capture

/** Intersection without subtraction until bounds are clamped, avoiding integer overflow. */
data class CaptureBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun intersect(width: Int, height: Int): CaptureBounds? {
        if (width <= 0 || height <= 0 || left >= right || top >= bottom) return null
        val clipped = CaptureBounds(left.coerceIn(0, width), top.coerceIn(0, height),
            right.coerceIn(0, width), bottom.coerceIn(0, height))
        return clipped.takeIf { it.left < it.right && it.top < it.bottom }
    }
}
