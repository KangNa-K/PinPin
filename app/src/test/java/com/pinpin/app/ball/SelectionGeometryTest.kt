package com.pinpin.app.ball

import com.pinpin.app.ball.SelectionGeometry.Bounds
import com.pinpin.app.ball.SelectionGeometry.Handle
import com.pinpin.app.ball.SelectionGeometry.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values are worked out by hand from the pre-extraction SelectionOverlayView math. */
class SelectionGeometryTest {

    // Selection (100, 100) - (300, 400), 32 px handle radius.
    private fun handleAt(x: Float, y: Float, left: Int = 100, right: Int = 300) =
        SelectionGeometry.nearestHandle(x, y, left, 100, right, 400, threshold = 32f)

    @Test fun nearestHandleFindsEachCorner() {
        assertEquals(Handle.TOP_LEFT, handleAt(100f, 100f))
        assertEquals(Handle.TOP_RIGHT, handleAt(300f, 100f))
        assertEquals(Handle.BOTTOM_LEFT, handleAt(100f, 400f))
        assertEquals(Handle.BOTTOM_RIGHT, handleAt(300f, 400f))
    }

    @Test fun nearestHandlePrefersCornerOverEdge() {
        // 14 px from the corner and also on the top and left edges.
        assertEquals(Handle.TOP_LEFT, handleAt(110f, 110f))
    }

    @Test fun nearestHandleCornerRadiusIsInclusive() {
        assertEquals(Handle.TOP_LEFT, handleAt(132f, 100f))
        // Past the corner radius but still on the top edge.
        assertEquals(Handle.TOP, handleAt(132.5f, 100f))
    }

    @Test fun nearestHandleFindsEachEdgeInsideItsSpan() {
        assertEquals(Handle.LEFT, handleAt(90f, 250f))
        assertEquals(Handle.RIGHT, handleAt(310f, 250f))
        assertEquals(Handle.TOP, handleAt(200f, 70f))
        assertEquals(Handle.BOTTOM, handleAt(200f, 430f))
        assertEquals(Handle.LEFT, handleAt(68f, 250f))
        assertEquals(Handle.NONE, handleAt(67.5f, 250f))
    }

    @Test fun nearestHandleIgnoresEdgesOutsideTheirSpanAndTheCentre() {
        // Left of the rect but below it, and 50 px from the bottom-left corner.
        assertEquals(Handle.NONE, handleAt(90f, 450f))
        assertEquals(Handle.NONE, handleAt(200f, 250f))
    }

    @Test fun nearestHandleNarrowRectPrefersLeftEdge() {
        // 40 px wide: the point is within 32 px of both vertical edges.
        assertEquals(Handle.LEFT, handleAt(120f, 250f, left = 100, right = 140))
    }

    private val start = Bounds(100f, 100f, 300f, 400f)

    @Test fun moveTranslatesKeepingFractions() {
        assertEquals(Bounds(150.5f, 80f, 350.5f, 380f), SelectionGeometry.move(start, 50.5f, -20f, 500, 800))
    }

    @Test fun moveStopsAtScreenEdges() {
        assertEquals(Bounds(300f, 0f, 500f, 300f), SelectionGeometry.move(start, 1000f, -1000f, 500, 800))
    }

    @Test fun moveKeepsOversizedSelectionAtOrigin() {
        val big = Bounds(0f, 0f, 600f, 900f)
        assertEquals(big, SelectionGeometry.move(big, 10f, 10f, 500, 800))
    }

    private fun resize(handle: Handle, dx: Float, dy: Float, bounds: Bounds = start) =
        SelectionGeometry.resize(bounds, handle, dx, dy, screenWidth = 500, screenHeight = 800, minSize = 16f)

    @Test fun resizeMovesOnlyTheHandlesEdges() {
        assertEquals(Bounds(100f, 100f, 320f, 400f), resize(Handle.RIGHT, 20f, 50f))
        assertEquals(Bounds(100f, 90f, 300f, 400f), resize(Handle.TOP, 20f, -10f))
        assertEquals(Bounds(70f, 60f, 300f, 400f), resize(Handle.TOP_LEFT, -30f, -40f))
        assertEquals(Bounds(100f, 100f, 280f, 420f), resize(Handle.BOTTOM_RIGHT, -20f, 20f))
    }

    @Test fun resizeKeepsMinimumSize() {
        assertEquals(Bounds(284f, 100f, 300f, 400f), resize(Handle.LEFT, 500f, 0f))
        assertEquals(Bounds(100f, 100f, 300f, 116f), resize(Handle.BOTTOM, 0f, -500f))
    }

    @Test fun resizeStopsAtScreenEdges() {
        assertEquals(Bounds(100f, 100f, 500f, 800f), resize(Handle.BOTTOM_RIGHT, 500f, 900f))
        assertEquals(Bounds(0f, 0f, 300f, 400f), resize(Handle.TOP_LEFT, -500f, -500f))
    }

    @Test fun resizeNarrowSelectionAtRightEdgeDoesNotThrow() {
        // At density 1.33125, 16 dp = 21.3 px but the accepted rect is truncated to 21 px.
        // Clamp range for the right edge was 479 + 21.3 .. 500, i.e. empty.
        val narrow = Bounds(479f, 300f, 500f, 500f)
        val result = SelectionGeometry.resize(narrow, Handle.TOP_RIGHT, -1f, 0f, 500, 800, minSize = 21.3f)
        assertEquals(Bounds(479f, 300f, 500f, 500f), result)
    }

    @Test fun resizeNarrowSelectionAtLeftEdgeDoesNotThrow() {
        val narrow = Bounds(0f, 300f, 21f, 500f)
        val result = SelectionGeometry.resize(narrow, Handle.LEFT, 1f, 0f, 500, 800, minSize = 21.3f)
        assertEquals(Bounds(0f, 300f, 21f, 500f), result)
    }

    @Test fun resizeShortSelectionAtBottomEdgeDoesNotThrow() {
        val short = Bounds(100f, 779f, 300f, 800f)
        val result = SelectionGeometry.resize(short, Handle.BOTTOM, 0f, -1f, 500, 800, minSize = 21.3f)
        assertEquals(Bounds(100f, 779f, 300f, 800f), result)
    }

    @Test fun resizeWithoutHandleLeavesBoundsAlone() {
        assertEquals(start, resize(Handle.NONE, 40f, 40f))
    }

    @Test fun resizeAccumulatesSubPixelDrags() {
        var bounds = start
        repeat(10) { bounds = resize(Handle.RIGHT, 0.2f, 0f, bounds) }
        assertEquals(302f, bounds.right, 0.001f)
    }

    // 500 x 800 window, 248 x 40 toolbar, 12 px margin.
    private fun toolbar(
        left: Int, top: Int, bottom: Int, width: Int, windowWidth: Int = 500,
        safeLeft: Int = 0, safeTop: Int = 0, safeRight: Int = 0, safeBottom: Int = 0,
    ) = SelectionGeometry.toolbarPosition(
        selectionLeft = left, selectionTop = top, selectionBottom = bottom, selectionWidth = width,
        toolbarWidth = 248, toolbarHeight = 40, margin = 12,
        windowWidth = windowWidth, windowHeight = 800,
        safeLeft = safeLeft, safeTop = safeTop, safeRight = safeRight, safeBottom = safeBottom,
    )

    @Test fun toolbarCentresBelowSelection() {
        // x = 100 + 200 / 2 - 248 / 2 = 76; y = 360 + 12 = 372
        assertEquals(ToolbarPosition(76, 372), toolbar(100, 120, 360, 200))
    }

    @Test fun toolbarBelowFitIsInclusive() {
        // 748 + 12 + 40 = 800 exactly.
        assertEquals(ToolbarPosition(76, 760), toolbar(100, 600, 748, 200))
    }

    @Test fun toolbarFlipsAboveWhenNoRoomBelow() {
        // 600 - 12 - 40 = 548
        assertEquals(ToolbarPosition(76, 548), toolbar(100, 600, 780, 200))
    }

    @Test fun toolbarFallsBackToTopMarginForFullHeightSelection() {
        // Neither side fits, so the toolbar overlaps the selection at the top margin.
        assertEquals(ToolbarPosition(76, 12), toolbar(100, 0, 800, 200))
    }

    @Test fun toolbarStaysInsideHorizontalMargins() {
        assertEquals(12, toolbar(0, 120, 360, 20).left)
        // 500 - 248 - 12 = 240
        assertEquals(240, toolbar(480, 120, 360, 20).left)
        // Window narrower than the toolbar: pinned to the left margin.
        assertEquals(12, toolbar(0, 120, 360, 100, windowWidth = 200).left)
    }

    @Test fun fullHeightSelectionKeepsToolbarBelowStatusBar() {
        // 100 px status bar: 100 + 12 = 112 instead of 12.
        assertEquals(ToolbarPosition(76, 112), toolbar(100, 0, 800, 200, safeTop = 100))
    }

    @Test fun flippedToolbarStaysBelowStatusBar() {
        // above = 130 - 12 - 40 = 78 would sit under a 100 px status bar.
        assertEquals(112, toolbar(100, 130, 800, 200, safeTop = 100).top)
    }

    @Test fun toolbarFlipsAboveWhenBelowWouldEnterNavigationBar() {
        // below = 713 fits the 800 px window but 713 + 40 > 800 - 48 = 752.
        // above = 400 - 12 - 40 = 348.
        assertEquals(348, toolbar(100, 400, 701, 200, safeBottom = 48).top)
        // Just fits: 712 + 40 = 752.
        assertEquals(712, toolbar(100, 400, 700, 200, safeBottom = 48).top)
    }

    @Test fun toolbarStaysInsideSideInsets() {
        // 40 px left inset: 40 + 12 = 52. Right inset 40: 500 - 40 - 12 - 248 = 200.
        assertEquals(52, toolbar(0, 120, 360, 20, safeLeft = 40).left)
        assertEquals(200, toolbar(480, 120, 360, 20, safeRight = 40).left)
    }
}
