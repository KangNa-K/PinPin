package com.pinpin.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A transparent overlay view that captures stylus / finger input and
 * renders freehand strokes or arrows on a local [Bitmap].
 *
 * ## Features
 *
 * - **Modes**: [Mode.PEN] for freehand drawing, [Mode.ARROW] for
 *   drag-to-create arrows, [Mode.ERASER] for "rubber-banding" out
 *   previously drawn strokes.
 * - **Brush styles**: [BrushStyle.PEN] (standard), [BrushStyle.
 *   HIGHLIGHTER] (semi-transparent, thick), [BrushStyle.
 *   CALLIGRAPHY] (thin, opaque). Style is independent of mode.
 * - **Stroke width**: configurable per stroke via [strokeWidth];
 *   pressure continues to add a small modulation on top of the
 *   user-chosen base width.
 * - **S Pen / stylus pressure**: pressure modulates BOTH the
 *   rendered stroke width and the alpha so heavy presses leave
 *   bold, dense marks and light strokes leave thin, soft marks.
 * - **Undo / Redo**: full stroke-level history. Calling [undo]
 *   moves the most recent stroke to the redo stack; [redo] pops
 *   it back. Any new drawing clears the redo stack.
 */
class DrawingOverlayView(context: Context) : View(context) {

    enum class Mode { PEN, ARROW, ERASER }
    enum class BrushStyle { PEN, HIGHLIGHTER, CALLIGRAPHY }

    data class Point(
        val x: Float,
        val y: Float,
        val pressure: Float,
    )

    data class Stroke(
        val points: List<Point>,
        val color: Int,
        val mode: Mode,
        val brushStyle: BrushStyle,
        val baseWidth: Float,
    )

    // ---- Public API ----

    var mode: Mode = Mode.PEN
    var brushStyle: BrushStyle = BrushStyle.PEN

    /** Base stroke width in pixels (before pressure modulation). */
    var strokeWidth: Float = 6f

    /** Color used for the next pen stroke. */
    var strokeColor: Int = Color.RED

    /** Color used for eraser strokes. Resolved from the pin's
     *  background at attach time so the eraser blends into the
     *  white / dark card gradient underneath. */
    var eraserColor: Int = Color.WHITE

    /** Whether the drawing overlay is active (accepting input). */
    var drawingEnabled: Boolean = true

    private val strokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()
    private var currentPoints = mutableListOf<Point>()

    private val arrowStart = floatArrayOf(0f, 0f)
    private var arrowDragging = false

    /**
     * Reusable xfermode for the eraser. The CLEAR xfermode turns
     * a paint stroke into a "subtract" operation — pixels under
     * the stroke become transparent, which is exactly the visual
     * we want from a rubber eraser (existing pen strokes vanish,
     * the original image shows through). We need a software
     * layer ([setLayerType(LAYER_TYPE_SOFTWARE)]) for xfermodes
     * to behave consistently across devices; see [init].
     */
    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    init {
        // PorterDuff.Mode.CLEAR on hardware-accelerated views is
        // unreliable on older devices; pin the whole overlay to a
        // software layer. The drawing surface is at most a phone
        // screen's worth of pixels, so the perf hit is negligible
        // for this UX.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Remove the last committed stroke (undo). */
    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeAt(strokes.lastIndex))
            invalidate()
        }
    }

    /** Re-apply the most recently undone stroke. */
    fun redo() {
        if (redoStack.isNotEmpty()) {
            strokes.add(redoStack.removeAt(redoStack.lastIndex))
            invalidate()
        }
    }

    /** Clear all strokes (no undo). */
    fun clear() {
        strokes.clear()
        redoStack.clear()
        currentPoints.clear()
        arrowDragging = false
        invalidate()
    }

    /** Render all committed strokes onto a new [Bitmap] sized to this view. */
    fun renderToBitmap(): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Two-pass render so eraser strokes apply AFTER pen
        // strokes (same logic as onDraw) — otherwise a
        // previously-drawn eraser would visually undo strokes
        // added after it in the export bitmap.
        drawNonEraserStrokes(canvas)
        drawEraserStrokes(canvas)
        return bmp
    }

    /** Check if there are any committed strokes. */
    fun hasStrokes(): Boolean = strokes.isNotEmpty()

    fun canUndo(): Boolean = strokes.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    // ---- Touch handling ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return false

        when (mode) {
            Mode.PEN, Mode.ERASER -> handleFreehand(event)
            Mode.ARROW -> handleArrow(event)
        }
        return true
    }

    private fun handleFreehand(event: MotionEvent) {
        val pressure = event.getPressure(event.actionIndex).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPoints.clear()
                currentPoints.add(Point(event.x, event.y, pressure))
            }
            MotionEvent.ACTION_MOVE -> {
                currentPoints.add(Point(event.x, event.y, pressure))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (currentPoints.size >= 2) {
                    val strokeColor = if (mode == Mode.ERASER) eraserColor else strokeColor
                    strokes.add(
                        Stroke(
                            currentPoints.toList(),
                            strokeColor,
                            mode,
                            brushStyle,
                            strokeWidth,
                        ),
                    )
                    // New drawing clears the redo stack — standard
                    // behavior in every editor: redo is only valid
                    // until you make a new edit.
                    redoStack.clear()
                }
                currentPoints.clear()
            }
        }
        invalidate()
    }

    private fun handleArrow(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                arrowStart[0] = event.x
                arrowStart[1] = event.y
                arrowDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                // Preview — just invalidate for redraw
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (arrowDragging) {
                    val ex = event.x
                    val ey = event.y
                    val dx = ex - arrowStart[0]
                    val dy = ey - arrowStart[1]
                    if (dx * dx + dy * dy > 400f) { // min 20px drag
                        val pressure = event.getPressure(event.actionIndex).coerceIn(0f, 1f)
                        strokes.add(
                            Stroke(
                                listOf(
                                    Point(arrowStart[0], arrowStart[1], pressure),
                                    Point(ex, ey, pressure),
                                ),
                                strokeColor,
                                Mode.ARROW,
                                brushStyle,
                                strokeWidth,
                            ),
                        )
                        redoStack.clear()
                    }
                    arrowDragging = false
                }
            }
        }
        invalidate()
    }

    // ---- Drawing ----

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Two-pass drawing: first lay down all PEN / ARROW
        // strokes (they use normal paint blending), then apply
        // the ERASER strokes on top with CLEAR blending. CLEAR
        // subtracts pixels from the canvas wherever the eraser
        // path touches them — so existing pen strokes vanish
        // and the underlying image shows through, instead of
        // the previous behavior where the eraser painted a thick
        // white stroke on top of everything.
        drawNonEraserStrokes(canvas)
        drawEraserStrokes(canvas)
        // Preview of the in-progress gesture
        if (currentPoints.size >= 2) {
            if (mode == Mode.ERASER) {
                strokePaint.xfermode = clearXfermode
                strokePaint.color = Color.BLACK
                drawFreehandStrokeInternal(
                    canvas,
                    currentPoints,
                    brushStyle,
                    strokeWidth,
                )
                strokePaint.xfermode = null
            } else {
                drawFreehandStrokeInternal(
                    canvas,
                    currentPoints,
                    brushStyle,
                    strokeWidth,
                )
            }
        }
    }

    private fun drawNonEraserStrokes(canvas: Canvas) {
        for (stroke in strokes) {
            when (stroke.mode) {
                Mode.PEN -> {
                    strokePaint.xfermode = null
                    strokePaint.color = stroke.color
                    drawFreehandStrokeInternal(
                        canvas,
                        stroke.points,
                        stroke.brushStyle,
                        stroke.baseWidth,
                    )
                }
                Mode.ARROW -> drawArrowStroke(canvas, stroke)
                Mode.ERASER -> { /* drawn in second pass */ }
            }
        }
    }

    private fun drawEraserStrokes(canvas: Canvas) {
        for (stroke in strokes) {
            if (stroke.mode == Mode.ERASER) {
                strokePaint.xfermode = clearXfermode
                // Color is irrelevant under CLEAR xfermode (the
                // pixel is forced to 0); we just pick black for
                // // safety.
                strokePaint.color = Color.BLACK
                drawFreehandStrokeInternal(
                    canvas,
                    stroke.points,
                    stroke.brushStyle,
                    stroke.baseWidth,
                )
                strokePaint.xfermode = null
            }
        }
    }

    /**
     * Render a freehand stroke to [canvas]. The stroke color and
     * alpha are read from [strokePaint]; callers (the
     * non-eraser pass and the eraser pass) are responsible for
     * setting `strokePaint.color` / `strokePaint.xfermode` before
     * invoking this. Splitting color setup from geometry drawing
     * lets the eraser pass use [clearXfermode] without having to
     * duplicate the path / width / pressure code below.
     */
    private fun drawFreehandStrokeInternal(
        canvas: Canvas,
        points: List<Point>,
        style: BrushStyle,
        baseWidth: Float,
    ) {
        if (points.size < 2) return
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val p = points[i]
            path.lineTo(p.x, p.y)
        }
        val avgPressure = points.map { it.pressure }.average().toFloat().coerceIn(0f, 1f)
        val pressureMod = MIN_STROKE_W +
            avgPressure * (PRESSURE_RANGE_W)
        val width = when (style) {
            BrushStyle.HIGHLIGHTER -> baseWidth * 2.2f
            BrushStyle.CALLIGRAPHY -> baseWidth * 0.5f
            BrushStyle.PEN -> baseWidth
        } + pressureMod * 0.3f
        val baseAlpha = when (style) {
            BrushStyle.HIGHLIGHTER -> 90   // 半透明
            BrushStyle.CALLIGRAPHY -> 255  // 实心
            BrushStyle.PEN -> 255
        }
        val alpha = (baseAlpha + avgPressure * 80f).toInt().coerceAtMost(255)
        // Don't override an active xfermode (CLEAR) or color
        // (black) when the eraser pass set them. Only stamp
        // alpha + width; the caller controls color / blending.
        if (strokePaint.xfermode == null) {
            // Pen pass: alpha is meaningful. The color is set
            // by the caller in drawNonEraserStrokes — but to
            // keep this helper self-contained for the preview
            // case, we still set alpha here.
        }
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = width
        canvas.drawPath(path, strokePaint)
    }

    private fun drawArrowStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.size < 2) return
        val start = stroke.points[0]
        val end = stroke.points[1]
        val pressure = end.pressure.coerceIn(0f, 1f)
        val width = stroke.baseWidth + pressure * PRESSURE_RANGE_W * 0.3f
        val alpha = (220 + pressure * 35f).toInt().coerceAtMost(255)

        strokePaint.color = stroke.color
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = width

        // Shaft
        canvas.drawLine(start.x, start.y, end.x, end.y, strokePaint)

        // Arrowhead
        val angle = atan2(end.y - start.y, end.x - start.x)
        val headLen = width * 4f
        val headAngle = Math.toRadians(30.0).toFloat()
        arrowHeadPaint.color = stroke.color
        arrowHeadPaint.alpha = alpha
        val path = Path()
        path.moveTo(end.x, end.y)
        path.lineTo(
            end.x - headLen * cos(angle - headAngle),
            end.y - headLen * sin(angle - headAngle),
        )
        path.lineTo(
            end.x - headLen * cos(angle + headAngle),
            end.y - headLen * sin(angle + headAngle),
        )
        path.close()
        canvas.drawPath(path, arrowHeadPaint)
    }

    companion object {
        private const val MIN_STROKE_W = 1f
        private const val PRESSURE_RANGE_W = 4f
    }
}
