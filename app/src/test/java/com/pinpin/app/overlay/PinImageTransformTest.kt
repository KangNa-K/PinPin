package com.pinpin.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class PinImageTransformTest {
    private fun map(m: FloatArray, x: Float, y: Float) =
        (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

    @Test fun everyRotationAndFlipKeepsCenterAtViewportCenter() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (flip in listOf(false, true)) {
                for (crop in listOf(false, true)) {
                    val m = PinImageTransform.values(800, 400, 300, 500, rotation, flip, crop)
                    val (x, y) = map(m, 400f, 200f)
                    assertEquals(150f, x, 0.001f)
                    assertEquals(250f, y, 0.001f)
                }
            }
        }
    }

    @Test fun quarterTurnMapsSourceTopLeftToViewportTopRight() {
        val m = PinImageTransform.values(800, 400, 200, 400, 90, false, false)
        assertEquals(200f, map(m, 0f, 0f).first, 0.001f)
        assertEquals(0f, map(m, 0f, 0f).second, 0.001f)
        assertEquals(0f, map(m, 800f, 400f).first, 0.001f)
        assertEquals(400f, map(m, 800f, 400f).second, 0.001f)
    }

    @Test fun cropIncludesNegativeOffsetsAndFlipMirrorsThem() {
        val normal = PinImageTransform.values(800, 400, 200, 200, 0, false, true)
        val flipped = PinImageTransform.values(800, 400, 200, 200, 0, true, true)
        assertEquals(-100f, map(normal, 0f, 0f).first, 0.001f)
        assertEquals(300f, map(flipped, 0f, 0f).first, 0.001f)
    }
}
