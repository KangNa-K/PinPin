package com.pinpin.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class PinColorPickerTest {
    @Before fun allowOverlay() {
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set com.pinpin.app SYSTEM_ALERT_WINDOW allow",
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }

    @Test fun longPressThenMovePicksInsteadOfMovingPin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "color-picker-gesture.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val windowManager = context.getSystemService(WindowManager::class.java)
        val size = (250 * context.resources.displayMetrics.density).toInt()
        lateinit var view: PinWindowView
        var attached = false
        try {
            instrumentation.runOnMainSync {
                view = PinWindowView(context, "color-gesture", PinWindowView.PinSpec(
                    label = "test", imageUri = Uri.fromFile(file).toString(), sourceType = "test",
                    initialX = 80, initialY = 100, widthPx = size, heightPx = size,
                    locked = false, zOrder = 1,
                ), onPositionChanged = { _, _ -> }, onSizeChanged = { _, _ -> },
                    onCloseClicked = {}, onActionClicked = {})
                windowManager.addView(view, view.layoutParams)
                attached = true
            }
            val bitmapField = PinWindowView::class.java.getDeclaredField("sourceBitmap").apply { isAccessible = true }
            var loaded = false
            repeat(100) {
                instrumentation.runOnMainSync { loaded = bitmapField.get(view) != null }
                if (!loaded) Thread.sleep(50)
            }
            assertTrue("Image did not load", loaded)
            val location = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    location[0] + x, location[1] + y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, size / 2f, size / 2f)
            Thread.sleep(ViewConfiguration.getLongPressTimeout() + 200L)
            lateinit var picker: PinColorPicker
            lateinit var magnifier: View
            var initialPosition = 0 to 0
            var initialMagnifierX = 0
            instrumentation.runOnMainSync {
                val field = PinWindowView::class.java.getDeclaredField("colorPicker").apply { isAccessible = true }
                assertNotNull(field.get(view))
                picker = field.get(view) as PinColorPicker
                val magnifierField = PinColorPicker::class.java.getDeclaredField("magnifier").apply { isAccessible = true }
                magnifier = magnifierField.get(picker) as View
                val panel = PinColorPicker::class.java.getDeclaredField("panel").apply { isAccessible = true }
                    .get(picker) as View
                val panelPosition = panel.layoutParams as WindowManager.LayoutParams
                initialPosition = panelPosition.x to panelPosition.y
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                val bottomMargin = (24 * context.resources.displayMetrics.density + .5f).toInt()
                assertEquals(metrics.bounds.bottom - insets.bottom - bottomMargin,
                    panelPosition.y + panel.minimumHeight)
                assertEquals((metrics.bounds.left + insets.left + metrics.bounds.right - insets.right - panelPosition.width) / 2,
                    panelPosition.x)
                assertEquals(View.VISIBLE, magnifier.visibility)
                val magnifierPosition = magnifier.layoutParams as WindowManager.LayoutParams
                initialMagnifierX = magnifierPosition.x
                assertEquals(0, magnifierPosition.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertEquals(1f, magnifierPosition.alpha, 0f)
            }
            send(MotionEvent.ACTION_MOVE, size / 2f + 50, size / 2f)
            instrumentation.runOnMainSync {
                val panel = PinColorPicker::class.java.getDeclaredField("panel").apply { isAccessible = true }
                    .get(picker) as View
                val panelPosition = panel.layoutParams as WindowManager.LayoutParams
                assertEquals(initialPosition, panelPosition.x to panelPosition.y)
                val position = magnifier.layoutParams as WindowManager.LayoutParams
                assertEquals(initialMagnifierX + 50, position.x)
                val bounds = windowManager.maximumWindowMetrics.bounds
                assertTrue(position.x >= bounds.left && position.x + position.width <= bounds.right)
                assertTrue(position.y >= bounds.top && position.y + position.height <= bounds.bottom)
            }
            send(MotionEvent.ACTION_UP, size / 2f + 50, size / 2f)
            instrumentation.runOnMainSync {
                assertEquals(View.GONE, magnifier.visibility)
                assertEquals(80, view.currentX)
                assertEquals(100, view.currentY)
                assertNotNull(PinWindowView::class.java.getDeclaredField("colorPicker").apply { isAccessible = true }.get(view))
            }
        } finally {
            instrumentation.runOnMainSync { if (attached) windowManager.removeViewImmediate(view) }
            file.delete()
        }
    }

    @Test fun readsOriginalPixelsAndDisplaysHsvAndAlpha() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "color-picker-test.png")
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        bitmap.setPixel(10, 10, Color.argb(128, 0, 255, 0))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        var picker: PinColorPicker? = null
        try {
            instrumentation.runOnMainSync {
                picker = PinColorPicker(context, scope, Uri.fromFile(file))
                picker?.update(.5f, .5f)
            }
            val valuesField = PinColorPicker::class.java.getDeclaredField("values").apply { isAccessible = true }
            var text = ""
            repeat(100) {
                instrumentation.runOnMainSync { text = (valuesField.get(picker) as TextView).text.toString() }
                if (text.contains("#00FF00")) return@repeat
                Thread.sleep(50)
            }
            assertTrue(text, text.contains("#00FF00"))
            assertTrue(text, text.contains("H 120°"))
            assertTrue(text, text.contains("S 100%") && text.contains("V 100%"))
            assertTrue(text, text.contains("不透明度 50%"))
            instrumentation.runOnMainSync {
                val panelField = PinColorPicker::class.java.getDeclaredField("panel").apply { isAccessible = true }
                val panel = panelField.get(picker) as LinearLayout
                assertTrue((panel.background as GradientDrawable).cornerRadius > panel.height / 2f)
                val swatchField = PinColorPicker::class.java.getDeclaredField("swatch").apply { isAccessible = true }
                assertEquals(GradientDrawable.OVAL, ((swatchField.get(picker) as View).background as GradientDrawable).shape)
                assertEquals(PinTheme.of(context).iconColor, (valuesField.get(picker) as TextView).currentTextColor)
            }
        } finally {
            instrumentation.runOnMainSync {
                picker?.close()
                scope.cancel()
            }
            file.delete()
        }
    }

    @Test fun magnifierShowsImageCrosshairAndSingleColorRing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val image = ImageView(context).apply {
                setImageDrawable(BitmapDrawable(context.resources, source))
                scaleType = ImageView.ScaleType.MATRIX
            }
            val magnifier = PinColorMagnifier(context).apply {
                layout(0, 0, 300, 300)
                update(image, 50f, 50f)
                setColor(Color.BLUE)
            }
            val rendered = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            magnifier.draw(Canvas(rendered))
            assertEquals(Color.RED, rendered.getPixel(130, 130))
            assertEquals(Color.BLUE, rendered.getPixel(150, 15))
            assertEquals(Color.BLUE, rendered.getPixel(150, 285))
            val density = context.resources.displayMetrics.density
            val outer = 150f - density
            val inner = outer - 12 * density
            assertEquals(Color.BLUE, rendered.getPixel(150, (150 - outer + 2 * density).toInt()))
            assertEquals(Color.BLUE, rendered.getPixel(150, (150 - inner - 2 * density).toInt()))
            val borderY = (150 - inner).toInt()
            val border = rendered.getPixel(150, borderY)
            assertTrue("Inner border should be white", Color.red(border) > 200 && Color.green(border) > 200)
            assertEquals(Color.WHITE, rendered.getPixel(150, 150))
            magnifier.setColor(Color.BLACK)
            rendered.eraseColor(Color.TRANSPARENT)
            magnifier.draw(Canvas(rendered))
            assertEquals(Color.BLACK, rendered.getPixel(150, (150 - outer + 2 * density).toInt()))
            val darkRingBorder = rendered.getPixel(150, borderY)
            assertTrue("Inner border should remain visible on a dark ring",
                Color.red(darkRingBorder) > 200 && Color.green(darkRingBorder) > 200)
            rendered.recycle()
            source.recycle()
        }
    }

    @Test fun colorPanelUsesSystemLightAndDarkThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = ApplicationProvider.getApplicationContext<Context>()
        val file = File(base.cacheDir, "color-picker-theme.png")
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        try {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync {
                    val config = Configuration(base.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                    }
                    val themed = base.createConfigurationContext(config)
                    val picker = PinColorPicker(themed, scope, Uri.fromFile(file))
                    try {
                        val panel = PinColorPicker::class.java.getDeclaredField("panel").apply { isAccessible = true }
                            .get(picker) as LinearLayout
                        val values = PinColorPicker::class.java.getDeclaredField("values").apply { isAccessible = true }
                            .get(picker) as TextView
                        val fill = (panel.background as GradientDrawable).color!!.defaultColor
                        assertEquals(255, Color.alpha(fill))
                        assertEquals(PinTheme.of(themed).surfaceFill and 0xffffff, fill and 0xffffff)
                        assertEquals(PinTheme.of(themed).iconColor, values.currentTextColor)
                        val params = panel.layoutParams as WindowManager.LayoutParams
                        assertEquals(1f, params.alpha, 0f)
                        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    } finally {
                        picker.close()
                    }
                }
            }
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
