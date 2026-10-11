package com.pinpin.app.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Immutable committed strokes; rendering owns its Paint/Path, never a View. */
internal class DrawingSnapshot(
    val width: Int,
    val height: Int,
    internal val strokes: List<DrawingOverlayView.Stroke>,
) {
    fun render(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            strokes.forEach { PreparedStroke(it).draw(canvas) }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }
}

/** Computed once for an immutable stroke, including pressure and arrow geometry. */
internal class PreparedStroke(val stroke: DrawingOverlayView.Stroke) {
    private val path = Path()
    private val arrowHead = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        val points = stroke.points
        paint.color = if (stroke.mode == DrawingOverlayView.Mode.ERASER) Color.BLACK else stroke.color
        if (stroke.mode == DrawingOverlayView.Mode.ERASER) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        if (points.size >= 2) {
            if (stroke.mode == DrawingOverlayView.Mode.ARROW) {
                val start = points[0]
                val end = points[1]
                val pressure = end.pressure.coerceIn(0f, 1f)
                paint.strokeWidth = stroke.baseWidth + pressure * 4f * 0.3f
                paint.alpha = (220 + pressure * 35f).toInt().coerceAtMost(255)
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)
                val angle = atan2(end.y - start.y, end.x - start.x)
                val headLen = paint.strokeWidth * 4f
                val headAngle = Math.toRadians(30.0).toFloat()
                arrowHead.moveTo(end.x, end.y)
                arrowHead.lineTo(end.x - headLen * cos(angle - headAngle), end.y - headLen * sin(angle - headAngle))
                arrowHead.lineTo(end.x - headLen * cos(angle + headAngle), end.y - headLen * sin(angle + headAngle))
                arrowHead.close()
                headPaint.color = stroke.color
                headPaint.alpha = paint.alpha
            } else {
                path.moveTo(points[0].x, points[0].y)
                var pressureSum = points[0].pressure.toDouble()
                for (index in 1 until points.size) {
                    val point = points[index]
                    pressureSum += point.pressure
                    path.lineTo(point.x, point.y)
                }
                val pressure = (pressureSum / points.size).toFloat().coerceIn(0f, 1f)
                val baseWidth = when (stroke.brushStyle) {
                    DrawingOverlayView.BrushStyle.HIGHLIGHTER -> stroke.baseWidth * 2.2f
                    DrawingOverlayView.BrushStyle.CALLIGRAPHY -> stroke.baseWidth * 0.5f
                    DrawingOverlayView.BrushStyle.PEN -> stroke.baseWidth
                }
                paint.strokeWidth = baseWidth + (1f + pressure * 4f) * 0.3f
                val baseAlpha = if (stroke.brushStyle == DrawingOverlayView.BrushStyle.HIGHLIGHTER) 90 else 255
                paint.alpha = (baseAlpha + pressure * 80f).toInt().coerceAtMost(255)
            }
        }
    }

    fun draw(canvas: Canvas) {
        if (stroke.points.size < 2) return
        canvas.drawPath(path, paint)
        if (stroke.mode == DrawingOverlayView.Mode.ARROW) canvas.drawPath(arrowHead, headPaint)
    }
}
