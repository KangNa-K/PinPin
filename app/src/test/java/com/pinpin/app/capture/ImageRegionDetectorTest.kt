package com.pinpin.app.capture

import org.junit.Assert.*
import org.junit.Test

class ImageRegionDetectorTest {
    private val width = 240
    private val height = 320
    private fun scene(left: Int = 35, right: Int = 205): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        if (x in left until right && y in 65 until 255) {
            if ((x + y) % 2 == 0) 0xff304e70.toInt() else 0xffe09040.toInt()
        } else 0xffeeeeee.toInt()
    }

    @Test fun detectsImageAroundSeed() {
        assertEquals(CaptureBounds(35, 65, 205, 255), ImageRegionDetector.detect(scene(), width, height, 120, 150))
    }
    @Test fun handlesImageTouchingHorizontalScreenEdges() {
        assertEquals(CaptureBounds(0, 65, 240, 255), ImageRegionDetector.detect(scene(0, 240), width, height, 120, 150))
    }
    @Test fun handlesImageTouchingTopDisplayEdge() {
        val pixels = IntArray(width * height) { 0xffeeeeee.toInt() }
        for (y in 0 until 255) for (x in 20 until 220) {
            pixels[y * width + x] = if ((x + y) % 2 == 0) 0xff304e70.toInt() else 0xffe09040.toInt()
        }
        assertEquals(CaptureBounds(20, 0, 220, 255), ImageRegionDetector.detect(pixels, width, height, 120, 150))
    }
    @Test fun doesNotAssumeWholeScreenWhenBoundariesAreMissing() {
        val pixels = IntArray(width * height) { if (it % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt() }
        assertNull(ImageRegionDetector.detect(pixels, width, height, 120, 150))
    }
    @Test fun ignoresBlankScreensAndSeedsOutsideImage() {
        assertNull(ImageRegionDetector.detect(IntArray(width * height) { 0xffeeeeee.toInt() }, width, height, 120, 150))
        assertNull(ImageRegionDetector.detect(scene(), width, height, 120, 20))
    }
    @Test fun rejectsInvalidCoordinatesAndDimensions() {
        assertNull(ImageRegionDetector.detect(scene(), width, height, -1, 150))
        assertNull(ImageRegionDetector.detect(scene(), width, height, width, 150))
        assertNull(ImageRegionDetector.detect(IntArray(1), width, height, 120, 150))
    }
    @Test fun splitScreenNeighborDoesNotChangeSelectedImage() {
        val pixels = scene(15, 108)
        for (y in 0 until height) for (x in 130 until width) pixels[y * width + x] = (x * y * 17123) or 0xff000000.toInt()
        assertEquals(CaptureBounds(15, 65, 108, 255), ImageRegionDetector.detect(pixels, width, height, 60, 150))
    }

    @Test fun findsArtworkEdgesOnAFeedPageWithPhotoTextureAndCaptionMarks() {
        val pageWidth = 240
        val pageHeight = 320
        val pixels = IntArray(pageWidth * pageHeight) { 0xffeeeeee.toInt() }
        for (y in 40 until 280) for (x in 20 until 220) {
            val grain = ((x * 37 + y * 71 + x * y * 13) xor (x * 19 + y * 31)) and 63
            val red = 80 + grain
            val green = 105 + grain
            val blue = 140 + grain
            pixels[y * pageWidth + x] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        // A short white label within the artwork should not become the detected edge.
        for (y in 145..148) for (x in 38..112) pixels[y * pageWidth + x] = 0xfff8f8f8.toInt()

        assertEquals(CaptureBounds(20, 40, 220, 280),
            ImageRegionDetector.detect(pixels, pageWidth, pageHeight, 120, 160))
    }

    @Test fun findsLowFrequencyArtAgainstTheFeedSurface() {
        val pageWidth = 240
        val pageHeight = 320
        val pixels = IntArray(pageWidth * pageHeight) { 0xffeeeeee.toInt() }
        for (y in 40 until 280) for (x in 20 until 220) {
            val color = ColorPattern.pixel(x, y)
            pixels[y * pageWidth + x] = color
        }
        assertEquals(CaptureBounds(20, 40, 220, 280),
            ImageRegionDetector.detect(pixels, pageWidth, pageHeight, 120, 160))
    }

    @Test fun detectsFlatArtworkWithAnIllustrationAndLabel() {
        val pixels = IntArray(width * height) { 0xffeeeeee.toInt() }
        val left = (width * .15f).toInt()
        val top = (height * .15f).toInt()
        val artWidth = (width * .70f).toInt()
        val artHeight = (height * .66f).toInt()
        for (y in top until top + artHeight) for (x in left until left + artWidth) pixels[y * width + x] = 0xff5078aa.toInt()
        for (y in top + (artHeight * .2f).toInt() until top + (artHeight * .8f).toInt()) {
            for (x in left + (artWidth * .3f).toInt() until left + (artWidth * .7f).toInt()) pixels[y * width + x] = 0xffe1af7d.toInt()
        }
        for (y in top + (artHeight * .47f).toInt()..top + (artHeight * .50f).toInt()) {
            for (x in left + 28..left + 132) pixels[y * width + x] = 0xffffffff.toInt()
        }
        assertEquals(CaptureBounds(left, top, left + artWidth, top + artHeight),
            ImageRegionDetector.detect(pixels, width, height, width / 2, height / 2))
    }

    @Test fun nearerArtworkEdgesWinOverStrongerHeaderAndFooterEdges() {
        val pixels = IntArray(width * height) { 0xffeeeeee.toInt() }
        for (y in 10 until 40) for (x in 20 until 220) pixels[y * width + x] = 0xff000000.toInt()
        for (y in 65 until 255) for (x in 20 until 220) {
            pixels[y * width + x] = if ((x + y) % 2 == 0) 0xff8090a0.toInt() else 0xff90a0b0.toInt()
        }
        for (y in 280 until 305) for (x in 20 until 220) pixels[y * width + x] = 0xff000000.toInt()
        assertEquals(CaptureBounds(20, 65, 220, 255),
            ImageRegionDetector.detect(pixels, width, height, 120, 150))
    }

    @Test fun whiteArtworkStopsBeforeCaptionAndActions() {
        val pixels = IntArray(width * height) { 0xffeeeeee.toInt() }
        for (y in 40 until 305) for (x in 20 until 220) pixels[y * width + x] = 0xffffffff.toInt()
        for (y in 45 until 225) for (x in 40 until 200) {
            pixels[y * width + x] = ColorPattern.pixel(x, y)
        }
        for (y in 245 until 275 step 8) for (x in 30 until 180 step 4) {
            for (dy in 0..2) pixels[(y + dy) * width + x] = 0xff000000.toInt()
        }
        for (y in 285 until 295) for (x in 20 until 220) pixels[y * width + x] = 0xff000000.toInt()
        val bounds = checkNotNull(ImageRegionDetector.detect(pixels, width, height, 120, 150))
        assertTrue("Caption was included: $bounds", bounds.bottom <= 245)
        assertTrue("Artwork was cut off: $bounds", bounds.bottom >= 225)
        assertTrue("Artwork top was cut off: $bounds", bounds.top <= 45)
    }

    private object ColorPattern {
        fun pixel(x: Int, y: Int): Int {
            // Low-frequency color gradients with only a few subdued details.
            val detail = if ((x / 12 + y / 16) % 2 == 0) 7 else 0
            val red = 60 + x / 2 + detail
            val green = 50 + y / 2 + detail
            val blue = 40 + (x + y) / 2 + detail
            return (0xff shl 24) or (red.coerceAtMost(255) shl 16) or
                (green.coerceAtMost(255) shl 8) or blue.coerceAtMost(255)
        }
    }
}
