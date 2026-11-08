package com.pinpin.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pinpin.app.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt

/** Reads only a small region of the persisted image, never the downsampled preview. */
internal class PinColorPicker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uri: Uri,
) {
    private data class Point(val x: Float, val y: Float)
    private val requests = Channel<Point>(Channel.CONFLATED)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private var color: Int? = null
    private var closed = false
    private val swatch = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        contentDescription = "取样颜色"
    }
    private val magnifier = PinColorMagnifier(context)
    private var magnifierAttached = false
    private val magnifierSize = dp(128)
    // Some systems cap NOT_TOUCHABLE overlay windows at 80% opacity. The lens
    // appears away from the active pointer, whose gesture remains on the pin.
    private val magnifierParams = WindowManager.LayoutParams(
        magnifierSize, magnifierSize, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    private val values = TextView(context).apply {
        textSize = 14f
        text = "正在读取颜色..."
        setPadding(dp(10), 0, dp(6), 0)
    }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
        }
        addView(swatch, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(values, LinearLayout.LayoutParams(0, -2, 1f))
        fun button(icon: Int, description: String, action: () -> Unit) = ImageButton(context).apply {
            setImageResource(icon)
            background = null
            contentDescription = description
            setOnClickListener { action() }
        }
        addView(button(R.drawable.ic_action_copy, "复制色号") { copy() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(button(R.drawable.ic_close, "关闭取色") { close() }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }
    private val worker: Job

    init {
        applyTheme()
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val left = bounds.left + insets.left
        val top = bounds.top + insets.top
        val right = (bounds.right - insets.right).coerceAtLeast(left + 1)
        val bottom = (bounds.bottom - insets.bottom).coerceAtLeast(top + 1)
        val margin = minOf(dp(8), (right - left - 1) / 2, (bottom - top - 1) / 2)
        val bottomMargin = minOf(dp(24), bottom - top - margin - 1)
        val safe = PinColorPanelGeometry.Bounds(left + margin, top + margin, right - margin, bottom - bottomMargin)
        val width = minOf(dp(340), safe.right - safe.left).coerceAtLeast(1)
        // Reserve enough height for the alpha line so the panel stays in place while sampling.
        val initialText = values.text
        values.text = "#FFFFFF\nH 359°  S 100%  V 100%\n不透明度 100%"
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.minimumHeight = panel.measuredHeight
        values.text = initialText
        val position = PinColorPanelGeometry.position(safe, width, panel.minimumHeight)
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = position.first
            y = position.second
        }
        windowManager.addView(panel, params)
        worker = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val orientation = runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        }
                    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
                    val decoder = context.contentResolver.openInputStream(uri)?.use {
                        BitmapRegionDecoder.newInstance(it, false)
                    } ?: error("Cannot decode image")
                    try {
                        for (point in requests) {
                            val result = sample(decoder, orientation, point)
                            withContext(Dispatchers.Main) { if (!closed) show(result) }
                        }
                    } finally {
                        decoder.recycle()
                    }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!closed) {
                    Toast.makeText(context, "无法读取图片颜色", Toast.LENGTH_SHORT).show()
                    close()
                }
            }
        }
    }

    fun update(x: Float, y: Float) { requests.trySend(Point(x, y)) }

    fun showMagnifier(image: ImageView, x: Float, y: Float, screenX: Float, screenY: Float) {
        if (closed) return
        magnifier.update(image, x, y)
        val bounds = windowManager.maximumWindowMetrics.bounds
        val margin = dp(8)
        val gap = dp(24)
        magnifierParams.x = (screenX.toInt() - magnifierSize / 2)
            .coerceIn(bounds.left + margin, (bounds.right - magnifierSize - margin).coerceAtLeast(bounds.left + margin))
        val above = screenY.toInt() - magnifierSize - gap
        magnifierParams.y = (if (above >= bounds.top + margin) above else screenY.toInt() + gap)
            .coerceIn(bounds.top + margin, (bounds.bottom - magnifierSize - margin).coerceAtLeast(bounds.top + margin))
        if (!magnifierAttached) {
            windowManager.addView(magnifier, magnifierParams)
            magnifierAttached = true
        } else {
            magnifier.visibility = View.VISIBLE
            windowManager.updateViewLayout(magnifier, magnifierParams)
        }
    }

    fun hideMagnifier() { magnifier.visibility = View.GONE }

    fun applyTheme() {
        val theme = PinTheme.of(context)
        (panel.background as GradientDrawable).apply {
            setColor(theme.surfaceFill or Color.BLACK)
            setStroke(dp(1), theme.surfaceStroke)
        }
        values.setTextColor(theme.iconColor)
        for (index in 0 until panel.childCount) {
            (panel.getChildAt(index) as? ImageButton)?.setColorFilter(theme.iconColor)
        }
        (swatch.background as GradientDrawable).setStroke(dp(1), theme.iconColor)
    }

    fun close() {
        if (closed) return
        closed = true
        requests.close()
        worker.cancel()
        if (magnifierAttached) runCatching { windowManager.removeView(magnifier) }
        runCatching { windowManager.removeView(panel) }
    }

    private fun copy() {
        val value = color ?: return
        val hex = String.format(Locale.US, "#%06X", value and 0xffffff)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("色号", hex))
        Toast.makeText(context, "已复制 $hex", Toast.LENGTH_SHORT).show()
    }

    private fun show(value: Int) {
        color = value
        val hsv = FloatArray(3)
        Color.colorToHSV(value, hsv)
        val alpha = Color.alpha(value)
        values.text = String.format(Locale.US, "#%06X\nH %d°  S %.0f%%  V %.0f%%%s",
            value and 0xffffff, hsv[0].roundToInt() % 360, hsv[1] * 100, hsv[2] * 100,
            if (alpha < 255) "\n不透明度 ${alpha * 100 / 255}%" else "")
        (swatch.background as GradientDrawable).setColor(value)
        magnifier.setColor(value)
    }

    private fun sample(decoder: BitmapRegionDecoder, orientation: Int, point: Point): Int {
        val w = decoder.width
        val h = decoder.height
        val swapped = orientation in 5..8
        val ow = if (swapped) h else w
        val oh = if (swapped) w else h
        val cx = floor(point.x * ow).toInt().coerceIn(0, ow - 1)
        val cy = floor(point.y * oh).toInt().coerceIn(0, oh - 1)
        fun raw(x: Int, y: Int) = PinColorCoordinates.raw(x, y, w, h, orientation)
        val (rx, ry) = raw(cx, cy)
        val rect = Rect(rx, ry, rx + 1, ry + 1)
        val region = decoder.decodeRegion(rect, BitmapFactory.Options()) ?: error("Cannot decode region")
        try {
            return region.getPixel(0, 0)
        } finally {
            region.recycle()
        }
    }
}
