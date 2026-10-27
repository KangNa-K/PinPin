package com.pinpin.app.ball

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutoCarryGestureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun touch(view: android.view.View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun stationaryHoldRequestsAutomaticCaptureAtVisualCenter() {
        val captured = CountDownLatch(1)
        lateinit var view: SelectionOverlayView
        var point: Pair<Int, Int>? = null
        instrumentation.runOnMainSync {
            view = SelectionOverlayView(instrumentation.targetContext)
            view.autoCaptureEnabled = true
            view.layoutParamsForScreen(1080, 2400)
            view.listener = object : SelectionOverlayView.Listener {
                override fun onConfirm(rect: Rect) = Unit
                override fun onCancel() = Unit
                override fun onAutoCapture(x: Int, y: Int) { point = x to y; captured.countDown() }
            }
            view.forceArm(150f, 180f, 145f, 175f)
        }
        assertTrue(captured.await(2, TimeUnit.SECONDS))
        assertEquals(150 to 180, point)
    }

    @Test fun movementReleaseAndMultitouchCancelAutoCapture() {
        for (action in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            var captures = 0
            instrumentation.runOnMainSync {
                val view = SelectionOverlayView(instrumentation.targetContext)
                view.autoCaptureEnabled = true
                view.listener = object : SelectionOverlayView.Listener {
                    override fun onConfirm(rect: Rect) = Unit
                    override fun onCancel() = Unit
                    override fun onAutoCapture(x: Int, y: Int) { captures++ }
                }
                view.layoutParamsForScreen(1080, 2400)
                view.forceArm(150f, 180f)
                touch(view, action, 260f, 350f)
            }
            SystemClock.sleep(600)
            instrumentation.runOnMainSync { assertEquals("action=$action", 0, captures) }
        }
    }

    @Test fun loadedBallStartsDragOnlyOnceAndNeverArmsSelection() {
        instrumentation.runOnMainSync {
            val view = BallView(instrumentation.targetContext)
            view.attachedParams = android.view.WindowManager.LayoutParams()
            view.setCarriedBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888))
            var drags = 0
            var taps = 0
            view.onCarryDrag = { drags++; true }
            view.onTap = { taps++ }
            touch(view, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, MotionEvent.ACTION_MOVE, 200f, 200f)
            touch(view, MotionEvent.ACTION_MOVE, 220f, 220f)
            touch(view, MotionEvent.ACTION_UP, 220f, 220f)
            assertEquals(1, drags)
            assertEquals(0, taps)
            touch(view, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, MotionEvent.ACTION_UP, 20f, 20f)
            assertEquals(1, taps)
            view.setCarriedBitmap(null)
        }
    }
    @Test fun releaseCancelAndSecondFingerBeforeLoadDoNotStartDragOrOpenActions() {
        for (action in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            instrumentation.runOnMainSync {
                val view = BallView(instrumentation.targetContext)
                view.attachedParams = android.view.WindowManager.LayoutParams()
                var drags = 0
                var taps = 0
                view.onCarryDrag = { drags++; true }
                view.onTap = { taps++ }
                touch(view, MotionEvent.ACTION_DOWN, 20f, 20f)
                view.beginCarryCaptureGesture()
                touch(view, MotionEvent.ACTION_MOVE, 90f, 90f)
                touch(view, action, 90f, 90f)
                view.setCarriedBitmap(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
                view.finishCaptureGesture()
                view.resumeCarryDragIfMoved()
                touch(view, MotionEvent.ACTION_UP, 90f, 90f)
                assertEquals("action=$action", 0, drags)
                assertEquals("action=$action", 0, taps)
                touch(view, MotionEvent.ACTION_DOWN, 20f, 20f)
                touch(view, MotionEvent.ACTION_UP, 20f, 20f)
                assertEquals(1, taps)
                view.setCarriedBitmap(null)
            }
        }
    }

    @Test fun loadedDuringHoldWaitsForMovementAndDoesNotTurnReleaseIntoTap() {
        instrumentation.runOnMainSync {
            val view = BallView(instrumentation.targetContext)
            view.attachedParams = android.view.WindowManager.LayoutParams()
            var drags = 0
            var taps = 0
            view.onCarryDrag = { drags++; true }
            view.onTap = { taps++ }
            touch(view, MotionEvent.ACTION_DOWN, 20f, 20f)
            view.beginCarryCaptureGesture()
            view.setCarriedBitmap(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
            view.finishCaptureGesture()
            view.resumeCarryDragIfMoved()
            assertEquals(0, drags)
            touch(view, MotionEvent.ACTION_UP, 20f, 20f)
            assertEquals(0, taps)
            touch(view, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, MotionEvent.ACTION_MOVE, 120f, 120f)
            assertEquals(1, drags)
            view.setCarriedBitmap(null)
        }
    }

    @Test fun loadedBallStationaryHoldCanArmAnotherCapture() {
        val armed = CountDownLatch(1)
        lateinit var view: BallView
        instrumentation.runOnMainSync {
            view = BallView(instrumentation.targetContext)
            view.attachedParams = android.view.WindowManager.LayoutParams()
            view.setCarriedBitmap(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
            view.setHost(object : BallView.Host {
                override fun onArm(originX: Int, originY: Int) { armed.countDown() }
                override fun onSelectDone(rectInScreen: Rect) = Unit
                override fun onCancel() = Unit
            })
            touch(view, MotionEvent.ACTION_DOWN, 20f, 20f)
        }
        try { assertTrue("Loaded ball never rearms on the next stationary hold", armed.await(1500, TimeUnit.MILLISECONDS)) }
        finally { instrumentation.runOnMainSync { view.resetToIdle(park = false); view.setCarriedBitmap(null) } }
    }

}
