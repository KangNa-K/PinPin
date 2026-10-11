package com.pinpin.app.overlay

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ShapeGestureTest {
    @Test fun tapSelectsButDrawingAcrossUnselectedShapeStillAddsInk() = onMain {
        val view = DrawingOverlayView(ApplicationProvider.getApplicationContext<Context>())
        view.layout(0, 0, 400, 400)
        drawRectangle(view)
        assertEquals(DrawingOverlayView.RecognizedShape.RECTANGLE, view.snapshot().strokes.single().recognizedShape)
        gesture(view, listOf(140f to 100f, 140f to 50f))
        assertEquals(2, view.snapshot().strokes.size)
        assertEquals(100f, view.snapshot().strokes.first().points.first().y, 1f)
        view.undo()
        gesture(view, listOf(140f to 100f))
        assertEquals(1, view.snapshot().strokes.size)
        gesture(view, listOf(140f to 100f, 140f to 140f))
        assertEquals(1, view.snapshot().strokes.size)
        assertEquals(140f, view.snapshot().strokes.first().points.first().y, 1f)
    }

    @Test fun recognitionToggleKeepsOriginalPointsAndUndoRedo() = onMain {
        val view = DrawingOverlayView(ApplicationProvider.getApplicationContext<Context>())
        view.layout(0, 0, 400, 400)
        view.shapeRecognitionEnabled = false
        drawRectangle(view)
        val raw = view.snapshot().strokes.single()
        assertEquals(DrawingOverlayView.RecognizedShape.NONE, raw.recognizedShape)
        view.undo()
        view.redo()
        assertEquals(raw, view.snapshot().strokes.single())
        view.shapeRecognitionEnabled = true
        drawRectangle(view)
        assertEquals(DrawingOverlayView.RecognizedShape.RECTANGLE,
            view.snapshot().strokes.last().recognizedShape)
    }

    private fun drawRectangle(view: DrawingOverlayView) {
        gesture(view, listOf(100f to 100f, 140f to 100f, 180f to 100f,
            180f to 140f, 180f to 180f, 140f to 180f,
            100f to 180f, 100f to 140f, 100f to 100f))
    }

    private fun gesture(view: DrawingOverlayView, points: List<Pair<Float, Float>>) {
        val events = if (points.size == 1) points + points else points
        events.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                events.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(0, (i * 16).toLong(), action, x, y, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
