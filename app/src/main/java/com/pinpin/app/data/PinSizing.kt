package com.pinpin.app.data

import kotlin.math.roundToInt

/** Pure image sizing logic shared by production code and unit tests. */
internal object PinSizing {
    fun fitInside(
        srcW: Int,
        srcH: Int,
        maxW: Int,
        maxH: Int,
    ): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) {
            val fallbackScale = minOf(
                1f,
                maxW / 960f,
                maxH / 540f,
            )
            return (960 * fallbackScale).roundToInt().coerceAtLeast(1) to
                (540 * fallbackScale).roundToInt().coerceAtLeast(1)
        }

        val scale = minOf(
            1f,
            maxW.toFloat() / srcW.toFloat(),
            maxH.toFloat() / srcH.toFloat(),
        )
        return (srcW * scale).roundToInt().coerceAtLeast(1) to
            (srcH * scale).roundToInt().coerceAtLeast(1)
    }
}
