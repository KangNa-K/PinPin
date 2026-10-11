package com.pinpin.app.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureBoundsTest {
    @Test fun clipsPartialSelection() {
        assertEquals(CaptureBounds(10, 20, 100, 200),
            CaptureBounds(10, 20, 300, 400).intersect(100, 200))
    }
    @Test fun rejectsOutsideAndReversedSelections() {
        assertNull(CaptureBounds(100, 0, 200, 10).intersect(100, 100))
        assertNull(CaptureBounds(20, 0, 10, 10).intersect(100, 100))
        assertNull(CaptureBounds(0, 10, 10, 10).intersect(100, 100))
    }
    @Test fun clampsBeforeComputingDimensions() {
        val result = CaptureBounds(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
            .intersect(100, 200)!!
        assertEquals(100, result.width)
        assertEquals(200, result.height)
    }
    @Test fun rejectsInvalidBitmapDimensions() {
        assertNull(CaptureBounds(0, 0, 10, 10).intersect(0, 100))
        assertNull(CaptureBounds(0, 0, 10, 10).intersect(100, -1))
    }
}
