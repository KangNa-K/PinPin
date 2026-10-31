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

    @Test fun sampleSizeKeepsShortEdgeAtLeastTarget() {
        // 3000 / 2, 4, 8 stay >= 300 with the long edge still > 600;
        // 3000 / 16 = 187 < 300, so halving stops at 8.
        assertEquals(8, BallGeometry.sampleSize(4000, 3000, 300))
        assertEquals(8, BallGeometry.sampleSize(3000, 4000, 300))
    }

    @Test fun sampleSizeStopsOnceLongEdgeIsAtMostTwiceTarget() {
        assertEquals(1, BallGeometry.sampleSize(600, 400, 300))
        // 601 > 600 and 400 / 2 = 200 < 300: halving would starve the short edge.
        assertEquals(1, BallGeometry.sampleSize(601, 400, 300))
        // 1200 x 1200: 600 >= 300 and 1200 > 600 -> 2; 300 >= 300 but 600 is not > 600.
        assertEquals(2, BallGeometry.sampleSize(1200, 1200, 300))
    }

    @Test fun sampleSizeKeepsPanoramaShortEdgeSharp() {
        // Old behaviour sampled 8000 x 1000 by 16, leaving a 62 px short edge.
        // 1000 / 4 = 250 < 294 stops at 2: 4000 x 500.
        assertEquals(2, BallGeometry.sampleSize(8000, 1000, 294))
        // Long screenshot: 1080 / 4 = 270 < 294 -> 2, giving 540 x 4000.
        assertEquals(2, BallGeometry.sampleSize(1080, 8000, 294))
    }

    @Test fun sampleSizeCapsLongEdgeForExtremeImages() {
        // Short-edge rule keeps 1, then 20000 -> 10000 -> 5000 -> 2500 <= 4096.
        assertEquals(8, BallGeometry.sampleSize(500, 20000, 294))
        assertEquals(1, BallGeometry.sampleSize(294, 4096, 294))
        assertEquals(2, BallGeometry.sampleSize(294, 4097, 294))
    }

    private companion object {
        const val LEFT = 0
        const val RIGHT = 1
    }
}
