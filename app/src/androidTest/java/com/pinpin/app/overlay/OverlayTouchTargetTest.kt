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
    @Test fun onlyGesturesStartingOnImageBodyCanMoveOrSelectPin() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val density = context.resources.displayMetrics.density
            val width = (200 * density).toInt()
            val height = (180 * density).toInt()
            var commits = 0
            val view = PinWindowView(
                context, "image-gesture-bounds",
                PinWindowView.PinSpec(
                    label = "test", imageUri = null, sourceType = "test",
                    initialX = 70, initialY = 90, widthPx = width, heightPx = height,
                    locked = false, zOrder = 1,
                ),
                onPositionChanged = { _, _ -> commits++ }, onSizeChanged = { _, _ -> },
                onCloseClicked = {}, onActionClicked = {},
            )
            view.showToolbar(true)
            // Keep controls stationary so this test targets hit testing,
            // not the toolbar's entrance animation.
            descendants(view).filterIsInstance<HorizontalScrollView>().single().apply {
                animate().cancel()
                translationY = 0f
            }
            val params = view.layoutParams as WindowManager.LayoutParams
            view.measure(
                View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, params.width, params.height)
            val image = view.getChildAt(0)
            val bodyX = (image.left + image.right) / 2f
            val bodyY = (image.top + image.bottom) / 2f
            fun event(action: Int, x: Float, y: Float): Boolean {
                val event = MotionEvent.obtain(0, 20, action, x, y, 0)
                return try { view.onTouchEvent(event) } finally { event.recycle() }
            }

            // Establish stale drag coordinates, then ensure no later
            // MOVE/UP can use them without an accepted image DOWN.
            assertTrue(event(MotionEvent.ACTION_DOWN, bodyX, bodyY))
            assertTrue(event(MotionEvent.ACTION_MOVE, bodyX + 40 * density, bodyY))
            assertTrue(event(MotionEvent.ACTION_UP, bodyX + 40 * density, bodyY))
            assertEquals(1, commits)
            val x = view.currentX
            val y = view.currentY
            val viewport = descendants(view).filterIsInstance<HorizontalScrollView>().single()
            val rejectedStarts = mutableListOf(
                image.left - 1f to bodyY,
                image.right.toFloat() to bodyY,
                bodyX to image.top - 1f,
                bodyX to image.bottom.toFloat(),
                viewport.left + 1f to viewport.top + 1f,
            )
            descendants(view).filterIsInstance<ImageButton>().forEach { button ->
                val rect = android.graphics.Rect()
                button.getDrawingRect(rect)
                view.offsetDescendantRectToMyCoords(button, rect)
                rejectedStarts.add(rect.exactCenterX() to rect.exactCenterY())
            }
            for ((startX, startY) in rejectedStarts) {
                assertFalse(event(MotionEvent.ACTION_DOWN, startX, startY))
                assertFalse(event(MotionEvent.ACTION_MOVE, bodyX + 80 * density, bodyY))
                assertFalse(event(MotionEvent.ACTION_UP, bodyX, bodyY))
                assertFalse(event(MotionEvent.ACTION_CANCEL, bodyX, bodyY))
                assertEquals(x, view.currentX)
                assertEquals(y, view.currentY)
                assertEquals(width, view.currentW)
                assertEquals(height, view.currentH)
                assertEquals(1, commits)
                assertTrue(view.isToolbarShown())
            }
            assertFalse(event(MotionEvent.ACTION_MOVE, bodyX, bodyY))
            assertFalse(event(MotionEvent.ACTION_UP, bodyX, bodyY))
        }
    }

    @Test fun toolbarScrollsInsideImageWindowAndCornersDoNotJump() {
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
            assertEquals("Input window must not extend beyond the image", width, view.width)
            assertEquals("Toolbar viewport must stay inside the image window", width, viewportWidth(view))
            val imageControls = descendants(view).filterIsInstance<ImageButton>()
            assertEquals(4, imageControls.size)
            val buttons = descendants(view).filter {
                it is android.widget.LinearLayout && it.isClickable && it.isFocusable &&
                    it.childCount == 1 && it.getChildAt(0) is android.widget.ImageView
            }
            assertEquals(7, buttons.size)
            for (imageWidth in listOf((460 * density).toInt(), (100 * density).toInt(), width)) {
                view.updateSizeFromExternal(imageWidth, height)
                layout()
                val viewport = descendants(view).filterIsInstance<HorizontalScrollView>().single()
                assertEquals("Input window must follow the resized image", imageWidth, view.width)
                assertEquals(
                    "Toolbar viewport must stay within the image and content widths",
                    minOf(imageWidth, 7 * (40 * density).toInt()),
                    viewport.width,
                )
                assertTrue(
                    "Toolbar must be centered beneath the image",
                    kotlin.math.abs(viewport.left - (view.width - viewport.right)) <= 1,
                )
                val bar = viewport.getChildAt(0)
                assertEquals(
                    "Toolbar content must retain all action widths",
                    7 * (40 * density).toInt(),
                    bar.width,
                )
                assertTrue("Toolbar content must cover the viewport", bar.width >= viewport.width)
                if (imageWidth < bar.width) {
                    assertTrue("Narrow image toolbar must scroll", viewport.canScrollHorizontally(1))
                }
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
                val geometry = listOf(view.currentX, view.currentY, view.currentW, view.currentH)
                event(MotionEvent.ACTION_CANCEL)
                assertEquals(geometry, listOf(view.currentX, view.currentY, view.currentW, view.currentH))
                view.updateSizeFromExternal(width, height)
                view.updateLayoutFromExternal(70, 90)
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

    private fun viewportWidth(view: PinWindowView): Int =
        descendants(view).filterIsInstance<HorizontalScrollView>().single().width
}
