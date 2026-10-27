package com.pinpin.app.capture

import kotlin.math.abs

/** Conservative rectangular-image detector. Ambiguous screens return null, never full-screen. */
internal object ImageRegionDetector {
    fun detect(pixels: IntArray, width: Int, height: Int, seedX: Int, seedY: Int): CaptureBounds? {
        if (width < 32 || height < 32 || pixels.size != width * height || seedX !in 0 until width || seedY !in 0 until height) return null
        fun distance(a: Int, b: Int): Int = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
            abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
        // Keep the probes local so split-screen neighbors do not dominate detection.
        val probe = maxOf(16, width / 8)
        var x0 = (seedX - probe).coerceAtLeast(0)
        var x1 = (seedX + probe).coerceAtMost(width - 1)
        var surfaceColor: Int? = null
        fun flatRow(y: Int): Boolean {
            val reference = pixels[y * width + x0]
            return (x0..x1).count { distance(reference, pixels[y * width + it]) <= 14 } >= (x1 - x0 + 1) * 0.97
        }
        fun rowEdgeCoverage(y: Int, direction: Int): Float {
            var changed = 0
            for (x in x0..x1) if (distance(pixels[y * width + x], pixels[(y + direction) * width + x]) > 28) changed++
            return changed.toFloat() / (x1 - x0 + 1)
        }
        fun rowEdgeNovelty(y: Int, direction: Int): Pair<Float, Float> {
            var changed = 0
            var total = 0
            for (x in x0..x1) {
                val atEdge = distance(pixels[y * width + x], pixels[(y + direction) * width + x])
                val inside = distance(pixels[(y - direction) * width + x], pixels[y * width + x])
                val outside = distance(pixels[(y + direction) * width + x], pixels[(y + 2 * direction) * width + x])
                val novelty = (atEdge - maxOf(inside, outside)).coerceAtLeast(0)
                if (novelty >= 22) changed++
                total += novelty
            }
            val count = x1 - x0 + 1
            return changed.toFloat() / count to total.toFloat() / count
        }
        fun rowTexture(y: Int): Float {
            var textured = 0
            for (x in x0 + 1..x1) if (distance(pixels[y * width + x], pixels[y * width + x - 1]) > 20) textured++
            return textured.toFloat() / (x1 - x0)
        }
        fun boundary(direction: Int, nearest: Boolean = false, edgeToCaption: Boolean = false): Int? {
            var y = seedY + direction * 8
            var fallback: Int? = null
            var bestEdge: Pair<Int, Float>? = null
            var seenContent = !flatRow(seedY)
            var gapStart = y
            var gapLength = 0
            while (y in 2 until height - 2) {
                if (fallback == null && flatRow(y) && flatRow(y + direction) && flatRow(y + direction * 2)) fallback = y
                if (nearest) {
                    val surface = surfaceColor
                    val blank = surface != null && flatRow(y) && distance(pixels[y * width + x0], surface) <= 14
                    if (blank && seenContent) {
                        if (gapLength++ == 0) gapStart = y
                        if (gapLength >= maxOf(3, height / 80)) return if (direction < 0) gapStart + 1 else gapStart
                    } else {
                        gapLength = 0
                        if (!flatRow(y)) seenContent = true
                    }
                }
                // Artwork may not have flat separators. Prefer a sharp change from image texture to UI.
                val coverage = rowEdgeCoverage(y, direction)
                val textureContrast = abs(rowTexture(y) - rowTexture(y + direction))
                val (noveltyCoverage, novelty) = rowEdgeNovelty(y, direction)
                val textureEdge = coverage >= 0.78f && textureContrast >= 0.20f
                val colorEdge = noveltyCoverage >= 0.78f && novelty >= 22f
                // Near a top-edge image, the picture may fade into a dark caption
                // area with no flat separator. Accept a strong texture-to-smooth
                // transition as the lower image edge without weakening other cases.
                val edgeToCaptionTransition = nearest && direction > 0 && edgeToCaption &&
                    coverage >= 0.40f && textureContrast >= 0.20f
                val score = maxOf(if (textureEdge) textureContrast * 255f else 0f, if (colorEdge) novelty else 0f)
                if (score > 0f) {
                    if (nearest) return if (direction < 0) y else y + 1
                    if (bestEdge == null || score > bestEdge!!.second) bestEdge = y to score
                }
                if (edgeToCaptionTransition) return y + 1
                y += direction
            }
            return bestEdge?.let { if (direction < 0) it.first else it.first + 1 }
                ?: fallback?.let { if (direction < 0) it + 1 else it }
        }
        // Full-screen media viewers often draw the artwork flush to the top or
        // bottom edge, leaving no pixel transition to detect on that side.
        // Treat a missing vertical edge as a display edge, then keep the later
        // image-texture and cross-axis checks as the conservative guard.
        var top = boundary(-1) ?: 0
        var bottom = boundary(1) ?: height
        if (bottom - top < maxOf(32, height / 12)) return null
        var y0 = top + (bottom - top) / 5
        var y1 = bottom - (bottom - top) / 5
        fun flatColumn(x: Int): Boolean {
            val reference = pixels[y0 * width + x]
            return (y0 until y1).count { distance(reference, pixels[it * width + x]) <= 14 } >= (y1 - y0) * 0.97
        }
        fun columnEdgeCoverage(x: Int, direction: Int): Float {
            var changed = 0
            for (y in y0 until y1) if (distance(pixels[y * width + x], pixels[y * width + x + direction]) > 28) changed++
            return changed.toFloat() / (y1 - y0)
        }
        fun columnEdgeNovelty(x: Int, direction: Int): Pair<Float, Float> {
            var changed = 0
            var total = 0
            for (y in y0 until y1) {
                val atEdge = distance(pixels[y * width + x], pixels[y * width + x + direction])
                val inside = distance(pixels[y * width + x - direction], pixels[y * width + x])
                val outside = distance(pixels[y * width + x + direction], pixels[y * width + x + 2 * direction])
                val novelty = (atEdge - maxOf(inside, outside)).coerceAtLeast(0)
                if (novelty >= 22) changed++
                total += novelty
            }
            val count = y1 - y0
            return changed.toFloat() / count to total.toFloat() / count
        }
        fun columnTexture(x: Int): Float {
            var textured = 0
            for (y in y0 + 1 until y1) if (distance(pixels[y * width + x], pixels[(y - 1) * width + x]) > 20) textured++
            return textured.toFloat() / (y1 - y0 - 1)
        }
        fun side(direction: Int, nearest: Boolean = false): Int {
            var x = seedX + direction * 8
            var fallback: Int? = null
            var bestEdge: Pair<Int, Float>? = null
            while (x in 1 until width - 1) {
                if (fallback == null && flatColumn(x) && flatColumn(x + direction)) fallback = if (direction < 0) x + 1 else x
                val coverage = columnEdgeCoverage(x, direction)
                val textureContrast = abs(columnTexture(x) - columnTexture(x + direction))
                val (noveltyCoverage, novelty) = columnEdgeNovelty(x, direction)
                val colorEdge = noveltyCoverage >= 0.78f && novelty >= 22f
                val score = maxOf(if (coverage >= 0.78f && textureContrast >= 0.20f) textureContrast * 255f else 0f,
                    if (colorEdge) novelty else 0f)
                if (score > 0f) {
                    if (nearest) return if (direction < 0) x else x + 1
                    if (bestEdge == null || score > bestEdge!!.second) bestEdge = x to score
                }
                x += direction
            }
            return bestEdge?.let { if (direction < 0) it.first else it.first + 1 }
                ?: fallback ?: if (direction < 0) 0 else width
        }
        var left = side(-1)
        var right = side(1)
        // Once the card width is known, probe almost its entire width. A local
        // probe confuses internal illustration shapes with image boundaries;
        // a global strongest-edge search includes other feed cards and UI.
        if (right - left < maxOf(32, width / 8)) return null
        val inset = maxOf(1, (right - left) / 20)
        x0 = left + inset
        x1 = right - inset - 1
        surfaceColor = when {
            left > 0 -> pixels[seedY * width + left - 1]
            right < width -> pixels[seedY * width + right]
            top > 0 -> pixels[(top - 1) * width + seedX]
            else -> null
        }
        val refinedTop = boundary(-1, nearest = true) ?: if (top == 0) 0 else return null
        val refinedBottom = boundary(1, nearest = true, edgeToCaption = top == 0) ?: if (bottom == height) height else return null
        top = maxOf(top, refinedTop)
        bottom = minOf(bottom, refinedBottom)
        if (bottom - top < maxOf(32, height / 12)) return null
        val verticalInset = maxOf(1, (bottom - top) / 20)
        y0 = top + verticalInset
        y1 = bottom - verticalInset
        left = maxOf(left, side(-1, nearest = true))
        right = minOf(right, side(1, nearest = true))
        if (seedX !in left until right || seedY !in top until bottom || right - left < maxOf(32, width / 8)) return null
        // Flat placeholders and text-only screens are not reference images. Smooth artwork
        // can have low local edge energy, so also accept a sufficiently varied color palette.
        var textured = 0
        var samples = 0
        var colorKinds = 0
        val colorCounts = IntArray(512)
        for (y in top + 1 until bottom step 3) for (x in left + 1 until right step 3) {
            samples++
            val pixel = pixels[y * width + x]
            if (distance(pixel, pixels[y * width + x - 1]) > 20 ||
                distance(pixel, pixels[(y - 1) * width + x]) > 20) textured++
            val colorBin = ((pixel shr 21 and 7) shl 6) or ((pixel shr 13 and 7) shl 3) or (pixel shr 5 and 7)
            if (colorCounts[colorBin]++ == 0) colorKinds++
        }
        val substantialColors = colorCounts.count { it >= maxOf(8, (samples * 0.005f).toInt()) }
        if (samples == 0 || (textured.toFloat() / samples < 0.08f && colorKinds < 24 && substantialColors < 2)) return null
        return CaptureBounds(left, top, right, bottom)
    }
}
