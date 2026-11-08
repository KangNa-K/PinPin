package com.pinpin.app.overlay

import com.pinpin.app.overlay.PinColorPanelGeometry.Bounds
import org.junit.Assert.assertEquals
import org.junit.Test

class PinColorPanelGeometryTest {
    @Test fun centersAtBottomOfLandscapeSafeArea() {
        assertEquals(350 to 720, PinColorPanelGeometry.position(Bounds(10, 20, 990, 780), 300, 60))
    }

    @Test fun centersAtBottomOfPortraitSafeArea() {
        assertEquals(45 to 720, PinColorPanelGeometry.position(Bounds(10, 20, 380, 780), 300, 60))
    }

    @Test fun oversizedPanelStartsAtSafeAreaOrigin() {
        assertEquals(10 to 20, PinColorPanelGeometry.position(Bounds(10, 20, 110, 60), 120, 60))
    }
}
