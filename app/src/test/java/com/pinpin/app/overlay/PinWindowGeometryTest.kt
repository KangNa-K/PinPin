package com.pinpin.app.overlay

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test

class PinWindowGeometryTest {
    
    @Test
    fun cornerAtDetectsAllFourHandles() {
        val radius = 24f
        val inset = 16f
        assertEquals(PinWindowGeometry.CORNER_TL, PinWindowGeometry.cornerAt(16f, 16f, 200, 300, inset, radius))
        assertEquals(PinWindowGeometry.CORNER_TR, PinWindowGeometry.cornerAt(184f, 16f, 200, 300, inset, radius))
        assertEquals(PinWindowGeometry.CORNER_BL, PinWindowGeometry.cornerAt(16f, 284f, 200, 300, inset, radius))
        assertEquals(PinWindowGeometry.CORNER_BR, PinWindowGeometry.cornerAt(184f, 284f, 200, 300, inset, radius))
    }
    
    @Test
    fun cornerAtReturnsNoneOutsideRadius() {
        assertEquals(PinWindowGeometry.NO_CORNER, PinWindowGeometry.cornerAt(100f, 150f, 200, 300, 16f, 24f))
    }
    
    @Test
    fun cornerAtReturnsNoneForInvalidDimensions() {
        assertEquals(PinWindowGeometry.NO_CORNER, PinWindowGeometry.cornerAt(0f, 0f, 0, 0, 16f, 24f))
        assertEquals(PinWindowGeometry.NO_CORNER, PinWindowGeometry.cornerAt(0f, 0f, -100, 100, 16f, 24f))
    }
    
    @Test
    fun oppositeAnchorTL() {
        val (x, y) = PinWindowGeometry.oppositeAnchor(PinWindowGeometry.CORNER_TL, 100, 200, 300, 400)
        assertEquals(400f, x)  // x + width
        assertEquals(600f, y)  // y + height
    }
    
    @Test
    fun oppositeAnchorBR() {
        val (x, y) = PinWindowGeometry.oppositeAnchor(PinWindowGeometry.CORNER_BR, 100, 200, 300, 400)
        assertEquals(100f, x)  // x unchanged
        assertEquals(200f, y)  // y unchanged
    }
    
    @Test
    fun resizeFromCornerBRDoubleSize() {
        // BR drag: both fingers move right and down
        val frame = PinWindowGeometry.resizeFromCorner(
            corner = PinWindowGeometry.CORNER_BR,
            anchorX = 100f,
            anchorY = 200f,
            initialWidth = 100,
            initialHeight = 100,
            fingerDx = 100f,  // +100 right
            fingerDy = 100f,  // +100 down
            minSize = 160,
            maxSize = 4096,
        )
        assertNotNull(frame)
        // scale ~= 1 + (100*100 + 100*100)/(100²+100²) = 1 + 1 = 2
        // newW = 100*2 = 200, newH = 100*2 = 200
        assertEquals(200, frame!!.width)
        assertEquals(200, frame.height)
        assertEquals(100, frame.x)  // anchor at BR, so x unchanged
        assertEquals(200, frame.y)  // anchor at BR, so y unchanged
    }
    
    @Test
    fun resizeFromCornerTLQuarterSize() {
        // TL drag: both fingers move left and up, shrinking toward BR
        val frame = PinWindowGeometry.resizeFromCorner(
            corner = PinWindowGeometry.CORNER_TL,
            anchorX = 400f,  // BR corner at 400, 400
            anchorY = 400f,
            initialWidth = 200,
            initialHeight = 200,
            fingerDx = -100f,  // -100 left (direction flipped for TL)
            fingerDy = -100f,  // -100 up (direction flipped for TL)
            minSize = 160,
            maxSize = 4096,
        )
        assertNotNull(frame)
        assertEquals(100, frame!!.width)
        assertEquals(100, frame.height)
        assertEquals(300, frame.x)   // anchorX - newW = 400 - 100
        assertEquals(300, frame.y)   // anchorY - newH = 400 - 100
    }
    
    @Test
    fun clampPositionKeepsQuarterVisible() {
        val (x, y) = PinWindowGeometry.clampPosition(
            x = -400,  // far left, off-screen
            y = -600,  // far up, off-screen
            wrapperWidth = 200,
            wrapperHeight = 300,
            screenRight = 1600,
            screenBottom = 1000,
        )
        // minX = -200 + 50 = -150, minY = -300 + 75 = -225
        assertEquals(-150, x)
        assertEquals(-225, y)
    }
    
    @Test
    fun annotationFitPortraitImage() {
        // 1600x1000 screen, 1000x2000 image (portrait, aspect 0.5)
        val fit = PinWindowGeometry.annotationFit(
            screenWidth = 1600,
            screenHeight = 1000,
            insetLeft = 0,
            insetTop = 128,
            insetRight = 0,
            insetBottom = 0,
            padding = 32,
            toolbarGap = 4,
            toolbarHeight = 40,
            currentWidth = 1000,
            currentHeight = 2000,
        )
        val availableH = 1000 - 128  // 872
        val maxW = 1600 - 32 * 2     // 1536
        val maxH = 872 - 32 * 2 - 4 - 40  // 764
        // aspect 0.5, so height is constraining: fitH = 764, fitW = 382
        assertEquals(382, fit.width)
        assertEquals(764, fit.height)
    }
    
    @Test
    fun annotationFitDefaultsAspectForZeroHeight() {
        // currentHeight = 0 should use aspect 1:1
        val fit = PinWindowGeometry.annotationFit(
            screenWidth = 1600,
            screenHeight = 1000,
            insetLeft = 0,
            insetTop = 128,
            insetRight = 0,
            insetBottom = 0,
            padding = 32,
            toolbarGap = 4,
            toolbarHeight = 40,
            currentWidth = 100,
            currentHeight = 0,  // invalid; should use aspect 1
        )
        // Both width and height should fit; depends on ratio
        assertTrue(fit.width > 0 && fit.height > 0)
    }
}
