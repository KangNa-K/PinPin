package com.pinpin.app.overlay

import kotlin.math.roundToInt

/**
 * Pure geometry used by [PinWindowView]. Takes and returns primitives only so
 * it runs in JVM unit tests without Android framework classes.
 */
internal object PinWindowGeometry {
    const val NO_CORNER = -1
    const val CORNER_TL = 0
    const val CORNER_TR = 1
    const val CORNER_BL = 2
    const val CORNER_BR = 3

    /** Window position (WindowManager x/y) and image size, in pixels. */
    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Corner whose handle is within [hitRadius] of the image-local point, or
     * [NO_CORNER]. Handles sit [inset] px in from each image corner, matching
     * the rounded-corner radius. Checked TL, TR, BL, BR; first match wins.
     */
    fun cornerAt(x: Float, y: Float, width: Int, height: Int, inset: Float, hitRadius: Float): Int {
        if (width <= 0 || height <= 0) return NO_CORNER
        val w = width.toFloat()
        val h = height.toFloat()
        val radiusSquared = hitRadius * hitRadius
        val handles = arrayOf(inset to inset, w - inset to inset, inset to h - inset, w - inset to h - inset)
        handles.forEachIndexed { index, (hx, hy) ->
            val dx = x - hx
            val dy = y - hy
            if (dx * dx + dy * dy <= radiusSquared) return index
        }
        return NO_CORNER
    }

    /** The fixed corner opposite [corner], which stays put during a resize. */
    fun oppositeAnchor(corner: Int, x: Int, y: Int, width: Int, height: Int): Pair<Float, Float> {
        val left = corner == CORNER_TL || corner == CORNER_BL
        val top = corner == CORNER_TL || corner == CORNER_TR
        return (x + if (left) width else 0).toFloat() to (y + if (top) height else 0).toFloat()
    }

    /**
     * Aspect-preserving resize from a corner drag. The finger delta is
     * projected onto the image diagonal, then the scale is clamped so neither
     * side exceeds [maxSize] or shrinks below [minSize] (or the starting size
     * when that is already smaller). Position is not screen-clamped here.
     */
    fun resizeFromCorner(
        corner: Int,
        anchorX: Float,
        anchorY: Float,
        initialWidth: Int,
        initialHeight: Int,
        fingerDx: Float,
        fingerDy: Float,
        minSize: Int,
        maxSize: Int,
    ): Frame? {
        if (corner !in CORNER_TL..CORNER_BR) return null
        val initialW = initialWidth.coerceAtLeast(1)
        val initialH = initialHeight.coerceAtLeast(1)
        val wf = initialW.toFloat()
        val hf = initialH.toFloat()
        val dx = fingerDx * if (corner == CORNER_TL || corner == CORNER_BL) -1f else 1f
        val dy = fingerDy * if (corner == CORNER_TL || corner == CORNER_TR) -1f else 1f
        val rawScale = 1f + (dx * wf + dy * hf) / (wf * wf + hf * hf)
        val maxScale = minOf(maxSize / wf, maxSize / hf)
        val minScale = minOf(maxOf(minOf(minSize, initialW) / wf, minOf(minSize, initialH) / hf), maxScale)
        val scale = rawScale.coerceIn(minScale, maxScale)
        val newW = (wf * scale).roundToInt().coerceAtLeast(1)
        val newH = (hf * scale).roundToInt().coerceAtLeast(1)
        val newX = if (corner == CORNER_TL || corner == CORNER_BL) (anchorX - newW).toInt() else anchorX.toInt()
        val newY = if (corner == CORNER_TL || corner == CORNER_TR) (anchorY - newH).toInt() else anchorY.toInt()
        return Frame(newX, newY, newW, newH)
    }

    /**
     * Keep at least a quarter of the wrapper on screen on every side.
     * [screenRight]/[screenBottom] are the display bounds' right/bottom edges.
     */
    fun clampPosition(
        x: Int,
        y: Int,
        wrapperWidth: Int,
        wrapperHeight: Int,
        screenRight: Int,
        screenBottom: Int,
    ): Pair<Int, Int> {
        val minX = -wrapperWidth + wrapperWidth / 4
        val maxX = screenRight - wrapperWidth / 4
        val minY = -wrapperHeight + wrapperHeight / 4
        val maxY = screenBottom - wrapperHeight / 4
        return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
    }

    /**
     * Annotation-mode frame: the largest aspect-preserving image that fits the
     * screen minus system-bar/cutout insets, [padding] on each side and the
     * toolbar reserve. The image + toolbar group is centred inside the safe
     * area so controls never land under the status or navigation bar.
     */
    fun annotationFit(
        screenWidth: Int,
        screenHeight: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        padding: Int,
        toolbarGap: Int,
        toolbarHeight: Int,
        currentWidth: Int,
        currentHeight: Int,
    ): Frame {
        // The wrapper is centred on the full width, so reserve the larger
        // horizontal inset on both sides to keep the fit symmetric.
        val horizontalInset = maxOf(insetLeft, insetRight)
        val availableH = (screenHeight - insetTop - insetBottom).coerceAtLeast(1)
        val maxW = (screenWidth - horizontalInset * 2 - padding * 2).coerceAtLeast(1)
        val maxH = (availableH - padding * 2 - (toolbarGap * 2 + toolbarHeight)).coerceAtLeast(1)
        val aspect = if (currentHeight > 0) currentWidth.toFloat() / currentHeight else 1f
        val fitW: Int
        val fitH: Int
        if (maxW.toFloat() / maxH > aspect) {
            fitH = maxH
            fitW = (maxH * aspect).toInt()
        } else {
            fitW = maxW
            fitH = (maxW / aspect).toInt()
        }
        val groupHeight = fitH + toolbarGap + toolbarHeight
        val fitX = (screenWidth - fitW) / 2
        val fitY = insetTop + ((availableH - groupHeight) / 2).coerceAtLeast(padding)
        return Frame(fitX, fitY, fitW, fitH)
    }
}
