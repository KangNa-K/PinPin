package com.pinpin.app.ball

import androidx.core.graphics.toColorInt
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.view.WindowInsets
import android.graphics.drawable.GradientDrawable
import android.widget.ImageButton
import android.widget.ImageView
import com.pinpin.app.ball.SelectionGeometry.Handle
import com.pinpin.app.overlay.PinTheme

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
        /** Called after the user chooses an action from the selection toolbar. */
        fun onAction(rect: Rect, action: Action) = onConfirm(rect)
        /** Called on CANCEL (back press or second tap). */
        fun onCancel()
        fun onAutoCapture(x: Int, y: Int) = Unit
    }

    enum class Action {
        ANNOTATE,
        PIN,
        COPY,
        SHARE,
        SAVE,
        CARRY,
    }

    var listener: Listener? = null
    /** Retained for existing callers; selection adjustment is now always inline. */
    var precisionEnabled: Boolean = false
    var autoCaptureEnabled: Boolean = false
    private var autoTouchX = 0f
    private var autoTouchY = 0f
    private val autoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoCapture = Runnable {
        if (armed && autoCaptureEnabled) {
            armed = false
            // Clear the carried stream offset before the overlay is removed.
            touchOffsetX = 0f
            touchOffsetY = 0f
            listener?.onAutoCapture(originX.toInt(), originY.toInt())
        }
    }
    internal val isPrecisionAdjusting: Boolean get() = adjusting
    private var originX = 0f
    private var originY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var armed = false
    private var adjusting = false
    private var activeHandle = Handle.NONE
    private var movingSelection = false
    private var lastAdjustX = 0f
    private var lastAdjustY = 0f
    private var adjustmentStart: Rect? = null
    private var adjustmentTarget = Handle.NONE
    private var selectionReady = false
    private var panelAtTop = false
    private var ignoreAdjustmentTail = false
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private val screenLocation = IntArray(2)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
    }
    private val cursorOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = dp(1f)
    }
    private val dimPaint = Paint().apply { color = Color.argb(72, 0, 0, 0) }
    private val clearSelectionPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // A thin blue-green outline with stronger corner arcs.
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
     * The reference selection transitions from blue to green.
     */
    private val gradientShader = LinearGradient(
        0f, 0f, 1f, 1f,
        "#78BFFF".toColorInt(),
        "#7DE6A4".toColorInt(),
        Shader.TileMode.CLAMP,
    )
    private val shaderMatrix = Matrix()
    private val cornerRadiusPx: Float by lazy { dp(24f) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val dimensions = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private val adjustmentPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(25, 25, 30))
        visibility = View.GONE
        addView(dimensions, LinearLayout.LayoutParams(-1, dp(32f).toInt()))
    }
    private val actionToolbar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val theme = PinTheme.of(context)
        setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        background = GradientDrawable().apply {
            setColor(theme.surfaceFill)
            cornerRadius = dp(24f)
            setStroke(dp(1f).toInt(), theme.surfaceStroke)
        }
        visibility = View.GONE
        elevation = 0f
        clipToOutline = true
    }

    init {
        fun button(row: LinearLayout, label: String, description: String, action: () -> Unit) {
            row.addView(Button(context).apply {
                text = label
                contentDescription = description
                tooltipText = description
                textSize = 18f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(48f).toInt(), 1f))
        }
        val directions = LinearLayout(context)
        button(directions, "←", "向左调整 1 像素") { nudge(-1, 0) }
        button(directions, "↑", "向上调整 1 像素") { nudge(0, -1) }
        button(directions, "↓", "向下调整 1 像素") { nudge(0, 1) }
        button(directions, "→", "向右调整 1 像素") { nudge(1, 0) }
        adjustmentPanel.addView(directions)
        val actions = LinearLayout(context)
        button(actions, "取消", "取消截图") { finishAdjustment(false) }
        button(actions, "完成", "确认截图范围") { finishAdjustment(true) }
        adjustmentPanel.addView(actions)
        addView(adjustmentPanel, LayoutParams(-1, -2, Gravity.BOTTOM))
        val actionIcons = listOf(
            Action.ANNOTATE to (com.pinpin.app.R.drawable.ic_action_annotate to "标注"),
            Action.CARRY to (com.pinpin.app.R.drawable.ic_action_carry to "吸入悬浮球"),
            Action.PIN to (com.pinpin.app.R.drawable.ic_pin_push_pin to "钉图"),
            Action.COPY to (com.pinpin.app.R.drawable.ic_action_copy to "复制"),
            Action.SHARE to (com.pinpin.app.R.drawable.ic_action_share to "分享"),
            Action.SAVE to (com.pinpin.app.R.drawable.ic_action_save to "保存到相册"),
        )
        actionIcons.forEach { (action, iconAndDescription) ->
            val (icon, description) = iconAndDescription
            actionToolbar.addView(ImageButton(context).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(PinTheme.of(context).iconColor)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(31, 0, 0, 0))
                    })
                    addState(intArrayOf(), GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                    })
                }
                contentDescription = description
                tooltipText = description
                setPadding(dp(11f).toInt(), dp(11f).toInt(), dp(11f).toInt(), dp(11f).toInt())
                setOnClickListener { finishAction(action) }
            }, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()))
        }
        addView(actionToolbar, LayoutParams(-2, -2))
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            adjustmentPanel.setPadding(bars.left, if (panelAtTop) bars.top else 0,
                bars.right, if (panelAtTop) 0 else bars.bottom)
            insets
        }
    }

    fun layoutParamsForScreen(
        w: Int,
        h: Int,
        windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    ): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w,
            h,
            windowType,
            // NOT_TOUCH_MODAL is intentionally **omitted**: with that
            // flag set, touches that start inside the overlay would
            // *also* be dispatched to lower windows — including the
            // ball view, which would then capture the gesture and
            // drag itself around instead of letting the overlay
            // extend its rubber-band rect. The overlay is full-screen
            // and we never need outside touches, so we want to fully
            // own the input while we're up.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                // Keep drawing and capture coordinates anchored to the display.
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            screenWidth = w
            screenHeight = h
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!armed) return
        getLocationOnScreen(screenLocation)
        canvas.save()
        canvas.translate(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
        val rawLeft = minOf(originX, currentX)
        val rawRight = maxOf(originX, currentX)
        val rawTop = minOf(originY, currentY)
        val rawBottom = maxOf(originY, currentY)
        val w = rawRight - rawLeft
        val h = rawBottom - rawTop

        // The marquee anchors at the origin (the armed point = the
        // ball's center) and grows continuously as the finger moves.
        // We deliberately do NOT draw a minimum-size square centered
        // on the origin. The previous code did that: while the drag
        // was smaller than minRectSidePx it drew a centered 48dp square,
        // then silently switched to an origin-anchored rectangle once
        // the drag exceeded the threshold. That centered→anchored
        // transition shifted the marquee's center by ~24dp in one
        // frame, which the user saw as the purple rect "jumping" the
        // instant they started dragging. It also made the square read
        // as a purple border around the ball during the armed pause.
        //
        // With nothing drawn until the finger actually moves, the
        // armed point is conveyed by the ball itself, and the marquee
        // grows smoothly out of that point — no pop, no jump.
        if (w <= 0f || h <= 0f) {
            val cursorSize = dp(7f)
            canvas.drawLine(currentX - cursorSize, currentY, currentX + cursorSize, currentY, cursorOutlinePaint)
            canvas.drawLine(currentX, currentY - cursorSize, currentX, currentY + cursorSize, cursorOutlinePaint)
            canvas.drawLine(currentX - cursorSize, currentY, currentX + cursorSize, currentY, cursorPaint)
            canvas.drawLine(currentX, currentY - cursorSize, currentX, currentY + cursorSize, cursorPaint)
            canvas.restore()
            return
        }
        val left = rawLeft
        val right = rawRight
        val top = rawTop
        val bottom = rawBottom

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

        val layer = canvas.saveLayer(null, null)
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), dimPaint)
        canvas.drawRoundRect(
            left, top, right, bottom,
            clampedR, clampedR,
            clearSelectionPaint,
        )
        canvas.drawRoundRect(
            left, top, right, bottom,
            clampedR, clampedR,
            borderPaint,
        )
        if (adjusting) drawAdjustmentControls(canvas, left, top, right, bottom)
        if (!selectionReady) {
            val cursorSize = dp(7f)
            canvas.drawLine(currentX - cursorSize, currentY, currentX + cursorSize, currentY, cursorOutlinePaint)
            canvas.drawLine(currentX, currentY - cursorSize, currentX, currentY + cursorSize, cursorOutlinePaint)
            canvas.drawLine(currentX - cursorSize, currentY, currentX + cursorSize, currentY, cursorPaint)
            canvas.drawLine(currentX, currentY - cursorSize, currentX, currentY + cursorSize, cursorPaint)
        }
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    private fun drawAdjustmentControls(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val radius = minOf(cornerRadiusPx, (right - left) / 2, (bottom - top) / 2)
        val oldWidth = borderPaint.strokeWidth
        borderPaint.strokeWidth = dp(4f)
        canvas.drawArc(left, top, left + radius * 2, top + radius * 2, 180f, 90f, false, borderPaint)
        canvas.drawArc(right - radius * 2, top, right, top + radius * 2, 270f, 90f, false, borderPaint)
        canvas.drawArc(left, bottom - radius * 2, left + radius * 2, bottom, 90f, 90f, false, borderPaint)
        canvas.drawArc(right - radius * 2, bottom - radius * 2, right, bottom, 0f, 90f, false, borderPaint)
        borderPaint.strokeWidth = oldWidth
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (adjusting && event.actionMasked == MotionEvent.ACTION_DOWN) ignoreAdjustmentTail = false
        if (adjusting && ignoreAdjustmentTail) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (adjusting) {
                    val x = event.rawX
                    val y = event.rawY
                    activeHandle = nearestHandle(x, y)
                    movingSelection = activeHandle == Handle.NONE && rect().contains(x.toInt(), y.toInt())
                    if (activeHandle == Handle.NONE && !movingSelection && selectionReady) {
                        selectionReady = false
                        adjusting = false
                        armed = false
                        actionToolbar.visibility = View.GONE
                        listener?.onCancel()
                        return true
                    }
                    if (activeHandle != Handle.NONE || movingSelection) adjustmentTarget = activeHandle
                    lastAdjustX = x
                    lastAdjustY = y
                    adjustmentStart = rect()
                    return true
                }
                if (!armed) {
                    // First finger after the host promoted us to
                    // SELECTING: lock origin at the touch point.
                    originX = event.rawX
                    originY = event.rawY
                    touchOffsetX = 0f
                    touchOffsetY = 0f
                    currentX = originX
                    currentY = originY
                    armed = true
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.hypot(event.rawX - autoTouchX, event.rawY - autoTouchY) > dp(12f)) autoHandler.removeCallbacks(autoCapture)
                if (armed) {
                    if (adjusting) {
                        adjust(event.rawX, event.rawY)
                        if (selectionReady) positionActionToolbar(rect())
                        postInvalidateOnAnimation()
                        return true
                    }
                    updateCursor(event)
                    postInvalidateOnAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                autoHandler.removeCallbacks(autoCapture)
                if (adjusting) {
                    adjust(event.rawX, event.rawY)
                    activeHandle = Handle.NONE
                    movingSelection = false
                    adjustmentStart = null
                    if (selectionReady) positionActionToolbar(rect())
                    invalidate()
                    return true
                }
                if (!armed) return true
                updateCursor(event)
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
                    showActionToolbar(rect)
                    Log.d(TAG, "ACTION_UP: selection ready $rect")
                }
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                autoHandler.removeCallbacks(autoCapture)
                if (!armed && !adjusting) return true
                if (adjusting) {
                    adjustmentStart?.let(::setRect)
                    activeHandle = Handle.NONE
                    movingSelection = false
                    adjustmentStart = null
                    ignoreAdjustmentTail = true
                    invalidate()
                    return true
                }
                armed = false
                adjusting = false
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
    fun forceArm(originX: Float, originY: Float, touchX: Float = originX, touchY: Float = originY) {
        autoHandler.removeCallbacks(autoCapture)
        autoTouchX = touchX
        autoTouchY = touchY
        if (autoCaptureEnabled) autoHandler.postDelayed(autoCapture, 450L)
        touchOffsetX = originX - touchX
        touchOffsetY = originY - touchY
        this.originX = clampX(originX)
        this.originY = clampY(originY)
        this.currentX = this.originX
        this.currentY = this.originY
        armed = true
        adjusting = false
        selectionReady = false
        adjustmentPanel.visibility = View.GONE
        actionToolbar.visibility = View.GONE
        invalidate()
    }

    private fun updateCursor(event: MotionEvent) {
        currentX = clampX(event.rawX + touchOffsetX)
        currentY = clampY(event.rawY + touchOffsetY)
    }

    override fun onDetachedFromWindow() {
        autoHandler.removeCallbacks(autoCapture)
        super.onDetachedFromWindow()
    }

    private fun rect(): Rect = Rect(
        minOf(originX, currentX).toInt(), minOf(originY, currentY).toInt(),
        maxOf(originX, currentX).toInt(), maxOf(originY, currentY).toInt(),
    )

    private fun nearestHandle(x: Float, y: Float): Handle {
        val r = rect()
        return SelectionGeometry.nearestHandle(x, y, r.left, r.top, r.right, r.bottom, threshold = dp(32f))
    }

    private fun adjust(x: Float, y: Float) {
        val dx = x - lastAdjustX
        val dy = y - lastAdjustY
        if (activeHandle == Handle.NONE && !movingSelection) return
        // While adjusting, origin is the top-left and current the bottom-right.
        val bounds = SelectionGeometry.Bounds(originX, originY, currentX, currentY)
        val next = if (movingSelection) {
            SelectionGeometry.move(bounds, dx, dy, screenWidth, screenHeight)
        } else {
            SelectionGeometry.resize(bounds, activeHandle, dx, dy, screenWidth, screenHeight, minSize = dp(16f))
        }
        originX = next.left
        originY = next.top
        currentX = next.right
        currentY = next.bottom
        lastAdjustX = x
        lastAdjustY = y
        updateDimensions()
    }

    private fun setRect(r: Rect) {
        originX = r.left.toFloat()
        originY = r.top.toFloat()
        currentX = r.right.toFloat()
        currentY = r.bottom.toFloat()
        updateDimensions()
    }

    private fun updateDimensions() {
        val r = rect()
        val target = when (adjustmentTarget) {
            Handle.NONE -> "移动"
            Handle.LEFT -> "左边"
            Handle.RIGHT -> "右边"
            Handle.TOP -> "上边"
            Handle.BOTTOM -> "下边"
            Handle.TOP_LEFT -> "左上角"
            Handle.TOP_RIGHT -> "右上角"
            Handle.BOTTOM_LEFT -> "左下角"
            Handle.BOTTOM_RIGHT -> "右下角"
        }
        dimensions.text = "$target · ${r.width()} × ${r.height()} px"
    }

    private fun nudge(dx: Int, dy: Int) {
        if (!adjusting) return
        activeHandle = adjustmentTarget
        movingSelection = activeHandle == Handle.NONE
        lastAdjustX = 0f
        lastAdjustY = 0f
        adjust(dx.toFloat(), dy.toFloat())
        activeHandle = Handle.NONE
        movingSelection = false
        invalidate()
    }

    private fun finishAdjustment(confirm: Boolean) {
        if (!adjusting) return
        adjusting = false
        armed = false
        adjustmentPanel.visibility = View.GONE
        if (confirm) {
            showActionToolbar(rect())
        } else {
            listener?.onCancel()
        }
    }

    private fun showActionToolbar(selection: Rect) {
        adjusting = true
        armed = true
        selectionReady = true
        setRect(selection)
        adjustmentPanel.visibility = View.GONE
        actionToolbar.visibility = View.VISIBLE
        positionActionToolbar(selection)
        actionToolbar.bringToFront()
        updateDimensions()
        invalidate()
    }

    private fun positionActionToolbar(selection: Rect) {
        getLocationOnScreen(screenLocation)
        val toolbar = actionToolbar.layoutParams as LayoutParams
        val margin = dp(12f).toInt()
        val toolbarWidth = dp(actionToolbar.childCount * 40f + 8f).toInt()
        val toolbarHeight = dp(40f).toInt()
        val position = SelectionGeometry.toolbarPosition(
            selectionLeft = selection.left - screenLocation[0],
            selectionTop = selection.top - screenLocation[1],
            selectionBottom = selection.bottom - screenLocation[1],
            selectionWidth = selection.width(),
            toolbarWidth = toolbarWidth,
            toolbarHeight = toolbarHeight,
            margin = margin,
            windowWidth = width.takeIf { it > 0 } ?: screenWidth,
            windowHeight = height.takeIf { it > 0 } ?: screenHeight,
        )
        toolbar.width = toolbarWidth
        toolbar.height = toolbarHeight
        toolbar.leftMargin = position.left
        toolbar.topMargin = position.top
        actionToolbar.layoutParams = toolbar
    }

    private fun finishAction(action: Action) {
        if (!selectionReady) return
        val selected = rect()
        selectionReady = false
        adjusting = false
        armed = false
        actionToolbar.visibility = View.GONE
        listener?.onAction(selected, action)
    }

    private fun clampX(x: Float): Float = x.coerceIn(0f, (screenWidth.takeIf { it > 0 } ?: width).toFloat())
    private fun clampY(y: Float): Float = y.coerceIn(0f, (screenHeight.takeIf { it > 0 } ?: height).toFloat())

    companion object {
        private const val TAG = "SelectionOverlay"
    }
}
