package com.pinpin.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView

/** Draws the displayed pin around the sampled point without taking another screenshot. */
internal class PinColorMagnifier(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val matrix = Matrix()
    private var bitmap: Bitmap? = null
    private var drawableWidth = 1
    private var drawableHeight = 1
    private var pointX = 0f
    private var pointY = 0f

    fun update(imageView: ImageView, x: Float, y: Float) {
        val drawable = imageView.drawable as? BitmapDrawable ?: return
        bitmap = drawable.bitmap
        drawableWidth = drawable.intrinsicWidth.coerceAtLeast(1)
        drawableHeight = drawable.intrinsicHeight.coerceAtLeast(1)
        matrix.set(imageView.imageMatrix)
        pointX = x
        pointY = y
        invalidate()
    }

    fun setColor(color: Int) {
        ringPaint.color = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(cx, cy) - density
        val inner = outer - 12 * density
        canvas.drawCircle(cx, cy, outer, ringPaint)
        clip.reset()
        clip.addCircle(cx, cy, inner, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(Color.DKGRAY)
        bitmap?.takeUnless { it.isRecycled }?.let {
            canvas.translate(cx, cy)
            canvas.scale(2.5f, 2.5f)
            canvas.translate(-pointX, -pointY)
            canvas.concat(matrix)
            canvas.scale(drawableWidth.toFloat() / it.width, drawableHeight.toFloat() / it.height)
            canvas.drawBitmap(it, 0f, 0f, imagePaint)
        }
        canvas.restore()
        linePaint.color = Color.WHITE
        linePaint.strokeWidth = density
        canvas.drawCircle(cx, cy, inner, linePaint)
        linePaint.color = 0x99000000.toInt()
        linePaint.strokeWidth = 3 * density
        val arm = 7 * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, linePaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, linePaint)
        linePaint.color = Color.WHITE
        linePaint.strokeWidth = density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, linePaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, linePaint)
    }
}
