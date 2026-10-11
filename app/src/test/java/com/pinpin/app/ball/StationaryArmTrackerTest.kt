package com.pinpin.app.ball

import org.junit.Assert.*
import org.junit.Test

class StationaryArmTrackerTest {
    @Test fun waitsFullDelayAfterLastSignificantMove() {
        val tracker = StationaryArmTracker(500, 12f)
        tracker.start(0f, 0f, 0)
        assertTrue(tracker.move(100f, 100f, 450))
        assertEquals(450L, tracker.remainingMs(500))
        assertEquals(0L, tracker.remainingMs(950))
    }

    @Test fun sensorJitterDoesNotRestartCountdown() {
        val tracker = StationaryArmTracker(500, 12f)
        tracker.start(100f, 100f, 0)
        assertFalse(tracker.move(104f, 103f, 200))
        assertFalse(tracker.move(98f, 99f, 400))
        assertEquals(0L, tracker.remainingMs(500))
    }

    @Test fun slowMovementAccumulatesAndLoopingMotionDoesNotArm() {
        val tracker = StationaryArmTracker(500, 12f)
        tracker.start(0f, 0f, 0)
        assertFalse(tracker.move(10f, 0f, 100))
        assertTrue(tracker.move(20f, 0f, 200))
        assertTrue(tracker.move(0f, 0f, 400))
        assertEquals(400L, tracker.remainingMs(500))
    }

    @Test fun progressUsesRealDeadlineAndCancelClearsProgress() {
        val tracker = StationaryArmTracker(500, 12f)
        tracker.start(0f, 0f, 100)
        assertEquals(0.5f, tracker.progress(350), 0.001f)
        assertEquals(1f, tracker.progress(700), 0.001f)
        tracker.cancel()
        assertEquals(0f, tracker.progress(700), 0.001f)
    }
}
