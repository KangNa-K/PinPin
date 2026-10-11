package com.pinpin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PinSizingTest {

    @Test
    fun portraitImageKeepsAspectRatio() {
        assertEquals(120 to 480, PinSizing.fitInside(1000, 4000, 480, 480))
    }

    @Test
    fun landscapeImageKeepsAspectRatio() {
        assertEquals(480 to 120, PinSizing.fitInside(4000, 1000, 480, 480))
    }

    @Test
    fun smallImageIsNotUpscaled() {
        assertEquals(100 to 50, PinSizing.fitInside(100, 50, 480, 480))
    }

    @Test
    fun invalidBoundsUseFittedFallback() {
        assertEquals(480 to 270, PinSizing.fitInside(0, 0, 480, 480))
    }
}
