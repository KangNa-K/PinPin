package com.pinpin.app.ball

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.withSave
import androidx.core.graphics.toColorInt
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pinpin.app.R
import kotlin.math.max
import kotlin.math.min

/**
 * Image-adjust preview launched when the user picks a new
 * floating-ball image. The activity renders the source bitmap
 * inside a circular mask and lets the user:
 *
 *  - drag to pan the image (up / down / left / right)
 *  - pinch to zoom in / out
 *  - confirm to apply the resulting transform
 *
 * What the user sees in the preview is exactly what ends up on
 * the ball. The preview uses the same math as
 * [com.pinpin.app.ball.BallView.setAvatarTransform]: the base
 * matrix is the same CENTER_CROP cover, the user-driven
 * (scale, offsetX, offsetY) is layered on top.
 *
 * Result contract: the activity sets a result Intent with the
 * picked URI and the (scale, offsetX, offsetY) the user picked.
 * The caller (HomeScreen) forwards those to
 * [BallService.setBallImage]. If the user cancels, RESULT_CANCELED
 * is returned and the caller should do nothing.
 *
 * Why an Activity instead of a Composable: the preview needs to
 * be a top-level window (not a sheet over the home screen) so the
 * user sees the image at full size while picking. An Activity is
 * the simplest way to get a dedicated window + back-button
 * integration without rebuilding the home navigation graph.
 */
class BallImageAdjustActivity : ComponentActivity() {

    private lateinit var preview: CircularImagePreview
    private var sourceBitmap: Bitmap? = null
    private val darkMode: Boolean
        get() = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val surfaceColor: Int get() = if (darkMode) Color.rgb(28, 28, 30) else Color.WHITE
    private val textColor: Int get() = if (darkMode) Color.WHITE else Color.rgb(31, 31, 35)
    /**
     * Snapshot of the latest transform from the preview.
     * The preview converts its preview-pixels into
     * ball-pixels (via [CircularImagePreview.displayMultiplier])
     * before storing here, so when we hand these values to
     * BallService.setBallImage, the ball will render
     * exactly what the user saw on the preview.
     */
    private var userScale: Float = 1f
    private var userOffsetX: Float = 0f
    private var userOffsetY: Float = 0f


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri: Uri = IntentCompat.getParcelableExtra(intent, EXTRA_SOURCE_URI, Uri::class.java)
            ?: run { finish(); return }
        userScale = (savedInstanceState?.getFloat(EXTRA_RESULT_SCALE)
            ?: intent.getFloatExtra(EXTRA_RESULT_SCALE, 1f)).let { if (it.isFinite()) it.coerceIn(0.5f, 5f) else 1f }
        userOffsetX = (savedInstanceState?.getFloat(EXTRA_RESULT_OFFSET_X)
            ?: intent.getFloatExtra(EXTRA_RESULT_OFFSET_X, 0f)).let { if (it.isFinite()) it else 0f }
        userOffsetY = (savedInstanceState?.getFloat(EXTRA_RESULT_OFFSET_Y)
            ?: intent.getFloatExtra(EXTRA_RESULT_OFFSET_Y, 0f)).let { if (it.isFinite()) it else 0f }
        // Root: a FrameLayout. We layer four bands explicitly:
        //   1. Top:    a white title bar with "调整悬浮球图片" and an X close
        //   2. Center: the BIG 280dp preview view (the ball itself is
        //              still drawn at BallView's exact 48dp inside)
        //   3. Below center: a hint ("拖动 / 双指") + a single row of
        //              zoom (− +) buttons
        //   4. Bottom: the three confirm buttons (取消 / 重置 / 保存)
        //
        // The previous design had everything crammed into a single
        // LinearLayout, which made the preview tiny and the
        // direction/zoom row hard to read. FrameLayout + explicit
        // gravity per layer gives each band a clean, predictable
        // spot on the screen.
        val root = FrameLayout(this).apply {
            setBackgroundColor(if (darkMode) Color.BLACK else "#F2F2F7".toColorInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // 1) Top title bar — white background to visually separate
        //    from the rest of the page.
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(0), dp(8), dp(0))
            setBackgroundColor(surfaceColor)
            elevation = dp(2).toFloat()
        }
        titleBar.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f,
                )
                addView(
                    TextView(this@BallImageAdjustActivity).apply {
                        text = getString(R.string.ball_adjust_title)
                        textSize = 17f
                        setTextColor(textColor)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    },
                )
                // Tiny hint that the preview circle is enlarged
                // for visibility; the actual ball is 48dp.
                TextView(this@BallImageAdjustActivity).apply {
                    text = getString(R.string.ball_adjust_actual_size)
                    textSize = 11f
                    setTextColor("#8E8E93".toColorInt())
                    setPadding(dp(6), 0, 0, 0)
                }
            },
        )
        // X close button — explicit "cancel and exit" affordance
        // so the user is never stranded on the screen.
        titleBar.addView(
            TextView(this).apply {
                text = "×"
                textSize = 24f
                setTextColor("#8E8E93".toColorInt())
                contentDescription = getString(R.string.ball_adjust_close_cd)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            },
        )
        root.addView(titleBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { gravity = android.view.Gravity.TOP })

        // 2) Big circular preview — 320dp touch target centered on
        //    screen. The user adjusts the image via gestures only
        //    (drag to pan, pinch to zoom), so the view has zero
        //    chrome inside it. The actual rendered ball is still
        //    drawn at BallView's exact 48dp diameter via the FIXED
        //    sidePx inside the view, so the math stays WYSIWYG.
        //    The much-larger view simply gives the user plenty of
        //    room to drag and pinch without finger occluding the
        //    preview.
        val previewSize = dp(320)
        preview = CircularImagePreview(this).apply {
            source = sourceBitmap
            layoutParams = FrameLayout.LayoutParams(previewSize, previewSize).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        preview.onTransformChanged = { s, x, y ->
            // Convert from preview pixels to ball pixels
            // before storing. The user dragged the image by
            // `x` preview-pixels; on the real (smaller) ball
            // the same visual offset is `x / displayMultiplier`
            // ball-pixels. Without this conversion the
            // adjustment would land in the wrong place on
            // the actual ball.
            userScale = s
            userOffsetX = x / preview.displayMultiplier
            userOffsetY = y / preview.displayMultiplier
        }
        root.addView(preview)
        preview.restoreTransform(userScale, userOffsetX * preview.displayMultiplier,
            userOffsetY * preview.displayMultiplier)


        // 3) Bottom confirm row — 3 buttons, each weight=1, 48dp
        //    tall, with proper padding so the text never crowds
        //    the edge.
        val confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(20))
            setBackgroundColor(surfaceColor)
        }
        confirmRow.addView(makeTextButton(getString(R.string.ball_adjust_cancel)) {
            setResult(RESULT_CANCELED)
            finish()
        })
        confirmRow.addView(makeTextButton(getString(R.string.ball_adjust_reset)) {
            preview.resetTransform()
        })
        val saveButton = makeTextButton(getString(R.string.ball_adjust_save)) {
            if (sourceBitmap == null) return@makeTextButton
            val data = Intent().apply {
                putExtra(EXTRA_RESULT_URI, uri)
                putExtra(EXTRA_RESULT_SCALE, userScale)
                putExtra(EXTRA_RESULT_OFFSET_X, userOffsetX)
                putExtra(EXTRA_RESULT_OFFSET_Y, userOffsetY)
            }
            setResult(RESULT_OK, data)
            finish()
        }.apply { isEnabled = false; alpha = 0.5f }
        confirmRow.addView(saveButton)
        root.addView(
            confirmRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = android.view.Gravity.BOTTOM },
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
        setContentView(root)
        lifecycleScope.launch {
            var decoded: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    decoded = try {
                        decodeBitmap(uri)
                    } catch (e: Exception) {
                        Log.w(TAG, "Image decode failed", e)
                        null
                    } catch (e: OutOfMemoryError) {
                        Log.w(TAG, "Image too large", e)
                        null
                    }
                }
                val image = decoded
                if (image == null) {
                    android.widget.Toast.makeText(this@BallImageAdjustActivity,
                        "无法读取图片，请重新选择", android.widget.Toast.LENGTH_LONG).show()
                    setResult(RESULT_CANCELED)
                    finish()
                } else {
                    sourceBitmap = image
                    decoded = null
                    preview.source = image
                    preview.invalidate()
                    saveButton.isEnabled = true
                    saveButton.alpha = 1f
                }
            } finally {
                decoded?.recycle()
            }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        // First-pass decode just to learn the intrinsic size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Power-of-two sampling bounds the preview to a 1024px long edge.
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeDirectionButton(glyph: String, onClick: () -> Unit): View {
        // Direction pad: 48dp oval tap target, 24sp bold glyph.
        // Reads as a precision control. The glyphs (arrows) are
        // Unicode characters which render consistently across
        // all Android API levels and locales.
        val density = resources.displayMetrics.density
        val size = dp(48)
        return TextView(this).apply {
            text = glyph
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor("#1F1F23".toColorInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                val m = dp(4)
                setMargins(m, m, m, m)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun makeZoomButton(
        glyph: String,
        contentDescriptionRes: Int,
        onClick: () -> Unit,
    ): View {
        // Pill-shaped zoom button — 56x48dp, 22sp glyph. The
        // wide format gives the + glyph room to breathe
        // without looking cramped against the screen edge.
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = glyph
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor("#1F1F23".toColorInt())
            contentDescription = getString(contentDescriptionRes)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(48)).apply {
                val m = dp(4)
                setMargins(m, m, m, m)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    @Suppress("unused")
    private fun makeIconButton(text: String, onClick: () -> Unit): View =
        // Kept as a thin alias for [makeDirectionButton] so
        // anything in the old layout (or future debug builds)
        // that still calls it keeps working.
        makeDirectionButton(text, onClick)

    private fun makeTextButton(text: String, onClick: () -> Unit): View {
        // Bottom confirm-row button. 48dp tall, 16sp bold text,
        // 10dp corner radius matches the rest of the app's
        // button language. Generous 6dp horizontal margins
        // ensure the three labels never crowd the edge.
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor("#007AFF".toColorInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(48),
                1f,
            ).apply {
                val m = dp(6)
                setMargins(m, 0, m, 0)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }


    override fun onDestroy() {
        if (::preview.isInitialized) preview.source = null
        sourceBitmap?.recycle()
        sourceBitmap = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat(EXTRA_RESULT_SCALE, userScale)
        outState.putFloat(EXTRA_RESULT_OFFSET_X, userOffsetX)
        outState.putFloat(EXTRA_RESULT_OFFSET_Y, userOffsetY)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val TAG = "BallImageAdjust"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_RESULT_URI = "result_uri"
        const val EXTRA_RESULT_SCALE = "result_scale"
        const val EXTRA_RESULT_OFFSET_X = "result_offset_x"
        const val EXTRA_RESULT_OFFSET_Y = "result_offset_y"
    }
}

/**
 * Round preview surface. Renders [source] inside a circular clip,
 * and lets the user pan and pinch. The same matrix math as
 * [com.pinpin.app.ball.BallView.onDraw] is used here so the
 * preview is a faithful representation of the eventual ball.
 *
 * The view is sized exactly like a real ball window
 * (`side × 2 = 48dp` for the visible disc, plus a 2dp white
 * ring on the outside) and the bitmap is drawn with the same
 * CENTER_CROP / user-transform formula as BallView, AND
 * `clipPath`'d to the visible circle. Without the clipPath
 * the bitmap would render as a full View-sized rectangle and
 * the user would see overflow; without the size match the
 * preview's "circle" would be a different diameter than what
 * BallView actually shows. With both fixed, what the user
 * sees here is exactly what ends up on the ball.
 */
private class CircularImagePreview(context: android.content.Context) : View(context) {

    var source: Bitmap? = null
    var onTransformChanged: ((scale: Float, offsetX: Float, offsetY: Float) -> Unit)? = null

    private var userScale: Float = 1f
    private var userOffsetX: Float = 0f
    private var userOffsetY: Float = 0f

    // [ballSidePx] is BallView's exact visible-disc radius
    // (coreRadius + ringStroke = 24dp). The preview's base
    // scale is derived from this number — NOT from the
    // bigger preview radius — so a userScale of 1.0 on the
    // preview corresponds to the same crop on the actual
    // ball. The final draw scale is then multiplied by
    // [displayMultiplier] to expand the bitmap to fill
    // the bigger preview circle.
    private val ballSidePx: Float = (24f * context.resources.displayMetrics.density)
    private val previewSidePx: Float = (100f * context.resources.displayMetrics.density)
    private val ringStrokePx: Float = (2f * context.resources.displayMetrics.density)
    private val outerRadiusPx: Float = previewSidePx + ringStrokePx
    val displayMultiplier: Float = previewSidePx / ballSidePx

    /**
     * The visible-disc radius, in pixels, computed at construction
     * from the same 24dp value BallView uses (`coreRadius + ringStroke`).
     * All drawing math is in this fixed radius, NOT the view's
     * geometric bounds, so the preview matches BallView exactly.
     */
    // The preview is rendered at 200dp diameter — roughly 4x the
    // actual BallView (which is 48dp) — so the user can clearly
    // see what the image will look like inside the ball. The
    // title bar advertises the real size ("实际 48dp") so the
    // user knows the WYSIWYG mapping.
private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = ringStrokePx
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#E0E0E5".toColorInt()
    }
    private val matrix = Matrix()
    private val clipPath = android.graphics.Path()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userScale = (userScale * detector.scaleFactor).coerceIn(0.5f, 5f)
                pushTransform()
                return true
            }
        },
    )

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    fun nudgeBy(dx: Float, dy: Float) {
        userOffsetX += dx
        userOffsetY += dy
        pushTransform()
    }

    fun zoomBy(factor: Float) {
        userScale = (userScale * factor).coerceIn(0.5f, 5f)
        pushTransform()
    }

    fun resetTransform() {
        userScale = 1f
        userOffsetX = 0f
        userOffsetY = 0f
        pushTransform()
    }

    fun restoreTransform(scale: Float, x: Float, y: Float) {
        userScale = scale
        userOffsetX = x
        userOffsetY = y
        pushTransform()
    }

    private fun pushTransform() {
        onTransformChanged?.invoke(userScale, userOffsetX, userOffsetY)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.pointerCount == 2) {
            val remaining = if (event.actionIndex == 0) 1 else 0
            lastTouchX = event.getX(remaining)
            lastTouchY = event.getY(remaining)
            isDragging = true
            return true
        }
        // Use only single-pointer events for drag; once two
        // fingers land the scale detector owns the gesture.
        if (event.pointerCount == 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isDragging = true
                }
                MotionEvent.ACTION_MOVE -> if (isDragging) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    userOffsetX += dx
                    userOffsetY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    pushTransform()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
        } else {
            isDragging = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // Use [previewSidePx] (200dp) so the visible disc is
        // much larger than BallView's real 48dp — that's
        // the whole point of the preview. The base scale
        // used inside onDraw is still computed against
        // [ballSidePx] so the math matches BallView exactly.
        val r = previewSidePx

        // White background fills the entire visible disc (so
        // transparent areas of the source bitmap show white
        // rather than a gray page background).
        canvas.drawCircle(cx, cy, r, bgPaint)
        // White ring on the outside of the disc — mirrors the
        // ring drawn on top of the bitmap in BallView.
        canvas.drawCircle(cx, cy, r + ringStrokePx / 2f, ringPaint)

        val bmp = source
        if (bmp == null) {
            canvas.drawCircle(cx, cy, r * 0.7f, placeholderPaint)
            return
        }

        // Same CENTER_CROP base as BallView: the image is scaled
        // so its smaller side equals the visible diameter, then
        // translated so the scaled image is centered on the
        // ball center. The user transform is layered on top.
        // Use the REAL BallView target diameter (48dp) for
        // the base scale — that's what BallView does and we
        // need the same math so a userScale of 1.0 (or any
        // other value) corresponds to the same crop on both
        // surfaces. The final draw scale is then multiplied
        // by [displayMultiplier] to expand the bitmap to
        // fill the bigger preview circle.
        val targetDiameter = ballSidePx * 2f
        val baseScale = max(
            targetDiameter / bmp.width,
            targetDiameter / bmp.height,
        )
        val finalScale = baseScale * userScale * displayMultiplier
        val scaledW = bmp.width * finalScale
        val scaledH = bmp.height * finalScale
        matrix.reset()
        matrix.setScale(finalScale, finalScale)
        matrix.postTranslate(
            cx - scaledW / 2f + userOffsetX,
            cy - scaledH / 2f + userOffsetY,
        )

        // CRITICAL: clip the bitmap to the visible circle.
        // Without this the bitmap fills the entire View as a
        // rectangle and the user sees overflow outside the
        // circle, which then looks different from what
        // BallView renders (BallView draws the bitmap AS the
        // fill of a circle, so anything outside the circle is
        // not drawn). Without this clip the preview lies.
        canvas.withSave {
            // Position the pre-built clipPath at the current center
            // and apply it to the canvas.
            clipPath.reset()
            clipPath.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
            clipPath(clipPath)
            drawBitmap(bmp, this@CircularImagePreview.matrix, basePaint)
        }

        // Re-draw the white ring on top so it stays crisp
        // regardless of the bitmap's coverage, exactly as
        // BallView does.
        canvas.drawCircle(cx, cy, r + ringStrokePx / 2f, ringPaint)
    }
}
