package com.pinpin.app.ball

import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import com.pinpin.app.a11y.PinAccessibilityService
import com.pinpin.app.capture.CaptureProtocol
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the production service, real window attachment and AccessibilityService.takeScreenshot. */
class AutoCarryServiceTest {
    @Test fun holdingBallAutomaticallyCapturesAndRecognizesArtwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(cmd: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd))
            .bufferedReader().use { it.readText().trim() }
        fun awaitCondition(message: String, test: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline) { if (test()) return; SystemClock.sleep(40) }
            fail(message)
        }
        val oldServices = shell("settings get secure enabled_accessibility_services")
        val oldEnabled = shell("settings get secure accessibility_enabled")
        val oldOverlayOp = shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW")
            .substringAfterLast(": ").takeIf { it in setOf("allow", "deny", "default", "ignore") } ?: "default"
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val firstResult = CountDownLatch(1)
        var error: String? = null
        var success = false
        var capturedBounds: Rect? = null
        var background: View? = null
        var ball: BallView? = null
        var downTime = 0L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                error = intent.getStringExtra(CaptureProtocol.ERROR)
                success = intent.getBooleanExtra(CaptureProtocol.SUCCESS, false)
                capturedBounds = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(CaptureProtocol.BOUNDS, Rect::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(CaptureProtocol.BOUNDS)
                }
                firstResult.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(CaptureProtocol.RESULT), Context.RECEIVER_NOT_EXPORTED)
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            val service = "${context.packageName}/${PinAccessibilityService::class.java.name}"
            shell("settings put secure enabled_accessibility_services $service")
            shell("settings put secure accessibility_enabled 1")
            awaitCondition("Screenshot accessibility service did not connect") { PinAccessibilityService.current() != null }
            instrumentation.runOnMainSync {
                background = object : View(context) {
                    private val paint = Paint()
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.rgb(238, 238, 238))
                        // Feed-like flat illustration with a rectangular border and a small label.
                        val left = width * .15f
                        val top = height * .15f
                        val artWidth = width * .70f
                        val artHeight = height * .66f
                        // Stronger neighboring UI edges must not expand the image crop.
                        paint.color = Color.BLACK
                        canvas.drawRect(left, height * .03f, left + artWidth, height * .10f, paint)
                        canvas.drawRect(left, height * .87f, left + artWidth, height * .95f, paint)
                        paint.color = Color.rgb(80, 120, 170)
                        canvas.drawRect(left, top, left + artWidth, top + artHeight, paint)
                        paint.color = Color.rgb(225, 175, 125)
                        canvas.drawOval(left + artWidth * .3f, top + artHeight * .2f,
                            left + artWidth * .7f, top + artHeight * .8f, paint)
                        paint.color = Color.WHITE
                        canvas.drawRect(left + 28, top + artHeight * .47f, left + 132, top + artHeight * .50f, paint)
                    }
                }.also {
                    manager.addView(it, WindowManager.LayoutParams(-1, -1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.OPAQUE).apply { gravity = Gravity.TOP or Gravity.START })
                }
                BallService.start(context)
            }
            awaitCondition("Production floating ball did not attach") {
                instrumentation.runOnMainSync { ball = WindowInspector.getGlobalWindowViews().filterIsInstance<BallView>().singleOrNull() }
                ball != null
            }
            instrumentation.runOnMainSync {
                val view = checkNotNull(ball)
                val bounds = manager.maximumWindowMetrics.bounds
                view.attachedParams!!.apply { x = bounds.width() / 2 - view.width / 2; y = bounds.height() / 2 - view.height / 2 }
                manager.updateViewLayout(view, view.attachedParams)
            }
            SystemClock.sleep(500)
            val position = IntArray(2)
            instrumentation.runOnMainSync { ball!!.getLocationOnScreen(position) }
            downTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                position[0] + ball!!.width / 2f, position[1] + ball!!.height / 2f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            assertTrue("Holding the ball did not request an automatic screenshot", firstResult.await(12, TimeUnit.SECONDS))
            assertTrue("Automatic screenshot failed: $error", success)
            assertNotNull("Automatic recognition did not return crop bounds", capturedBounds)
            val display = manager.maximumWindowMetrics.bounds
            assertTrue("Recognition returned a full-screen crop: $capturedBounds",
                checkNotNull(capturedBounds).width() < display.width())
            awaitCondition("Recognized artwork was not loaded into the floating ball") {
                var loaded = false
                instrumentation.runOnMainSync { loaded = ball!!.carryingImage }
                loaded
            }
            instrumentation.runOnMainSync {
                assertFalse("Automatic recognition incorrectly opened manual fallback",
                    WindowInspector.getGlobalWindowViews().any { it is SelectionOverlayView })
            }
        } finally {
            if (downTime != 0L) {
                val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 500f, 500f, 0)
                try { instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(up, true) } finally { up.recycle() }
            }
            instrumentation.runOnMainSync {
                BallService.stop(context)
                background?.let { manager.removeViewImmediate(it) }
            }
            context.unregisterReceiver(receiver)
            shell(if (oldServices == "null") "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $oldServices")
            shell(if (oldEnabled == "null") "settings delete secure accessibility_enabled" else "settings put secure accessibility_enabled $oldEnabled")
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $oldOverlayOp")
        }
    }
}
