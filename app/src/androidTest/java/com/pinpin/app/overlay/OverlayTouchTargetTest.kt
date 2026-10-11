package com.pinpin.app.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pinpin.app.ball.BallView
import org.junit.Assert.*
import org.junit.Test

class OverlayTouchTargetTest {
    @Test fun toolbarButtonsStayInsideNarrowWindowAndCornersDoNotJump() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val density = context.resources.displayMetrics.density
            val width = (200 * density).toInt()
            val height = (180 * density).toInt()
            val view = PinWindowView(
                context, "geometry-test",
                PinWindowView.PinSpec(
                    label = "test", imageUri = null, sourceType = "test",
                    initialX = 70, initialY = 90, widthPx = width, heightPx = height,
                    locked = false, zOrder = 1,
                ),
                onPositionChanged = { _, _ -> }, onSizeChanged = { _, _ -> },
                onCloseClicked = {}, onActionClicked = {},
            )
            fun layout() {
                val params = view.layoutParams as WindowManager.LayoutParams
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, params.width, params.height)
            }
            view.showToolbar(true)
            layout()
            val imageControls = descendants(view).filterIsInstance<ImageButton>()
            assertEquals(4, imageControls.size)
            val buttons = descendants(view).filter {
                it is android.widget.LinearLayout && it.isClickable && it.isFocusable &&
                    it.childCount == 1 && it.getChildAt(0) is android.widget.ImageView
            }
            assertEquals(7, buttons.size)
            for (button in buttons) {
                val rect = android.graphics.Rect()
                button.getDrawingRect(rect)
                view.offsetDescendantRectToMyCoords(button, rect)
                assertTrue("Button outside left edge", rect.left >= 0)
                assertTrue("Button outside right edge", rect.right <= view.width)
                assertTrue("Button below window", rect.bottom <= view.height)
            }
            for (imageWidth in listOf((460 * density).toInt(), (100 * density).toInt(), width)) {
                view.updateSizeFromExternal(imageWidth, height)
                layout()
                val viewport = descendants(view).filterIsInstance<HorizontalScrollView>().single()
                assertTrue(
                    "Toolbar must be centered beneath the image",
                    kotlin.math.abs(viewport.left - (view.width - viewport.right)) <= 1,
                )
                val bar = viewport.getChildAt(0)
                assertEquals(viewport.width, bar.width)
                val rects = imageControls.map { button ->
                    android.graphics.Rect().also {
                        button.getDrawingRect(it)
                        view.offsetDescendantRectToMyCoords(button, it)
                        assertTrue(it.left >= 0)
                        assertTrue(it.right <= imageWidth)
                        assertTrue(it.top >= 0)
                        assertTrue(it.bottom <= height)
                        assertEquals((40 * density).toInt(), it.width())
                    }
                }
                for (i in rects.indices) for (j in 0 until i) {
                    assertFalse("Image buttons overlap", android.graphics.Rect.intersects(rects[i], rects[j]))
                }
            }
            view.showToolbar(false)
            layout()
            val inset = (16 * density).toInt().toFloat()
            for ((x, y) in listOf(
                inset to inset, width - inset to inset,
                inset to height - inset, width - inset to height - inset,
            )) {
                fun event(action: Int, dx: Float = 0f, dy: Float = 0f) {
                    val event = MotionEvent.obtain(0, 20, action, x + dx, y + dy, 0)
                    try { view.onTouchEvent(event) } finally { event.recycle() }
                }
                event(MotionEvent.ACTION_DOWN)
                event(MotionEvent.ACTION_MOVE, 1f, 1f)
                assertEquals(70, view.currentX)
                assertEquals(90, view.currentY)
                assertEquals(width, view.currentW)
                assertEquals(height, view.currentH)
                event(MotionEvent.ACTION_MOVE, 60 * density, 60 * density)
                if (x < width / 2) {
                    assertEquals(70 + width, view.currentX + view.currentW)
                } else {
                    assertEquals(70, view.currentX)
                }
                if (y < height / 2) {
                    assertEquals(90 + height, view.currentY + view.currentH)
                } else {
                    assertEquals(90, view.currentY)
                }
                event(MotionEvent.ACTION_CANCEL)
                assertEquals(width, view.currentW)
                assertEquals(height, view.currentH)
                layout()
            }
        }
    }

    @Test fun hiddenControlsCannotReceiveTouchesAndWindowFitsImage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val actions = mutableListOf<String>()
            var closed = false
            val view = PinWindowView(
                context, "touch-test",
                PinWindowView.PinSpec(
                    label = "test", imageUri = null, sourceType = "test",
                    initialX = 0, initialY = 0, widthPx = 200, heightPx = 300,
                    locked = false, zOrder = 1,
                ),
                onPositionChanged = { _, _ -> }, onSizeChanged = { _, _ -> },
                onCloseClicked = { closed = true }, onActionClicked = { actions.add(it) },
            )
            val controls = descendants(view).filterIsInstance<ImageButton>()
            assertEquals(4, controls.size)
            val viewport = descendants(view).filterIsInstance<HorizontalScrollView>().single()
            val params = view.layoutParams as WindowManager.LayoutParams
            assertEquals(200, params.width)
            assertEquals(300, params.height)
            assertTrue(controls.all { it.visibility == View.INVISIBLE && !it.isEnabled })
            assertEquals(View.INVISIBLE, viewport.visibility)

            view.showToolbar(true)
            assertTrue(controls.all { it.isEnabled })
            controls.forEach { it.performClick() }
            assertTrue(closed)
            assertEquals(listOf("passthrough", "lock", "minimize"), actions)
            assertEquals(View.VISIBLE, viewport.visibility)
            assertEquals(200, params.width)
            assertTrue(params.height > 300)
            view.showToolbar(false)
            assertEquals(300, params.height)
            assertTrue(controls.all { !it.isEnabled })
            assertEquals(View.INVISIBLE, viewport.visibility)

            view.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 200, 300)
            val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 210f, 100f, 0)
            try { assertFalse(view.onTouchEvent(outside)) } finally { outside.recycle() }
            assertFalse(view.isToolbarShown())
        }
    }

    @Test fun ballHasOnlySmallEdgeTolerance() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val ball = BallView(context)
            val density = context.resources.displayMetrics.density
            assertEquals((56f * density).toInt() + (4f * density).toInt(), ball.minimumWidth)
            assertEquals(ball.minimumWidth, ball.minimumHeight)
        }
    }

    private fun descendants(parent: ViewGroup): List<View> = buildList {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            add(child)
            if (child is ViewGroup) addAll(descendants(child))
        }
    }
}
