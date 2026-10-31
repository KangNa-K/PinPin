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

    /** Longest decoded edge for the ball avatar, whatever the aspect ratio. */
    const val MAX_DECODED_EDGE = 4096

    /**
     * Power-of-two `inSampleSize` for a [width] x [height] avatar source.
     *
     * The ball centre-crops the image, so the short edge is what ends up
     * filling the circle: halving stops before the short edge would drop
     * below [reqSide], or once the long edge is at most `2 * reqSide`.
     * Very long images (e.g. long screenshots) are then halved further until
     * the long edge is at most [maxEdge], trading short-edge detail for a
     * bounded bitmap.
     */
    fun sampleSize(width: Int, height: Int, reqSide: Int, maxEdge: Int = MAX_DECODED_EDGE): Int {
        val shortest = minOf(width, height)
        val longest = maxOf(width, height)
        var sample = 1
        while (shortest / (sample * 2) >= reqSide && longest / sample > reqSide * 2) sample *= 2
        while (longest / sample > maxEdge) sample *= 2
        return sample
    }
}
