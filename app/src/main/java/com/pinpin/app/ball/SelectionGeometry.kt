package com.pinpin.app.ball

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure geometry used by [SelectionOverlayView]. Takes and returns primitives
 * only so it runs in JVM unit tests without Android framework classes.
 */
internal object SelectionGeometry {

    enum class Handle { NONE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * Selection edges in screen pixels. Kept as floats so sub-pixel drags
     * accumulate; [SelectionOverlayView] truncates to Int only when it builds
     * the final rect.
     */
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Toolbar margins inside the overlay window. */
    data class ToolbarPosition(val left: Int, val top: Int)

    /**
     * Handle within [threshold] px of ([x], [y]) on the integer selection
     * rect. Corners are checked before edges (TL, TR, BL, BR, then L, R, T, B);
     * edges only count inside the rect's span. Distances are inclusive.
     */
    fun nearestHandle(
        x: Float, y: Float,
        left: Int, top: Int, right: Int, bottom: Int,
        threshold: Float,
    ): Handle {
        fun near(px: Int, py: Int) = hypot(x - px, y - py) <= threshold
        val inRows = y in top.toFloat()..bottom.toFloat()
        val inColumns = x in left.toFloat()..right.toFloat()
        return when {
            near(left, top) -> Handle.TOP_LEFT
            near(right, top) -> Handle.TOP_RIGHT
            near(left, bottom) -> Handle.BOTTOM_LEFT
            near(right, bottom) -> Handle.BOTTOM_RIGHT
            abs(x - left) <= threshold && inRows -> Handle.LEFT
            abs(x - right) <= threshold && inRows -> Handle.RIGHT
            abs(y - top) <= threshold && inColumns -> Handle.TOP
            abs(y - bottom) <= threshold && inColumns -> Handle.BOTTOM
            else -> Handle.NONE
        }
    }

    /** Translates [bounds] by ([dx], [dy]) without leaving the screen. */
    fun move(bounds: Bounds, dx: Float, dy: Float, screenWidth: Int, screenHeight: Int): Bounds {
        val w = bounds.right - bounds.left
        val h = bounds.bottom - bounds.top
        val left = (bounds.left + dx).coerceIn(0f, (screenWidth - w).coerceAtLeast(0f))
        val top = (bounds.top + dy).coerceIn(0f, (screenHeight - h).coerceAtLeast(0f))
        return Bounds(left, top, left + w, top + h)
    }

    /**
     * Moves the edges that [handle] controls by ([dx], [dy]), keeping each
     * edge on screen and at least [minSize] px from the opposite edge.
     *
     * The accepted selection is truncated to whole pixels, so at densities
     * where [minSize] is fractional it can be slightly narrower than
     * [minSize]. Against a screen edge both limits then can't hold; the screen
     * edge wins instead of throwing from an empty `coerceIn` range.
     */
    fun resize(
        bounds: Bounds, handle: Handle, dx: Float, dy: Float,
        screenWidth: Int, screenHeight: Int, minSize: Float,
    ): Bounds {
        var (left, top, right, bottom) = bounds
        val maxX = screenWidth.toFloat()
        val maxY = screenHeight.toFloat()
        when (handle) {
            Handle.LEFT, Handle.TOP_LEFT, Handle.BOTTOM_LEFT -> left = clampLow(left + dx, right - minSize)
            Handle.RIGHT, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT -> right = clampHigh(right + dx, left + minSize, maxX)
            else -> Unit
        }
        when (handle) {
            Handle.TOP, Handle.TOP_LEFT, Handle.TOP_RIGHT -> top = clampLow(top + dy, bottom - minSize)
            Handle.BOTTOM, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> bottom = clampHigh(bottom + dy, top + minSize, maxY)
            else -> Unit
        }
        return Bounds(left, top, right, bottom)
    }

    /** [value] in 0..[max]; 0 when the range is empty. */
    private fun clampLow(value: Float, max: Float): Float = value.coerceIn(0f, max.coerceAtLeast(0f))

    /** [value] in [min]..[screenMax]; [screenMax] when the range is empty. */
    private fun clampHigh(value: Float, min: Float, screenMax: Float): Float =
        value.coerceIn(min.coerceAtMost(screenMax), screenMax)

    /**
     * Places the action toolbar centred under the selection, or above it when
     * there is no room below. The selection is in window-local pixels.
     */
    fun toolbarPosition(
        selectionLeft: Int, selectionTop: Int, selectionBottom: Int, selectionWidth: Int,
        toolbarWidth: Int, toolbarHeight: Int, margin: Int,
        windowWidth: Int, windowHeight: Int,
    ): ToolbarPosition {
        val left = (selectionLeft + selectionWidth / 2 - toolbarWidth / 2)
            .coerceIn(margin, (windowWidth - toolbarWidth - margin).coerceAtLeast(margin))
        val below = selectionBottom + margin
        val above = selectionTop - margin - toolbarHeight
        val top = if (below + toolbarHeight <= windowHeight) below else above.coerceAtLeast(margin)
        return ToolbarPosition(left, top)
    }
}
