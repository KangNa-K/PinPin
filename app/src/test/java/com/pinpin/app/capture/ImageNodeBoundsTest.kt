package com.pinpin.app.capture

import org.junit.Assert.*
import org.junit.Test

class ImageNodeBoundsTest {
    private val window = CaptureBounds(55, 162, 733, 1592)
    private val artwork = CaptureBounds(55, 345, 733, 1249)

    @Test fun actualXiaohongshuBoundsKeepWhiteTopAndExcludeCaption() {
        val icons = listOf(CaptureBounds(164, 226, 292, 345), CaptureBounds(393, 1488, 499, 1573))
        assertEquals(artwork, ImageNodeBounds.select(icons + artwork, window, 390, 800, 162))
        assertEquals(artwork, ImageNodeBounds.select(listOf(artwork), window, 390, 360, 162))
        assertNull(ImageNodeBounds.select(icons + artwork, window, 390, 1300, 162))
    }

    @Test fun clipsImageToVisibleWindow() {
        val partiallyVisible = CaptureBounds(55, 0, 733, 1249)
        assertEquals(CaptureBounds(55, 162, 733, 1249),
            ImageNodeBounds.select(listOf(partiallyVisible), window, 390, 800, 162))
    }

    @Test fun excludesSmallIconsAndOtherWindows() {
        assertNull(ImageNodeBounds.select(listOf(CaptureBounds(393, 1488, 499, 1573)), window, 420, 1500, 162))
        assertNull(ImageNodeBounds.select(listOf(CaptureBounds(800, 0, 2800, 1752)), window, 390, 800, 162))
    }
}
