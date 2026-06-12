package com.pinpin.app.ball

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * A full-screen transparent overlay that, when shown, captures every
 * touch event from the user to draw a "rubber band" selection rect.
 *
 * Lifecycle: created lazily by [BallService] and added to the
 * WindowManager when the ball arms. Removed on confirm or cancel.
 *
 * Touch model:
 *  - ACTION_DOWN: lock the origin at the touch point.
 *  - ACTION_MOVE: extend the rect; redraw.
 *  - ACTION_UP / CANCEL: hand the final rect back to the host via
 *    [Listener] and remove ourselves from the window manager.
 *
 * The view is intentionally not focusable so the underlying ball does
 * not receive stray motion events while the overlay is up.
 */
class SelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    init {
        // FrameLayout defaults to willNotDraw=true, which means
        // invalidate() does NOT trigger onDraw — the marquee
        // would never appear even though we keep painting it in
        // onDraw. Forcing this to false is the only thing that
        // makes the rect actually show up on screen.
        setWillNotDraw(false)
    }


    interface Listener {
        /** Called on ACTION_UP. Rect is in screen pixel coordinates. */
        fun onConfirm(rect: Rect)
        /** Called on CANCEL (back press or second tap). */
        fun onCancel()
    }

    var listener: Listener? = null
    private var originX = 0f
    private var originY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var armed = false

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // Samsung's Smart Select / screen-capture marquee uses a
        // purple→blue gradient stroke on a transparent interior.
        // We mimic that with a linear gradient sampled from the
        // top-left of the rect to the bottom-right; the stroke
        // colour shifts as the rect grows or moves.
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * Cached unit-square gradient — allocated once at field init and
     * reused across every onDraw call. The current rect bounds are
     * applied per-frame via [shaderMatrix] + [Shader.setLocalMatrix],
     * which is orders of magnitude cheaper than allocating a new
     * `LinearGradient` (each new shader builds a native Skia
     * `SkShader` peer; doing this 60-120 times per second during a
     * marquee drag was the main source of the mid-drag stutter the
     * user reported — the Java allocations churned the heap and
     * triggered occasional minor GCs).
     *
     * Math: with the gradient defined on the unit square `(0,0)..(1,1)`
     * and `setLocalMatrix(scale = w,h; translate = left,top)`, the
     * gradient colour at screen `(x, y)` is sampled at gradient
     * coordinate `((x-left)/w, (y-top)/h)`. At the rect's TL we get
     * `gradientStart` (0,0); at the BR we get `gradientEnd` (1,1) —
     * identical visual to the previous per-frame allocation.
     *
     * Gradient stops are pulled from a screenshot of Samsung's Smart
     * Select toolbar pill (top-left → bottom-right): a vibrant
     * blue-violet to a slightly cooler blue.
     */
    private val gradientShader = LinearGradient(
        0f, 0f, 1f, 1f,
        Color.parseColor("#6E8BFF"),
        Color.parseColor("#9A6BFF"),
        Shader.TileMode.CLAMP,
    )
    private val shaderMatrix = Matrix()
    private val cornerRadiusPx: Float by lazy { dp(24f) }
    private val minRectSidePx: Float = dp(48f)

    fun layoutParamsForScreen(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL is intentionally **omitted**: with that
            // flag set, touches that start inside the overlay would
            // *also* be dispatched to lower windows — including the
            // ball view, which would then capture the gesture and
            // drag itself around instead of letting the overlay
            // extend its rubber-band rect. The overlay is full-screen
            // and we never need outside touches, so we want to fully
            // own the input while we're up.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!armed) return

        val rawLeft = minOf(originX, currentX)
        val rawRight = maxOf(originX, currentX)
        val rawTop = minOf(originY, currentY)
        val rawBottom = maxOf(originY, currentY)
        val w = rawRight - rawLeft
        val h = rawBottom - rawTop

        // When the rect has collapsed to a point (the user is still
        // paused at the origin), Samsung's marquee shows a
        // single-point pin that grows as the user drags. We mirror
        // that by drawing a small rounded square at the origin
        // while the user hasn't moved; as soon as the user moves,
        // we switch to the full rounded-rect outline.
        val left: Float
        val right: Float
        val top: Float
        val bottom: Float
        if (w < minRectSidePx || h < minRectSidePx) {
            val side = minRectSidePx
            left = originX - side / 2f
            right = originX + side / 2f
            top = originY - side / 2f
            bottom = originY + side / 2f
        } else {
            left = rawLeft
            right = rawRight
            top = rawTop
            bottom = rawBottom
        }

        // Re-target the cached unit-square gradient at the current
        // rect via its local matrix. setLocalMatrix is a native call
        // but no allocation; the previous "new LinearGradient per
        // frame" path was the main mid-drag stutter source. See
        // [gradientShader] KDoc for the math.
        shaderMatrix.setScale(right - left, bottom - top)
        shaderMatrix.postTranslate(left, top)
        gradientShader.setLocalMatrix(shaderMatrix)
        borderPaint.shader = gradientShader

        val r = cornerRadiusPx
        // Halve the corner radius if the rect is smaller than 2*r on
        // either axis, so the rounded shape never inverts.
        val clampedR = kotlin.math.min(r, (right - left) / 2f)
            .coerceAtMost((bottom - top) / 2f)
            .coerceAtLeast(0f)

        canvas.drawRoundRect(
            left, top, right, bottom,
            clampedR, clampedR,
            borderPaint,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!armed) {
                    // First finger after the host promoted us to
                    // SELECTING: lock origin at the touch point.
                    originX = event.rawX
                    originY = event.rawY
                    currentX = originX
                    currentY = originY
                    armed = true
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed) {
                    currentX = event.rawX
                    currentY = event.rawY
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!armed) return true
                armed = false
                val left = minOf(originX, currentX).toInt()
                val top = minOf(originY, currentY).toInt()
                val right = maxOf(originX, currentX).toInt()
                val bottom = maxOf(originY, currentY).toInt()
                if (right - left < dp(16f).toInt() || bottom - top < dp(16f).toInt()) {
                    Log.d(TAG, "ACTION_UP: rect too small, cancel")
                    listener?.onCancel()
                } else {
                    val rect = Rect(left, top, right, bottom)
                    Log.d(TAG, "ACTION_UP: confirm $rect")
                    listener?.onConfirm(rect)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                armed = false
                Log.d(TAG, "ACTION_CANCEL")
                listener?.onCancel()
                return true
            }
            MotionEvent.ACTION_OUTSIDE -> {
                // Not used for selection itself but useful for
                // diagnostics — the user might have multitouched
                // outside our rect.
                return false
            }
        }
        return false
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /**
     * Skip the "wait for ACTION_DOWN" path: the host already knows the
     * origin (the point where the user paused with the ball). Pre-arm
     * so the next ACTION_MOVE / ACTION_UP drives the rubber band.
     */
    fun forceArm(originX: Float, originY: Float) {
        this.originX = originX
        this.originY = originY
        this.currentX = originX
        this.currentY = originY
        armed = true
        invalidate()
    }

    companion object {
        private const val TAG = "SelectionOverlay"
    }
}
