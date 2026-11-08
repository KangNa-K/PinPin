package com.pinpin.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinColorCoordinatesTest {
    @Test fun exifOrientationsMapEveryPixelExactlyOnce() {
        val width = 7
        val height = 4
        for (orientation in 1..8) {
            val orientedWidth = if (orientation >= 5) height else width
            val orientedHeight = if (orientation >= 5) width else height
            val pixels = mutableSetOf<Pair<Int, Int>>()
            for (y in 0 until orientedHeight) for (x in 0 until orientedWidth) {
                val raw = PinColorCoordinates.raw(x, y, width, height, orientation)
                assertTrue(raw.first in 0 until width && raw.second in 0 until height)
                pixels.add(raw)
            }
            assertEquals(width * height, pixels.size)
        }
        assertEquals(0 to 3, PinColorCoordinates.raw(0, 0, width, height, 6))
        assertEquals(6 to 0, PinColorCoordinates.raw(0, 0, width, height, 8))
    }

    @Test fun screenDeltasInvertThePinTransform() {
        for (rotation in listOf(0, 90, 180, 270)) for (flip in listOf(false, true)) {
            val matrix = PinImageTransform.values(40, 40, 40, 40, rotation, flip, false)
            val (dx, dy) = PinColorCoordinates.imageDelta(3, -2, rotation, flip)
            assertEquals(3f, matrix[0] * dx + matrix[1] * dy, 0.001f)
            assertEquals(-2f, matrix[3] * dx + matrix[4] * dy, 0.001f)
        }
    }
}
