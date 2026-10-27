package com.pinpin.app.ball

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BallCarryWindowTest {
    @Test fun acceptedDropClearsImageAndKeepsExportReadable() = exerciseDrag(false, true)
    @Test fun rejectedDropKeepsImage() = exerciseDrag(false, false)
    @Test fun heldCaptureContinuesNativeDragWithoutAnotherDown() = exerciseDrag(true, true)
    @Test fun cancelledContinuousDragKeepsImage() = exerciseDrag(true, false, cancel = true)

    @Test fun threeConsecutiveHoldsCaptureDropAndRearm() = exerciseDrag(true, true, cycles = 3)
    @Test fun threeRejectedDropsStillAllowRecaptureWithoutRestart() = exerciseDrag(true, false, cycles = 3)

    /** Opt-in: requires Magpie's PinPinDropReceiptDeviceTest running in the foreground. */
    @Test fun externalMagpieDropClearsThreeConsecutiveImages() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("externalMagpie") == "true")
        exerciseDrag(true, true, cycles = 3, external = true)
    }

    private fun exerciseDrag(continuous: Boolean, accept: Boolean, cancel: Boolean = false, cycles: Int = 1, external: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var received = CountDownLatch(1)
        var ended = CountDownLatch(1)
        var readable = false
        var exportedUri: android.net.Uri? = null
        val file = File(context.cacheDir, "carry-test-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        lateinit var ball: BallView
        lateinit var target: View
        lateinit var controller: BallCarryController
        val preferencesName = "carry-test-${UUID.randomUUID()}"
        val filesBefore = File(context.filesDir, "carried_images").listFiles()?.toSet().orEmpty()
        var controllerCreated = false
        var targetAdded = false
        var ballAdded = false
        try {
            instrumentation.runOnMainSync {
                target = View(context).apply {
                    setBackgroundColor(Color.DKGRAY)
                    setOnDragListener { _, event ->
                        when (event.action) {
                            DragEvent.ACTION_DRAG_STARTED -> true
                            DragEvent.ACTION_DROP -> {
                                exportedUri = event.clipData?.getItemAt(0)?.uri
                                readable = runCatching {
                                    context.contentResolver.openInputStream(checkNotNull(exportedUri))!!.use { it.read() >= 0 }
                                }.getOrDefault(false)
                                received.countDown()
                                readable && accept
                            }
                            else -> true
                        }
                    }
                }
                val bounds = manager.maximumWindowMetrics.bounds
                if (!external) manager.addView(target, WindowManager.LayoutParams(bounds.width(), bounds.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START })
                targetAdded = !external
                ball = BallView(context)
                val lp = WindowManager.LayoutParams(160, 160, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.START; x = 70; y = 150
                }
                ball.attachedParams = lp
                ball.attachedDisplayWidth = bounds.width()
                ball.attachedDisplayHeight = bounds.height()
                manager.addView(ball, lp)
                ballAdded = true
                controller = BallCarryController(context, ball, manager, { if (it.contains("目标应用已接收") || it.contains("未接收")) ended.countDown() }, {}, preferencesName)
                controllerCreated = true
                if (!continuous) controller.accept(file, null)
            }
            repeat(cycles) { round ->
            received = CountDownLatch(1)
            ended = CountDownLatch(1)
            val deadline = SystemClock.uptimeMillis() + 5000
            var loaded = continuous
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { loaded = ball.carryingImage }
                SystemClock.sleep(50)
            }
            assertTrue("Carried image did not load", loaded)
            SystemClock.sleep(500)
            val location = IntArray(2)
            instrumentation.runOnMainSync { ball.getLocationOnScreen(location) }
            val x = location[0] + ball.width / 2f
            val y = location[1] + ball.height / 2f
            val down = SystemClock.uptimeMillis()
            fun inject(action: Int, px: Float, py: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            val capturedAgain = CountDownLatch(1)
            if (cycles > 1) {
                instrumentation.runOnMainSync {
                    ball.setHost(object : BallView.Host {
                        override fun onArm(originX: Int, originY: Int) {
                            val selection = SelectionOverlayView(context).apply {
                                autoCaptureEnabled = true
                                layoutParamsForScreen(1080, 2400)
                                listener = object : SelectionOverlayView.Listener {
                                    override fun onConfirm(rect: android.graphics.Rect) = Unit
                                    override fun onCancel() = Unit
                                    override fun onAutoCapture(x: Int, y: Int) {
                                        ball.beginCarryCaptureGesture()
                                        controller.accept(file, null)
                                        capturedAgain.countDown()
                                    }
                                }
                            }
                            ball.enterSelecting()
                            selection.forceArm(originX.toFloat(), originY.toFloat(), ball.selectionTouchX, ball.selectionTouchY)
                        }
                        override fun onSelectDone(rectInScreen: android.graphics.Rect) = Unit
                        override fun onCancel() = Unit
                    })
                }
            }
            inject(MotionEvent.ACTION_DOWN, x, y)
            if (cycles > 1) {
                assertTrue("Round ${round + 1}: automatic capture never fired", capturedAgain.await(3, TimeUnit.SECONDS))
                val readyDeadline = SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < readyDeadline) {
                    instrumentation.runOnMainSync { ready = ball.carryingImage && !controller.isBusy }
                    SystemClock.sleep(30)
                }
                assertTrue("Round ${round + 1}: stationary hold did not capture again", ready)
                inject(MotionEvent.ACTION_MOVE, x + 40, y + 40)
            } else if (continuous) {
                instrumentation.runOnMainSync {
                    ball.beginCarryCaptureGesture()
                    ball.alpha = 0f // Production screenshot hiding must not revoke the touch stream.
                }
                inject(MotionEvent.ACTION_MOVE, x + 40, y + 40)
                instrumentation.runOnMainSync { controller.accept(file, null) }
                val readyDeadline = SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < readyDeadline) {
                    instrumentation.runOnMainSync { ready = ball.carryingImage }
                    SystemClock.sleep(30)
                }
                assertTrue("Capture did not hand off to the existing pointer", ready)
            } else inject(MotionEvent.ACTION_MOVE, x + 40, y + 40)
            SystemClock.sleep(200)
            inject(MotionEvent.ACTION_MOVE, x + 200, y + 350)
            SystemClock.sleep(200)
            inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x + 200, y + 350)
            if (!cancel && !external) {
                assertTrue("Receiver did not get native drop", received.await(4, TimeUnit.SECONDS))
                assertTrue("Exported image not readable", readable)
                assertEquals("content", exportedUri?.scheme)
            }
            assertTrue("Sender did not get drag completion", ended.await(4, TimeUnit.SECONDS))
            SystemClock.sleep(350)
            instrumentation.runOnMainSync {
                assertEquals(!accept || cancel, ball.carryingImage)
                assertEquals(!accept || cancel, controller.hasImage)
                assertEquals(!accept || cancel, context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).contains("file"))
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                val lp = checkNotNull(ball.attachedParams)
                if (accept && !cancel) {
                    assertTrue("Accepted drop did not park the ball: x=${lp.x}",
                        lp.x == margin || lp.x == ball.attachedDisplayWidth - ball.width - margin)
                } else {
                    assertEquals("Rejected drag moved the ball unexpectedly", 70, lp.x)
                }
            }
            if (!cancel && !external) assertTrue("Clearing the ball revoked the receiver export", context.contentResolver.openInputStream(checkNotNull(exportedUri))!!.use { it.read() >= 0 })
            }
            // Finishing instrumentation force-stops this package and revokes its URI grants.
            // Production keeps the foreground service alive while Magpie copies asynchronously.
            if (external) SystemClock.sleep(3000)
        } finally {
            instrumentation.runOnMainSync {
                if (controllerCreated) controller.close()
                if (ballAdded) manager.removeViewImmediate(ball)
                if (targetAdded) manager.removeViewImmediate(target)
            }
            file.delete()
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
            File(context.filesDir, "carried_images").listFiles()?.filter { it !in filesBefore }?.forEach { it.delete() }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
