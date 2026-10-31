package com.pinpin.app.overlay

import androidx.core.net.toUri
import androidx.core.graphics.toColorInt
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.pinpin.app.R
import com.pinpin.app.data.PinEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import java.util.Locale

/**
 * A single floating pin window. Owns the visual + touch handling for one
 * pinned image.
 *
 * ## Layout
 *
 * The window is a vertical stack of two children:
 *
 *   ┌────────────────────────────────┐  ← WM layoutParams (currentW × wrapperH)
 *   │  imageCard  (currentW × currentH) │     - rounded 16dp clip, elevation
 *   │  ┌──────┐                      │     - imageView / closeButton / label
 *   │  │  ✕  │  ← close button       │       are children of imageCard
 *   │  └──────┘                      │     - corner handles drawn in onDraw
 *   │  ╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲╲│
 *   ├────────────────────────────────┤  ← TOOLBAR_GAP (4dp breathing room)
 *   │  toolbar   (currentW × TOOLBAR_H) │   - M3 tonal pill, 28dp rounded
 *   │  [旋][翻]│[分][保]│[#][✎][→]│[⊙][🔒] │   - 9 buttons, 4 dividers
 *   └────────────────────────────────┘
 *
 * `imageCard` holds its own `clipToOutline` rounded rect so the four
 * image corners stay perfectly round regardless of the wrapper's
 * bottom edge. The toolbar is a sibling (NOT clipped by imageCard)
 * so its 28dp pill corners render true.
 *
 * ## Touch model
 *
 *  - **Tap on a corner handle** (24dp radius): single-finger corner
 *    drag resize, aspect-locked via diagonal-driven uniform scale.
 *    See [applyCornerDrag].
 *  - **Tap on the image body** (not a corner, not a button):
 *    short tap (<[TAP_THRESHOLD_MS]) toggles [toolbarShown]; long
 *    press / drag (movement > slop) starts a body drag.
 *  - **Tap on the close button**: fires [onCloseClicked] (host
 *    deletes the pin).
 *  - **Tap on a toolbar button**: fires [onActionClicked] with the
 *    action id; the toolbar stays visible so the user can chain
 *    actions (rotate → save, etc.) without re-tapping.
 *  - **Touch outside the wrapper** (`ACTION_OUTSIDE`): deselects
 *    the pin. Requires `FLAG_WATCH_OUTSIDE_TOUCH` +
 *    `FLAG_NOT_TOUCH_MODAL` on the WM layout params (set in [init]).
 *
 * ## Selection / animation
 *
 *  The pin starts unselected: the close button and toolbar are
 *  drawn at alpha 0 (still in the tree, no layout change, just
 *  invisible). Tapping the image body sets [toolbarShown] true, which
 *  animates the close button to alpha 1 (200ms) and the toolbar to
 *  alpha 1 + translationY 16dp → 0 (250ms). Tapping outside
 *  (`ACTION_OUTSIDE`) reverses the animation.
 */
@SuppressLint("ViewConstructor")
class PinWindowView(
    context: Context,
    val pinId: String,
    /** Mutable so the "锁定" toolbar action can flip the flag on
     *  the live view without going through Room. The repository
     *  still persists the change. */
    var spec: PinSpec,
    private val onPositionChanged: (x: Int, y: Int) -> Unit,
    private val onSizeChanged: (w: Float, h: Float) -> Unit,
    private val onCloseClicked: () -> Unit,
    private val onActionClicked: (actionId: String) -> Unit,
    /**
     * Called whenever the live WindowManager geometry changes. This is
     * intentionally separate from the persistence callbacks above: the
     * overlay service can keep auxiliary windows visually aligned during
     * a gesture without writing every intermediate frame to Room.
     */
    private val onLayoutChanged: () -> Unit = {},
) : FrameLayout(context) {

    data class PinSpec(
        val label: String,
        /** Mutable so the toolbar can re-render after [loadImage]
         *  resolves and we need to swap the placeholder for the
         *  real drawable. */
        var imageUri: String?,
        /**
         * Optional post-annotation image URI. When non-null, the
         * view renders this image in [imageView] instead of
         * [imageUri] (the original is preserved on disk). Set by
         * [setAnnotatedImageUri] after the user commits an
         * annotation; cleared (back to `null`) to revert to the
         * original.
         *
         * Kept separate from [imageUri] so:
         *  - the original can be restored without a Room round-trip
         *  - the "redo annotation" path can read the original
         *    without the annotated version getting overwritten
         */
        var annotatedImageUri: String? = null,
        val sourceType: String,
        val initialX: Int,
        val initialY: Int,
        val widthPx: Int,
        val heightPx: Int,
        /** Mutable so the "锁定" toolbar action can flip the flag
         *  on the live view without going through Room. */
        var locked: Boolean,
        /** The pin's display order (1-based). Rendered as a small
         *  red badge on the "收起" toolbar action and on the
         *  minimized floating ball, so the user can tell which
         *  pin is which. Matches [com.pinpin.app.data.PinEntity.zOrder]
         *  at attach time. Mutable so [updateZOrder] can refresh
         *  it in place after the repository renumbers the
         *  remaining pins. */
        var zOrder: Int,
        var serialVisible: Boolean = false,
        var rotation: Int = 0,
        var flipped: Boolean = false,
    )

    // ----------------------------------------------------------------
    // Image position/size — these are the "logical" pin dimensions.
    // The wrapper's WM layoutParams height is imageH + toolbar
    // overhead (gap + toolbar), so the window is taller than the
    // imageCard alone. All the corner-drag / body-drag / DB-update
    // math operates on these image-only coordinates.
    // ----------------------------------------------------------------
    internal var currentX: Int = spec.initialX
        private set
    internal var currentY: Int = spec.initialY
        private set
    internal var currentW: Int = spec.widthPx
        private set
    internal var currentH: Int = spec.heightPx
        private set

    /** Whether the pin is in "selected" mode (close + toolbar visible).
     *  Renamed from `isSelected` to avoid clashing with [View.isSelected]. */
    private var toolbarShown: Boolean = false

    /** Translucent dashed outline drawn on top of [imageCard] when
     *  passthrough is on, so the user can see at a glance why the
     *  image isn't responding to taps. Starts at alpha 0. */
    private val passthroughIndicator: View

    /** Solid red border drawn on top of [imageCard] when the pin
     *  is locked, so the user can see at a glance that the image
     *  is in a locked state. Starts at alpha 0. */
    private val lockedIndicator: View

    /** ImageView of the toolbar's "锁定" button. Cached so the
     *  lock state can swap the icon (open ↔ closed) and the tint
     *  (dark gray ↔ red) without rebuilding the whole toolbar. */
    private var lockButtonIcon: ImageView? = null

    /**
     * The current theme tokens. Refreshed by [applyTheme] whenever
     * the system toggles light / dark mode. The toolbar pill, the
     * close button, the toolbar buttons' icon tints, and the
     * passthrough exit button (managed by the service) all read
     * from this single source of truth so the entire pin UI
     * changes color in lockstep with the system theme.
     */
    private var theme: PinTheme = PinTheme.of(context)

    // ----------------------------------------------------------------
    // Body drag state
    // ----------------------------------------------------------------
    private var downRawX: Float = 0f
    private var downRawY: Float = 0f
    private var downLayoutX: Int = spec.initialX
    private var downLayoutY: Int = spec.initialY
    private var dragged: Boolean = false
    private var interacting = false
    private var gestureStartW = spec.widthPx
    private var gestureStartH = spec.heightPx
    private var deferredSnapshot: PinEntity? = null
    private var awaitingGeometryCommit = false

    // ----------------------------------------------------------------
    // Tap-to-select state.
    //
    // We track the DOWN time so a quick down+up can be classified as
    // a "tap" (toggles selection) rather than a no-op. If the user
    // moves their finger past [ViewConfiguration.scaledTouchSlop]
    // before lifting, we set `dragged = true` and the tap is
    // suppressed (it becomes a body drag).
    // ----------------------------------------------------------------
    private var downTimeMs: Long = 0L
    private var downLocalX: Float = 0f
    private var downLocalY: Float = 0f

    // ----------------------------------------------------------------
    // Corner-drag state — see [applyCornerDrag] for the geometry.
    // ----------------------------------------------------------------
    private var cornerDragIndex: Int = NO_CORNER_DRAG

    /** Screen-space anchor corner (the one NOT being dragged). */
    private var cornerDragAnchorX: Float = 0f
    private var cornerDragAnchorY: Float = 0f

    private var cornerDragDownFingerX: Float = 0f
    private var cornerDragDownFingerY: Float = 0f
    private var cornerDragInitialDiagonal: Float = 0f
    private var cornerDragInitialW: Int = 0
    private var cornerDragInitialH: Int = 0

    // ----------------------------------------------------------------
    // Child views — created in [init].
    // ----------------------------------------------------------------
    private val imageCard: FrameLayout
    private val imageView: ImageView
    private val closeButton: ImageButton
    private val imageActionButtons = mutableListOf<ImageButton>()
    private val label: TextView?
    private val toolbar: LinearLayout
    private val toolbarViewport: HorizontalScrollView
    private var toolbarColumns = 0
    private var cornerMoved = false

    private fun toolbarWidth(): Int =
        7 * dpToPx(TOOLBAR_BUTTON_DP)

    private fun toolbarRows(@Suppress("UNUSED_PARAMETER") width: Int): Int = 1

    private fun arrangeToolbar(width: Int) {
        // The image toolbar is intentionally a single horizontal row.
        // Its viewport can scroll horizontally, but the row itself is
        // never split into multiple lines.
        val columns = 7
        toolbarViewport.layoutParams = toolbarViewport.layoutParams.apply {
            this.width = minOf(width, toolbarWidth())
        }
        toolbar.layoutParams = toolbar.layoutParams.apply {
            this.width = toolbarWidth()
        }
        if (columns == toolbarColumns) return
        val buttons = mutableListOf<View>()
        if (toolbarColumns == 0) {
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child.isClickable) buttons.add(child)
            }
        } else {
            for (i in 0 until toolbar.childCount) {
                val row = toolbar.getChildAt(i) as LinearLayout
                for (j in 0 until row.childCount) buttons.add(row.getChildAt(j))
            }
        }
        buttons.forEach { (it.parent as android.view.ViewGroup).removeView(it) }
        toolbar.removeAllViews()
        toolbar.orientation = LinearLayout.VERTICAL
        buttons.chunked(columns).forEach { group ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            group.forEach { row.addView(it) }
            toolbar.addView(row, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dpToPx(TOOLBAR_HEIGHT_DP),
            ))
        }
        toolbarColumns = columns
    }

    // The unrotated source bitmap (decoded from the active URI).
    // Rotation is applied on top of this — see [applyCurrentRotation]. We
    // cache it so a rotation re-applied after the image has been
    // reloaded (e.g. switching back from the annotated image) still
    // works. Cleared and re-populated every time the source URI
    // changes via [loadImage]. Coil owns the source bitmap.
    private var sourceBitmap: Bitmap? = null

    // 0/90/180/270. The state we persist via PinEntity.rotation and
    // the only thing we need to remember between user interactions.
    // We DO NOT use `imageView.rotation` for state — that field
    // stays 0f because the image matrix rotates the drawable. Tracking
    // it in our own field keeps the rotation correct after a source
    // reload or window resize.
    private var currentRotation: Int = spec.rotation
    private var imageLoadJob: Job? = null
    internal var annotationSaving = false
    private var annotationRenderJob: Job? = null

    internal suspend fun awaitAnnotationRendering() {
        annotationRenderJob?.join()
    }

    internal fun exportSnapshot(): PersistedPinImageSnapshot? =
        (spec.annotatedImageUri ?: spec.imageUri)?.let {
            PersistedPinImageSnapshot(it.toUri(), currentRotation, spec.flipped)
        }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ----------------------------------------------------------------
    // Corner-handle rendering (drawn on top of the imageCard content).
    // ----------------------------------------------------------------
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inactiveHandleFill = Color.WHITE
    private val inactiveHandleStroke = "#7AA7FF".toColorInt()
    private val activeHandleFill = "#7AA7FF".toColorInt()
    private val activeHandleStroke = Color.WHITE

    /** Rounded-corner radius of the image card. Captured for handle positioning. */
    private var cornerRadiusPx: Float = 0f

    init {
        val initialImageW = spec.widthPx
        val initialImageH = spec.heightPx
        // Controls scroll within the image width rather than expanding
        // the overlay's input window into the surrounding app.
        val wrapperH = initialImageH

        layoutParams = WindowManager.LayoutParams(
            initialImageW,
            wrapperH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Match the selection overlay's display-pixel coordinate space.
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = spec.initialX
            y = spec.initialY
        }

        setWillNotDraw(false)

        // ============ imageCard ============
        // Rounded outline + gradient bg + elevation. Children (image,
        // close button, label) are drawn on top. The wrapper (this
        // FrameLayout) has no clip / bg / elevation of its own, so
        // the toolbar sibling below can render its own rounded pill
        // without inheriting the imageCard's outline.
        cornerRadiusPx = dpToPx(CORNER_RADIUS_DP).toFloat()
        // In normal mode the wrapper matches the image width. Centering
        // also keeps the image aligned when annotation mode widens it.
        val imageCardLp = LayoutParams(initialImageW, initialImageH).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        imageCard = object : FrameLayout(context) {
            // Corner handles are drawn ON TOP of the imageView / label
            // / close button, AFTER all children. Using
            // `onDrawForeground` (API 23+) keeps the handles above
            // child content without us having to add another View
            // layer. Project minSdk is 30, so we're safe to use it.
            override fun onDrawForeground(canvas: Canvas) {
                super.onDrawForeground(canvas)
                drawCornerHandles(canvas)
            }
        }.apply {
            layoutParams = imageCardLp
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            elevation = dpToPx(6f).toFloat()
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf("#7AA7FF".toColorInt(), "#3D5DBF".toColorInt()),
            ).apply { cornerRadius = cornerRadiusPx }
        }
        addView(imageCard)

        // A purely-decorative, non-clickable dashed border used as
        // the passthrough-on indicator. It's a sibling of
        // [imageCard] (not a child), so it can extend to the full
        // wrapper bounds and be drawn over the rounded card edge
        // for a "glowing outline" effect. Initial alpha 0 — only
        // the [setPassthrough] call fades it in.
        passthroughIndicator = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setStroke(
                    (resources.displayMetrics.density * 2).toInt(),
                    "#80FF6F00".toColorInt(),
                )
                cornerRadius = cornerRadiusPx
                setColor("#00000000".toColorInt())
            }
            alpha = 0f
            isClickable = false
            isFocusable = false
            layoutParams = LayoutParams(
                initialImageW, initialImageH,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        addView(passthroughIndicator)

        // Locked indicator: solid red border drawn on top of the
        // imageCard when the pin is locked. Mirrors the
        // passthroughIndicator pattern (sibling of imageCard, full
        // overlay) but uses a red stroke to signal "locked" state.
        // Initially alpha 0 — only [setLockedVisual] fades it in.
        lockedIndicator = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setStroke(
                    (resources.displayMetrics.density * 3).toInt(),
                    "#E53935".toColorInt(),
                )
                cornerRadius = cornerRadiusPx
                setColor("#00000000".toColorInt())
            }
            alpha = 0f
            isClickable = false
            isFocusable = false
            layoutParams = LayoutParams(
                initialImageW, initialImageH,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        addView(lockedIndicator)

        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            )
        }
        imageCard.addView(imageView)
        // Close button — M3 Filled Tonal IconButton style: circular
        // tonal background, X icon, 40dp hit area, 8dp margin. Starts
        // hidden (alpha 0). Made visible via [showToolbar].
        val closeSize = dpToPx(CLOSE_BUTTON_SIZE_DP).toFloat()
        val closeMargin = dpToPx(CLOSE_BUTTON_MARGIN_DP).toFloat()
        closeButton = ImageButton(context).apply {
            // Theme-aware surface: the close button shares the
            // same fill / stroke / icon color tokens as the
            // toolbar pill, so the two always look like they
            // belong to the same floating UI surface. The
            // [applyTheme] method re-applies these when the
            // system toggles light / dark mode.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(theme.surfaceFill)
                setStroke(dpToPx(1), theme.surfaceStroke)
            }
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.pin_action_close_cd)
            imageTintList = ColorStateList.valueOf(theme.iconColor)
            layoutParams = LayoutParams(closeSize.toInt(), closeSize.toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = closeMargin.toInt()
                marginEnd = closeMargin.toInt()
            }
            setOnClickListener {
                if (annotationMode) exitAnnotationMode(discardChanges = true) else onCloseClicked()
            }
            alpha = 0f
            visibility = View.INVISIBLE
            isEnabled = false
        }
        imageCard.addView(closeButton)
        imageActionButtons.add(makeImageActionButton(ACTION_PASSTHROUGH, R.drawable.ic_action_passthrough, R.string.pin_action_passthrough))
        imageActionButtons.add(makeImageActionButton(ACTION_LOCK, R.drawable.ic_action_lock_open, R.string.pin_action_lock))
        imageActionButtons.add(makeImageActionButton(ACTION_MINIMIZE, R.drawable.ic_action_minimize, R.string.pin_action_minimize))
        lockButtonIcon = imageActionButtons[1]
        setLockButtonLocked(spec.locked)
        arrangeImageActions(initialImageW)

        // Optional test-pin label (shown only when there's no image).
        // Prefer the annotated URI if one is set — same image the user
        // sees should drive the label-vs-image branch.
        val imageUri = spec.annotatedImageUri ?: spec.imageUri
        label = if (imageUri == null || imageUri.startsWith("pinpin://")) {
            TextView(context).apply {
                text = spec.label
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                )
            }.also { imageCard.addView(it) }
        } else {
            loadImage(imageUri)
            null
        }

        // ============ toolbar ============
        // Seven actions below the image; window controls live on the image.
        // Hidden by default (alpha 0). `clickable = true` so taps in
        // its bounds are consumed and don't leak to the corner-drag
        // hit-test (which would otherwise start a corner drag the
        // moment the user tapped near the BL/BR corner of the image).
        toolbar = buildToolbar().apply {
            isClickable = true
        }
        toolbarViewport = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            background = toolbar.background
            toolbar.background = null
            clipToOutline = true
            visibility = View.INVISIBLE
            alpha = 0f
            translationY = dpToPx(TOOLBAR_ANIM_TRANSLATE_DP).toFloat()
            addView(toolbar, LayoutParams(
                toolbarWidth(), LayoutParams.WRAP_CONTENT,
            ))
        }
        addView(
            toolbarViewport,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(TOOLBAR_HEIGHT_DP),
                // Center horizontally so the toolbar sits below the
                // image, not glued to its left edge.
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                // Sit directly below the imageCard, with the configured
                // gap. Without this topMargin the toolbar defaults to
                // (0,0) in a FrameLayout and overlaps the image.
                topMargin = dpToPx(TOOLBAR_GAP_DP) + initialImageH
            },
        )
        arrangeToolbar(initialImageW)

        imageCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            arrangeImageActions(imageCard.width)
        }

        setOnClickListener { /* consume taps so they don't fall through */ }
        imageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyCurrentRotation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
        sourceBitmap = null
        imageView.setImageDrawable(null)
    }

    internal fun loadImage(uriString: String) {
        imageLoadJob?.cancel()
        sourceBitmap = null
        val load = scope.launch {
            val uri = runCatching { uriString.toUri() }.getOrNull() ?: return@launch
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(2048, 2048)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                sourceBitmap = (result.drawable as? BitmapDrawable)?.bitmap
                imageView.setImageDrawable(result.drawable)
                // The blue card background is only a placeholder for
                // image-less pins. A real image must never reveal it
                // when a rotation or resize is being applied.
                imageCard.setBackgroundColor(Color.TRANSPARENT)
                applyCurrentRotation()
            } else {
                Log.w(TAG, "Image load failed for $uriString; falling back to placeholder")
                android.widget.Toast.makeText(context, "图片读取失败，请重新导入", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        imageLoadJob = load
        load.invokeOnCompletion {
            if (imageLoadJob === load) imageLoadJob = null
        }
    }

    /**
     * Swap the pin's displayed image to a freshly-rendered
     * annotation. Stores the URI on [PinSpec.annotatedImageUri]
     * (so subsequent re-attaches / clones pick it up) and reloads
     * the bitmap into [imageView] without touching
     * [PinSpec.imageUri] — the original is preserved on disk and
     * can be restored by passing `null`.
     *
     * The original `imageUri` is intentionally NOT overwritten:
     * doing so would destroy the path back to the pre-annotation
     * image, making "discard this annotation and go back" impossible.
     *
     * Idempotent: passing the same URI twice is a no-op.
     */
    fun setAnnotatedImageUri(uri: String?) {
        if (spec.annotatedImageUri == uri) return
        spec.annotatedImageUri = uri
        val target = uri ?: spec.imageUri
        if (target != null) {
            loadImage(target)
        }
    }

    // ----------------------------------------------------------------
    // Toolbar construction
    // ----------------------------------------------------------------
    private fun buildToolbar(): LinearLayout {
        val gapH = dpToPx(TOOLBAR_GAP_DP)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Frosted-glass pill, matching the iOS / macOS control
            // center look from the reference image. Colors are
            // theme-aware: the fill, stroke, and (via the icon
            // button tint below) the icons all read from
            // [PinTheme] so the pill matches the close button and
            // automatically follows the system's light / dark
            // mode. [applyTheme] re-runs this drawable swap on
            // every UI mode change.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(theme.surfaceFill)
                cornerRadius = dpToPx(24f).toFloat()
                setStroke(dpToPx(1), theme.surfaceStroke)
            }
            // No elevation — overlay windows render their own shadow
            // poorly on a pinned screenshot, so the pill stays flat.
            elevation = 0f
            // Clip children to the rounded rect so dividers / pressed
            // ripples do not poke past the pill ends.
            clipToOutline = true
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                dpToPx(TOOLBAR_HEIGHT_DP),
            ).apply {
                topMargin = gapH
            }
            // Group 1: 旋转 / 翻转
            addView(makeToolbarButton(ACTION_ROTATE, R.drawable.ic_action_rotate, R.string.pin_action_rotate))
            addView(makeToolbarDivider())
            addView(makeToolbarButton(ACTION_FLIP, R.drawable.ic_action_flip, R.string.pin_action_flip))
            // Group 2: 分享 / 保存
            addView(makeToolbarDivider())
            addView(makeToolbarButton(ACTION_SHARE, R.drawable.ic_action_share, R.string.pin_action_share))
            addView(makeToolbarButton(ACTION_SAVE, R.drawable.ic_action_save, R.string.pin_action_save))
            // Group 3: 序列号 / 标注 / 箭头
            addView(makeToolbarDivider())
            addView(makeToolbarButton(ACTION_SERIAL, R.drawable.ic_action_serial, R.string.pin_action_serial))
            addView(makeToolbarButton(ACTION_ANNOTATE, R.drawable.ic_action_annotate, R.string.pin_action_annotate))
            addView(makeToolbarButton(ACTION_ARROW, R.drawable.ic_action_arrow, R.string.pin_action_arrow))
        }
    }

    private fun makeImageActionButton(action: String, icon: Int, description: Int): ImageButton =
        ImageButton(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.surfaceFill)
                setStroke(dpToPx(1), theme.surfaceStroke)
            }
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(theme.iconColor)
            contentDescription = context.getString(description)
            layoutParams = LayoutParams(dpToPx(CLOSE_BUTTON_SIZE_DP), dpToPx(CLOSE_BUTTON_SIZE_DP))
            alpha = 0f
            visibility = View.INVISIBLE
            isEnabled = false
            setOnClickListener { onActionClicked(action) }
            imageCard.addView(this)
        }

    private fun arrangeImageActions(width: Int) {
        val size = dpToPx(CLOSE_BUTTON_SIZE_DP)
        val margin = dpToPx(CLOSE_BUTTON_MARGIN_DP)
        val step = size + dpToPx(4f)
        val columns = ((width - margin * 2 + dpToPx(4f)) / step).coerceIn(1, 4)
        // Keep close at the top-right; fill each row towards the left.
        (imageActionButtons + closeButton).asReversed().forEachIndexed { index, button ->
            val end = margin + (index % columns) * step
            val top = margin + (index / columns) * step
            val params = button.layoutParams as LayoutParams
            if (params.marginEnd != end || params.topMargin != top || params.gravity != (Gravity.TOP or Gravity.END)) {
                button.layoutParams = LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.END
                    marginEnd = end
                    topMargin = top
                }
            }
        }
    }

    private fun restoreImageActionVisibility() {
        imageActionButtons.forEach { button ->
            button.animate().cancel()
            val shown = toolbarShown && !annotationMode
            button.visibility = if (shown) View.VISIBLE else View.INVISIBLE
            button.isEnabled = shown
            button.alpha = if (shown) 1f else 0f
            button.bringToFront()
        }
    }

    private fun makeToolbarButton(actionId: String, iconRes: Int, labelRes: Int): View {
        // Icon-only, no visible background — matches the iOS
        // control-center reference. The button is still 40dp square
        // for the touch target (M3 minimum) but visually shows only
        // the 18dp glyph.
        val buttonW = dpToPx(TOOLBAR_BUTTON_DP)
        val buttonH = dpToPx(TOOLBAR_HEIGHT_DP)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(buttonW, buttonH).apply {
                gravity = Gravity.CENTER
            }
            // Press feedback via a state-list drawable. We avoid
            // setOnTouchListener because on overlay windows the
            // wrapper's own onTouchEvent (which always returns true
            // to claim body drags) can intercept and swallow the
            // child button's click in some OEM dispatchers. A pure
            // state-driven background tint keeps the click path
            // untouched.
            val idle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#00000000".toColorInt())
            }
            val pressed = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#1F000000".toColorInt())
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), idle)
            }
            setOnClickListener { onActionClicked(actionId) }
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            // Theme-aware icon tint. The lock action swaps this
            // to the M3 error red on its own (see
            // [setLockButtonLocked]); all other actions read
            // from [theme.iconColor] so they automatically
            // re-tint when the system theme changes.
            imageTintList = android.content.res.ColorStateList.valueOf(
                theme.iconColor,
            )
            contentDescription = context.getString(labelRes)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(18), dpToPx(18),
            )
            // Tag the inner ImageView with the resource id of the
            // glyph it currently shows. The host can later look
            // this tag up to find the right view to swap icons on
            // — for the lock button, we want to flip
            // ic_action_lock_open ↔ ic_action_lock and recolor.
            tag = ToolbarIconTag(iconRes)
        }
        container.addView(icon)
        return container
    }

    /**
     * Build the "收起" toolbar action as a [FrameLayout] (rather
     * than a plain LinearLayout) so a small red zOrder badge
     * can be overlaid on the button's top-right corner. The
     * badge mirrors the one on the minimized floating ball —
     * showing the same number across both surfaces means the
     * user can keep track of "which pin is which" without
     * reading the image content.
     *
     * The button's underlying behavior is the same as any
     * other toolbar action: tap fires [ACTION_MINIMIZE].
     */
    private fun makeMinimizeButtonWithBadge(zOrderBadge: Int): View {
        val density = resources.displayMetrics.density
        val buttonW = dpToPx(TOOLBAR_BUTTON_DP)
        val buttonH = dpToPx(TOOLBAR_HEIGHT_DP)
        val container = android.widget.FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(buttonW, buttonH).apply {
                gravity = Gravity.CENTER
            }
            // Press feedback — same oval highlight as the
            // other toolbar buttons.
            val idle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#00000000".toColorInt())
            }
            val pressed = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#1F000000".toColorInt())
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), idle)
            }
            setOnClickListener { onActionClicked(ACTION_MINIMIZE) }
        }
        // The icon — same icon and tint rules as the other
        // toolbar buttons.
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_action_minimize)
            imageTintList = android.content.res.ColorStateList.valueOf(theme.iconColor)
            contentDescription = context.getString(R.string.pin_action_minimize)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dpToPx(18), dpToPx(18),
            ).apply {
                gravity = Gravity.CENTER
            }
            tag = ToolbarIconTag(R.drawable.ic_action_minimize)
        }
        container.addView(icon)
        // The badge — 16dp red circle, white text, anchored to
        // the top-right corner with a tiny 1dp inset so it
        // doesn't get clipped by the toolbar pill's rounded
        // edge.
        val badgeSize = (16 * density).toInt()
        val badgeInset = (1 * density).toInt()
        val badge = TextView(context).apply {
            text = String.format(Locale.getDefault(), "%d", zOrderBadge)
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#E53935".toColorInt())
                setStroke((1 * density).toInt(), Color.WHITE)
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                badgeSize, badgeSize,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = badgeInset
                marginEnd = badgeInset
            }
        }
        container.addView(badge)
        return container
    }

    /** Marker stored on each toolbar button's inner [ImageView] so
     *  callers (e.g. the lock-toggle action) can find the right
     *  view to mutate without rebuilding the toolbar. */
    private data class ToolbarIconTag(val iconRes: Int)

    private fun makeToolbarDivider(): View = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor("#22000000".toColorInt())
            setSize(dpToPx(1), dpToPx(20))
        }
        layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(20)).apply {
            gravity = Gravity.CENTER
        }
    }

    // ----------------------------------------------------------------
    // Selection / animation
    // ----------------------------------------------------------------

    /**
     * Programmatically show or hide the close button + toolbar.
     * Idempotent — calling with the current state is a no-op (no
     * animation kick).
     *
     *  - show(true): close button fades in (200ms), toolbar fades
     *    in and slides up (250ms).
     *  - show(false): reverse.
     */
    fun showToolbar(show: Boolean) {
        if (show == toolbarShown) return
        toolbarShown = show
        animateToolbarVisibilityChange()
    }

    fun isToolbarShown(): Boolean = toolbarShown

    /** Apply the image transform; the persisted bounds are synced separately by [applySnapshot]. */
    fun applyRotation(rotation: Int) {
        val deg = ((rotation % 360) + 360) % 360
        currentRotation = deg
        spec.rotation = deg
        imageView.rotation = 0f
        imageView.layoutParams = imageView.layoutParams.also { lp ->
            lp.width = LayoutParams.MATCH_PARENT
            lp.height = LayoutParams.MATCH_PARENT
        }
        applyCurrentRotation()
    }

    /** Update a matrix, never allocate a rotated bitmap during a gesture. */
    private fun applyCurrentRotation() {
        if (sourceBitmap == null) return
        val drawable = imageView.drawable ?: return
        // BitmapDrawable density can change its intrinsic size. The display
        // matrix uses drawable coordinates; export compensates for this below.
        val matrix = Matrix().apply {
            setValues(PinImageTransform.values(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                currentW.coerceAtLeast(1),
                currentH.coerceAtLeast(1),
                currentRotation, spec.flipped, annotationMode || currentRotation != 0,
            ))
        }
        imageView.scaleX = 1f
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.imageMatrix = matrix
    }

    /**
     * Toggle horizontal flip on the inner imageView. The imageCard
     * is unchanged.
     */
    fun applyFlip(flipped: Boolean) {
        spec.flipped = flipped
        applyCurrentRotation()
    }

    /**
     * Passthrough state. We DON'T use [WindowManager.LayoutParams.
     * FLAG_NOT_TOUCHABLE] for this — that flag would disable the
     * entire wrapper, including the toolbar, so the user could
     * never tap the "穿透" button a second time to disable it.
     *
     * Instead, we keep the wrapper always touchable and gate the
     * gesture in [onTouchEvent]: when [passthrough] is true and the
     * touch is inside [imageCard], we return `false` (don't
     * consume), so the event is propagated through
     * [WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL] to the
     * window behind the pin. Taps on the toolbar / close button
     * still get a `true` back from this view because those are
     * real children that handle their own touches before they
     * reach us.
     */
    private var passthrough: Boolean = false

    fun setPassthrough(enabled: Boolean) {
        if (passthrough == enabled) return
        passthrough = enabled
        // The service adds a separate, touchable exit button because this
        // window becomes non-touchable in passthrough mode. Keep only one
        // visual affordance by hiding the matching button inside this
        // window until passthrough is disabled again.
        applyPassthroughActionVisibility()
        // Visually distinguish passthrough state so the user
        // understands why the image is no longer responding to
        // taps. A subtle dashed outline on the imageCard (drawn
        // over the rounded clip) signals "I'm in passthrough"
        // without obscuring the pinned content.
        passthroughIndicator.alpha = if (enabled) 1f else 0f
        // Restore the close button's default icon and click
        // handler. The "exit passthrough" affordance is a
        // separate floating WindowManager window managed by the
        // service (see [onPassthroughChanged]). The tint comes
        // from the current theme so the close button stays in
        // sync with the toolbar pill and the rest of the pin
        // UI after light / dark mode switches.
        closeButton.setImageResource(R.drawable.ic_close)
        closeButton.imageTintList = ColorStateList.valueOf(theme.iconColor)
        // While in annotation mode the X close button is
        // the user's escape hatch: tapping it exits the
        // annotation mode (discarding any in-progress
        // strokes) rather than tearing the pin down. In
        // every other state the X button does the usual
        // thing: close the pin window via the host callback.
        closeButton.setOnClickListener {
            if (annotationMode) {
                exitAnnotationMode(discardChanges = true)
            } else {
                onCloseClicked()
            }
        }
        // When passthrough is on, add FLAG_NOT_TOUCHABLE so that
        // touches on the entire window pass through to the
        // underlying app. Returning false from onTouchEvent does
        // NOT forward touches to the underlying window — the
        // event is simply dropped. FLAG_NOT_TOUCHABLE is the
        // only reliable mechanism. The "exit passthrough"
        // button is a separate top-level WindowManager window
        // (added by the service) that is not affected by this
        // flag.
        applyPassthroughFlags()
        // Notify the host so it can show/hide the exit button.
        onPassthroughChanged?.invoke(enabled)
    }

    /** Optional callback invoked when the passthrough state
     *  changes. The host uses this to show / hide a separate
     *  floating button that lets the user exit passthrough
     *  (because the main window has FLAG_NOT_TOUCHABLE while
     *  passthrough is on, which blocks the button on the
     *  main window from receiving clicks). */
    var onPassthroughChanged: ((Boolean) -> Unit)? = null

    // ----------------------------------------------------------------
    // Annotation / drawing mode
    // ----------------------------------------------------------------

    /** Whether the pin is currently in annotation (drawing) mode. */
    private var annotationMode: Boolean = false

    /** The drawing overlay added on top of imageView when annotating. */
    private var drawingOverlay: DrawingOverlayView? = null

    /** Annotation toolbar (undo + done), shown only in annotation mode. */
    private var annotationToolbar: LinearLayout? = null

    /** Saved pin state before annotation, used to restore on exit. */
    private var preAnnotationX: Int = 0
    private var preAnnotationY: Int = 0
    private var preAnnotationW: Int = 0
    private var preAnnotationH: Int = 0

    /** Callback invoked when the user finishes annotating and the
     *  composited bitmap needs to be saved. The host (service) is
     *  responsible for persisting the new image and updating the
     *  pin's imageUri.
     */
    var onAnnotationDone: (suspend (compositedBitmap: android.graphics.Bitmap) -> Unit)? = null

    // ----------------------------------------------------------------
    // Serial number badge
    // ----------------------------------------------------------------

    /** The serial number badge view, or null if not shown. */
    private var _serialBadge: TextView? = null

    /** Public read-only access so the service can check if a badge is shown. */
    val serialBadge: TextView? get() = _serialBadge

    /**
     * Show a numbered badge on the image. The badge is a circular
     * view with the [number] displayed in white on a colored
     * background, positioned at the top-left corner of the image
     * (mirroring the close button's top-right position).
     */
    fun showSerialBadge(number: Int) {
        removeSerialBadge()
        spec.serialVisible = true
        val density = resources.displayMetrics.density
        val size = (32 * density).toInt()
        val margin = (8 * density).toInt()
        val badge = TextView(context).apply {
            text = String.format(Locale.getDefault(), "%d", number)
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#E53935".toColorInt())
            }
            layoutParams = LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = margin
                leftMargin = margin
            }
        }
        imageCard.addView(badge)
        _serialBadge = badge
    }

    /** Remove the serial number badge if it exists. */
    fun removeSerialBadge() {
        _serialBadge?.let { imageCard.removeView(it) }
        _serialBadge = null
        spec.serialVisible = false
    }

    fun setSerialVisible(visible: Boolean, number: Int = spec.zOrder) {
        if (visible) showSerialBadge(number) else removeSerialBadge()
    }

    /**
     * Read-only access to the current serial-badge number, or
     * `null` if the user hasn't enabled the badge for this
     * pin. Used by [PinOverlayService.minimizePin] to forward
     * the badge to the minimized ball so the user can still
     * see which pin the ball represents.
     *
     * The number is read from the badge TextView's own text
     * (the same value the user sees on the pin) rather than
     * from a separate field on the spec — this way the badge
     * is the single source of truth and there's no risk of
     * the live value drifting from the spec.
     */
    fun serialBadgeNumber(): Int? = _serialBadge?.text?.toString()?.toIntOrNull()

    /**
     * Update the live view when the underlying pin's
     * [com.pinpin.app.data.PinEntity.zOrder] changes (e.g.
     * after [com.pinpin.app.data.PinRepository.renormalizeZOrders]
     * following a delete or a toggle). The spec is updated
     * and the on-pin serial badge, if currently shown, is
     * re-stamped with the new number. The minimized-ball
     * badge is recomputed from [serialBadgeNumber] on the
     * next [PinOverlayService.minimizePin] call.
     */
    fun updateZOrder(newZ: Int) {
        if (spec.zOrder == newZ) return
        spec.zOrder = newZ
        _serialBadge?.text = String.format(Locale.getDefault(), "%d", newZ)
    }

    fun isPassthrough(): Boolean = passthrough

    /**
     * Re-apply the current system theme tokens to every themed
     * surface in the view. Called by the service whenever the
     * system reports a configuration change (light → dark or
     * dark → light), and also safe to call on demand.
     *
     * What gets re-themed:
     *  - close button background + icon tint
     *  - toolbar pill background (fill + stroke)
     *  - every toolbar button's icon tint (except the lock
     *    button, which is red when locked and follows the
     *    theme when unlocked — re-applied via
     *    [setLockButtonLocked])
     *
     * What stays constant across themes:
     *  - lock active red (deliberate signal color)
     *  - locked red border + passthrough orange border
     *    (both are state indicators, not surface tokens)
     *  - the image card's blue gradient background (it's the
     *    "image", not a surface)
     */
    fun applyTheme() {
        theme = PinTheme.of(context)

        // Image controls share the toolbar's theme tokens.
        (imageActionButtons + closeButton).forEach { button ->
            (button.background as? GradientDrawable)?.apply {
                setColor(theme.surfaceFill)
                setStroke(dpToPx(1), theme.surfaceStroke)
            }
            button.imageTintList = ColorStateList.valueOf(theme.iconColor)
        }

        // Toolbar pill
        (toolbarViewport.background as? android.graphics.drawable.GradientDrawable)?.apply {
            setColor(theme.surfaceFill)
            setStroke(dpToPx(1), theme.surfaceStroke)
        }

        // Every toolbar button's icon tint. We re-apply the
        // theme color to each, then re-apply the lock override
        // if the lock is currently engaged.
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is android.view.ViewGroup) {
                walkAndRetint(child)
            } else if (child is ImageView) {
                val tag = child.tag
                if (tag is ToolbarIconTag && tag.iconRes != R.drawable.ic_action_lock) {
                    child.imageTintList = ColorStateList.valueOf(theme.iconColor)
                }
            }
        }
        // Re-apply the lock button state on top of the generic
        // retint — setLockButtonLocked reads the current lock
        // state and writes the correct tint (red if locked,
        // theme color if unlocked).
        setLockButtonLocked(spec.locked)

        // Force the toolbar / close button to redraw with the
        // new drawables. The imageView, imageCard, and locked /
        // passthrough indicators are intentionally NOT re-themed.
        toolbar.invalidate()
        closeButton.invalidate()
    }

    /**
     * Recursively re-tint every [ImageView] inside a toolbar
     * button container, except the lock button's icon. Used by
     * [applyTheme] to walk the small button tree and swap icon
     * tints in place without rebuilding the toolbar.
     */
    private fun walkAndRetint(group: android.view.ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ImageView) {
                val tag = child.tag
                if (tag is ToolbarIconTag && tag.iconRes != R.drawable.ic_action_lock) {
                    child.imageTintList = ColorStateList.valueOf(theme.iconColor)
                }
            } else if (child is android.view.ViewGroup) {
                walkAndRetint(child)
            }
        }
    }

    /**
     * Enter annotation mode:
     *  1. Save current size/position
     *  2. Auto-zoom the image to fit the screen
     *  3. Add a [DrawingOverlayView] on top of the image
     *  4. Show an annotation toolbar (undo + done)
     *  5. Hide the regular toolbar and close button
     */
    fun enterAnnotationMode(drawingMode: DrawingOverlayView.Mode = DrawingOverlayView.Mode.PEN) {
        if (annotationMode) return
        if (annotationSaving || annotationRenderJob?.isActive == true || AnnotationSaveState.isSaving(pinId)) {
            android.widget.Toast.makeText(context, "上一份标注正在保存", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (sourceBitmap == null || imageLoadJob != null) {
            android.widget.Toast.makeText(context, "图片载入后再开始标注", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        annotationMode = true
        restoreImageActionVisibility()

        // 1. Save current state
        preAnnotationX = currentX
        preAnnotationY = currentY
        preAnnotationW = currentW
        preAnnotationH = currentH

        // Auto-fit the imageCard to the screen with 32dp
        // padding on every side, so the user has a large
        // canvas to draw on and the annotation toolbar
        // below the image is fully visible. The vertical
        // reserve accounts for: top gap + main row + bottom
        // gap. (The old brush sub-panel was removed so
        // there's no longer a sub-panel reserve.)
        val metrics = windowManager.currentWindowMetrics
        val screenW = metrics.bounds.width()
        val screenH = metrics.bounds.height()
        // Keep the canvas, close button and annotation toolbar clear of the
        // status/navigation bars and cutouts. Those system windows sit above
        // TYPE_APPLICATION_OVERLAY and would swallow taps on controls drawn
        // underneath them (e.g. the X button landing under the status bar).
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or
                android.view.WindowInsets.Type.displayCutout(),
        )
        // Centre the image and its bottom toolbar as one group inside the
        // safe area; see [PinWindowGeometry.annotationFit].
        val fit = PinWindowGeometry.annotationFit(
            screenWidth = screenW,
            screenHeight = screenH,
            insetLeft = insets.left,
            insetTop = insets.top,
            insetRight = insets.right,
            insetBottom = insets.bottom,
            padding = (resources.displayMetrics.density * 32).toInt(),
            toolbarGap = dpToPx(TOOLBAR_GAP_DP),
            toolbarHeight = dpToPx(TOOLBAR_HEIGHT_DP),
            currentWidth = currentW,
            currentHeight = currentH,
        )
        val fitX = fit.x
        val fitY = fit.y
        val fitW = fit.width
        val fitH = fit.height

        // Apply the zoom.
        currentW = fitW
        currentH = fitH
        currentX = fitX
        currentY = fitY
        applyLayoutSize(fitW, fitH)
        applyLayoutPosition(fitX, fitY)

        // Widen the wrapper to the full screen width so the
        // annotation toolbar (MATCH_PARENT) has maximum
        // horizontal space. The imageCard is centred inside
        // the wrapper via Gravity.CENTER_HORIZONTAL, so it
        // stays visually centred on screen even though the
        // wrapper is now wider than the image.
        val wrapperLp = layoutParams as? WindowManager.LayoutParams
        if (wrapperLp != null) {
            wrapperLp.width = screenW
            wrapperLp.x = 0
            currentX = 0
            runCatching { windowManager.updateViewLayout(this, wrapperLp) }
        }

        // Reset the imageView to fill the new imageCard. A
        // FIT_CENTER-scaled drawable would render at its
        // intrinsic resolution in the middle of the card,
        // leaving a huge band of the card's blue background
        // visible around it. CENTER_CROP scales the drawable
        // to fully cover the card so the user sees the image
        // edge-to-edge while drawing on it.
        //
        // Reuse the asynchronously decoded active image, including saved ink.
        applyCurrentRotation()

        // 3. Add drawing overlay
        val overlay = DrawingOverlayView(context).apply {
            mode = drawingMode
            shapeRecognitionEnabled = true
            strokeColor = selectedAnnotationColor
            // Eraser color matches the toolbar surface so the
            // rubber-band visually "wipes" the area. Light theme
            // uses the white surface; dark uses the dark surface.
            eraserColor = theme.surfaceFill
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            )
        }
        imageCard.addView(overlay)
        drawingOverlay = overlay
        // The drawing canvas must never cover the emergency exit.
        closeButton.animate().cancel()
        closeButton.visibility = View.VISIBLE
        closeButton.isEnabled = true
        closeButton.alpha = 1f
        closeButton.bringToFront()

        // 4. Show annotation toolbar
        val annToolbar = buildAnnotationToolbar()
        addView(annToolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dpToPx(TOOLBAR_HEIGHT_DP),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = currentH + dpToPx(TOOLBAR_GAP_DP)
        })
        annotationToolbar = annToolbar
        // Trigger an initial layout pass so the toolbar's
        // measured height matches the actual content (currently
        // a single 40dp row, but [repositionAnnotationToolbar]
        // also accounts for the brush sub-panel if it's been
        // opened).
        annToolbar.post { repositionAnnotationToolbar() }

        // 5. Hide the regular toolbar so the annotation
        //    toolbar below the image is the only thing
        //    drawing the user's attention — but keep the
        //    close button (X) visible as the escape hatch.
        //    Its click handler is wired to exit annotation
        //    mode (set up in [setOnClickListener] below),
        //    so the user always has a way to back out
        //    even if the annotation toolbar overflows off
        //    the bottom of the screen.
        toolbarViewport.visibility = View.INVISIBLE
        repositionAnnotationToolbar()
    }

    /**
     * Exit annotation mode:
     *  1. Composite the drawing onto the image
     *  2. Remove the drawing overlay and annotation toolbar
     *  3. Restore original size/position
     *  4. Show the regular toolbar and close button
     */
    fun exitAnnotationMode(discardChanges: Boolean = false) {
        if (!annotationMode) return
        val startedAt = SystemClock.uptimeMillis()
        Log.d(TAG, "Annotation exit requested: discard=$discardChanges saving=$annotationSaving")
        val overlay = drawingOverlay
        var snapshot: AnnotationSnapshot? = null
        val consumer = onAnnotationDone
        try {
            if (!discardChanges && overlay != null && overlay.hasStrokes()) {
                snapshot = captureAnnotation(overlay)
            }
        } catch (failure: Exception) {
            reportAnnotationFailure(failure)
        } catch (failure: OutOfMemoryError) {
            reportAnnotationFailure(failure)
        } finally {
            // Always remove the canvas, even if rendering fails. Otherwise
            // a non-annotation window can be left covered by an orphan canvas.
            annotationMode = false
            overlay?.let { imageCard.removeView(it) }
            drawingOverlay = null
            annotationToolbar?.let { removeView(it) }
            annotationToolbar = null
            annotationPenButton = null
            annotationEraserButton = null

            currentW = preAnnotationW
            currentH = preAnnotationH
            currentX = preAnnotationX
            currentY = preAnnotationY
            applyLayoutSize(preAnnotationW, preAnnotationH)
            applyLayoutPosition(preAnnotationX, preAnnotationY)
            applyCurrentRotation()

            toolbarViewport.animate().cancel()
            closeButton.animate().cancel()
            toolbarViewport.visibility = if (toolbarShown) View.VISIBLE else View.INVISIBLE
            toolbarViewport.alpha = if (toolbarShown) 1f else 0f
            toolbarViewport.translationY = 0f
            closeButton.visibility = if (toolbarShown) View.VISIBLE else View.INVISIBLE
            closeButton.isEnabled = toolbarShown
            closeButton.alpha = if (toolbarShown) 1f else 0f
            restoreImageActionVisibility()
            deferredSnapshot?.let {
                deferredSnapshot = null
                applySnapshot(it)
            }
        }

        // View state is restored before any pixel allocation; retain only immutable input.
        snapshot?.let { captured ->
            if (!AnnotationSaveState.begin(pinId)) {
                reportAnnotationFailure(IllegalStateException("Annotation save already in progress"))
                return@let
            }
            annotationSaving = true
            android.widget.Toast.makeText(context, "正在保存标注…", android.widget.Toast.LENGTH_SHORT).show()
            annotationRenderJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    var rendered: Bitmap? = null
                    try {
                        ImageRenderWork.run {
                            val bitmap = captured.render()
                            rendered = bitmap
                            if (consumer != null) {
                                withContext(Dispatchers.Main) { consumer(bitmap) }
                                rendered = null
                            }
                        }
                    } catch (failure: Exception) {
                        reportAnnotationFailure(failure)
                    } catch (failure: OutOfMemoryError) {
                        reportAnnotationFailure(failure)
                    } finally {
                        rendered?.let { if (!it.isRecycled) it.recycle() }
                        annotationSaving = false
                        AnnotationSaveState.end(pinId)
                    }
                }
            }
        }
        Log.d(TAG, "Annotation exit finished in ${SystemClock.uptimeMillis() - startedAt}ms")
    }

    private fun captureAnnotation(overlay: DrawingOverlayView): AnnotationSnapshot {
        val source = checkNotNull(sourceBitmap)
        val originalDrawable = checkNotNull(imageView.drawable)
        val inverse = Matrix()
        check(imageView.imageMatrix.invert(inverse))
        return AnnotationSnapshot(
            source, overlay.snapshot(), FloatArray(9).also { inverse.getValues(it) },
            originalDrawable.intrinsicWidth.coerceAtLeast(1),
            originalDrawable.intrinsicHeight.coerceAtLeast(1),
        )
    }

    private fun reportAnnotationFailure(failure: Throwable) {
        Log.e(TAG, "Could not finish annotation for $pinId", failure)
        android.widget.Toast.makeText(context, "标注未保存，已退出涂鸦，请重试", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun isAnnotationMode(): Boolean = annotationMode

    private var annotationPenButton: View? = null
    private var annotationEraserButton: View? = null

    /**
     * The color currently selected on the annotation toolbar's 8
     * swatch row. Updated when the user taps a swatch; the toolbar
     * is rebuilt only on entry to annotation mode, so the active
     * ring is updated in place by re-tagging the swatch views
     * (see [refreshSwatchSelection]). Default mirrors the historical
     * red swatch on the first two swatches of the old toolbar, so
     * an existing user picks up the same color they had before.
     */
    private var selectedAnnotationColor: Int = Color.RED

    /**
     * The eight swatch views on the annotation toolbar, kept by
     * reference so [refreshSwatchSelection] can update their ring
     * stroke (3dp white when selected, 1dp 33%-white otherwise)
     * without rebuilding the toolbar. Indexed by their position in
     * [ANNOTATION_SWATCH_COLORS].
     */
    private val swatchViews: MutableList<View> = mutableListOf()

    /** Scrollable drawing tools with fixed save, apply and cancel actions. */
    private fun buildAnnotationToolbar(): LinearLayout {
        val density = resources.displayMetrics.density
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Theme-aware surface so the toolbar blends with the
            // rest of the pin UI in both light and dark mode.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(theme.surfaceFill)
                cornerRadius = dpToPx(24f).toFloat()
                setStroke(dpToPx(1), theme.surfaceStroke)
            }
            clipToOutline = true
            elevation = 0f
            // Horizontal padding so the toolbar pill doesn't
            // touch the screen edges when the wrapper is full
            // screen width.
            val hPad = (12 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
        }

        // ---- Main row ----
        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1-8. 8-swatch color row (red, black, orange, yellow, green,
        //      cyan, blue, purple). The active swatch shows a 3dp
        //      white ring; the others show a 1dp 33%-white ring.
        //      Selection state is held in [selectedAnnotationColor]
        //      and re-applied to the swatches by
        //      [refreshSwatchSelection] on every change.
        swatchViews.clear()
        val palette = ANNOTATION_SWATCH_COLORS
        val swatchLabels = arrayOf("红", "黑", "橙", "黄", "绿", "青", "蓝", "紫")
        for (i in palette.indices) {
            val color = palette[i]
            val label = swatchLabels[i]
            val swatch = makeColorSwatch(
                color = color,
                label = label,
                isSelected = color == selectedAnnotationColor,
                onPick = { selectAnnotationColor(color) },
            )
            swatchViews.add(swatch)
            mainRow.addView(swatch)
        }
        mainRow.addView(makeVerticalDivider())
        // Shape recognition is built into the pen, not a separate tool.
        annotationPenButton = makeAnnotationActionButton(R.drawable.ic_action_annotate, "签字笔") {
            selectAnnotationMode(DrawingOverlayView.Mode.PEN)
        }.also { mainRow.addView(it) }
        annotationEraserButton = makeAnnotationActionButton(R.drawable.ic_action_eraser, "橡皮擦") {
            selectAnnotationMode(DrawingOverlayView.Mode.ERASER)
        }.also { mainRow.addView(it) }
        refreshAnnotationModeSelection()
        // 4b. Brush-size slider. Both pen and eraser read
        //     strokeWidth off the same drawingOverlay, so
        //     dragging here changes the size for whichever
        //     mode the user is in (and the next stroke they
        //     make in the OTHER mode). The leftmost child is
        //     a live preview circle whose diameter mirrors
        //     the current value, so the user can see what
        //     they're about to draw.
        mainRow.addView(makeBrushSizeSlider())
        mainRow.addView(makeVerticalDivider())
        // 5. 撤销
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_action_undo, "撤销") {
            drawingOverlay?.undo()
        })
        // 6. 重做
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_action_redo, "重做") {
            drawingOverlay?.redo()
        })
        // Keep exit actions outside the scrolling tools so they remain reachable.
        val exitActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        exitActions.addView(makeAnnotationActionButton(R.drawable.ic_action_save, "保存") {
            onActionClicked(ACTION_SAVE_ANNOTATE)
        })
        // 8. 应用 — apply annotation, restore size/position, exit
        exitActions.addView(makeAnnotationActionButton(R.drawable.ic_action_apply, "应用") {
            exitAnnotationMode()
        })
        // 9. 取消 — discard and exit
        exitActions.addView(makeAnnotationActionButton(R.drawable.ic_close, "取消", asCancel = true) {
            exitAnnotationMode(discardChanges = true)
        })

        toolbar.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dpToPx(TOOLBAR_HEIGHT_DP),
                    1f,
                )
                addView(mainRow)
            }
        )
        toolbar.addView(makeVerticalDivider())
        toolbar.addView(exitActions)
        // Pen and eraser switch directly; width stays on the shared slider.
        return toolbar
    }

    private fun repositionAnnotationToolbar() {
        val toolbar = annotationToolbar ?: return
        // No brush sub-panel anymore; toolbar height is
        // just the base pill plus the bottom gap.
        val baseH = dpToPx(TOOLBAR_HEIGHT_DP)
        val totalH = baseH + dpToPx(TOOLBAR_GAP_DP)
        (toolbar.layoutParams as? FrameLayout.LayoutParams)?.apply {
            height = totalH
            topMargin = currentH + dpToPx(TOOLBAR_GAP_DP)
        }
        toolbar.requestLayout()

        // Grow the wrapper so the sub-panel is not clipped by
        // the WindowManager height. The wrapper was sized for
        // image + gap + base toolbar only at enter-time; if the
        // sub-panel is now open, the wrapper's window height
        // must grow by the same amount to avoid the panel being
        // chopped at the wrapper's bottom edge.
        val wrapperLp = layoutParams as? WindowManager.LayoutParams
        if (wrapperLp != null) {
            val gap = dpToPx(TOOLBAR_GAP_DP)
            val newH = currentH + gap + totalH
            if (newH != wrapperLp.height) {
                wrapperLp.height = newH
                runCatching { windowManager.updateViewLayout(this, wrapperLp) }
            }
        }
    }


    private fun makeBrushSizeSlider(): View {
        val density = resources.displayMetrics.density
        val currentWidthPx = drawingOverlay?.strokeWidth ?: 6f
        // Live preview circle — diameter tracks the current
        // strokeWidth so the user gets immediate visual
        // feedback as they drag. Min 8dp so a 2dp brush is
        // still visible on the toolbar.
        val preview = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(theme.iconColor)
            }
            val px = (currentWidthPx * density).toInt()
                .coerceAtLeast((8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(px, px).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
        }
        // Divider between preview and slider, matching the
        // visual rhythm of the rest of the toolbar.
        val divider = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor("#22000000".toColorInt())
                setSize(dpToPx(1), dpToPx(20))
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(20)).apply {
                gravity = Gravity.CENTER
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
        }
        // The actual slider. Range: 2dp to 24dp (24px on a
        // baseline 1.0 density, 48px on 2.0 — the widest
        // brush is still well within the wrapper's imageCard
        // bounds).
        val slider = android.widget.SeekBar(context).apply {
            max = 100
            progress = ((currentWidthPx - 2f) / 22f * 100f).toInt()
                .coerceIn(0, 100)
            layoutParams = LinearLayout.LayoutParams(
                (140 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            contentDescription = "笔刷大小"
            // Keep the gesture inside SeekBar instead of letting the
            // overlay wrapper classify it as a window drag.
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    val baseWidth = 2f + (progress / 100f) * 22f
                    drawingOverlay?.strokeWidth = baseWidth
                    val newPx = (baseWidth * density).toInt()
                        .coerceAtLeast((8 * density).toInt())
                    preview.layoutParams = preview.layoutParams.apply {
                        width = newPx
                        height = newPx
                    }
                    preview.requestLayout()
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview)
            addView(divider)
            addView(slider)
        }
    }

        private fun makeColorSwatch(
        color: Int,
        label: String,
        isSelected: Boolean = false,
        onPick: () -> Unit,
    ): View {
        val density = resources.displayMetrics.density
        val size = (28 * density).toInt()
        // Selected swatch: keep the color fill but draw a thicker
        // ring on the outside so the active choice is obvious
        // against the other 7 swatches in the row.
        val strokePx = if (isSelected) (3 * density).toInt() else (1 * density).toInt()
        val strokeColor = if (isSelected) Color.WHITE else "#33FFFFFF".toColorInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
                marginStart = (2 * density).toInt()
                marginEnd = (2 * density).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                setStroke(strokePx, strokeColor)
            }
            // Own the entire touch stream so the wrapper's
            // onTouchEvent never sees anything from this swatch.
            // Without this, a sloppy tap that crossed the
            // swatch boundary on the way to UP would be
            // picked up as an in-card drag, and the wrapper
            // would translate the imageCard in response —
            // which is the "image shifts after tapping a
            // color" symptom the user reported. Returning
            // true here consumes the event; performClick()
            // fires the OnClickListener on ACTION_UP so the
            // color selection still goes through.
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    v.performClick()
                }
                true
            }
            setOnClickListener { onPick() }
        }
    }

    /**
     * Switch the active annotation color. Updates the drawing
     * overlay's stroke color (the next stroke will use it),
     * re-tags the toolbar swatches so the active one shows the
     * selection ring, and remembers the choice for the duration
     * of this annotation session.
     *
     * Picking even the already-active color returns to the pen;
     * only a changed color needs to refresh the swatch rings.
     */
    private fun selectAnnotationColor(color: Int) {
        selectAnnotationMode(DrawingOverlayView.Mode.PEN)
        if (selectedAnnotationColor == color) return
        selectedAnnotationColor = color
        drawingOverlay?.strokeColor = color
        refreshSwatchSelection()
    }

    private fun selectAnnotationMode(mode: DrawingOverlayView.Mode) {
        drawingOverlay?.mode = mode
        refreshAnnotationModeSelection()
    }

    private fun refreshAnnotationModeSelection() {
        val mode = drawingOverlay?.mode
        annotationPenButton?.isSelected = mode == DrawingOverlayView.Mode.PEN
        annotationEraserButton?.isSelected = mode == DrawingOverlayView.Mode.ERASER
    }

    /**
     * Re-stamp the active-ring stroke on each of the 8 swatch
     * views based on the current [selectedAnnotationColor]. The
     * swatches' backgrounds are GradientDrawables, so we mutate
     * their stroke in place — no view rebuild, no flicker. Called
     * once after entering annotation mode (initial state) and on
     * every [selectAnnotationColor] call.
     */
    private fun refreshSwatchSelection() {
        val density = resources.displayMetrics.density
        val palette = ANNOTATION_SWATCH_COLORS
        for ((i, view) in swatchViews.withIndex()) {
            val isActive = i < palette.size && palette[i] == selectedAnnotationColor
            val strokePx = if (isActive) (3 * density).toInt() else (1 * density).toInt()
            val strokeColor = if (isActive) Color.WHITE else "#33FFFFFF".toColorInt()
            (view.background as? android.graphics.drawable.GradientDrawable)?.apply {
                setStroke(strokePx, strokeColor)
            }
        }
    }

    private fun makeVerticalDivider(): View {
        val density = resources.displayMetrics.density
        return View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor("#22000000".toColorInt())
                setSize(dpToPx(1), dpToPx(20))
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(20)).apply {
                gravity = Gravity.CENTER
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
        }
    }

    private fun makeAnnotationActionButton(iconRes: Int, label: String, asCancel: Boolean = false, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            tooltipText = label
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                gravity = Gravity.CENTER
            }
            val idle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                // The cancel button uses a slightly red-tinted
                // background so it reads as "destructive" at a
                // glance, matching the M3 pattern of using red
                // for cancel/abort affordances.
                setColor(if (asCancel) "#1AEF5350".toColorInt() else "#00000000".toColorInt())
            }
            val pressed = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (asCancel) "#33EF5350".toColorInt() else "#1F000000".toColorInt())
            }
            val selected = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#334488FF".toColorInt())
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(android.R.attr.state_selected), selected)
                addState(intArrayOf(), idle)
            }
            setOnTouchListener { view, event ->
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
                // Cancel icon is the theme's red, to reinforce the
                // "destructive" meaning; the other action icons
                // follow the theme's normal icon color.
                val tint = if (asCancel) {
                    "#D32F2F".toColorInt()
                } else {
                    theme.iconColor
                }
                imageTintList = android.content.res.ColorStateList.valueOf(tint)
                layoutParams = LinearLayout.LayoutParams(dpToPx(18), dpToPx(18))
            })
        }
    }

    /** Actual screen bounds, including any window-manager inset or cutout offset. */
    fun closeButtonScreenRect(): android.graphics.Rect {
        return imageButtonScreenRect(closeButton)
    }

    fun passthroughButtonScreenRect(): android.graphics.Rect =
        imageButtonScreenRect(imageActionButtons.first())

    private fun imageButtonScreenRect(button: ImageButton): android.graphics.Rect {
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + button.width
        val bottom = top + button.height
        return android.graphics.Rect(left, top, right, bottom)
    }

    /**
     * Add or remove [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]
     * on the window based on the current passthrough state.
     *
     * When passthrough is on, the entire window becomes
     * non-touchable — touches pass through to the underlying app.
     * The service is responsible for creating a separate floating
     * button that the user can tap to disable passthrough.
     */
    private fun applyPassthroughFlags() {
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        if (passthrough) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(this, lp) }
    }

    /**
     * Whether the pin is locked (no drag, no resize). Reflects the
     * toolbar "锁定" action.
     */
    fun setLockedVisual(locked: Boolean) {
        spec.locked = locked
        // Show / hide the red border indicator so the user can
        // tell at a glance that the pin is locked.
        lockedIndicator.alpha = if (locked) 1f else 0f
        // Refresh the lock button's icon to mirror the new state
        // (open padlock when unlocked, closed padlock + red tint
        // when locked). The icon and tint both change so the user
        // has a clear visual signal of the toggle, separate from
        // any border/indicator we draw on the image.
        setLockButtonLocked(locked)
        // Refresh corner-handle visibility: when locked, the
        // handles are no longer useful. We don't redraw here —
        // [drawCornerHandles] reads [spec.locked] on its own.
        invalidate()
    }

    /**
     * Swap the "锁定" toolbar button's icon and tint to reflect
     * the current lock state. Safe to call from any thread; only
     * mutates View state, so it dispatches to the main looper if
     * called off-thread. No-op if the cached [lockButtonIcon] is
     * null (toolbar not yet built).
     */
    fun setLockButtonLocked(locked: Boolean) {
        val icon = lockButtonIcon ?: return
        val setVisuals = Runnable {
            icon.setImageResource(
                if (locked) R.drawable.ic_action_lock else R.drawable.ic_action_lock_open,
            )
            // Locked → M3 error red, unlocked → theme icon color
            // (light: dark gray, dark: white). The red is a
            // deliberate signal color and stays the same in both
            // themes; only the unlocked tint follows the theme.
            val tint = if (locked) {
                context.getColor(R.color.pin_lock_active_red)
            } else {
                theme.iconColor
            }
            icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
            // Re-tag so the iconRes on the tag stays in sync with
            // the displayed glyph (mostly defensive — the tag is
            // not read elsewhere yet, but it costs nothing to keep
            // it accurate).
            icon.tag = ToolbarIconTag(
                if (locked) R.drawable.ic_action_lock else R.drawable.ic_action_lock_open,
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            setVisuals.run()
        } else {
            icon.post(setVisuals)
        }
    }

    private fun animateToolbarVisibilityChange() {
        imageActionButtons.forEach { button ->
            val shown = toolbarShown && !annotationMode &&
                (button !== imageActionButtons.firstOrNull() || !passthrough)
            button.isEnabled = shown
            button.visibility = if (shown) View.VISIBLE else View.INVISIBLE
            button.bringToFront()
            button.animate().alpha(if (shown) 1f else 0f).setDuration(SELECTION_ANIM_MS).start()
        }
        closeButton.bringToFront()
        closeButton.isEnabled = toolbarShown
        closeButton.visibility = if (toolbarShown) View.VISIBLE else View.INVISIBLE
        toolbarViewport.visibility = if (toolbarShown) View.VISIBLE else View.INVISIBLE
        if (!annotationMode) applyLayoutSize(currentW, currentH)
        val targetAlpha = if (toolbarShown) 1f else 0f
        val targetTransY = if (toolbarShown) 0f else dpToPx(TOOLBAR_ANIM_TRANSLATE_DP).toFloat()

        closeButton.animate()
            .alpha(targetAlpha)
            .setDuration(SELECTION_ANIM_MS)
            .start()

        toolbarViewport.animate()
            .alpha(targetAlpha)
            .translationY(targetTransY)
            .setDuration(TOOLBAR_ANIM_MS)
            .start()
    }

    private fun applyPassthroughActionVisibility() {
        val button = imageActionButtons.firstOrNull() ?: return
        val shown = toolbarShown && !annotationMode && !passthrough
        button.isEnabled = shown
        button.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        button.bringToFront()
        button.animate().cancel()
        button.alpha = if (shown) 1f else 0f
    }

    // ----------------------------------------------------------------
    // Touch handling
    // ----------------------------------------------------------------
    private fun isImageGestureTarget(x: Float, y: Float): Boolean {
        if (x < imageCard.left || x >= imageCard.right ||
            y < imageCard.top || y >= imageCard.bottom
        ) return false
        val imageX = x - imageCard.left
        val imageY = y - imageCard.top
        return (imageActionButtons + closeButton).none {
            it.visibility == View.VISIBLE && it.isEnabled &&
                imageX >= it.left && imageX < it.right &&
                imageY >= it.top && imageY < it.bottom
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // In annotation mode, the drawing overlay handles all touches
        // inside the imageCard. Only touches outside (e.g. on the
        // annotation toolbar) are handled by the wrapper.
        if (annotationMode) {
            val x = event.x
            val y = event.y
            val cardLeft = imageCard.left.toFloat()
            val cardTop = imageCard.top.toFloat()
            val cardRight = cardLeft + imageCard.width
            val cardBottom = cardTop + imageCard.height
            if (x >= cardLeft && x <= cardRight && y >= cardTop && y <= cardBottom) {
                // Forward to drawing overlay
                val localEvent = MotionEvent.obtain(event)
                localEvent.offsetLocation(-cardLeft, -cardTop)
                drawingOverlay?.onTouchEvent(localEvent)
                localEvent.recycle()
                return true
            }
            // Outside imageCard — let annotation toolbar handle it
            return true
        }
        // Locked pins still need to let the user toggle the
        // toolbar by tapping the image. We do NOT bail out early
        // for [spec.locked] anymore — instead, the locked flag is
        // checked only when we'd actually start a drag or a
        // corner-resize, so the tap-to-toggle path still works.
        // The close button keeps working too because it lives
        // inside [imageCard] and gets its own touch dispatch.

        // Passthrough mode: any touch in the imageCard area (DOWN,
        // MOVE, UP, CANCEL — every action) must return false so
        // the gesture is propagated through FLAG_NOT_TOUCH_MODAL
        // to the window behind. We can't just gate on ACTION_DOWN:
        // the first DOWN returns false (so the parent's
        // onTouchEvent gets subsequent MOVE/UP events), but the
        // drag handler below would otherwise happily translate the
        // MOVE into a pin-drag. Skipping on every action ensures
        // the whole gesture is dropped, leaving the underlying
        // app's click/tap to play out untouched.
        //
        // Children like the toolbar buttons and the close button
        // still see their own touches first because ViewGroup
        // dispatches in reverse-z-order — so the toolbar remains
        // interactive even in passthrough mode, which is what
        // allows the user to tap "穿透" a second time to turn it
        // off.
        if (passthrough) {
            val x = event.x
            val y = event.y
            val cardLeft = imageCard.left.toFloat()
            val cardTop = imageCard.top.toFloat()
            val cardRight = cardLeft + imageCard.width
            val cardBottom = cardTop + imageCard.height
            if (x >= cardLeft && x <= cardRight && y >= cardTop && y <= cardBottom) {
                return false
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interacting = false
                cornerDragIndex = NO_CORNER_DRAG
                dragged = false
                // A gesture belongs to the image only when its DOWN hits
                // the image body, never wrapper padding or action controls.
                if (!isImageGestureTarget(event.x, event.y)) return false
                interacting = true
                downLayoutX = currentX
                downLayoutY = currentY
                gestureStartW = currentW
                gestureStartH = currentH
                downTimeMs = event.eventTime
                dragged = false
                val cornerIndex = hitTestCorner(event.x, event.y)
                if (cornerIndex >= 0) {
                    // A locked pin refuses to resize from any corner.
                    // We still return true on DOWN so the gesture
                    // doesn't escape, but we never call
                    // [applyCornerDrag] on MOVE.
                    if (spec.locked) {
                        return true
                    }
                    // Start a corner drag. See [applyCornerDrag] for the
                    // aspect-preserving geometry.
                    cornerDragIndex = cornerIndex
                    cornerMoved = false
                    val (anchorX, anchorY) = PinWindowGeometry.oppositeAnchor(
                        cornerIndex, downLayoutX, downLayoutY, currentW, currentH,
                    )
                    cornerDragAnchorX = anchorX
                    cornerDragAnchorY = anchorY
                    cornerDragDownFingerX = event.rawX
                    cornerDragDownFingerY = event.rawY
                    cornerDragInitialW = currentW
                    cornerDragInitialH = currentH
                    cornerDragInitialAspect = if (currentH > 0) currentW.toFloat() / currentH.toFloat() else 1f
                    // A corner drag is NOT a tap candidate — we don't
                    // want to toggle selection if the user happened to
                    // press a corner.
                    dragged = true
                    invalidate()
                } else {
                    // Plain body drag setup + tap candidate.
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downLayoutX = currentX
                    downLayoutY = currentY
                    downLocalX = event.x
                    downLocalY = event.y
                    downTimeMs = event.eventTime
                    dragged = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!interacting) return false
                if (cornerDragIndex >= 0) {
                    if (!cornerMoved && hypot(
                            event.rawX - cornerDragDownFingerX,
                            event.rawY - cornerDragDownFingerY,
                        ) > ViewConfiguration_getScaledTouchSlop()
                    ) cornerMoved = true
                    if (cornerMoved) applyCornerDrag(event.rawX, event.rawY)
                } else if (!spec.locked) {
                    // Body drag is disabled when locked. The DOWN
                    // handler still set up `downRawX`/etc., so a
                    // tap-to-toggle on UP is unaffected.
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragged && (
                        abs(dx) > ViewConfiguration_getScaledTouchSlop() ||
                            abs(dy) > ViewConfiguration_getScaledTouchSlop()
                        )
                    ) {
                        dragged = true
                    }
                    if (dragged) {
                        val (cx, cy) = clampToScreen(
                            x = downLayoutX + dx,
                            y = downLayoutY + dy,
                            w = currentW,
                            h = currentH,
                        )
                        if (cx != currentX || cy != currentY) {
                            currentX = cx
                            currentY = cy
                            applyLayoutPosition(cx, cy)
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!interacting) return false
                cornerDragIndex = NO_CORNER_DRAG
                interacting = false
                dragged = false
                // Resizing the overlay window during a corner drag can
                // make Android cancel the current touch stream. The
                // geometry already applied before cancellation is still
                // valid; restoring the pre-gesture values here makes the
                // image visibly jump away. Keep the last valid geometry
                // and persist it just like ACTION_UP.
                val deferred = deferredSnapshot
                deferredSnapshot = null
                if (currentX != downLayoutX || currentY != downLayoutY ||
                    currentW != gestureStartW || currentH != gestureStartH
                ) {
                    deferred?.let { applySnapshot(it.copy(
                        x = currentX, y = currentY,
                        width = currentW.toFloat(), height = currentH.toFloat(),
                    )) }
                    awaitingGeometryCommit = true
                    onPositionChanged(currentX, currentY)
                } else {
                    deferred?.let(::applySnapshot)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!interacting) return false
                val changed = currentX != downLayoutX || currentY != downLayoutY ||
                    currentW != gestureStartW || currentH != gestureStartH
                if (cornerDragIndex >= 0) {
                    cornerDragIndex = NO_CORNER_DRAG
                    invalidate()
                } else if (!dragged) {
                    // The user pressed and released without moving past
                    // touch slop. Classify by duration: a short tap
                    // toggles toolbar; a long press is ignored (no
                    // affordance for it in this UX).
                    val dur = event.eventTime - downTimeMs
                    if (dur < TAP_THRESHOLD_MS) {
                        showToolbar(!toolbarShown)
                    }
                }
                interacting = false
                // A completed local gesture supersedes old geometry snapshots.
                val deferred = deferredSnapshot
                deferredSnapshot = null
                if (changed) {
                    deferred?.let { applySnapshot(it.copy(
                        x = currentX, y = currentY,
                        width = currentW.toFloat(), height = currentH.toFloat(),
                    )) }
                    awaitingGeometryCommit = true
                    onPositionChanged(currentX, currentY)
                } else {
                    deferred?.let(::applySnapshot)
                }
                dragged = false
            }
            MotionEvent.ACTION_OUTSIDE -> {
                // Fires when the user taps outside the wrapper (requires
                // FLAG_WATCH_OUTSIDE_TOUCH + FLAG_NOT_TOUCH_MODAL set in
                // init). Dismisses the toolbar / close button without
                // any other side effects. Works even when passthrough
                // or locked — the toolbar auto-hides normally.
                if (toolbarShown) showToolbar(false)
            }
        }
        return true
    }

    private fun ViewConfiguration_getScaledTouchSlop(): Int =
        android.view.ViewConfiguration.get(context).scaledTouchSlop

    /**
     * Hit-test the 4 corner handles. Returns the corner index
     * (0=TL, 1=TR, 2=BL, 3=BR) if [localX] / [localY] (view-local
     * coords, as `event.x` / `event.y`) are within
     * [CORNER_HIT_RADIUS_DP] of any of the 4 corners; -1 otherwise.
     *
     * The 4 corner positions are in IMAGE coordinates
     * (`(0,0), (W,0), (0,H), (W,H)`) — NOT in wrapper coordinates —
     * because the corners belong to the image, and the toolbar's
     * extra height should not be reachable from the corner handles.
     * To find the BL handle the user taps inside the image (just
     * above the toolbar) or in the now-empty toolbar area when the
     * toolbar is hidden (visibility INVISIBLE → no hit area, taps
     * fall through to this view).
     *
     * **Important**: [localX]/[localY] are in *wrapper* coordinates
     * (the outer FrameLayout that also contains the toolbar), but the
     * corner positions are in *imageCard* coordinates. We must offset
     * the touch point into imageCard space before comparing, otherwise
     * the BL/BR handles are unreachable (the y-offset from the toolbar
     * overhead causes every hit to miss).
     */
    private fun hitTestCorner(localX: Float, localY: Float): Int {
        if (currentW <= 0 || currentH <= 0) return -1
        // Map wrapper-local touch coords into imageCard-local coords.
        // imageCard is the first child at (0,0) with size (currentW, currentH).
        val imgX = localX - imageCard.left
        val imgY = localY - imageCard.top
        if ((imageActionButtons + closeButton).any {
                it.visibility == View.VISIBLE && it.isEnabled &&
                    imgX >= it.left && imgX < it.right && imgY >= it.top && imgY < it.bottom
            }) return -1
        val corner = PinWindowGeometry.cornerAt(
            imgX, imgY, currentW, currentH,
            inset = cornerRadiusPx,
            hitRadius = dpToPx(CORNER_HIT_RADIUS_DP).toFloat(),
        )
        // The close button sits in the TR corner area, which overlaps the
        // TR handle's hit region. Only suppress the handle when the user
        // actually tapped *inside* the close button, and only when the
        // toolbar is shown (the button is alpha 0 otherwise). The close
        // button is a child of imageCard so the same imgX/imgY coords apply.
        if (corner == CORNER_TR && toolbarShown) {
            val cb = closeButtonRect()
            if (imgX >= cb.left && imgX <= cb.right && imgY >= cb.top && imgY <= cb.bottom) {
                return -1
            }
        }
        return corner
    }

    private fun closeButtonRect(): android.graphics.RectF {
        val size = dpToPx(CLOSE_BUTTON_SIZE_DP).toFloat()
        val inset = dpToPx(CLOSE_BUTTON_MARGIN_DP).toFloat()
        return android.graphics.RectF(
            currentW - size - inset,
            inset,
            currentW - inset,
            inset + size,
        )
    }

    private fun anchorScreenPosition(dragIndex: Int): Pair<Float, Float> {
        val location = IntArray(2)
        imageCard.getLocationOnScreen(location)
        val x: Float
        val y: Float
        when (dragIndex) {
            CORNER_TL, CORNER_BL -> x = (location[0] + currentW).toFloat()
            CORNER_TR, CORNER_BR -> x = location[0].toFloat()
            else -> x = 0f
        }
        when (dragIndex) {
            CORNER_TL, CORNER_TR -> y = (location[1] + currentH).toFloat()
            CORNER_BL, CORNER_BR -> y = location[1].toFloat()
            else -> y = 0f
        }
        return x to y
    }

    /** The aspect ratio (width / height) at the start of a corner drag, used to enforce proportional scaling. */
    private var cornerDragInitialAspect: Float = 1f

    private fun applyCornerDrag(fingerX: Float, fingerY: Float) {
        val index = cornerDragIndex
        if (index < 0) return

        val frame = PinWindowGeometry.resizeFromCorner(
            corner = index,
            anchorX = cornerDragAnchorX,
            anchorY = cornerDragAnchorY,
            initialWidth = cornerDragInitialW,
            initialHeight = cornerDragInitialH,
            fingerDx = fingerX - cornerDragDownFingerX,
            fingerDy = fingerY - cornerDragDownFingerY,
            minSize = MIN_SIZE,
            maxSize = MAX_SIZE,
        ) ?: return
        val newW = frame.width
        val newH = frame.height
        var newX = frame.x
        var newY = frame.y
        // Corner drags used to bypass the same bounds check as body drags.
        // When a finger crossed a screen edge, the resulting WindowManager
        // coordinates could move most of the pin off-screen and look like
        // the image had jumped away. Keep the resize visible as well.
        val (clampedX, clampedY) = clampToScreen(newX, newY, newW, newH)
        newX = clampedX
        newY = clampedY
        if (newW != currentW || newH != currentH || newX != currentX || newY != currentY) {
            currentW = newW
            currentH = newH
            currentX = newX
            currentY = newY
            applyLayoutSize(newW, newH)
            applyLayoutPosition(newX, newY)
            invalidate()
        }
    }

    private fun drawCornerHandles(canvas: Canvas) {
        // Corner handles are intentionally not drawn — the resize
        // affordance is implicit (tap & drag any of the four
        // image corners). Keeping the hit-test in place preserves
        // the resize-by-corner gesture without the visual dots.
    }

    private fun clampToScreen(x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        // Wrapper height = image + gap + toolbar — clamp on the
        // wrapper so we never push the toolbar entirely off-screen
        // (the user might still want to see it / tap it).
        val toolbarOverhead = dpToPx(TOOLBAR_GAP_DP) + toolbarRows(w) * dpToPx(TOOLBAR_HEIGHT_DP)
        val wrapperH = h + toolbarOverhead
        return PinWindowGeometry.clampPosition(x, y, w, wrapperH, bounds.right, bounds.bottom)
    }

    private fun applyLayoutPosition(x: Int, y: Int) {
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = x
        lp.y = y
        runCatching { windowManager.updateViewLayout(this, lp) }
        onLayoutChanged()
    }

    internal fun updateLayoutFromExternal(x: Int, y: Int) {
        if (annotationMode) return
        currentX = x
        currentY = y
        applyLayoutPosition(x, y)
    }

    internal fun applySnapshot(entity: PinEntity) {
        if (annotationMode || interacting) {
            deferredSnapshot = entity
            return
        }
        val geometryMatches = currentX == entity.x && currentY == entity.y &&
            currentW == entity.width.toInt() && currentH == entity.height.toInt()
        if (geometryMatches) awaitingGeometryCommit = false
        if (!awaitingGeometryCommit) {
            if (currentX != entity.x || currentY != entity.y) updateLayoutFromExternal(entity.x, entity.y)
            if (currentW != entity.width.toInt() || currentH != entity.height.toInt()) {
                updateSizeFromExternal(entity.width.toInt(), entity.height.toInt())
            }
        }
        if (spec.locked != entity.locked) setLockedVisual(entity.locked)
        if (spec.rotation != entity.rotation) applyRotation(entity.rotation)
        if (spec.flipped != entity.flipped) applyFlip(entity.flipped)
        val imageChanged = spec.imageUri != entity.imageUri
        spec.imageUri = entity.imageUri
        if (spec.annotatedImageUri != entity.annotatedImageUri) {
            setAnnotatedImageUri(entity.annotatedImageUri)
        } else if (imageChanged) {
            loadImage(spec.annotatedImageUri ?: entity.imageUri)
        }
        setSerialVisible(entity.serialVisible, entity.zOrder)
        updateZOrder(entity.zOrder)
    }

    internal fun geometryCommitFailed() {
        awaitingGeometryCommit = false
    }

    internal fun updateSizeFromExternal(w: Int, h: Int) {
        if (annotationMode) return
        currentW = w
        currentH = h
        applyLayoutSize(w, h)
    }

    private fun applyLayoutSize(w: Int, h: Int) {
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        arrangeImageActions(w)
        arrangeToolbar(w)
        val toolbarHeight = toolbarRows(w) * dpToPx(TOOLBAR_HEIGHT_DP)
        val toolbarOverhead = dpToPx(TOOLBAR_GAP_DP) + toolbarHeight
        // Keep the input window aligned to the image. The toolbar viewport
        // scrolls inside this width when the image is narrower than its content.
        lp.width = w
        lp.height = h + if (toolbarShown) toolbarOverhead else 0
        runCatching { windowManager.updateViewLayout(this, lp) }
        // Sync the imageCard dimensions so the image scales rather
        // than gets clipped. Without this the imageCard stays at its
        // initial size and clipToOutline crops the content.
        imageCard.layoutParams = imageCard.layoutParams.apply {
            width = w
            height = h
        }
        // Reposition the toolbar so it follows the new imageCard
        // bottom. The toolbar uses WRAP_CONTENT, so we only need to
        // update its topMargin — the width is computed from its
        // children and stays compact.
        (toolbarViewport.layoutParams as? FrameLayout.LayoutParams)?.apply {
            topMargin = h + dpToPx(TOOLBAR_GAP_DP)
            height = toolbarHeight
        }
        // Mutating layoutParams alone doesn't trigger a re-layout on
        // most Android versions — the child keeps its old measured
        // size until something forces a measure pass. Explicitly
        // request a layout on the affected children.
        imageCard.requestLayout()
        toolbarViewport.requestLayout()
        // Re-render the rotated bitmap against the (potentially
        // new) source dimensions, in case the user resized from a
        // corner handle after rotating. Without this the rotated
        // bitmap would be the one generated against the pre-resize
        // card size and might look low-res on the new, larger card.
        applyCurrentRotation()
        // Keep the passthrough indicator aligned to the imageCard
        // bounds so the orange outline always hugs the visible
        // image, even after the user resizes from a corner.
        passthroughIndicator.layoutParams = passthroughIndicator.layoutParams.apply {
            width = w
            height = h
        }
        passthroughIndicator.requestLayout()
        // Keep the locked indicator aligned to the imageCard bounds
        // so the red border always hugs the visible image, even
        // after the user resizes from a corner.
        lockedIndicator.layoutParams = lockedIndicator.layoutParams.apply {
            width = w
            height = h
        }
        lockedIndicator.requestLayout()
        // Child positions (including the passthrough action button) are
        // resolved during the next layout pass, so notify after that pass.
        postOnAnimation { onLayoutChanged() }
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()

    /** Int overload — the `f` suffix is annoying to scatter across
     *  every literal call site. */
    private fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat())


    companion object {
        private const val TAG = "PinWindowView"

        // Pin image dimension clamps.
        private const val MIN_SIZE = 160
        private const val MAX_SIZE = 4096

        // Corner-handle geometry (see [applyCornerDrag]).
        private const val CORNER_HIT_RADIUS_DP = 24f
        private const val HANDLE_RADIUS_DP = 7f
        private const val HANDLE_STROKE_DP = 2f

        /**
         * Eight fixed colors on the annotation toolbar's swatch row.
         * The first two (red, black) are the original choices that
         * the toolbar shipped with; the other six fill out a basic
         * 8-color palette. The 9th slot (rainbow / palette icon)
         * is added later as a "调色盘" entry point for custom HSV
         * — not part of this list because it does not pick a color
         * itself.
         *
         * Order matters: the toolbar renders them in array order,
         * left to right.
         */
        val ANNOTATION_SWATCH_COLORS: IntArray = intArrayOf(
            Color.RED,
            Color.BLACK,
            "#FF9800".toColorInt(), // orange
            "#FFEB3B".toColorInt(), // yellow
            "#4CAF50".toColorInt(), // green
            "#00BCD4".toColorInt(), // cyan
            "#2196F3".toColorInt(), // blue
            "#9C27B0".toColorInt(), // purple
        )

        // Layout measurements.
        private const val CORNER_RADIUS_DP = 16f
        private const val TOOLBAR_HEIGHT_DP = 40f
        private const val TOOLBAR_GAP_DP = 4f
        private const val TOOLBAR_BUTTON_DP = 40f
        private const val CLOSE_BUTTON_SIZE_DP = 40f
        private const val CLOSE_BUTTON_MARGIN_DP = 8f

        // Tap-to-select timing. Quick down+up (< threshold) toggles
        // selection; long press is a no-op (no affordance).
        private const val TAP_THRESHOLD_MS = 300L

        // Selection animation.
        private const val SELECTION_ANIM_MS = 200L
        private const val TOOLBAR_ANIM_MS = 250L
        private const val TOOLBAR_ANIM_TRANSLATE_DP = 16f

        // Corner-drag indices.
        private const val NO_CORNER_DRAG = PinWindowGeometry.NO_CORNER
        private const val CORNER_TL = PinWindowGeometry.CORNER_TL
        private const val CORNER_TR = PinWindowGeometry.CORNER_TR
        private const val CORNER_BL = PinWindowGeometry.CORNER_BL
        private const val CORNER_BR = PinWindowGeometry.CORNER_BR

        // Toolbar action ids — passed to [onActionClicked] verbatim.
        // The host (PinOverlayService) is responsible for dispatching
        // each id to the appropriate feature module.
        const val ACTION_ROTATE = "rotate"
        const val ACTION_FLIP = "flip"
        const val ACTION_SHARE = "share"
        const val ACTION_SAVE = "save"
        const val ACTION_SERIAL = "serial"
        const val ACTION_ANNOTATE = "annotate"
        const val ACTION_ARROW = "arrow"
        const val ACTION_PASSTHROUGH = "passthrough"
        const val ACTION_LOCK = "lock"
        // "Minimize" — tear down the pin window and replace it
        // with a small floating ball that shows the pin's
        // image. Tapping the ball restores the full pin.
        const val ACTION_MINIMIZE = "minimize"
        // Internal-only — fired by the "×" cancel button on the
        // annotation toolbar, never from the main toolbar.
        const val ACTION_CANCEL_ANNOTATE = "cancel_annotate"
        // "Apply" — composite annotation onto the pin image, then
        // restore the pin to its original size / position. Does
        // NOT save to the device gallery.
        const val ACTION_APPLY_ANNOTATE = "apply_annotate"
        // "Save" — apply + write a copy of the composited image
        // to MediaStore (Pictures/pinpin).
        const val ACTION_SAVE_ANNOTATE = "save_annotate"
    }
}
