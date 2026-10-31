package com.pinpin.app.ball

/**
 * Pure geometry used by [BallView]. Takes and returns primitives only so it
 * runs in JVM unit tests without Android framework classes.
 */
internal object BallGeometry {

    /** Where the ball window should come to rest, and which edge it hugs. */
    data class SnapTarget(val x: Int, val y: Int, val edge: Int)

    /**
     * Snaps a ball window at ([x], [y]) to the nearer horizontal display edge,
     * [margin] px in. X stays on screen; Y is kept on screen with [margin] at
     * the bottom only. [edgeLeft] / [edgeRight] are the caller's edge ids.
     */
    fun snapTarget(
        x: Int, y: Int, ballWidth: Int, ballHeight: Int,
        displayWidth: Int, displayHeight: Int, margin: Int,
        edgeLeft: Int, edgeRight: Int,
    ): SnapTarget {
        val centerX = x + ballWidth / 2
        val (rawX, edge) = if (centerX < displayWidth / 2) {
            margin to edgeLeft
        } else {
            (displayWidth - ballWidth - margin) to edgeRight
        }
        val targetX = rawX.coerceIn(0, (displayWidth - ballWidth).coerceAtLeast(0))
        val targetY = y.coerceIn(0, (displayHeight - ballHeight - margin).coerceAtLeast(0))
        return SnapTarget(targetX, targetY, edge)
    }

    /**
     * Power-of-two `inSampleSize` that leaves the longest edge of a
     * [width] x [height] image at most `2 * reqSide` px (and above `reqSide`
     * whenever any downsampling happens).
     */
    fun sampleSize(width: Int, height: Int, reqSide: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > reqSide * 2) sample *= 2
        return sample
    }
}
