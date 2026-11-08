package com.pinpin.app.ball

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BallNotificationGestureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun touch(
        view: BallView,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            assertTrue(view.onTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    @Test fun fastDownwardSwipeOpensShadeAndLeavesBallInPlace() {
        instrumentation.runOnMainSync {
            val view = BallView(instrumentation.targetContext)
            val params = WindowManager.LayoutParams().apply { x = 100; y = 240 }
            view.attachedParams = params
            var shadeRequests = 0
            var taps = 0
            view.onSwipeDown = { shadeRequests++; true }
            view.onTap = { taps++ }
            val swipeDistance = 80f * view.resources.displayMetrics.density
            val downTime = SystemClock.uptimeMillis()

            touch(view, downTime, downTime, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, downTime, downTime + 60, MotionEvent.ACTION_MOVE, 20f, 20f + swipeDistance / 4)
            assertEquals(100, params.x)
            assertEquals(240, params.y)
            touch(view, downTime, downTime + 120, MotionEvent.ACTION_MOVE, 20f, 20f + swipeDistance)
            assertEquals(100, params.x)
            assertEquals(240, params.y)
            touch(view, downTime, downTime + 140, MotionEvent.ACTION_MOVE, 20f, 20f + swipeDistance + 20f)
            assertEquals(100, params.x)
            assertEquals(240, params.y)
            touch(view, downTime, downTime + 160, MotionEvent.ACTION_UP, 20f, 20f + swipeDistance + 20f)

            assertEquals(1, shadeRequests)
            assertEquals(0, taps)
            assertEquals(100, params.x)
            assertEquals(240, params.y)
            view.resetToIdle(park = false)
        }
    }

    @Test fun shortSwipeRecognizedOnReleaseKeepsPosition() {
        instrumentation.runOnMainSync {
            val view = BallView(instrumentation.targetContext)
            val params = WindowManager.LayoutParams().apply { x = 100; y = 240 }
            view.attachedParams = params
            var requests = 0
            var taps = 0
            view.onSwipeDown = { requests++; true }
            view.onTap = { taps++ }
            val density = view.resources.displayMetrics.density
            val start = SystemClock.uptimeMillis()
            touch(view, start, start, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, start, start + 60, MotionEvent.ACTION_MOVE, 20f, 20f + 16f * density)
            assertEquals(240, params.y)
            touch(view, start, start + 130, MotionEvent.ACTION_UP, 20f, 20f + 32f * density)
            assertEquals(1, requests)
            assertEquals(0, taps)
            assertEquals(100, params.x)
            assertEquals(240, params.y)
            // A subsequent tap must not be swallowed by the completed swipe.
            touch(view, start + 200, start + 200, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, start + 200, start + 240, MotionEvent.ACTION_UP, 20f, 20f)
            assertEquals(1, taps)
            view.resetToIdle(park = false)
        }
    }

    @Test fun movementWithoutLongPressDoesNotReposition() {
        instrumentation.runOnMainSync {
            val view = BallView(instrumentation.targetContext)
            val params = WindowManager.LayoutParams().apply { x = 100; y = 240 }
            view.attachedParams = params
            var shadeRequests = 0
            view.onSwipeDown = { shadeRequests++; true }
            val swipeDistance = 80f * view.resources.displayMetrics.density
            val downTime = SystemClock.uptimeMillis()

            touch(view, downTime, downTime, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, downTime, downTime + 700, MotionEvent.ACTION_MOVE, 20f, 20f + swipeDistance)

            assertEquals(0, shadeRequests)
            assertEquals(240, params.y)
            touch(view, downTime, downTime + 710, MotionEvent.ACTION_CANCEL, 20f, 20f + swipeDistance)
            view.resetToIdle(park = false)
        }
    }

    @Test fun unavailableShadeActionDoesNotMoveBallAndIsAttemptedOnce() {
        instrumentation.runOnMainSync {
            val view = BallView(instrumentation.targetContext)
            val params = WindowManager.LayoutParams().apply { x = 100; y = 240 }
            view.attachedParams = params
            var shadeRequests = 0
            view.onSwipeDown = { shadeRequests++; false }
            val swipeDistance = 80f * view.resources.displayMetrics.density
            val downTime = SystemClock.uptimeMillis()

            touch(view, downTime, downTime, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, downTime, downTime + 120, MotionEvent.ACTION_MOVE, 20f, 20f + swipeDistance)
            touch(view, downTime, downTime + 180, MotionEvent.ACTION_MOVE, 20f, 40f + swipeDistance)

            assertEquals(1, shadeRequests)
            assertEquals(240, params.y)
            touch(view, downTime, downTime + 200, MotionEvent.ACTION_CANCEL, 20f, 40f + swipeDistance)
            view.resetToIdle(park = false)
        }
    }

    @Test fun longPressEnablesDragAndStationaryDragArmsCapture() {
        lateinit var view: BallView
        lateinit var params: WindowManager.LayoutParams
        val armed = java.util.concurrent.CountDownLatch(1)
        val downTime = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            view = BallView(instrumentation.targetContext)
            params = WindowManager.LayoutParams().apply { x = 100; y = 240 }
            view.attachedParams = params
            view.setHost(object : BallView.Host {
                override fun onArm(originX: Int, originY: Int) { armed.countDown() }
                override fun onSelectDone(rectInScreen: android.graphics.Rect) = Unit
                override fun onCancel() = Unit
            })
            touch(view, downTime, downTime, MotionEvent.ACTION_DOWN, 20f, 20f)
        }
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100)
        assertEquals(1L, armed.count)
        instrumentation.runOnMainSync {
            val distance = 40f * view.resources.displayMetrics.density
            touch(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 20f + distance, 20f)
            assertEquals(100 + distance.toInt(), params.x)
            assertEquals(240, params.y)
        }
        assertTrue(armed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.runOnMainSync { view.resetToIdle(park = false) }
    }

    @Test fun carriedImageSwipeOpensShadeWithoutStartingImageDrag() {
        instrumentation.runOnMainSync {
            val view = BallView(instrumentation.targetContext)
            view.attachedParams = WindowManager.LayoutParams().apply { x = 100; y = 240 }
            view.setCarriedBitmap(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
            var shadeRequests = 0
            var imageDrags = 0
            view.onSwipeDown = { shadeRequests++; true }
            view.onCarryDrag = { imageDrags++; true }
            val swipeDistance = 80f * view.resources.displayMetrics.density
            val downTime = SystemClock.uptimeMillis()

            touch(view, downTime, downTime, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(view, downTime, downTime + 120, MotionEvent.ACTION_MOVE, 20f, 20f + swipeDistance)
            touch(view, downTime, downTime + 140, MotionEvent.ACTION_UP, 20f, 20f + swipeDistance)

            assertEquals(1, shadeRequests)
            assertEquals(0, imageDrags)
            view.setCarriedBitmap(null)
            view.resetToIdle(park = false)
        }
    }
}
