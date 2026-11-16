package com.pinpin.app.ball

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BallWindowGestureTest {
    @Test fun earlyDragCanPauseAndContinueSelectionAfterOverlayIsAdded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val armed = CountDownLatch(1)
        val confirmed = CountDownLatch(1)
        var result: Rect? = null
        var cancelled = false
        lateinit var ball: BallView
        lateinit var overlay: SelectionOverlayView
        var overlayAdded = false
        var ballAdded = false
        val location = IntArray(2)
        try {
            instrumentation.runOnMainSync {
                ball = BallView(context)
                overlay = SelectionOverlayView(context)
                overlay.listener = object : SelectionOverlayView.Listener {
                    override fun onConfirm(rect: Rect) { result = rect; confirmed.countDown() }
                    override fun onCancel() { cancelled = true; confirmed.countDown() }
                }
                ball.setHost(object : BallView.Host {
                    override fun onArm(originX: Int, originY: Int) {
                        val bounds = manager.maximumWindowMetrics.bounds
                        manager.addView(overlay, overlay.layoutParamsForScreen(bounds.width(), bounds.height()))
                        overlayAdded = true
                        overlay.forceArm(originX.toFloat(), originY.toFloat(), ball.selectionTouchX, ball.selectionTouchY)
                        ball.enterSelecting()
                        ball.alpha = 0f
                        armed.countDown()
                    }
                    override fun onSelectDone(rectInScreen: Rect) = Unit
                    override fun onCancel() { cancelled = true; confirmed.countDown() }
                })
                ball.setArmRedirect { overlay.onTouchEvent(it) }
                val params = WindowManager.LayoutParams(ball.minimumWidth, ball.minimumHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 200
                    y = 400
                }
                ball.attachedParams = params
                manager.addView(ball, params)
                ballAdded = true
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { ball.getLocationOnScreen(location) }
            val x = location[0] + ball.width / 2f
            val y = location[1] + ball.height / 2f
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int, px: Float, py: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, px, py, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN, x, y)
            inject(MotionEvent.ACTION_MOVE, x - 100f, y + 100f)
            SystemClock.sleep(600)
            assertTrue("Ball did not arm", armed.await(3, TimeUnit.SECONDS))
            inject(MotionEvent.ACTION_MOVE, x + 200f, y + 300f)
            inject(MotionEvent.ACTION_UP, x + 220f, y + 320f)
            instrumentation.runOnMainSync {
                var pin: android.view.View? = null
                fun find(view: android.view.View) {
                    if (view.contentDescription == "钉图") pin = view
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) find(view.getChildAt(i))
                    }
                }
                find(overlay)
                pin?.performClick()
            }
            assertTrue("Selection did not receive release", confirmed.await(3, TimeUnit.SECONDS))
            assertFalse("Adding overlay cancelled the original input stream", cancelled)
            val actual = requireNotNull(result)
            val expected = Rect((x - 100).toInt(), (y + 100).toInt(), (x + 220).toInt(), (y + 320).toInt())
            assertTrue("Expected $expected but was $actual", listOf(
                actual.left - expected.left, actual.top - expected.top,
                actual.right - expected.right, actual.bottom - expected.bottom
            ).all { kotlin.math.abs(it) <= 2 })
        } finally {
            instrumentation.runOnMainSync {
                if (overlayAdded) manager.removeViewImmediate(overlay)
                if (ballAdded) manager.removeViewImmediate(ball)
            }
        }
    }
}
