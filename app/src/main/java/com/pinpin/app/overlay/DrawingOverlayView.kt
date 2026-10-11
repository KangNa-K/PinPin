package com.pinpin.app.overlay

import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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
 * - **Shape selection**: recognized shapes (circle, triangle,
 *   rectangle, line) can be tapped to select, then dragged to
 *   move or corner-dragged to scale.
 */
class DrawingOverlayView(context: Context) : View(context) {

    enum class Mode { PEN, ARROW, ERASER }
    enum class BrushStyle { PEN, HIGHLIGHTER, CALLIGRAPHY }

    /**
     * The geometric primitives the auto-recogniser can detect.
     * [NONE] means the stroke was not recognized as a shape
     * and is kept as freehand.
     */
    enum class RecognizedShape { NONE, LINE, CIRCLE, ELLIPSE, RECTANGLE, TRIANGLE }

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
        /** Which geometric shape this stroke was recognized as,
         *  or [RecognizedShape.NONE] for freehand. */
        val recognizedShape: RecognizedShape = RecognizedShape.NONE,
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
     * straight lines, circles, ellipses, triangles or rectangles
     * on ACTION_UP.
     */
    var shapeRecognitionEnabled: Boolean = true

    private val strokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()
    private var currentPoints = mutableListOf<Point>()
    private val preparedStrokes = mutableListOf<PreparedStroke>()
    private var committedLayer: Bitmap? = null
    private var rasterizedStrokeCount = 0
    private val previewPath = Path()

    private val arrowStart = floatArrayOf(0f, 0f)
    private var arrowDragging = false

    // ---- Shape selection state ----

    /** Index of the currently selected shape stroke, or -1. */
    private var selectedStrokeIndex: Int = -1

    /** Drag state for moving a selected shape. */
    private var shapeDragState = SHAPE_DRAG_NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    /** Snapshot of the selected stroke's points at drag start. */
    private var dragStartPoints: List<Point> = emptyList()
    /** For rotation: angle from center to drag-start position. */
    private var dragStartAngle = 0f
    /** For scale: which corner handle is being dragged (0-3), or -1. */
    private var dragCornerIdx = -1
    private var pendingShapeIndex = -1

    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Remove the last committed stroke (undo). */
    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeAt(strokes.lastIndex))
            selectedStrokeIndex = -1
            invalidate()
        }
    }

    /** Re-apply the most recently undone stroke. */
    fun redo() {
        if (redoStack.isNotEmpty()) {
            strokes.add(redoStack.removeAt(redoStack.lastIndex))
            selectedStrokeIndex = -1
            invalidate()
        }
    }

    /** Clear all strokes (no undo). */
    fun clear() {
        strokes.clear()
        redoStack.clear()
        currentPoints.clear()
        arrowDragging = false
        selectedStrokeIndex = -1
        releaseCommittedLayer()
        invalidate()
    }

    /** Render all committed strokes onto a new [Bitmap] sized to this view. */
    fun renderToBitmap(): Bitmap {
        return snapshot().render()
    }

    internal fun snapshot(): DrawingSnapshot = DrawingSnapshot(
        width.coerceAtLeast(1), height.coerceAtLeast(1), strokes.toList(),
    )

    private fun releaseCommittedLayer() {
        committedLayer?.recycle()
        committedLayer = null
        rasterizedStrokeCount = 0
        preparedStrokes.clear()
    }

    override fun onDetachedFromWindow() {
        releaseCommittedLayer()
        super.onDetachedFromWindow()
    }

    private fun drawCommittedLayer(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        var layer = committedLayer
        if (layer != null && (layer.width != width || layer.height != height)) {
            layer.recycle()
            layer = null
            committedLayer = null
            rasterizedStrokeCount = 0
        }
        // Immutable stroke identity detects edits, undo/redo and cancelled shape drags.
        var unchanged = 0
        val limit = minOf(strokes.size, preparedStrokes.size)
        while (unchanged < limit && preparedStrokes[unchanged].stroke === strokes[unchanged]) {
            unchanged++
        }
        if (unchanged < preparedStrokes.size) {
            preparedStrokes.subList(unchanged, preparedStrokes.size).clear()
            layer?.eraseColor(Color.TRANSPARENT)
            rasterizedStrokeCount = 0
        }
        for (index in unchanged until strokes.size) preparedStrokes.add(PreparedStroke(strokes[index]))
        // A full-size raster costs more than a few cached paths and consumes screen-sized memory.
        if (preparedStrokes.size < 64) {
            layer?.recycle()
            committedLayer = null
            rasterizedStrokeCount = 0
            preparedStrokes.forEach { it.draw(canvas) }
            return
        }
        if (layer == null) {
            layer = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            committedLayer = layer
            rasterizedStrokeCount = 0
        }
        if (rasterizedStrokeCount < preparedStrokes.size) {
            val target = Canvas(layer)
            for (index in rasterizedStrokeCount until preparedStrokes.size) preparedStrokes[index].draw(target)
            rasterizedStrokeCount = preparedStrokes.size
        }
        canvas.drawBitmap(layer, 0f, 0f, null)
    }

    /** Check if there are any committed strokes. */
    fun hasStrokes(): Boolean = strokes.isNotEmpty()

    fun canUndo(): Boolean = strokes.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    // ---- Touch handling ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (shapeDragState in SHAPE_DRAG_MOVE..SHAPE_DRAG_ROTATE && selectedStrokeIndex in strokes.indices) {
                strokes[selectedStrokeIndex] = strokes[selectedStrokeIndex].copy(points = dragStartPoints)
            }
            shapeDragState = SHAPE_DRAG_NONE
            pendingShapeIndex = -1
            currentPoints.clear()
            arrowDragging = false
            invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (shapeDragState in SHAPE_DRAG_MOVE..SHAPE_DRAG_ROTATE && selectedStrokeIndex in strokes.indices) {
                strokes[selectedStrokeIndex] = strokes[selectedStrokeIndex].copy(points = dragStartPoints)
            }
            shapeDragState = SHAPE_DRAG_NONE
            pendingShapeIndex = -1
            currentPoints.clear()
            arrowDragging = false
            invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount > 1) return true

        when (mode) {
            Mode.PEN -> handlePen(event)
            Mode.ERASER -> handleFreehand(event)
            Mode.ARROW -> handleArrow(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * PEN mode: handles both drawing new strokes and
     * selecting/moving/scaling recognized shapes.
     *
     * Flow:
     * 1. ACTION_DOWN — check if the tap hits a recognized shape
     *    or its corner handles. If so, enter shape drag mode.
     *    Otherwise, start a new freehand stroke.
     * 2. ACTION_MOVE — if dragging a shape, move/scale it.
     *    Otherwise, accumulate freehand points.
     * 3. ACTION_UP — if dragging a shape, commit the new position.
     *    Otherwise, finalize the freehand stroke with shape
     *    recognition.
     */
    private fun handlePen(event: MotionEvent) {
        val pressure = event.getPressure(event.actionIndex).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                // 1. Check if tapping the rotation handle.
                if (selectedStrokeIndex >= 0 && selectedStrokeIndex < strokes.size) {
                    if (hitTestRotationHandle(x, y, strokes[selectedStrokeIndex])) {
                        shapeDragState = SHAPE_DRAG_ROTATE
                        dragStartX = x
                        dragStartY = y
                        dragStartPoints = strokes[selectedStrokeIndex].points.toList()
                        val shape = strokes[selectedStrokeIndex].recognizedShape
                        val corners = orientedSelectionCorners(dragStartPoints, shape)!!
                        val ccx = corners.map { it.first }.average().toFloat()
                        val ccy = corners.map { it.second }.average().toFloat()
                        dragStartAngle = atan2(y - ccy, x - ccx)
                        return
                    }
                }

                // 2. Check if tapping a corner handle.
                if (selectedStrokeIndex >= 0 && selectedStrokeIndex < strokes.size) {
                    val corner = hitTestCornerHandle(x, y, strokes[selectedStrokeIndex])
                    if (corner >= 0) {
                        shapeDragState = SHAPE_DRAG_SCALE
                        dragStartX = x
                        dragStartY = y
                        dragStartPoints = strokes[selectedStrokeIndex].points.toList()
                        dragCornerIdx = corner
                        return
                    }
                }

                // 3. Check if tapping inside any recognized shape.
                val hitIdx = hitTestShape(x, y)
                if (hitIdx >= 0) {
                    dragStartX = x
                    dragStartY = y
                    if (hitIdx == selectedStrokeIndex) {
                        shapeDragState = SHAPE_DRAG_MOVE
                        dragStartPoints = strokes[hitIdx].points.toList()
                    } else {
                        shapeDragState = SHAPE_DRAG_PENDING
                        pendingShapeIndex = hitIdx
                    }
                    invalidate()
                    return
                }

                // 4. No shape hit — deselect and start drawing.
                selectedStrokeIndex = -1
                shapeDragState = SHAPE_DRAG_NONE
                currentPoints.clear()
                currentPoints.add(Point(x, y, pressure))
            }
            MotionEvent.ACTION_MOVE -> {
                when (shapeDragState) {
                    SHAPE_DRAG_PENDING -> {
                        val distance = hypot(event.x - dragStartX, event.y - dragStartY)
                        if (distance > ViewConfiguration.get(context).scaledTouchSlop) {
                            shapeDragState = SHAPE_DRAG_NONE
                            pendingShapeIndex = -1
                            selectedStrokeIndex = -1
                            currentPoints.clear()
                            currentPoints.add(Point(dragStartX, dragStartY, pressure))
                            appendMotionPoints(event, pressure)
                        }
                    }
                    SHAPE_DRAG_MOVE -> {
                        val dx = event.x - dragStartX
                        val dy = event.y - dragStartY
                        applyShapeMove(dx, dy)
                    }
                    SHAPE_DRAG_SCALE -> {
                        applyShapeScale(event.x, event.y)
                    }
                    SHAPE_DRAG_ROTATE -> {
                        applyShapeRotation(event.x, event.y)
                    }
                    else -> {
                        appendMotionPoints(event, pressure)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                when (shapeDragState) {
                    SHAPE_DRAG_PENDING -> {
                        if (hypot(event.x - dragStartX, event.y - dragStartY) >
                            ViewConfiguration.get(context).scaledTouchSlop) {
                            selectedStrokeIndex = -1
                            currentPoints.add(Point(dragStartX, dragStartY, pressure))
                            finishPenStroke(event, pressure)
                        } else {
                            selectedStrokeIndex = pendingShapeIndex
                        }
                        pendingShapeIndex = -1
                        shapeDragState = SHAPE_DRAG_NONE
                    }
                    SHAPE_DRAG_MOVE, SHAPE_DRAG_SCALE, SHAPE_DRAG_ROTATE -> {
                        when (shapeDragState) {
                            SHAPE_DRAG_MOVE -> applyShapeMove(event.x - dragStartX, event.y - dragStartY)
                            SHAPE_DRAG_SCALE -> applyShapeScale(event.x, event.y)
                            SHAPE_DRAG_ROTATE -> applyShapeRotation(event.x, event.y)
                        }
                        shapeDragState = SHAPE_DRAG_NONE
                    }
                    else -> {
                        finishPenStroke(event, pressure)
                    }
                }
            }
        }
        invalidate()
    }

    private fun finishPenStroke(event: MotionEvent, pressure: Float) {
        if (currentPoints.isNotEmpty()) {
            val end = Point(event.x, event.y, pressure)
            if (hypot(end.x - currentPoints.last().x, end.y - currentPoints.last().y) > 0.01f) {
                currentPoints.add(end)
            }
        }
        if (currentPoints.size >= 2) {
            val recognition = if (shapeRecognitionEnabled) {
                ShapeRecognizer.recognize(currentPoints, resources.displayMetrics.density)
            } else {
                ShapeRecognizer.Result(RecognizedShape.NONE, currentPoints.toList())
            }
            strokes.add(Stroke(
                recognition.points, strokeColor, mode, brushStyle, strokeWidth, recognition.shape,
            ))
            redoStack.clear()
        }
        currentPoints.clear()
    }

    private fun appendMotionPoints(event: MotionEvent, pressure: Float) {
        for (i in 0 until event.historySize) {
            currentPoints.add(Point(
                event.getHistoricalX(i), event.getHistoricalY(i),
                event.getHistoricalPressure(i).coerceIn(0f, 1f),
            ))
        }
        currentPoints.add(Point(event.x, event.y, pressure))
    }

    private fun handleFreehand(event: MotionEvent) {
        val pressure = event.getPressure(event.actionIndex).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectedStrokeIndex = -1
                currentPoints.clear()
                currentPoints.add(Point(event.x, event.y, pressure))
            }
            MotionEvent.ACTION_MOVE -> {
                currentPoints.add(Point(event.x, event.y, pressure))
            }
            MotionEvent.ACTION_UP -> {
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
                selectedStrokeIndex = -1
                arrowStart[0] = event.x
                arrowStart[1] = event.y
                arrowDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                // Preview — just invalidate for redraw
            }
            MotionEvent.ACTION_UP -> {
                if (arrowDragging) {
                    val ex = event.x
                    val ey = event.y
                    val dx = ex - arrowStart[0]
                    val dy = ey - arrowStart[1]
                    if (dx * dx + dy * dy > 400f) {
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

    // ---- Shape hit testing ----

    /**
     * Returns the index of the topmost recognized shape that
     * contains (x, y), or -1 if none. Shapes are tested in
     * reverse order (topmost first).
     */
    private fun hitTestShape(x: Float, y: Float): Int {
        val hitRadius = 24f * resources.displayMetrics.density
        for (i in strokes.lastIndex downTo 0) {
            val stroke = strokes[i]
            if (stroke.recognizedShape == RecognizedShape.NONE) continue
            if (stroke.mode != Mode.PEN) continue
            if (isPointNearStroke(x, y, stroke.points, hitRadius)) {
                return i
            }
        }
        return -1
    }

    /**
     * Check if point (x,y) is within [tolerance] pixels of
     * any segment of the polyline defined by [points].
     */
    private fun isPointNearStroke(x: Float, y: Float, points: List<Point>, tolerance: Float): Boolean {
        for (i in 0 until points.size - 1) {
            val d = perpendicularDistance(Point(x, y, 0f), points[i], points[i + 1])
            if (d <= tolerance) return true
        }
        return false
    }

    /**
     * Returns the index of the corner handle hit by (x, y),
     * or -1 if none. Uses oriented selection box corners so
     * it works correctly for rotated shapes.
     */
    private fun hitTestCornerHandle(x: Float, y: Float, stroke: Stroke): Int {
        val corners = orientedSelectionCorners(stroke.points, stroke.recognizedShape) ?: return -1
        val handleRadius = 20f * resources.displayMetrics.density
        for (i in corners.indices) {
            if (hypot(x - corners[i].first, y - corners[i].second) <= handleRadius) {
                return i
            }
        }
        return -1
    }

    // ---- Shape manipulation ----

    /**
     * Move the selected shape by (dx, dy) relative to drag start.
     * Updates the stroke's points in-place.
     */
    private fun applyShapeMove(dx: Float, dy: Float) {
        if (selectedStrokeIndex < 0 || selectedStrokeIndex >= strokes.size) return
        val newPoints = dragStartPoints.map { p ->
            Point(p.x + dx, p.y + dy, p.pressure)
        }
        strokes[selectedStrokeIndex] = strokes[selectedStrokeIndex].copy(points = newPoints)
    }

    /**
     * Scale the selected shape based on the current drag position.
     * Uses projection-based scaling for better responsiveness:
     * the finger position is projected onto the diagonal direction
     * for a 1D scale that feels linear and responsive. Aspect
     * ratio is preserved.
     *
     * The corner index is determined once at drag start and stored
     * in [dragCornerIdx] — re-computing it each frame would fail
     * because the stroke has already been scaled.
     */
    private fun applyShapeScale(currentX: Float, currentY: Float) {
        if (selectedStrokeIndex < 0 || selectedStrokeIndex >= strokes.size) return
        if (dragCornerIdx < 0) return
        val shape = strokes[selectedStrokeIndex].recognizedShape
        val corners = orientedSelectionCorners(dragStartPoints, shape) ?: return

        val anchorIdx = (dragCornerIdx + 2) % 4
        val anchorX = corners[anchorIdx].first
        val anchorY = corners[anchorIdx].second

        // Project the current finger vector onto the diagonal
        // direction for a 1D signed scale factor.
        val origDx = corners[dragCornerIdx].first - anchorX
        val origDy = corners[dragCornerIdx].second - anchorY
        val origDiag2 = origDx * origDx + origDy * origDy
        if (origDiag2 < 1f) return

        val curDx = currentX - anchorX
        val curDy = currentY - anchorY
        val dot = curDx * origDx + curDy * origDy
        val scale = (dot / origDiag2).coerceIn(0.05f, 20f)

        val newPoints = dragStartPoints.map { p ->
            Point(
                anchorX + (p.x - anchorX) * scale,
                anchorY + (p.y - anchorY) * scale,
                p.pressure,
            )
        }
        strokes[selectedStrokeIndex] = strokes[selectedStrokeIndex].copy(points = newPoints)
    }

    /**
     * Rotate the selected shape around its center based on
     * the angle change from the drag start.
     */
    private fun applyShapeRotation(currentX: Float, currentY: Float) {
        if (selectedStrokeIndex < 0 || selectedStrokeIndex >= strokes.size) return
        val shape = strokes[selectedStrokeIndex].recognizedShape
        val corners = orientedSelectionCorners(dragStartPoints, shape) ?: return
        val cx = corners.map { it.first }.average().toFloat()
        val cy = corners.map { it.second }.average().toFloat()

        val currentAngle = atan2(currentY - cy, currentX - cx)
        val deltaAngle = currentAngle - dragStartAngle

        val cosA = cos(deltaAngle)
        val sinA = sin(deltaAngle)

        val newPoints = dragStartPoints.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            Point(
                cx + dx * cosA - dy * sinA,
                cy + dx * sinA + dy * cosA,
                p.pressure,
            )
        }
        strokes[selectedStrokeIndex] = strokes[selectedStrokeIndex].copy(points = newPoints)
    }

    /**
     * Check if (x, y) hits the rotation handle — a circle
     * positioned above the top-center of the oriented
     * selection box, connected by a thin line.
     */
    private fun hitTestRotationHandle(x: Float, y: Float, stroke: Stroke): Boolean {
        val corners = orientedSelectionCorners(stroke.points, stroke.recognizedShape) ?: return false
        val (rx, ry) = rotationHandlePosition(corners)
        val handleRadius = 20f * resources.displayMetrics.density
        return hypot(x - rx, y - ry) <= handleRadius
    }

    /**
     * Position of the rotation handle: above the top-center
     * of the oriented selection box, in the direction
     * perpendicular to the top edge pointing away from center.
     */
    private fun rotationHandlePosition(corners: List<Pair<Float, Float>>): Pair<Float, Float> {
        // Top-center is midpoint of edge 0→1 (TL→TR)
        val topMidX = (corners[0].first + corners[1].first) / 2f
        val topMidY = (corners[0].second + corners[1].second) / 2f
        // Direction perpendicular to top edge, pointing outward
        val edgeDx = corners[1].first - corners[0].first
        val edgeDy = corners[1].second - corners[0].second
        val edgeLen = hypot(edgeDx, edgeDy)
        if (edgeLen < 1f) return Pair(topMidX, topMidY - 36f * resources.displayMetrics.density)
        // Normal pointing away from center (upward for unrotated)
        val nx = -edgeDy / edgeLen
        val ny = edgeDx / edgeLen
        // Check that normal points away from center
        val cx = corners.map { it.first }.average().toFloat()
        val cy = corners.map { it.second }.average().toFloat()
        val toCenterX = cx - topMidX
        val toCenterY = cy - topMidY
        val sign = if (nx * toCenterX + ny * toCenterY > 0) -1f else 1f
        val offset = 36f * resources.displayMetrics.density
        return Pair(topMidX + nx * sign * offset, topMidY + ny * sign * offset)
    }

    // ---- Bounding box helpers ----

    private fun strokeBBox(points: List<Point>): RectF? {
        if (points.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    /** Returns TL, TR, BR, BL corners of a bounding box. */
    private fun bboxCorners(bbox: RectF): List<Pair<Float, Float>> = listOf(
        Pair(bbox.left, bbox.top),       // TL = 0
        Pair(bbox.right, bbox.top),      // TR = 1
        Pair(bbox.right, bbox.bottom),   // BR = 2
        Pair(bbox.left, bbox.bottom),    // BL = 3
    )

    /**
     * Returns the 4 corners of the oriented (rotated) selection
     * box for a recognized shape. For rectangles, these are the
     * actual corner vertices. For triangles, the minimum-area
     * bounding rectangle is computed. For circles and lines,
     * falls back to the axis-aligned bounding box.
     *
     * Corners are returned in order: TL, TR, BR, BL relative
     * to the shape's own orientation.
     */
    private fun orientedSelectionCorners(
        points: List<Point>,
        shape: RecognizedShape,
    ): List<Pair<Float, Float>>? {
        when (shape) {
            RecognizedShape.RECTANGLE -> {
                if (points.size < 4) return null
                // The rectangle's first 4 points ARE the corners.
                // Sort them into TL, TR, BR, BL order based on
                // the shape's own coordinate system.
                val raw = points.take(4).map { Pair(it.x, it.y) }
                return sortCornersTLTRBRBL(raw)
            }
            RecognizedShape.TRIANGLE -> {
                if (points.size < 3) return null
                return computeMinBoundingRect(points.take(3))
            }
            RecognizedShape.LINE -> {
                if (points.size < 2) return null
                // For a line, compute a thin bounding rect
                // aligned with the line direction.
                return computeMinBoundingRect(points.take(2))
            }
            RecognizedShape.ELLIPSE -> {
                if (points.size < 64) return null
                val cx = (points[0].x + points[32].x) / 2f
                val cy = (points[0].y + points[32].y) / 2f
                val ux = points[0].x - cx
                val uy = points[0].y - cy
                val vx = points[16].x - cx
                val vy = points[16].y - cy
                return listOf(
                    Pair(cx - ux - vx, cy - uy - vy),
                    Pair(cx + ux - vx, cy + uy - vy),
                    Pair(cx + ux + vx, cy + uy + vy),
                    Pair(cx - ux + vx, cy - uy + vy),
                )
            }
            else -> {
                // Circle or unknown — axis-aligned bbox
                val bbox = strokeBBox(points) ?: return null
                return bboxCorners(bbox)
            }
        }
    }

    /**
     * Sort 4 corners into TL, TR, BR, BL order based on their
     * position relative to the centroid.
     */
    private fun sortCornersTLTRBRBL(corners: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (corners.size != 4) return corners
        val cx = corners.map { it.first }.average().toFloat()
        val cy = corners.map { it.second }.average().toFloat()
        val sorted = corners.sortedBy { atan2(it.second - cy, it.first - cx) }
        // After sorting by angle, the order is CCW starting from
        // the point closest to the positive-X axis. We need
        // TL, TR, BR, BL (CW from top-left).
        // Find the point with the smallest (x+y) as TL.
        val tlIdx = sorted.indices.minByOrNull {
            sorted[it].first + sorted[it].second
        } ?: 0
        // Reorder: TL first, then CW
        val result = ArrayList<Pair<Float, Float>>(4)
        for (i in 0 until 4) {
            result.add(sorted[(tlIdx + i) % 4])
        }
        // Verify CW order by checking cross product
        // If not CW, swap the middle two
        val cross = (result[1].first - result[0].first) * (result[2].second - result[1].second) -
            (result[1].second - result[0].second) * (result[2].first - result[1].first)
        if (cross < 0) {
            // CCW — swap indices 1 and 3 to get CW
            val tmp = result[1]
            result[1] = result[3]
            result[3] = tmp
        }
        return result
    }

    /**
     * Compute the minimum-area bounding rectangle for a set of
     * points by trying each edge as the baseline direction.
     * Returns 4 corners in TL, TR, BR, BL order.
     */
    private fun computeMinBoundingRect(points: List<Point>): List<Pair<Float, Float>> {
        if (points.size < 2) {
            val bbox = strokeBBox(points) ?: return emptyList()
            return bboxCorners(bbox)
        }

        var bestArea = Float.MAX_VALUE
        var bestCorners = listOf<Pair<Float, Float>>()

        // Try each edge direction as the baseline
        for (i in points.indices) {
            val j = (i + 1) % points.size
            val angle = -atan2(points[j].y - points[i].y, points[j].x - points[i].x)
            val cosA = cos(angle)
            val sinA = sin(angle)

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE

            for (p in points) {
                val rx = p.x * cosA - p.y * sinA
                val ry = p.x * sinA + p.y * cosA
                if (rx < minX) minX = rx
                if (ry < minY) minY = ry
                if (rx > maxX) maxX = rx
                if (ry > maxY) maxY = ry
            }

            val area = (maxX - minX) * (maxY - minY)
            if (area < bestArea) {
                bestArea = area
                // Rotate the bbox corners back to original frame
                val localCorners = listOf(
                    Pair(minX, minY),
                    Pair(maxX, minY),
                    Pair(maxX, maxY),
                    Pair(minX, maxY),
                )
                bestCorners = localCorners.map { (x, y) ->
                    val ox = x * cos(-angle) - y * sin(-angle)
                    val oy = x * sin(-angle) + y * cos(-angle)
                    Pair(ox, oy)
                }
            }
        }

        return bestCorners
    }

    // ---- Drawing ----

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = "#4488FF".toColorInt()
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(8f * resources.displayMetrics.density, 4f * resources.displayMetrics.density),
            0f,
        )
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = "#4488FF".toColorInt()
        strokeWidth = 2f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCommittedLayer(canvas)
        // Preview of the in-progress gesture.
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

        // Draw selection UI for the selected shape.
        if (selectedStrokeIndex >= 0 && selectedStrokeIndex < strokes.size) {
            val selected = strokes[selectedStrokeIndex]
            if (selected.recognizedShape != RecognizedShape.NONE) {
                drawSelectionUI(canvas, selected)
            }
        }
    }

    private val rotationLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = "#4488FF".toColorInt()
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val rotationHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#4488FF".toColorInt()
    }

    private fun drawSelectionUI(canvas: Canvas, stroke: Stroke) {
        val corners = orientedSelectionCorners(stroke.points, stroke.recognizedShape) ?: return
        if (corners.size < 4) return
        val pad = 6f * resources.displayMetrics.density
        val handleR = 6f * resources.displayMetrics.density

        // Expand corners outward by padding
        val cx = corners.map { it.first }.average().toFloat()
        val cy = corners.map { it.second }.average().toFloat()
        val paddedCorners = corners.map { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            val len = hypot(dx, dy)
            if (len < 1f) return@map Pair(x, y)
            Pair(x + dx / len * pad, y + dy / len * pad)
        }

        // Dashed selection rectangle (as a Path through 4 corners)
        val rectPath = Path()
        rectPath.moveTo(paddedCorners[0].first, paddedCorners[0].second)
        for (i in 1..3) {
            rectPath.lineTo(paddedCorners[i].first, paddedCorners[i].second)
        }
        rectPath.close()
        canvas.drawPath(rectPath, selectionPaint)

        // Corner handles
        for ((hx, hy) in corners) {
            canvas.drawCircle(hx, hy, handleR, handleFillPaint)
            canvas.drawCircle(hx, hy, handleR, handleStrokePaint)
        }

        // Rotation handle: line from top-center outward to a circle
        val topMidX = (corners[0].first + corners[1].first) / 2f
        val topMidY = (corners[0].second + corners[1].second) / 2f
        val (rx, ry) = rotationHandlePosition(corners)
        canvas.drawLine(topMidX, topMidY, rx, ry, rotationLinePaint)
        canvas.drawCircle(rx, ry, handleR * 1.2f, rotationHandlePaint)
    }

    /**
     * Render a freehand stroke to [canvas]. The stroke color and
     * alpha are read from [strokePaint]; callers are responsible
     * for setting `strokePaint.color` / `strokePaint.xfermode`
     * before invoking this.
     */
    private fun drawFreehandStrokeInternal(
        canvas: Canvas,
        points: List<Point>,
        style: BrushStyle,
        baseWidth: Float,
    ) {
        if (points.size < 2) return
        val path = previewPath.apply { rewind() }
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val p = points[i]
            path.lineTo(p.x, p.y)
        }
        var pressureSum = 0.0
        for (point in points) pressureSum += point.pressure
        val avgPressure = (pressureSum / points.size).toFloat().coerceIn(0f, 1f)
        val pressureMod = MIN_STROKE_W +
            avgPressure * (PRESSURE_RANGE_W)
        val width = when (style) {
            BrushStyle.HIGHLIGHTER -> baseWidth * 2.2f
            BrushStyle.CALLIGRAPHY -> baseWidth * 0.5f
            BrushStyle.PEN -> baseWidth
        } + pressureMod * 0.3f
        val baseAlpha = when (style) {
            BrushStyle.HIGHLIGHTER -> 90
            BrushStyle.CALLIGRAPHY -> 255
            BrushStyle.PEN -> 255
        }
        val alpha = (baseAlpha + avgPressure * 80f).toInt().coerceAtMost(255)
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = width
        canvas.drawPath(path, strokePaint)
    }

    // Hit testing uses clamped segment distance so extending a line cannot capture distant taps.
    private fun perpendicularDistance(p: Point, a: Point, b: Point): Float {
        return ShapeRecognizer.segmentDistance(p, a, b)
    }

    companion object {
        private const val MIN_STROKE_W = 1f
        private const val PRESSURE_RANGE_W = 4f

        // Shape drag states
        private const val SHAPE_DRAG_NONE = 0
        private const val SHAPE_DRAG_MOVE = 1
        private const val SHAPE_DRAG_SCALE = 2
        private const val SHAPE_DRAG_ROTATE = 3
        private const val SHAPE_DRAG_PENDING = 4
    }
}
