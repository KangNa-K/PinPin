package com.pinpin.app.ball

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SelectionGestureTest {
    @Test fun dimLayerCoversTopEdgeAndLeavesSelectionClear() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = overlay({})
            view.forceArm(100f, 120f)
            event(view, MotionEvent.ACTION_UP, 300f, 360f)
            val bitmap = Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                assertEquals(72, Color.alpha(bitmap.getPixel(5, 0)))
                assertEquals(bitmap.getPixel(5, 0), bitmap.getPixel(5, 500))
                assertEquals(0, Color.alpha(bitmap.getPixel(200, 240)))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun selectionWindowUsesDisplayCoordinatesForTopBarMask() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val view = SelectionOverlayView(context)
            val params = view.layoutParamsForScreen(1080, 2400,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
            assertEquals(0, params.fitInsetsTypes)
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                params.layoutInDisplayCutoutMode)
            assertEquals(1080, params.width)
            assertEquals(2400, params.height)
        }
    }

    @Test fun allDirectionsUseBallCenterOffsetAndFinalReleaseCoordinates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((x, y) in listOf(350f to 350f, 50f to 350f, 350f to 50f, 50f to 50f)) {
                var result: Rect? = null
                val view = overlay(confirm = { result = it })
                view.forceArm(210f, 220f, 200f, 200f)
                event(view, MotionEvent.ACTION_MOVE, 200f, 200f)
                event(view, MotionEvent.ACTION_UP, x, y)
                control(view, "钉图").performClick()
                assertEquals(Rect(minOf(210, (x + 10).toInt()), minOf(220, (y + 20).toInt()),
                    maxOf(210, (x + 10).toInt()), maxOf(220, (y + 20).toInt())), result)
            }
        }
    }

    @Test fun clampsToDisplayAndRejectsTinySelection() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var result: Rect? = null
            var cancelled = 0
            val view = overlay({ result = it }, { cancelled++ })
            view.forceArm(210f, 220f)
            event(view, MotionEvent.ACTION_UP, -100f, 1000f)
            control(view, "钉图").performClick()
            assertEquals(Rect(0, 220, 210, 800), result)
            result = null
            view.forceArm(210f, 220f)
            event(view, MotionEvent.ACTION_UP, 211f, 221f)
            assertNull(result)
            assertEquals(1, cancelled)
        }
    }

    @Test fun secondPointerAndSystemCancelNeverSubmit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (action in listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL)) {
                var result: Rect? = null
                var cancelled = 0
                val view = overlay({ result = it }, { cancelled++ })
                view.forceArm(210f, 220f)
                event(view, MotionEvent.ACTION_MOVE, 400f, 500f)
                event(view, action, 400f, 500f)
                event(view, MotionEvent.ACTION_UP, 400f, 500f)
                assertNull(result)
                assertEquals(1, cancelled)
            }
        }
    }

    @Test fun fallbackSelectionNeverRetriesAutomaticCaptureWhileFingerIsHeld() {
        val captured = java.util.concurrent.atomic.AtomicInteger()
        lateinit var view: SelectionOverlayView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = overlay({})
            view.autoCaptureEnabled = false
            view.listener = object : SelectionOverlayView.Listener {
                override fun onConfirm(rect: Rect) = Unit
                override fun onCancel() = Unit
                override fun onAutoCapture(x: Int, y: Int) { captured.incrementAndGet() }
            }
            view.forceArm(100f, 100f)
            event(view, MotionEvent.ACTION_DOWN, 150f, 180f)
        }
        Thread.sleep(600)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals(0, captured.get())
            event(view, MotionEvent.ACTION_MOVE, 300f, 360f)
            event(view, MotionEvent.ACTION_UP, 300f, 360f)
            assertEquals(View.VISIBLE, control(view, "钉图").visibility)
        }
    }

    @Test fun fallbackSelectionRemainsManuallySelectable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var automaticCaptures = 0
            var selected: Rect? = null
            val view = overlay({ selected = it })
            view.autoCaptureEnabled = false
            view.listener = object : SelectionOverlayView.Listener {
                override fun onConfirm(rect: Rect) { selected = rect }
                override fun onCancel() = Unit
                override fun onAutoCapture(x: Int, y: Int) { automaticCaptures++ }
            }
            view.forceArm(100f, 100f)
            event(view, MotionEvent.ACTION_DOWN, 150f, 180f)
            event(view, MotionEvent.ACTION_MOVE, 300f, 360f)
            event(view, MotionEvent.ACTION_UP, 300f, 360f)
            assertEquals(View.VISIBLE, control(view, "钉图").visibility)
            control(view, "钉图").performClick()
            assertEquals(Rect(100, 100, 300, 360), selected)
            assertEquals(0, automaticCaptures)
        }
    }

    @Test fun precisionModeShowsInlineToolbarWithoutExtraConfirmation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var confirmed: Rect? = null
            var cancelled = 0
            val view = overlay({ confirmed = it }, { cancelled++ })
            view.precisionEnabled = true
            view.forceArm(100f, 120f)
            event(view, MotionEvent.ACTION_UP, 300f, 360f)
            assertNull(confirmed)
            assertEquals(0, cancelled)

            event(view, MotionEvent.ACTION_DOWN, 300f, 360f)
            event(view, MotionEvent.ACTION_MOVE, 320f, 380f)
            event(view, MotionEvent.ACTION_UP, 320f, 380f)
            assertNull(confirmed)
            assertEquals(0, cancelled)
            assertEquals(View.VISIBLE, control(view, "钉图").visibility)
            control(view, "钉图").performClick()
            assertEquals(Rect(100, 120, 320, 380), confirmed)
        }
    }

    @Test fun reverseSelectionCanMoveOnePixelAndCancelledDragRollsBack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var confirmed: Rect? = null
            val view = overlay({ confirmed = it })
            view.precisionEnabled = true
            view.forceArm(300f, 400f)
            event(view, MotionEvent.ACTION_UP, 100f, 100f)
            event(view, MotionEvent.ACTION_DOWN, 200f, 250f)
            event(view, MotionEvent.ACTION_MOVE, 201f, 250f)
            event(view, MotionEvent.ACTION_UP, 201f, 250f)
            event(view, MotionEvent.ACTION_DOWN, 101f, 100f)
            event(view, MotionEvent.ACTION_MOVE, 140f, 160f)
            event(view, MotionEvent.ACTION_CANCEL, 140f, 160f)
            event(view, MotionEvent.ACTION_UP, 140f, 160f)
            control(view, "钉图").performClick()
            assertEquals(Rect(101, 100, 301, 400), confirmed)
        }
    }

    @Test fun outsideTapCancelsAndFractionalMovesAccumulate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var confirmed: Rect? = null
            var cancellations = 0
            val view = overlay({ confirmed = it }, { cancellations++ })
            view.precisionEnabled = true
            view.forceArm(100f, 100f)
            event(view, MotionEvent.ACTION_UP, 300f, 400f)
            event(view, MotionEvent.ACTION_DOWN, 300f, 400f)
            for (i in 1..10) event(view, MotionEvent.ACTION_MOVE, 300f + i * 0.2f, 400f)
            event(view, MotionEvent.ACTION_UP, 302f, 400f)
            control(view, "钉图").performClick()
            assertEquals(Rect(100, 100, 302, 400), confirmed)
            confirmed = null
            view.forceArm(100f, 100f)
            event(view, MotionEvent.ACTION_UP, 300f, 400f)
            event(view, MotionEvent.ACTION_DOWN, 600f, 600f)
            assertNull(confirmed)
            assertEquals(1, cancellations)
        }
    }

    private fun control(view: View, description: String): View {
        if (view.contentDescription == description && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            runCatching { control(view.getChildAt(i), description) }.getOrNull()?.let { return it }
        }
        error("Missing control: $description")
    }

    @Test fun eachActionDispatchesTheSelectedRectAndAction() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val observed = mutableListOf<Pair<Rect, SelectionOverlayView.Action>>()
            val view = overlay({})
            view.listener = object : SelectionOverlayView.Listener {
                override fun onConfirm(rect: Rect) = error("Action must not use confirm")
                override fun onAction(rect: Rect, action: SelectionOverlayView.Action) {
                    observed += rect to action
                }
                override fun onCancel() = error("Action must not cancel")
            }
            val expectedRect = Rect(100, 120, 300, 360)
            for (action in SelectionOverlayView.Action.entries) {
                view.forceArm(100f, 120f)
                event(view, MotionEvent.ACTION_UP, 300f, 360f)
                control(view, when (action) {
                    SelectionOverlayView.Action.ANNOTATE -> "标注"
                    SelectionOverlayView.Action.PIN -> "钉图"
                    SelectionOverlayView.Action.COPY -> "复制"
                    SelectionOverlayView.Action.SHARE -> "分享"
                    SelectionOverlayView.Action.SAVE -> "保存到相册"
                    SelectionOverlayView.Action.CARRY -> "吸入悬浮球"
                }).performClick()
            }
            assertEquals(SelectionOverlayView.Action.entries.map { expectedRect to it }, observed)
        }
    }

    private fun overlay(confirm: (Rect) -> Unit, cancel: () -> Unit = {}): SelectionOverlayView {
            val view = SelectionOverlayView(ApplicationProvider.getApplicationContext<Context>())
        view.layoutParams = view.layoutParamsForScreen(500, 800)
        view.layout(0, 0, 500, 800)
        view.listener = object : SelectionOverlayView.Listener {
            override fun onConfirm(rect: Rect) = confirm(rect)
            override fun onCancel() = cancel()
        }
        return view
    }

    private fun event(view: SelectionOverlayView, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 20, action, x, y, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
