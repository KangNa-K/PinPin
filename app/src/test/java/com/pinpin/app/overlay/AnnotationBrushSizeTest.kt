package com.pinpin.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationBrushSizeTest {
    @Test fun sliderEndsMapToTwoAndTwentyFourDp() {
        assertEquals(2f, AnnotationBrushSize.widthForProgress(0), 0.0001f)
        assertEquals(24f, AnnotationBrushSize.widthForProgress(100), 0.0001f)
        // 2 + 0.5 * 22 = 13
        assertEquals(13f, AnnotationBrushSize.widthForProgress(50), 0.0001f)
    }

    @Test fun defaultCanvasWidthStartsSliderAt18() {
        // DrawingOverlayView starts at 6 dp: (6 - 2) / 22 * 100 = 18.18 -> 18.
        assertEquals(18, AnnotationBrushSize.progressForWidth(6f))
    }

    @Test fun widthsOutsideRangeClampToSliderEnds() {
        assertEquals(0, AnnotationBrushSize.progressForWidth(1f))
        assertEquals(100, AnnotationBrushSize.progressForWidth(30f))
    }

    @Test fun sliderStepReadsBackWithinOneStep() {
        // progressForWidth truncates like the original code, so float error can
        // drop a step (3 -> 2.66 dp -> 2) but never move it up.
        for (progress in 0..100) {
            val readBack = AnnotationBrushSize.progressForWidth(AnnotationBrushSize.widthForProgress(progress))
            assertTrue("progress $progress read back as $readBack", readBack in (progress - 1)..progress)
        }
    }

    @Test fun previewDotNeverShrinksBelowEightDp() {
        // density 2: 2 dp brush = 4 px, floor is 8 dp = 16 px.
        assertEquals(16, AnnotationBrushSize.previewSizePx(2f, 2f))
        assertEquals(48, AnnotationBrushSize.previewSizePx(24f, 2f))
        // density 1.5: 6 dp = 9 px, floor 8 dp = 12 px.
        assertEquals(12, AnnotationBrushSize.previewSizePx(6f, 1.5f))
    }
}
