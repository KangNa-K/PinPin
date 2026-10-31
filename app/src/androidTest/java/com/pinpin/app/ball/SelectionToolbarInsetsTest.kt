package com.pinpin.app.ball

import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The selection overlay is full screen and draws under the system bars, so the
 * action toolbar must keep itself inside the safe area.
 */
class SelectionToolbarInsetsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Before fun allowOverlay() = shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")

    @After fun resetOverlay() = shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")

    @Test fun fullScreenSelectionKeepsToolbarBelowStatusBar() = assertToolbarInSafeArea { w, h, _ ->
        Rect(0, 0, w, h)
    }

    @Test fun selectionNearBottomKeepsToolbarAboveNavigationBar() = assertToolbarInSafeArea { w, h, _ ->
        // Toolbar (40 dp) plus margin (12 dp) still fits the window below the
        // selection, but only by overlapping the navigation bar.
        val density = context.resources.displayMetrics.density
        Rect(w / 4, h / 3, w * 3 / 4, h - (52 * density).toInt())
    }

    @Test fun selectionNearTopKeepsToolbarBelowStatusBar() = assertToolbarInSafeArea { w, h, safe ->
        // No room below: the toolbar flips above and must not enter the status bar.
        Rect(w / 4, safe.top + 10, w * 3 / 4, h)
    }

    private fun assertToolbarInSafeArea(selection: (Int, Int, Insets) -> Rect) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        lateinit var view: SelectionOverlayView
        instrumentation.runOnMainSync {
            view = SelectionOverlayView(context)
            manager.addView(view, view.layoutParamsForScreen(metrics.widthPixels, metrics.heightPixels))
        }
        try {
            instrumentation.waitForIdleSync()
            lateinit var safe: Insets
            instrumentation.runOnMainSync {
                safe = view.rootWindowInsets
                    .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            }
            assumeTrue("no system bar insets on this device", safe.top > 0 || safe.bottom > 0)
            val target = selection(metrics.widthPixels, metrics.heightPixels, safe)
            instrumentation.runOnMainSync {
                view.forceArm(target.left.toFloat(), target.top.toFloat())
                touch(view, MotionEvent.ACTION_UP, target.right.toFloat(), target.bottom.toFloat())
            }
            instrumentation.waitForIdleSync()
            val location = IntArray(2)
            var toolbarHeight = 0
            instrumentation.runOnMainSync {
                val bar = control(view, "钉图").parent as View
                bar.getLocationOnScreen(location)
                toolbarHeight = bar.height
            }
            val message = "selection=$target toolbarTop=${location[1]} height=$toolbarHeight safe=$safe " +
                "screen=${metrics.widthPixels}x${metrics.heightPixels}"
            assertTrue(message, location[1] >= safe.top)
            assertTrue(message, location[1] + toolbarHeight <= metrics.heightPixels - safe.bottom)
        } finally {
            instrumentation.runOnMainSync { manager.removeViewImmediate(view) }
        }
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 20, action, x, y, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun control(view: View, description: String): View {
        if (view.contentDescription == description && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            runCatching { control(view.getChildAt(i), description) }.getOrNull()?.let { return it }
        }
        error("Missing control: $description")
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
}
