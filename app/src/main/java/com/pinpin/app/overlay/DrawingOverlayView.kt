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
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

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

    /**
     * Whether freehand strokes are auto-recognised as
     * straight lines, circles, rectangles or triangles
     * on ACTION_UP. The original hand-drawn points are
     * replaced with the regularised geometric shape;
     * if the gesture does not match any of the four
     * target shapes closely enough, the original
     * hand-drawn stroke is kept unchanged. Defaulted
     * to ON — every existing user of the annotation
     * feature gets shape-cleanup for free; if a user
     * really wants to keep a wobbly doodle they can
     * disable this from the toolbar (future work).
     */
    var shapeRecognitionEnabled: Boolean = true

    /**
     * The four geometric primitives the auto-recogniser
     * knows how to detect. [NONE] is the "leave the
     * hand-drawn stroke alone" outcome — i.e. the gesture
     * did not match any of the four closely enough.
     */
    private enum class RecognizedShape { NONE, LINE, CIRCLE, RECTANGLE, TRIANGLE }

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
        // Single chronological pass — matches the onDraw
        // rewrite above so the exported PNG is bit-for-bit
        // consistent with what the user saw on the overlay.
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
                Mode.ERASER -> {
                    strokePaint.xfermode = clearXfermode
                    strokePaint.color = Color.BLACK
                    strokePaint.alpha = 255
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
                    // For PEN strokes, run the auto-shape
                    // recogniser. Eraser strokes are kept as
                    // the raw input (an eraser is always a
                    // freehand "rub this area out" gesture;
                    // regularising it would just shrink the
                    // hitbox). ARROW strokes come from a
                    // straight drag between two points and
                    // are already a primitive — skip them.
                    val finalPoints = when {
                        mode == Mode.PEN && shapeRecognitionEnabled -> {
                            recognizeAndRegularize(currentPoints)
                        }
                        else -> currentPoints.toList()
                    }
                    strokes.add(
                        Stroke(
                            finalPoints,
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
        // SINGLE chronological pass. The previous two-pass
        // design (draw all pen strokes, then run the eraser
        // pass) wiped out strokes that the user drew AFTER
        // an eraser because the eraser pass cleared
        // every pixel in its path regardless of when the
        // overlaid pen stroke was actually added. With a
        // single pass, each eraser only affects the canvas
        // state at the moment it executes — i.e. it clears
        // whatever was drawn BEFORE it, but leaves everything
        // drawn afterwards intact. The user's workflow
        // "erase here, then redraw in the same place" now
        // works as expected.
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
                Mode.ERASER -> {
                    // CLEAR mode wipes the canvas in this
                    // path; setting alpha to 255 is required
                    // because some Android devices honour
                    // paint.alpha inside CLEAR compositing
                    // and only partially clear otherwise.
                    strokePaint.xfermode = clearXfermode
                    strokePaint.color = Color.BLACK
                    strokePaint.alpha = 255
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
        // Preview of the in-progress gesture. Same
        // explicit color/xfermode reset as before, so the
        // first previewed pen stroke isn't black from the
        // shared paint's default.
        if (currentPoints.size >= 2) {
            if (mode == Mode.ERASER) {
                strokePaint.xfermode = clearXfermode
                strokePaint.color = Color.BLACK
                strokePaint.alpha = 255
                drawFreehandStrokeInternal(
                    canvas,
                    currentPoints,
                    brushStyle,
                    strokeWidth,
                )
                strokePaint.xfermode = null
            } else {
                strokePaint.xfermode = null
                strokePaint.color = strokeColor
                drawFreehandStrokeInternal(
                    canvas,
                    currentPoints,
                    brushStyle,
                    strokeWidth,
                )
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


    // ---- Shape recognition & regularisation ----
    //
    // After every pen stroke finishes (ACTION_UP) we run a
    // tiny classifier over the captured points. Intentionally
    // cheap: a couple of geometric ratios. Handles the four
    // primitives a user is most likely to want from an
    // annotation tool. False positives are worse than missed
    // hits, so thresholds are permissive — a slightly wobbly
    // circle still gets detected.
    private fun recognizeAndRegularize(points: List<Point>): List<Point> {
        if (points.size < 3) return points.toList()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val bboxW = (maxX - minX).coerceAtLeast(1f)
        val bboxH = (maxY - minY).coerceAtLeast(1f)
        val bboxDiag = hypot(bboxW, bboxH)
        if (bboxDiag < 36f) return points.toList()

        val start = points.first()
        val end = points.last()
        val closeGap = hypot(end.x - start.x, end.y - start.y)
        val isClosed = closeGap < 0.18f * bboxDiag

        var pathLen = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLen += hypot(dx, dy)
        }
        val straightDist = hypot(end.x - start.x, end.y - start.y)
        val straightness = if (pathLen > 0f) straightDist / pathLen else 0f

        if (!isClosed) {
            return if (straightness > 0.95f) listOf(start, end) else points.toList()
        }

        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = bboxW / 2f
        val ry = bboxH / 2f
        val distances = DoubleArray(points.size) { i ->
            val px = (points[i].x - cx) / rx
            val py = (points[i].y - cy) / ry
            hypot(px.toFloat(), py.toFloat()).toDouble()
        }
        val avg = distances.average()
        val variance = distances.map { (it - avg) * (it - avg) }.average()
        val stddev = sqrt(variance)
        val circularity = if (avg > 0.0) 1.0 - stddev / avg else 0.0
        if (circularity > 0.85) {
            val radius = (rx + ry) / 2f
            return generateCirclePoints(cx, cy, radius, 64, avgPressure(points))
        }

        val epsilon = 0.04f * bboxDiag
        val simplified = rdpSimplify(points, epsilon)
        val avgP = avgPressure(points)
        return when (simplified.size) {
            3 -> listOf(
                Point(simplified[0].x, simplified[0].y, avgP),
                Point(simplified[1].x, simplified[1].y, avgP),
                Point(simplified[2].x, simplified[2].y, avgP),
                Point(simplified[0].x, simplified[0].y, avgP),
            )
            4 -> {
                val sorted = sortQuadCorners(simplified)
                (sorted + sorted.first()).map { Point(it.x, it.y, avgP) }
            }
            else -> points.toList()
        }
    }

    private fun rdpSimplify(points: List<Point>, epsilon: Float): List<Point> {
        if (points.size < 3) return points.toList()
        val n = points.size
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        rdpRecursive(points, epsilon, 0, n - 1, keep)
        val out = ArrayList<Point>(n)
        for (i in 0 until n) if (keep[i]) out.add(points[i])
        return out
    }

    private fun rdpRecursive(
        points: List<Point>,
        epsilon: Float,
        first: Int,
        last: Int,
        keep: BooleanArray,
    ) {
        if (last <= first + 1) return
        val a = points[first]
        val b = points[last]
        var maxDist = 0f
        var maxIdx = first
        for (i in (first + 1) until last) {
            val d = perpendicularDistance(points[i], a, b)
            if (d > maxDist) { maxDist = d; maxIdx = i }
        }
        if (maxDist > epsilon) {
            keep[maxIdx] = true
            rdpRecursive(points, epsilon, first, maxIdx, keep)
            rdpRecursive(points, epsilon, maxIdx, last, keep)
        }
    }

    private fun perpendicularDistance(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot(p.x - projX, p.y - projY)
    }

    private fun sortQuadCorners(corners: List<Point>): List<Point> {
        if (corners.size != 4) return corners
        var tl = 0
        var bestSum = Float.MAX_VALUE
        for (i in 0..3) {
            val s = corners[i].x + corners[i].y
            if (s < bestSum) { bestSum = s; tl = i }
        }
        val ordered = ArrayList<Point>(4)
        ordered.add(corners[tl])
        val cx = (corners.sumOf { it.x.toDouble() } / 4.0).toFloat()
        val cy = (corners.sumOf { it.y.toDouble() } / 4.0).toFloat()
        val rest = (0..3).filter { it != tl }.map { it to corners[it] }
        val byAngle = rest.sortedBy { atan2(it.second.y - cy, it.second.x - cx) }
        for ((_, p) in byAngle) ordered.add(p)
        if (!isConvexQuad(ordered)) {
            val minX = corners.minOf { it.x }
            val maxX = corners.maxOf { it.x }
            val minY = corners.minOf { it.y }
            val maxY = corners.maxOf { it.y }
            return listOf(
                Point(minX, minY, 0f),
                Point(maxX, minY, 0f),
                Point(maxX, maxY, 0f),
                Point(minX, maxY, 0f),
            )
        }
        return ordered
    }

    private fun isConvexQuad(ordered: List<Point>): Boolean {
        var sign = 0f
        for (i in 0..3) {
            val a = ordered[i]
            val b = ordered[(i + 1) % 4]
            val c = ordered[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross != 0f) {
                if (sign == 0f) sign = cross
                else if (sign * cross < 0f) return false
            }
        }
        return true
    }

    private fun avgPressure(points: List<Point>): Float =
        if (points.isEmpty()) 0.5f
        else points.map { it.pressure }.average().toFloat().coerceIn(0f, 1f)

    private fun generateCirclePoints(
        cx: Float,
        cy: Float,
        r: Float,
        count: Int,
        pressure: Float,
    ): List<Point> {
        val out = ArrayList<Point>(count + 1)
        for (i in 0..count) {
            val theta = 2.0 * Math.PI * i / count
            out.add(
                Point(
                    cx + r * cos(theta).toFloat(),
                    cy + r * sin(theta).toFloat(),
                    pressure,
                ),
            )
        }
        return out
    }

    companion object {
        private const val MIN_STROKE_W = 1f
        private const val PRESSURE_RANGE_W = 4f
    }
}
