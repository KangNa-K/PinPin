package com.pinpin.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.provider.Settings
import android.widget.ImageView
import com.pinpin.app.data.PinEntity
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OverlayReliabilityTest {
    @Test fun pinnedCaptureWindowUsesExactScreenCoordinatesIncludingScreenEdges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Overlay permission required", Settings.canDrawOverlays(context))
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        for ((x, y) in listOf(230 to 410, 0 to 0)) {
            lateinit var view: PinWindowView
            val laidOut = CountDownLatch(1)
            instrumentation.runOnMainSync {
                view = PinWindowView(
                    context, "capture-position", PinWindowView.PinSpec(
                        label = "Capture test", imageUri = "pinpin://test", sourceType = "ball-screencap",
                        initialX = x, initialY = y, widthPx = 240, heightPx = 180,
                        locked = false, zOrder = 1,
                    ), { _, _ -> }, { _, _ -> }, {}, {},
                )
                view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> laidOut.countDown() }
                manager.addView(view, view.layoutParams)
            }
            try {
                assertTrue("Overlay must be laid out", laidOut.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    val position = IntArray(2)
                    view.getChildAt(0).getLocationOnScreen(position)
                    assertArrayEquals(intArrayOf(x, y), position)
                    assertEquals(240, view.getChildAt(0).width)
                    assertEquals(180, view.getChildAt(0).height)
                    view.showToolbar(true)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val position = IntArray(2)
                    view.getChildAt(0).getLocationOnScreen(position)
                    assertArrayEquals(intArrayOf(x, y), position)
                }
            } finally {
                instrumentation.runOnMainSync { manager.removeViewImmediate(view) }
            }
        }
    }

    @Test fun rotationSnapshotsApplyBoundsOnceAndRemainStableWhenRepeatedOrRestored() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val original = PinEntity("rotation", "pinpin://test", "test", 120, 180, 400f, 200f)
            fun create(pin: PinEntity) = PinWindowView(
                context, pin.id, PinWindowView.PinSpec(
                    label = "Rotation test", imageUri = pin.imageUri, sourceType = pin.sourceType,
                    initialX = pin.x, initialY = pin.y,
                    widthPx = pin.width.toInt(), heightPx = pin.height.toInt(),
                    locked = false, zOrder = 1, rotation = pin.rotation,
                ), { _, _ -> }, { _, _ -> }, {}, {},
            )
            val view = create(original)
            for (rotation in listOf(90, 180, 270, 0)) {
                val quarterTurn = rotation % 180 != 0
                val snapshot = original.copy(
                    rotation = rotation,
                    x = if (quarterTurn) 220 else 120,
                    y = if (quarterTurn) 80 else 180,
                    width = if (quarterTurn) 200f else 400f,
                    height = if (quarterTurn) 400f else 200f,
                )
                repeat(2) {
                    view.applySnapshot(snapshot)
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(view.currentW, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(view.currentH, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, view.currentW, view.currentH)
                    assertEquals(snapshot.width.toInt(), view.currentW)
                    assertEquals(snapshot.height.toInt(), view.currentH)
                    assertEquals(snapshot.x, view.currentX)
                    assertEquals(snapshot.y, view.currentY)
                    assertEquals(rotation, view.spec.rotation)
                    val image = (view.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as ImageView
                    assertEquals(view.currentW, image.width)
                    assertEquals(view.currentH, image.height)
                }
                val restored = create(snapshot)
                restored.applySnapshot(snapshot)
                assertEquals(view.currentW, restored.currentW)
                assertEquals(view.currentH, restored.currentH)
                assertEquals(rotation, restored.spec.rotation)
            }
        }
    }

    @Test fun cancellationDiscardsInkAndPreservesRedoForEveryTool() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (mode in DrawingOverlayView.Mode.entries) {
                val view = DrawingOverlayView(ApplicationProvider.getApplicationContext<Context>())
                view.layout(0, 0, 200, 200)
                view.shapeRecognitionEnabled = false
                view.mode = mode
                gesture(view, MotionEvent.ACTION_UP)
                assertTrue(view.canUndo())
                view.undo()
                assertTrue(view.canRedo())
                gesture(view, MotionEvent.ACTION_CANCEL)
                assertFalse(view.canUndo())
                assertTrue(view.canRedo())
                view.redo()
                assertTrue(view.canUndo())
            }
        }
    }

    private fun gesture(view: DrawingOverlayView, terminal: Int) {
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, terminal).forEachIndexed { i, action ->
            val event = MotionEvent.obtain(0, i * 20L, action, 20f + i * 60, 20f + i * 60, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
    }

    @Test fun annotationInverseMatchesDisplayedPixelsForAllTransforms() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (flip in listOf(false, true)) {
                val transform = Matrix().apply {
                    setValues(PinImageTransform.values(160, 80, 100, 100, rotation, flip, true))
                }
                val inverse = Matrix()
                assertTrue(transform.invert(inverse))
                val ink = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                val saved = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
                val displayed = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(ink).drawRect(35f, 42f, 60f, 58f, android.graphics.Paint().apply { color = Color.RED })
                    Canvas(saved).drawBitmap(ink, inverse, null)
                    Canvas(displayed).drawBitmap(saved, transform, null)
                    assertEquals(Color.RED, displayed.getPixel(45, 50))
                    assertEquals(Color.TRANSPARENT, displayed.getPixel(10, 10))
                } finally {
                    ink.recycle(); saved.recycle(); displayed.recycle()
                }
            }
        }
    }

    @Test fun exportPreservesRotationAndFlipWithoutMutatingSource() {
        val source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        source.setPixel(0, 0, Color.RED)
        try {
            val output = PinImageSnapshot(source, 90, true).render()
            try {
                assertEquals(2, output.width)
                assertEquals(4, output.height)
                assertEquals(Color.RED, output.getPixel(0, 0))
                assertFalse(source.isRecycled)
                assertEquals(Color.RED, source.getPixel(0, 0))
            } finally { output.recycle() }
        } finally { source.recycle() }
    }
}
