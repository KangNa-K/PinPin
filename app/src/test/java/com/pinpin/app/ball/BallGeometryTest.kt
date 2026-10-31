package com.pinpin.app.ball

import com.pinpin.app.ball.BallGeometry.SnapTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values are worked out by hand from the pre-extraction BallView math. */
class BallGeometryTest {

    // 120 px ball on a 1600 x 1000 display with a 20 px edge margin.
    private fun snap(x: Int, y: Int, displayWidth: Int = 1600, displayHeight: Int = 1000) =
        BallGeometry.snapTarget(
            x, y, ballWidth = 120, ballHeight = 120,
            displayWidth = displayWidth, displayHeight = displayHeight, margin = 20,
            edgeLeft = LEFT, edgeRight = RIGHT,
        )

    @Test fun snapsToNearerHorizontalEdge() {
        assertEquals(SnapTarget(20, 300, LEFT), snap(100, 300))
        // 1600 - 120 - 20 = 1460
        assertEquals(SnapTarget(1460, 300, RIGHT), snap(1000, 300))
    }

    @Test fun ballCentredOnScreenSnapsRight() {
        // centre x = 740 + 60 = 800, not less than 1600 / 2
        assertEquals(RIGHT, snap(740, 300).edge)
        assertEquals(LEFT, snap(739, 300).edge)
    }

    @Test fun verticalClampHasMarginAtBottomOnly() {
        assertEquals(0, snap(100, -50).y)
        assertEquals(5, snap(100, 5).y)
        // 1000 - 120 - 20 = 860
        assertEquals(860, snap(100, 950).y)
    }

    @Test fun unknownDisplaySizeParksAtOrigin() {
        assertEquals(SnapTarget(0, 0, RIGHT), snap(100, 300, displayWidth = 0, displayHeight = 0))
    }

    @Test fun displayNarrowerThanBallKeepsXAtZero() {
        assertEquals(0, snap(30, 300, displayWidth = 100).x)
    }

    @Test fun sampleSizeHalvesUntilLongestEdgeIsAtMostTwiceTarget() {
        // 4000 -> 2000 -> 1000 -> 500 <= 600
        assertEquals(8, BallGeometry.sampleSize(4000, 3000, 300))
        assertEquals(8, BallGeometry.sampleSize(3000, 4000, 300))
    }

    @Test fun sampleSizeBoundaryIsInclusive() {
        assertEquals(1, BallGeometry.sampleSize(600, 400, 300))
        assertEquals(2, BallGeometry.sampleSize(601, 10, 300))
    }

    @Test fun sampleSizeIgnoresShortEdgeOfPanoramas() {
        // 8000 / 16 = 500 <= 588, leaving the short edge at 1000 / 16 = 62 px.
        assertEquals(16, BallGeometry.sampleSize(8000, 1000, 294))
    }

    private companion object {
        const val LEFT = 0
        const val RIGHT = 1
    }
}
