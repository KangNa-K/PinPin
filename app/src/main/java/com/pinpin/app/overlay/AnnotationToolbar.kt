package com.pinpin.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.graphics.toColorInt
import com.pinpin.app.R

/**
 * The toolbar shown under a pin while annotating: colour swatches, pen and
 * eraser, brush size, undo and redo scroll horizontally; save, apply and
 * cancel stay fixed on the right so they are always reachable.
 *
 * It drives [drawing] directly. (Not named `overlay`: inside View.apply blocks
 * that would resolve to View.getOverlay().) [PinWindowView] owns the window layout and
 * decides what the exit actions do.
 */
internal class AnnotationToolbar(
    private val context: Context,
    private val theme: PinTheme,
    private val drawing: DrawingOverlayView,
    initialColor: Int,
    toolbarHeightPx: Int,
    private val onColorSelected: (Int) -> Unit,
    onSave: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private var selectedColor = initialColor
    private val swatchViews = mutableListOf<View>()
    private val penButton: View
    private val eraserButton: View

    val view: LinearLayout

    init {
        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((color, label) in SWATCHES) {
            makeColorSwatch(color, label, color == selectedColor) { selectColor(color) }
                .also { swatchViews.add(it); mainRow.addView(it) }
        }
        mainRow.addView(makeVerticalDivider())
        // Shape recognition is built into the pen, not a separate tool.
        penButton = makeActionButton(R.drawable.ic_action_annotate, "签字笔") {
            selectMode(DrawingOverlayView.Mode.PEN)
        }.also { mainRow.addView(it) }
        eraserButton = makeActionButton(R.drawable.ic_action_eraser, "橡皮擦") {
            selectMode(DrawingOverlayView.Mode.ERASER)
        }.also { mainRow.addView(it) }
        refreshModeSelection()
        // Pen and eraser share the canvas strokeWidth, so one slider sets both.
        mainRow.addView(makeBrushSizeSlider())
        mainRow.addView(makeVerticalDivider())
        mainRow.addView(makeActionButton(R.drawable.ic_action_undo, "撤销") { drawing.undo() })
        mainRow.addView(makeActionButton(R.drawable.ic_action_redo, "重做") { drawing.redo() })

        val exitActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(makeActionButton(R.drawable.ic_action_save, "保存", onClick = onSave))
            addView(makeActionButton(R.drawable.ic_action_apply, "应用", onClick = onApply))
            addView(makeActionButton(R.drawable.ic_close, "取消", asCancel = true, onClick = onCancel))
        }

        view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(theme.surfaceFill)
                cornerRadius = px(24f).toFloat()
                setStroke(px(1f), theme.surfaceStroke)
            }
            clipToOutline = true
            elevation = 0f
            // Keeps the pill off the screen edges when the wrapper is full width.
            setPadding(px(12f), 0, px(12f), 0)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, toolbarHeightPx, 1f)
                addView(mainRow)
            })
            addView(makeVerticalDivider())
            addView(exitActions)
        }
    }

    /** Picking any colour, even the active one, returns to the pen. */
    private fun selectColor(color: Int) {
        selectMode(DrawingOverlayView.Mode.PEN)
        if (selectedColor == color) return
        selectedColor = color
        drawing.strokeColor = color
        onColorSelected(color)
        for ((i, swatch) in swatchViews.withIndex()) {
            (swatch.background as? GradientDrawable)?.let { applySwatchRing(it, SWATCHES[i].first == color) }
        }
    }

    private fun selectMode(mode: DrawingOverlayView.Mode) {
        drawing.mode = mode
        refreshModeSelection()
    }

    private fun refreshModeSelection() {
        penButton.isSelected = drawing.mode == DrawingOverlayView.Mode.PEN
        eraserButton.isSelected = drawing.mode == DrawingOverlayView.Mode.ERASER
    }

    /** Active swatch: 3 dp white ring; others: 1 dp 20%-white ring. */
    private fun applySwatchRing(drawable: GradientDrawable, selected: Boolean) {
        if (selected) drawable.setStroke(px(3f), Color.WHITE)
        else drawable.setStroke(px(1f), "#33FFFFFF".toColorInt())
    }

    private fun makeBrushSizeSlider(): View {
        val previewPx = AnnotationBrushSize.previewSizePx(drawing.strokeWidth, density)
        // Live preview dot whose diameter follows the brush width.
        val preview = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.iconColor)
            }
            layoutParams = LinearLayout.LayoutParams(previewPx, previewPx).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = px(4f)
                marginEnd = px(4f)
            }
        }
        val slider = SeekBar(context).apply {
            max = AnnotationBrushSize.MAX_PROGRESS
            progress = AnnotationBrushSize.progressForWidth(drawing.strokeWidth)
            layoutParams = LinearLayout.LayoutParams(px(140f), LinearLayout.LayoutParams.WRAP_CONTENT)
            contentDescription = "笔刷大小"
            // Keep the gesture inside SeekBar instead of letting the
            // overlay wrapper classify it as a window drag.
            setOnTouchListener { view, _ ->
                view.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val width = AnnotationBrushSize.widthForProgress(progress)
                    drawing.strokeWidth = width
                    val size = AnnotationBrushSize.previewSizePx(width, density)
                    preview.layoutParams = preview.layoutParams.apply {
                        this.width = size
                        height = size
                    }
                    preview.requestLayout()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview)
            addView(makeVerticalDivider())
            addView(slider)
        }
    }

    private fun makeColorSwatch(color: Int, label: String, isSelected: Boolean, onPick: () -> Unit): View {
        val size = px(28f)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
                marginStart = px(2f)
                marginEnd = px(2f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                applySwatchRing(this, isSelected)
            }
            // Own the whole touch stream so the pin wrapper never treats a
            // sloppy tap that crosses the swatch edge as an image drag.
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                true
            }
            setOnClickListener { onPick() }
        }
    }

    private fun makeVerticalDivider(): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor("#22000000".toColorInt())
            setSize(px(1f), px(20f))
        }
        layoutParams = LinearLayout.LayoutParams(px(1f), px(20f)).apply {
            gravity = Gravity.CENTER
            marginStart = px(4f)
            marginEnd = px(4f)
        }
    }

    private fun makeActionButton(
        iconRes: Int,
        label: String,
        asCancel: Boolean = false,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        tooltipText = label
        layoutParams = LinearLayout.LayoutParams(px(40f), px(40f)).apply { gravity = Gravity.CENTER }
        // Cancel gets a red tint so it reads as destructive at a glance.
        val idle = oval(if (asCancel) "#1AEF5350" else "#00000000")
        val pressed = oval(if (asCancel) "#33EF5350" else "#1F000000")
        val selected = oval("#334488FF")
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), idle)
        }
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                Log.d(TAG, "Annotation action $label: ${MotionEvent.actionToString(event.action)} tool=${event.getToolType(0)}")
            }
            // Let View handle click eligibility, cancellation and stylus input.
            false
        }
        setOnClickListener {
            Log.d(TAG, "Annotation action clicked: $label")
            onClick()
        }
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            val tint = if (asCancel) "#D32F2F".toColorInt() else theme.iconColor
            imageTintList = ColorStateList.valueOf(tint)
            layoutParams = LinearLayout.LayoutParams(px(18f), px(18f))
        })
    }

    private fun oval(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color.toColorInt())
    }

    private fun px(dp: Float): Int = (dp * density).toInt()

    companion object {
        // PinWindowView logged these actions under its own tag; keep it so
        // existing logcat filters still match.
        private const val TAG = "PinWindowView"

        /** Swatch colours and their accessibility labels, left to right. */
        val SWATCHES: List<Pair<Int, String>> = listOf(
            Color.RED to "红",
            Color.BLACK to "黑",
            "#FF9800".toColorInt() to "橙",
            "#FFEB3B".toColorInt() to "黄",
            "#4CAF50".toColorInt() to "绿",
            "#00BCD4".toColorInt() to "青",
            "#2196F3".toColorInt() to "蓝",
            "#9C27B0".toColorInt() to "紫",
        )
    }
}
