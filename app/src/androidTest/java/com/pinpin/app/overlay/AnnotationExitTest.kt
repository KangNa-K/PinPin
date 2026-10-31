package com.pinpin.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class AnnotationExitTest {
    @Before fun allowTestOverlays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "appops set $packageName SYSTEM_ALERT_WINDOW allow",
        ).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    @Test fun attachedOverlayExitsWithInjectedStylusAfterRepeatedDrawing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        for (exit in listOf("应用", "取消", "close")) {
            lateinit var view: PinWindowView
            var deleted = false
            var saved = false
            onMain {
                view = pin { deleted = true }
                view.onAnnotationDone = { bitmap ->
                    saved = true
                    bitmap.recycle()
                }
                manager.addView(view, view.layoutParams)
            }
            try {
                instrumentation.waitForIdleSync()
                onMain {
                    view.showToolbar(true)
                    view.enterAnnotationMode()
                }
                instrumentation.waitForIdleSync()
                val canvasRect = android.graphics.Rect()
                onMain {
                    val canvas = descendants(view).filterIsInstance<DrawingOverlayView>().single()
                    assertTrue(canvas.getGlobalVisibleRect(canvasRect))
                    val location = IntArray(2)
                    canvas.getLocationOnScreen(location)
                    canvasRect.offsetTo(location[0], location[1])
                }
                val x = canvasRect.centerX().toFloat()
                val y = canvasRect.centerY().toFloat()
                repeat(20) {
                    injectStylus(MotionEvent.ACTION_DOWN, x, y)
                    injectStylus(MotionEvent.ACTION_MOVE, x + 35, y + 35)
                    injectStylus(MotionEvent.ACTION_UP, x + 35, y + 35)
                }
                injectStylus(MotionEvent.ACTION_DOWN, x, y)
                injectStylus(MotionEvent.ACTION_CANCEL, x, y)
                instrumentation.waitForIdleSync()
                val target = IntArray(2)
                onMain {
                    val button = if (exit == "close") {
                        descendants(view).filterIsInstance<ImageButton>().single {
                            it.contentDescription == view.context.getString(com.pinpin.app.R.string.pin_action_close_cd)
                        }
                    } else {
                        descendants(view).single { it.contentDescription == exit }
                    }
                    button.getLocationOnScreen(target)
                    target[0] += button.width / 2
                    target[1] += button.height / 2
                }
                injectStylus(MotionEvent.ACTION_DOWN, target[0].toFloat(), target[1].toFloat())
                injectStylus(MotionEvent.ACTION_UP, target[0].toFloat(), target[1].toFloat())
                instrumentation.waitForIdleSync()
                runBlocking { view.awaitAnnotationRendering() }
                onMain {
                    assertFalse("Exit action did not respond: $exit", view.isAnnotationMode())
                    assertFalse(deleted)
                    assertEquals(exit == "应用", saved)
                    assertTrue(descendants(view).none { it is DrawingOverlayView })
                    assertEquals(200, view.currentW)
                    assertEquals(300, view.currentH)
                }
            } finally {
                onMain { manager.removeViewImmediate(view) }
            }
        }
    }

    private fun injectStylus(action: Int, x: Float, y: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        val event = stylusEvent(action, x, y, time)
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    @Test fun stylusCanCancelThroughWindowAfterDrawingAndInterruptedGesture() {
        lateinit var view: PinWindowView
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        onMain {
            view = pin()
            manager.addView(view, view.layoutParams)
        }
        try {
        onMain {
            view.enterAnnotationMode()
            addStroke(view)
            val params = view.layoutParams as WindowManager.LayoutParams
            view.measure(
                View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, params.width, params.height)
            val canvas = descendants(view).filterIsInstance<DrawingOverlayView>().single()
            val rect = android.graphics.Rect()
            canvas.getDrawingRect(rect)
            view.offsetDescendantRectToMyCoords(canvas, rect)
            dispatchStylus(view, MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat())
            dispatchStylus(view, MotionEvent.ACTION_CANCEL, rect.centerX().toFloat(), rect.centerY().toFloat())

            val cancel = descendants(view).single { it.contentDescription == "取消" }
            cancel.getDrawingRect(rect)
            view.offsetDescendantRectToMyCoords(cancel, rect)
            assertTrue(rect.bottom <= view.height)
            dispatchStylus(view, MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat())
            dispatchStylus(view, MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain { assertRestored(view) }
        } finally {
            onMain { manager.removeViewImmediate(view) }
        }
    }

    private fun dispatchStylus(view: View, action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val event = stylusEvent(action, x, y, time)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun stylusEvent(action: Int, x: Float, y: Float, time: Long): MotionEvent =
        MotionEvent.obtain(
            time, time, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            }),
            arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
        )

    @Test fun cancelledStylusTapDoesNotExitOrLeaveButtonPressed() = onMain {
        val view = pin()
        view.enterAnnotationMode()
        val cancel = descendants(view).single { it.contentDescription == "取消" }
        cancel.layout(0, 0, 120, 120)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
            val event = MotionEvent.obtain(
                0, 20, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }),
                arrayOf(MotionEvent.PointerCoords().apply {
                    x = 60f
                    y = 60f
                    pressure = 1f
                    size = 1f
                }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
            )
            try { assertTrue(cancel.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        assertFalse(cancel.isPressed)
        assertTrue(view.isAnnotationMode())
        view.exitAnnotationMode(discardChanges = true)
        assertRestored(view)
    }

    @Test fun closeRemainsAboveCanvasAndDiscardsWithoutDeletingPin() = onMain {
        var deleted = false
        val view = pin { deleted = true }
        view.enterAnnotationMode()
        val canvas = descendants(view).filterIsInstance<DrawingOverlayView>().single()
        val close = descendants(view).filterIsInstance<ImageButton>().single {
            it.contentDescription == view.context.getString(com.pinpin.app.R.string.pin_action_close_cd)
        }
        val card = close.parent as ViewGroup
        assertTrue(card.indexOfChild(close) > card.indexOfChild(canvas))
        assertEquals(View.VISIBLE, close.visibility)
        assertTrue(close.isEnabled)
        assertTrue(descendants(view).filterIsInstance<ImageButton>().filter { it !== close }
            .all { it.visibility == View.INVISIBLE && !it.isEnabled })
        close.performClick()
        assertFalse(deleted)
        assertRestored(view)
        assertFalse(close.isEnabled)
    }

    @Test fun annotationToolsReflectModeAndKeepShapeRecognitionInPen() = onMain {
        val view = pin()
        for (initialMode in DrawingOverlayView.Mode.entries) {
            view.enterAnnotationMode(initialMode)
            try {
                val children = descendants(view)
                val canvas = children.filterIsInstance<DrawingOverlayView>().single()
                val pen = children.single { it.contentDescription == "签字笔" }
                val eraser = children.single { it.contentDescription == "橡皮擦" }
                assertTrue(children.none { it.contentDescription == "智能形状" })
                assertTrue(canvas.shapeRecognitionEnabled)
                assertEquals(initialMode == DrawingOverlayView.Mode.PEN, pen.isSelected)
                assertEquals(initialMode == DrawingOverlayView.Mode.ERASER, eraser.isSelected)

                repeat(3) {
                    eraser.performClick()
                    assertEquals(DrawingOverlayView.Mode.ERASER, canvas.mode)
                    assertTrue(eraser.isSelected)
                    assertFalse(pen.isSelected)
                    pen.performClick()
                    assertEquals(DrawingOverlayView.Mode.PEN, canvas.mode)
                    assertTrue(pen.isSelected)
                    assertFalse(eraser.isSelected)
                    assertTrue(canvas.shapeRecognitionEnabled)
                }

                for (color in listOf("红", "蓝", "蓝")) {
                    eraser.performClick()
                    children.single { it.contentDescription == color }.performClick()
                    assertEquals(DrawingOverlayView.Mode.PEN, canvas.mode)
                    assertTrue(pen.isSelected)
                    assertFalse(eraser.isSelected)
                }
            } finally {
                view.exitAnnotationMode(discardChanges = true)
            }
        }
    }

    @Test fun pickedColourDrivesCanvasAndCarriesIntoNextSession() = onMain {
        val blue = android.graphics.Color.parseColor("#2196F3")
        val view = pin()
        view.enterAnnotationMode()
        try {
            val children = descendants(view)
            val canvas = children.filterIsInstance<DrawingOverlayView>().single()
            assertEquals(android.graphics.Color.RED, canvas.strokeColor)
            children.single { it.contentDescription == "蓝" }.performClick()
            assertEquals(blue, canvas.strokeColor)
        } finally {
            view.exitAnnotationMode(discardChanges = true)
        }
        view.enterAnnotationMode()
        try {
            val canvas = descendants(view).filterIsInstance<DrawingOverlayView>().single()
            assertEquals(blue, canvas.strokeColor)
        } finally {
            view.exitAnnotationMode(discardChanges = true)
        }
    }

    @Test fun brushSliderSetsCanvasStrokeWidth() = onMain {
        val view = pin()
        view.enterAnnotationMode()
        try {
            val children = descendants(view)
            val canvas = children.filterIsInstance<DrawingOverlayView>().single()
            val slider = children.filterIsInstance<android.widget.SeekBar>().single()
            assertEquals("笔刷大小", slider.contentDescription)
            assertEquals(18, slider.progress)
            slider.progress = 100
            assertEquals(24f, canvas.strokeWidth, 0.001f)
            slider.progress = 0
            assertEquals(2f, canvas.strokeWidth, 0.001f)
        } finally {
            view.exitAnnotationMode(discardChanges = true)
        }
    }

    @Test fun exitActionsFitNarrowScreenWithoutScrollingTools() = onMain {
        val view = pin()
        view.enterAnnotationMode()
        val density = view.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val toolbar = descendants(view).first { it.contentDescription == "取消" }.parent as ViewGroup
        val bar = toolbar.parent as ViewGroup
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, width, bar.measuredHeight)
        for (label in listOf("保存", "应用", "取消")) {
            val action = descendants(bar).single { it.contentDescription == label }
            assertFalse(action.parent is HorizontalScrollView)
            assertTrue(toolbar.left + action.left >= 0)
            assertTrue(toolbar.left + action.right <= width)
        }
        descendants(bar).single { it.contentDescription == "取消" }.performClick()
        assertRestored(view)
    }

    @Test fun renderingFailureStillRemovesCanvasAndRestoresGeometry() = onMain {
        val view = pin()
        view.enterAnnotationMode()
        addStroke(view)
        sourceField.set(view, null)
        view.exitAnnotationMode()
        assertRestored(view)
    }

    @Test fun persistenceCallbackRunsOnlyAfterRestoringInteraction() {
        lateinit var view: PinWindowView
        var called = false
        onMain {
            view = pin()
            view.enterAnnotationMode()
            addStroke(view)
            view.onAnnotationDone = {
                called = true
                assertRestored(view)
                throw IllegalStateException("Simulated persistence failure")
            }
            view.exitAnnotationMode()
            assertRestored(view)
        }
        runBlocking { view.awaitAnnotationRendering() }
        onMain {
            assertTrue(called)
            assertRestored(view)
        }
    }

    @Test fun queuedAnnotationSaveSurvivesWindowDetachAndRejectsReentry(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            ImageRenderWork.run { entered.complete(Unit); release.await() }
        }
        entered.await()
        lateinit var view: PinWindowView
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var delivered = 0
        try {
            onMain {
                view = pin()
                manager.addView(view, view.layoutParams)
                view.enterAnnotationMode()
                addStroke(view)
                view.onAnnotationDone = {
                    delivered++
                    assertFalse(it.isRecycled)
                    it.recycle()
                }
                view.exitAnnotationMode()
                assertRestored(view)
                assertTrue(view.annotationSaving)
                view.enterAnnotationMode()
                assertFalse(view.isAnnotationMode())
                manager.removeViewImmediate(view)
                assertTrue(AnnotationSaveState.isSaving(view.pinId))
                val restored = pin()
                restored.enterAnnotationMode()
                assertFalse("New window must not edit the old pixels during a save", restored.isAnnotationMode())
            }
        } finally {
            release.complete(Unit)
            holder.join()
        }
        view.awaitAnnotationRendering()
        onMain {
            assertEquals(1, delivered)
            assertFalse(view.annotationSaving)
            assertFalse(AnnotationSaveState.isSaving(view.pinId))
        }
    }

    private val sourceField
        get() = PinWindowView::class.java.getDeclaredField("sourceBitmap").apply { isAccessible = true }

    private fun pin(onClose: () -> Unit = {}): PinWindowView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PinWindowView(
            context, "annotation-test",
            PinWindowView.PinSpec(
                label = "test", imageUri = null, sourceType = "test",
                initialX = 20, initialY = 30, widthPx = 200, heightPx = 300,
                locked = false, zOrder = 1,
            ),
            onPositionChanged = { _, _ -> }, onSizeChanged = { _, _ -> },
            onCloseClicked = onClose, onActionClicked = {},
        ).also {
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            sourceField.set(it, bitmap)
            descendants(it).filterIsInstance<ImageView>().first { image -> image !is ImageButton }
                .setImageBitmap(bitmap)
        }
    }

    private fun addStroke(view: PinWindowView) {
        val canvas = descendants(view).filterIsInstance<DrawingOverlayView>().single()
        canvas.layout(0, 0, 100, 100)
        canvas.shapeRecognitionEnabled = false
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
            .forEachIndexed { index, action ->
                val event = MotionEvent.obtain(0, index * 20L, action, 10f + index * 20, 20f, 0)
                try { canvas.onTouchEvent(event) } finally { event.recycle() }
            }
        assertTrue(canvas.hasStrokes())
    }

    private fun assertRestored(view: PinWindowView) {
        assertFalse(view.isAnnotationMode())
        assertTrue(descendants(view).none { it is DrawingOverlayView })
        assertTrue(descendants(view).none { it.contentDescription == "取消" })
        assertEquals(20, view.currentX)
        assertEquals(30, view.currentY)
        assertEquals(200, view.currentW)
        assertEquals(300, view.currentH)
        val params = view.layoutParams as WindowManager.LayoutParams
        assertEquals(200, params.width)
        assertEquals(300, params.height)
    }

    private fun descendants(parent: ViewGroup): List<View> = buildList {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            add(child)
            if (child is ViewGroup) addAll(descendants(child))
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
