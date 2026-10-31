package com.pinpin.app.overlay

import com.pinpin.app.overlay.PinWindowGeometry.CORNER_BL
import com.pinpin.app.overlay.PinWindowGeometry.CORNER_BR
import com.pinpin.app.overlay.PinWindowGeometry.CORNER_TL
import com.pinpin.app.overlay.PinWindowGeometry.CORNER_TR
import com.pinpin.app.overlay.PinWindowGeometry.ActionSlot
import com.pinpin.app.overlay.PinWindowGeometry.Box
import com.pinpin.app.overlay.PinWindowGeometry.Frame
import com.pinpin.app.overlay.PinWindowGeometry.NO_CORNER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values are worked out by hand from the pre-extraction PinWindowView math. */
class PinWindowGeometryTest {

    @Test fun cornerAtDetectsAllFourInsetHandles() {
        assertEquals(CORNER_TL, PinWindowGeometry.cornerAt(16f, 16f, 200, 300, 16f, 24f))
        assertEquals(CORNER_TR, PinWindowGeometry.cornerAt(184f, 16f, 200, 300, 16f, 24f))
        assertEquals(CORNER_BL, PinWindowGeometry.cornerAt(16f, 284f, 200, 300, 16f, 24f))
        assertEquals(CORNER_BR, PinWindowGeometry.cornerAt(184f, 284f, 200, 300, 16f, 24f))
    }

    @Test fun cornerAtUsesInclusiveRadiusAndRejectsCentre() {
        // Exactly on the radius counts; just past it does not.
        assertEquals(CORNER_TL, PinWindowGeometry.cornerAt(40f, 16f, 200, 300, 16f, 24f))
        assertEquals(NO_CORNER, PinWindowGeometry.cornerAt(40.5f, 16f, 200, 300, 16f, 24f))
        assertEquals(NO_CORNER, PinWindowGeometry.cornerAt(100f, 150f, 200, 300, 16f, 24f))
    }

    @Test fun cornerAtRejectsEmptyImage() {
        assertEquals(NO_CORNER, PinWindowGeometry.cornerAt(0f, 0f, 0, 300, 16f, 24f))
        assertEquals(NO_CORNER, PinWindowGeometry.cornerAt(0f, 0f, 200, -1, 16f, 24f))
    }

    @Test fun oppositeAnchorIsTheFixedCorner() {
        assertEquals(400f to 600f, PinWindowGeometry.oppositeAnchor(CORNER_TL, 100, 200, 300, 400))
        assertEquals(100f to 600f, PinWindowGeometry.oppositeAnchor(CORNER_TR, 100, 200, 300, 400))
        assertEquals(400f to 200f, PinWindowGeometry.oppositeAnchor(CORNER_BL, 100, 200, 300, 400))
        assertEquals(100f to 200f, PinWindowGeometry.oppositeAnchor(CORNER_BR, 100, 200, 300, 400))
    }

    @Test fun bottomRightDragAlongDiagonalDoublesSize() {
        // scale = 1 + (100*100 + 100*100) / (100^2 + 100^2) = 2
        assertEquals(Frame(100, 200, 200, 200), resize(CORNER_BR, 100f, 200f, 100, 100, 100f, 100f))
    }

    @Test fun topLeftDragUpAndLeftGrowsAwayFromAnchor() {
        // TL inverts the delta: (-100, -100) grows. scale = 1 + 40000 / 80000 = 1.5
        assertEquals(Frame(100, 100, 300, 300), resize(CORNER_TL, 400f, 400f, 200, 200, -100f, -100f))
    }

    @Test fun dragAcrossDiagonalKeepsSize() {
        // Perpendicular to the 1:1 diagonal: projection is zero.
        assertEquals(Frame(0, 0, 200, 200), resize(CORNER_BR, 0f, 0f, 200, 200, 50f, -50f))
    }

    @Test fun shrinkStopsAtMinimumSize() {
        // raw scale 0.25 clamps to 160 / 200 = 0.8
        assertEquals(Frame(0, 0, 160, 160), resize(CORNER_BR, 0f, 0f, 200, 200, -150f, -150f))
    }

    @Test fun imageAlreadyBelowMinimumCannotShrinkFurtherButKeepsSize() {
        assertEquals(Frame(0, 0, 100, 50), resize(CORNER_BR, 0f, 0f, 100, 50, -80f, -40f))
    }

    @Test fun growStopsWhenLongerSideHitsMaximum() {
        // max scale = min(4096 / 2000, 4096 / 1000) = 2.048
        assertEquals(Frame(0, 0, 4096, 2048), resize(CORNER_BR, 0f, 0f, 2000, 1000, 10_000f, 10_000f))
    }

    @Test fun invalidCornerProducesNoFrame() {
        assertNull(resize(NO_CORNER, 0f, 0f, 200, 200, 10f, 10f))
        assertNull(resize(4, 0f, 0f, 200, 200, 10f, 10f))
    }

    @Test fun clampKeepsAQuarterOfTheWrapperOnScreen() {
        // wrapper 200x300: min = (-150, -225), max = (1600 - 50, 1000 - 75)
        assertEquals(-150 to -225, PinWindowGeometry.clampPosition(-400, -600, 200, 300, 1600, 1000))
        assertEquals(1550 to 925, PinWindowGeometry.clampPosition(5000, 5000, 200, 300, 1600, 1000))
        assertEquals(10 to 20, PinWindowGeometry.clampPosition(10, 20, 200, 300, 1600, 1000))
    }

    @Test fun annotationFitTallImageOnEmulatorStaysBelowStatusBar() {
        // 1600x1000 emulator with a 128px status bar, the setup that hid the X button.
        // maxH = 872 - 64 - (8 + 40) = 760, aspect 0.5 -> 380x760
        val fit = fit(insetTop = 128, width = 1000, height = 2000)
        assertEquals(Frame(610, 162, 380, 760), fit)
        assertTrue("image must start below the status bar", fit.y >= 128 + 32)
    }

    @Test fun annotationFitWideImageIsWidthBound() {
        // maxW = 1600 - 64 = 1536, maxH = 760; aspect 4 > 1536 / 760 -> 1536x384
        // y = 128 + (872 - (384 + 44)) / 2 = 350
        assertEquals(Frame(32, 350, 1536, 384), fit(insetTop = 128, width = 800, height = 200))
    }

    @Test fun annotationFitReservesLargerSideInsetOnBothSides() {
        // 100px cutout on the left only: maxW = 1600 - 200 - 64 = 1336
        assertEquals(1336, fit(insetLeft = 100, width = 800, height = 200).width)
    }

    @Test fun annotationFitTreatsZeroHeightAsSquare() {
        // aspect 1, maxH = 1000 - 64 - 48 = 888 -> 888x888
        val fit = fit(width = 100, height = 0)
        assertEquals(888, fit.width)
        assertEquals(888, fit.height)
    }

    // 60 px buttons, 12 px margin, 6 px spacing (density 1.5): step 66.
    private fun slot(index: Int, width: Int) =
        PinWindowGeometry.imageActionSlot(index, width, buttonSize = 60, margin = 12, spacing = 6)

    @Test fun imageActionsFillRowsLeftwardsFromTopRight() {
        // (300 - 24 + 6) / 66 = 4 columns
        assertEquals(ActionSlot(12, 12), slot(0, 300))
        assertEquals(ActionSlot(210, 12), slot(3, 300))
        // (150 - 24 + 6) / 66 = 2 columns
        assertEquals(ActionSlot(78, 12), slot(1, 150))
        assertEquals(ActionSlot(12, 78), slot(2, 150))
        assertEquals(ActionSlot(78, 78), slot(3, 150))
    }

    @Test fun imageActionColumnsAreClampedBetweenOneAndFour() {
        // Two columns start at exactly 150 px: (150 - 24 + 6) / 66 = 2.
        assertEquals(ActionSlot(12, 78), slot(1, 149))
        assertEquals(ActionSlot(78, 12), slot(1, 150))
        assertEquals(ActionSlot(12, 210), slot(3, 0))
        assertEquals(ActionSlot(12, 78), slot(4, 2000))
    }

    private val card = Box(0, 0, 300, 200)
    private val close = Box(248, 8, 288, 48)

    @Test fun imageBodyHitExcludesVisibleControls() {
        assertTrue(PinWindowGeometry.isImageBodyHit(150f, 100f, card, listOf(close)))
        assertFalse(PinWindowGeometry.isImageBodyHit(260f, 20f, card, listOf(close)))
        // Control right/bottom edges are exclusive.
        assertTrue(PinWindowGeometry.isImageBodyHit(288f, 20f, card, listOf(close)))
        // A hidden control is simply not passed in.
        assertTrue(PinWindowGeometry.isImageBodyHit(260f, 20f, card, emptyList()))
    }

    @Test fun imageBodyHitRejectsPointsOutsideTheCard() {
        assertTrue(PinWindowGeometry.isImageBodyHit(0f, 0f, card, emptyList()))
        assertFalse(PinWindowGeometry.isImageBodyHit(300f, 100f, card, emptyList()))
        assertFalse(PinWindowGeometry.isImageBodyHit(150f, 200f, card, emptyList()))
        assertFalse(PinWindowGeometry.isImageBodyHit(-0.5f, 100f, card, emptyList()))
    }

    @Test fun imageBodyHitMapsControlsIntoCardCoordinates() {
        val offsetCard = Box(10, 20, 310, 220)
        assertFalse(PinWindowGeometry.isImageBodyHit(270f, 40f, offsetCard, listOf(close)))
        assertTrue(PinWindowGeometry.isImageBodyHit(260f, 20f, offsetCard, listOf(close)))
    }

    @Test fun bodyDragNeedsOneAxisPastSlop() {
        assertFalse(PinWindowGeometry.bodyDragStarted(16, 16, slop = 16))
        assertTrue(PinWindowGeometry.bodyDragStarted(17, 0, slop = 16))
        assertTrue(PinWindowGeometry.bodyDragStarted(0, -17, slop = 16))
    }

    @Test fun cornerDragUsesTravelDistance() {
        // hypot(12, 12) = 16.97 > 16 although neither axis passes the slop.
        assertTrue(PinWindowGeometry.cornerDragStarted(12f, 12f, slop = 16))
        assertFalse(PinWindowGeometry.cornerDragStarted(16f, 0f, slop = 16))
    }

    private fun resize(
        corner: Int, anchorX: Float, anchorY: Float, w: Int, h: Int, dx: Float, dy: Float,
    ) = PinWindowGeometry.resizeFromCorner(corner, anchorX, anchorY, w, h, dx, dy, minSize = 160, maxSize = 4096)

    private fun fit(insetLeft: Int = 0, insetTop: Int = 0, width: Int, height: Int) =
        PinWindowGeometry.annotationFit(
            screenWidth = 1600, screenHeight = 1000,
            insetLeft = insetLeft, insetTop = insetTop, insetRight = 0, insetBottom = 0,
            padding = 32, toolbarGap = 4, toolbarHeight = 40,
            currentWidth = width, currentHeight = height,
        )
}
