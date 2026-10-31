package com.pinpin.app.overlay

/**
 * Maps the annotation brush-size slider (0..[MAX_PROGRESS]) to a stroke width
 * in dp and back. Pure functions so they run in JVM unit tests.
 */
internal object AnnotationBrushSize {
    const val MAX_PROGRESS = 100
    const val MIN_WIDTH_DP = 2f
    const val MAX_WIDTH_DP = 24f

    /** Smallest preview dot, so a 2 dp brush is still visible on the toolbar. */
    const val MIN_PREVIEW_DP = 8f

    fun widthForProgress(progress: Int): Float =
        MIN_WIDTH_DP + (progress / MAX_PROGRESS.toFloat()) * (MAX_WIDTH_DP - MIN_WIDTH_DP)

    fun progressForWidth(width: Float): Int =
        ((width - MIN_WIDTH_DP) / (MAX_WIDTH_DP - MIN_WIDTH_DP) * MAX_PROGRESS).toInt()
            .coerceIn(0, MAX_PROGRESS)

    /** Diameter of the live preview dot in px. */
    fun previewSizePx(width: Float, density: Float): Int =
        (width * density).toInt().coerceAtLeast((MIN_PREVIEW_DP * density).toInt())
}
