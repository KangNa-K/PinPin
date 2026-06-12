package com.pinpin.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.pinpin.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

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
class PinWindowView(
    context: Context,
    val id: String,
    /** Mutable so the "锁定" toolbar action can flip the flag on
     *  the live view without going through Room. The repository
     *  still persists the change. */
    var spec: PinSpec,
    private val onPositionChanged: (x: Int, y: Int) -> Unit,
    private val onSizeChanged: (w: Float, h: Float) -> Unit,
    private val onCloseClicked: () -> Unit,
    private val onActionClicked: (actionId: String) -> Unit,
) : FrameLayout(context) {

    data class PinSpec(
        val label: String,
        /** Mutable so the toolbar can re-render after [loadImage]
         *  resolves and we need to swap the placeholder for the
         *  real drawable. */
        var imageUri: String?,
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
    private val label: TextView?
    private val toolbar: LinearLayout

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ----------------------------------------------------------------
    // Corner-handle rendering (drawn on top of the imageCard content).
    // ----------------------------------------------------------------
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inactiveHandleFill = Color.WHITE
    private val inactiveHandleStroke = Color.parseColor("#7AA7FF")
    private val activeHandleFill = Color.parseColor("#7AA7FF")
    private val activeHandleStroke = Color.WHITE

    /** Rounded-corner radius of the image card. Captured for handle positioning. */
    private var cornerRadiusPx: Float = 0f

    init {
        val initialImageW = spec.widthPx
        val initialImageH = spec.heightPx
        val toolbarH = dpToPx(TOOLBAR_HEIGHT_DP)
        val gapH = dpToPx(TOOLBAR_GAP_DP)
        val minToolbarW = dpToPx(MIN_TOOLBAR_WIDTH_DP)
        // Wrapper is wide enough to fit the toolbar at its full
        // design width. If the image is narrower, the wrapper grows
        // and the imageCard is centered inside it so the toolbar
        // can sit centered on the image without being clipped. If
        // the image is wider than the toolbar, the wrapper is the
        // image's width and the toolbar floats below.
        val wrapperW = maxOf(initialImageW, minToolbarW)
        val wrapperH = initialImageH + gapH + toolbarH

        layoutParams = WindowManager.LayoutParams(
            wrapperW,
            wrapperH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
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
        // imageCard is centered horizontally inside the wrapper. If
        // the wrapper is wider than the image (because the toolbar's
        // minimum width forced a larger wrapper), the extra space is
        // split equally on both sides so the toolbar — which is
        // itself centered in the wrapper — stays aligned with the
        // image's horizontal center.
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
                intArrayOf(Color.parseColor("#7AA7FF"), Color.parseColor("#3D5DBF")),
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
                    Color.parseColor("#80FF6F00"),
                )
                cornerRadius = cornerRadiusPx
                setColor(Color.parseColor("#00000000"))
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
                    Color.parseColor("#E53935"),
                )
                cornerRadius = cornerRadiusPx
                setColor(Color.parseColor("#00000000"))
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
            setOnClickListener { onCloseClicked() }
            alpha = 0f
        }
        imageCard.addView(closeButton)

        // Optional test-pin label (shown only when there's no image).
        val imageUri = spec.imageUri
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
        // Horizontal pill of 9 actions, 4 dividers between groups.
        // Hidden by default (alpha 0). `clickable = true` so taps in
        // its bounds are consumed and don't leak to the corner-drag
        // hit-test (which would otherwise start a corner drag the
        // moment the user tapped near the BL/BR corner of the image).
        toolbar = buildToolbar().apply {
            alpha = 0f
            translationY = dpToPx(TOOLBAR_ANIM_TRANSLATE_DP).toFloat()
            isClickable = true
        }
        addView(
            toolbar,
            FrameLayout.LayoutParams(
                // WRAP_CONTENT, not MATCH_PARENT: the toolbar pill
                // should size to its content (9 icon buttons + 4
                // dividers). If we matched parent, it would stretch
                // to the imageCard width on resize, compressing each
                // button and hiding affordances.
                FrameLayout.LayoutParams.WRAP_CONTENT,
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

        setOnClickListener { /* consume taps so they don't fall through */ }
    }

    internal fun loadImage(uriString: String) {
        scope.launch {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@launch
            val request = ImageRequest.Builder(context)
                .data(uri)
                .target { drawable -> imageView.setImageDrawable(drawable) }
                .crossfade(true)
                .build()
            val result = context.imageLoader.execute(request)
            if (result !is SuccessResult) {
                Log.w(TAG, "Image load failed for $uriString; falling back to placeholder")
            }
        }
    }

    /**
     * Synchronously decode the original pin image and hand it to
     * [imageView]. Used by [enterAnnotationMode] when the pin
     * zooms up to the full screen — Coil's cached drawable was
     * sampled at the pin's original thumbnail size, so when the
     * imageView suddenly jumps to fitW × fitH (the screen), a
     * FIT_CENTER-scaled drawable would render as a tiny postage
     * stamp surrounded by the card's blue gradient.
     *
     * We decode the full-resolution bitmap with an [inSampleSize]
     * computed to keep memory bounded (target ≤ 2× the view
     * dimensions), and set it as a [BitmapDrawable] directly on
     * the imageView with `CENTER_CROP` so it fills the new
     * imageCard. Synchronous on purpose: we need the bitmap in
     * place before the user starts drawing, so they never see a
     * blank blue card while Coil reloads in the background.
     */
    private fun loadOriginalBitmapSync(targetW: Int, targetH: Int) {
        val uriStr = spec.imageUri ?: return
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return
        val bmp = runCatching {
            val resolver = context.contentResolver
            // First pass: just bounds, to compute inSampleSize
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOpts)
            }
            val origW = boundsOpts.outWidth
            val origH = boundsOpts.outHeight
            if (origW <= 0 || origH <= 0) return@runCatching null
            // Sample down so neither dimension exceeds 2× the
            // target. The 2× headroom gives CENTER_CROP enough
            // pixels to cover the view cleanly without bloating
            // memory for a 4K screenshot.
            var sample = 1
            while (origW / sample > targetW * 2 || origH / sample > targetH * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull() ?: return
        imageView.setImageBitmap(bmp)
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
            // Group 4: 穿透 / 锁定 / 收起
            addView(makeToolbarDivider())
            addView(makeToolbarButton(ACTION_PASSTHROUGH, R.drawable.ic_action_passthrough, R.string.pin_action_passthrough))
            // Default state is *unlocked*, so start with the
            // open-padlock glyph. The closed glyph + red tint are
            // applied on the first tap via [setLockButtonLocked].
            addView(makeToolbarButton(ACTION_LOCK, R.drawable.ic_action_lock_open, R.string.pin_action_lock).also { btn ->
                // The inner ImageView is the only child and is
                // tagged with [ToolbarIconTag] by
                // [makeToolbarButton]. Walk the small tree to
                // cache the handle so the toggle is O(1) later.
                var icon: ImageView? = null
                if (btn is android.view.ViewGroup) {
                    for (i in 0 until btn.childCount) {
                        val child = btn.getChildAt(i)
                        if (child is ImageView) { icon = child; break }
                    }
                }
                lockButtonIcon = icon
            })
            // Group 5: 收起 — tear down the pin window and
            // replace it with a floating ball that shows the
            // pin's image. Tapping the ball restores the full
            // pin. Lives at the very right of the toolbar so the
            // user is unlikely to hit it accidentally (it's
            // separated from the destructive lock by a divider).
            // The number badge is intentionally NOT shown here —
            // it lives on the floating ball so the user only
            // sees it once the pin has actually been minimized.
            addView(makeToolbarDivider())
            addView(makeToolbarButton(ACTION_MINIMIZE, R.drawable.ic_action_minimize, R.string.pin_action_minimize))
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
                setColor(Color.parseColor("#00000000"))
            }
            val pressed = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#1F000000"))
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
                setColor(Color.parseColor("#00000000"))
            }
            val pressed = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#1F000000"))
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
            text = zOrderBadge.toString()
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
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
            setColor(Color.parseColor("#22000000"))
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

    /**
     * Apply a 0/90/180/270 rotation to the imageView. The imageCard
     * itself is NOT rotated — only the inner imageView — so the
     * rounded outline and the toolbar layout stay aligned with the
     * pin's bounding box.
     *
     * Implementation: rather than just setting `imageView.rotation`
     * (which leaves a FIT_CENTER-scaled bitmap centered in the
     * imageCard's original rectangle, causing a huge band of the
     * card's blue background to show through when the card's
     * aspect ratio differs from the image's), we *also* swap the
     * imageView's layoutParams dimensions. That way the rotated
     * imageView bounding box matches the rotation 90°/270° swap,
     * the FIT_CENTER bitmap is laid out in the swapped rectangle
     * (which already has the rotated aspect ratio), and no blue
     * gap appears.
     */
    fun applyRotation(rotation: Int) {
        val deg = ((rotation % 360) + 360) % 360
        // Snapshot the card's current size BEFORE we mutate anything
        // so we can compute the swapped imageView rectangle.
        val cardW = imageCard.width
        val cardH = imageCard.height
        if (cardW <= 0 || cardH <= 0) return
        imageView.rotation = deg.toFloat()
        val lp = imageView.layoutParams as? LayoutParams ?: return
        if (deg == 90 || deg == 270) {
            // Swap: imageView layout box becomes cardH × cardW, so
            // after the 90° rotation the visible content fills the
            // full cardW × cardH.
            lp.width = cardH
            lp.height = cardW
        } else {
            // 0°/180°: keep MATCH_PARENT so the bitmap is scaled
            // against the card's natural rectangle.
            lp.width = LayoutParams.MATCH_PARENT
            lp.height = LayoutParams.MATCH_PARENT
        }
        // Cancel the rotation's pivot offset: by default a rotated
        // view's rotation pivot is the center of its un-rotated
        // layout box. After we change the layoutParams to a
        // swapped rectangle, the layout box's center may not be the
        // card's center anymore, so we re-center via gravity.
        lp.gravity = Gravity.CENTER
        imageView.layoutParams = lp
        imageView.requestLayout()
    }

    /**
     * Toggle horizontal flip on the inner imageView. The imageCard
     * is unchanged.
     */
    fun applyFlip(flipped: Boolean) {
        imageView.scaleX = if (flipped) -1f else 1f
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
        closeButton.setOnClickListener { onCloseClicked() }
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
    var onAnnotationDone: ((compositedBitmap: android.graphics.Bitmap) -> Unit)? = null

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
        val density = resources.displayMetrics.density
        val size = (32 * density).toInt()
        val margin = (8 * density).toInt()
        val badge = TextView(context).apply {
            text = number.toString()
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
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
        _serialBadge?.text = newZ.toString()
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

        // Close button
        (closeButton.background as? android.graphics.drawable.GradientDrawable)?.apply {
            setColor(theme.surfaceFill)
            setStroke(dpToPx(1), theme.surfaceStroke)
        }
        closeButton.imageTintList = ColorStateList.valueOf(theme.iconColor)

        // Toolbar pill
        (toolbar.background as? android.graphics.drawable.GradientDrawable)?.apply {
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
        annotationMode = true

        // 1. Save current state
        preAnnotationX = currentX
        preAnnotationY = currentY
        preAnnotationW = currentW
        preAnnotationH = currentH

        // 2. Calculate screen-fitting size. The imageCard shrinks so
        //    that the WHOLE wrapper (image + gap + annotation
        //    toolbar + brush sub-panel) fits inside the screen with
        //    32dp padding on every side. We reserve vertical space
        //    for the brush sub-panel up-front so toggling it
        //    doesn't push the toolbar past the screen bottom or
        //    outside the wrapper bounds.
        val metrics = windowManager.currentWindowMetrics
        val screenW = metrics.bounds.width()
        val screenH = metrics.bounds.height()
        val padding = (resources.displayMetrics.density * 32).toInt()
        // Reserve enough for: gap (4dp) + main row (40dp) + gap
        // (4dp) + brush sub-panel (40dp).
        val brushPanelReserve = dpToPx(TOOLBAR_HEIGHT_DP)
        val toolbarOverheadForFit =
            dpToPx(TOOLBAR_GAP_DP) * 2 + dpToPx(TOOLBAR_HEIGHT_DP) + brushPanelReserve
        val maxW = screenW - padding * 2
        val maxH = screenH - padding * 2 - toolbarOverheadForFit
        val aspect = if (currentH > 0) currentW.toFloat() / currentH.toFloat() else 1f
        val fitW: Int
        val fitH: Int
        if (maxW.toFloat() / maxH.toFloat() > aspect) {
            fitH = maxH
            fitW = (maxH * aspect).toInt()
        } else {
            fitW = maxW
            fitH = (maxW / aspect).toInt()
        }

        // Center on screen
        val fitX = (screenW - fitW) / 2
        val fitY = (screenH - fitH) / 2

        // Apply the zoom
        currentW = fitW
        currentH = fitH
        currentX = fitX
        currentY = fitY
        applyLayoutSize(fitW, fitH)
        applyLayoutPosition(fitX, fitY)

        // Reset the imageView to fill the new imageCard. The pin
        // view's imageView is normally configured for a small
        // thumbnail-sized card; once we zoom up to the screen
        // size, a FIT_CENTER-scaled drawable would render at its
        // intrinsic resolution in the middle of the card, leaving
        // a huge band of the card's blue background visible around
        // it. CENTER_CROP scales the drawable to fully cover the
        // card (cropping overflow) so the user sees the image
        // edge-to-edge while drawing on it.
        //
        // We also synchronously reload the source bitmap here
        // (rather than reusing the cached thumbnail) so the
        // imageView has a high-resolution drawable sized for the
        // new view, not a tiny cached sample that would render
        // blurry. The reload runs on the main thread but is
        // bounded to ≤2× the target dimensions via inSampleSize,
        // so it stays snappy even for 4K screenshots.
        loadOriginalBitmapSync(fitW, fitH)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP

        // 3. Add drawing overlay
        val overlay = DrawingOverlayView(context).apply {
            mode = drawingMode
            strokeColor = Color.RED
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
        // Brush sub-panel starts collapsed; the user opens it by
        // tapping the pen button.
        brushPanelVisible = false

        // 4. Show annotation toolbar
        val annToolbar = buildAnnotationToolbar()
        addView(annToolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
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

        // 5. Hide regular toolbar and close button
        toolbar.alpha = 0f
        closeButton.alpha = 0f
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
        annotationMode = false

        // 1. Composite drawing onto image (unless the user chose to
        //    discard). discardChanges is used by the "×" cancel
        //    button on the annotation toolbar — the strokes are
        //    thrown away so the pin reverts to its pre-annotation
        //    appearance.
        val overlay = drawingOverlay
        if (!discardChanges && overlay != null && overlay.hasStrokes()) {
            val drawingBmp = overlay.renderToBitmap()
            // Get the original image bitmap from imageView
            val originalDrawable = imageView.drawable
            if (originalDrawable != null) {
                val origW = originalDrawable.intrinsicWidth.coerceAtLeast(1)
                val origH = originalDrawable.intrinsicHeight.coerceAtLeast(1)
                val composited = android.graphics.Bitmap.createBitmap(origW, origH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(composited)
                // Draw original image
                originalDrawable.setBounds(0, 0, origW, origH)
                originalDrawable.draw(canvas)
                // Scale and draw annotation
                canvas.scale(origW.toFloat() / overlay.width, origH.toFloat() / overlay.height)
                canvas.drawBitmap(drawingBmp, 0f, 0f, null)
                drawingBmp.recycle()
                // Notify host to save the composited image
                onAnnotationDone?.invoke(composited)
            } else {
                drawingBmp.recycle()
            }
        }

        // 2. Remove drawing overlay and annotation toolbar
        overlay?.let { imageCard.removeView(it) }
        drawingOverlay = null
        annotationToolbar?.let { removeView(it) }
        annotationToolbar = null

        // 3. Restore original size/position
        currentW = preAnnotationW
        currentH = preAnnotationH
        currentX = preAnnotationX
        currentY = preAnnotationY
        applyLayoutSize(preAnnotationW, preAnnotationH)
        applyLayoutPosition(preAnnotationX, preAnnotationY)

        // 4. Show regular toolbar and close button
        if (toolbarShown) {
            toolbar.alpha = 1f
            closeButton.alpha = 1f
        }
    }

    fun isAnnotationMode(): Boolean = annotationMode

    /**
     * The pen button, when active, shows a small brush-style +
     * brush-width sub-panel below the main toolbar. The sub-panel
     * is collapsed by default. Tapping the pen button toggles
     * its visibility, and tapping a brush style or adjusting the
     * slider updates the active style / width on the drawing
     * overlay.
     */
    private var brushPanel: LinearLayout? = null
    private var brushPanelVisible: Boolean = false

    /**
     * Build the annotation toolbar in the order requested:
     * [红] [黑] [签字笔] [橡皮擦] [撤销] [重做] [保存] [应用] [取消]
     *
     * The toolbar is a vertical LinearLayout with two rows: the
     * main pill at the top and a brush sub-panel (hidden by
     * default) below. Tapping the pen button toggles the
     * sub-panel; the sub-panel holds brush style icons + a
     * width slider, modeled on the iOS / Samsung Notes style
     * picker.
     */
    private fun buildAnnotationToolbar(): LinearLayout {
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
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
        }

        // ---- Main row ----
        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. 红色色块
        mainRow.addView(makeColorSwatch(Color.RED, "红") {
            drawingOverlay?.strokeColor = Color.RED
            drawingOverlay?.mode = DrawingOverlayView.Mode.PEN
        })
        // 2. 黑色色块
        mainRow.addView(makeColorSwatch(Color.BLACK, "黑") {
            drawingOverlay?.strokeColor = Color.BLACK
            drawingOverlay?.mode = DrawingOverlayView.Mode.PEN
        })
        mainRow.addView(makeVerticalDivider())
        // 3. 签字笔 — toggles the brush sub-panel
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_action_annotate, "签字笔") {
            toggleBrushPanel()
        })
        // 4. 橡皮擦
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_action_eraser, "橡皮擦") {
            drawingOverlay?.mode = DrawingOverlayView.Mode.ERASER
        })
        mainRow.addView(makeVerticalDivider())
        // 5. 撤销
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_action_undo, "撤销") {
            drawingOverlay?.undo()
        })
        // 6. 重做
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_action_redo, "重做") {
            drawingOverlay?.redo()
        })
        mainRow.addView(makeVerticalDivider())
        // 7. 保存 — apply AND save to gallery
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_action_save, "保存") {
            onActionClicked(ACTION_SAVE_ANNOTATE)
        })
        // 8. 应用 — apply annotation, restore size/position, exit
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_action_apply, "应用") {
            onActionClicked(ACTION_APPLY_ANNOTATE)
        })
        // 9. 取消 — discard and exit
        mainRow.addView(makeAnnotationActionButton(R.drawable.ic_close, "取消", asCancel = true) {
            onActionClicked(ACTION_CANCEL_ANNOTATE)
        })

        toolbar.addView(mainRow)

        // ---- Brush sub-panel (hidden by default) ----
        val panel = buildBrushPanel()
        panel.visibility = View.GONE
        toolbar.addView(panel)
        brushPanel = panel

        return toolbar
    }

    private fun toggleBrushPanel() {
        val panel = brushPanel ?: return
        brushPanelVisible = !brushPanelVisible
        panel.visibility = if (brushPanelVisible) View.VISIBLE else View.GONE
        repositionAnnotationToolbar()
    }

    private fun repositionAnnotationToolbar() {
        val toolbar = annotationToolbar ?: return
        val panel = brushPanel
        val panelH = if (panel != null && panel.visibility == View.VISIBLE) {
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            panel.measuredHeight
        } else {
            0
        }
        val baseH = dpToPx(TOOLBAR_HEIGHT_DP)
        val totalH = baseH + panelH + dpToPx(TOOLBAR_GAP_DP)
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

    private fun buildBrushPanel(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(makeBrushStyleButton(DrawingOverlayView.BrushStyle.PEN, "钢笔"))
            addView(makeBrushStyleButton(DrawingOverlayView.BrushStyle.HIGHLIGHTER, "高亮笔"))
            addView(makeBrushStyleButton(DrawingOverlayView.BrushStyle.CALLIGRAPHY, "毛笔"))
            addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#22000000"))
                    setSize(dpToPx(1), dpToPx(20))
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(20)).apply {
                    gravity = Gravity.CENTER
                    marginStart = (6 * density).toInt()
                    marginEnd = (6 * density).toInt()
                }
            })
            val slider = android.widget.SeekBar(context).apply {
                max = 100
                progress = 40
                layoutParams = LinearLayout.LayoutParams(
                    (120 * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        val baseWidth = 2f + (progress / 100f) * 16f
                        drawingOverlay?.strokeWidth = baseWidth
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                })
            }
            addView(slider)
        }
    }

    private fun makeBrushStyleButton(
        style: DrawingOverlayView.BrushStyle,
        label: String,
    ): View {
        val density = resources.displayMetrics.density
        val size = (32 * density).toInt()
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
                setColor(theme.surfaceFill)
                setStroke((2 * density).toInt(), theme.iconColor)
            }
            val preview = View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = when (style) {
                        DrawingOverlayView.BrushStyle.HIGHLIGHTER -> android.graphics.drawable.GradientDrawable.RECTANGLE
                        else -> android.graphics.drawable.GradientDrawable.OVAL
                    }
                    setColor(theme.iconColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    if (style == DrawingOverlayView.BrushStyle.HIGHLIGHTER) (16 * density).toInt() else (10 * density).toInt(),
                    if (style == DrawingOverlayView.BrushStyle.HIGHLIGHTER) (6 * density).toInt() else (10 * density).toInt(),
                )
            }
            addView(preview)
            setOnClickListener {
                drawingOverlay?.brushStyle = style
            }
        }
    }

    private fun makeColorSwatch(color: Int, label: String, onPick: () -> Unit): View {
        val density = resources.displayMetrics.density
        val size = (28 * density).toInt()
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
                setStroke((1 * density).toInt(), Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { onPick() }
        }
    }

    private fun makeVerticalDivider(): View {
        val density = resources.displayMetrics.density
        return View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#22000000"))
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
        val density = resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                gravity = Gravity.CENTER
            }
            val idle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                // The cancel button uses a slightly red-tinted
                // background so it reads as "destructive" at a
                // glance, matching the M3 pattern of using red
                // for cancel/abort affordances.
                setColor(if (asCancel) Color.parseColor("#1AEF5350") else Color.parseColor("#00000000"))
            }
            val pressed = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (asCancel) Color.parseColor("#33EF5350") else Color.parseColor("#1F000000"))
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), idle)
            }
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                // Cancel icon is the theme's red, to reinforce the
                // "destructive" meaning; the other action icons
                // follow the theme's normal icon color.
                val tint = if (asCancel) {
                    Color.parseColor("#D32F2F")
                } else {
                    theme.iconColor
                }
                imageTintList = android.content.res.ColorStateList.valueOf(tint)
                layoutParams = LinearLayout.LayoutParams(dpToPx(18), dpToPx(18))
            })
        }
    }

    /**
     * Compute the close button's exact screen rectangle (left, top,
     * right, bottom) by combining the wrapper's window-manager
     * position with the close button's position inside the
     * wrapper. The close button is a grandchild of the wrapper
     * (child of [imageCard]), so its screen bounds depend on
     * imageCard's offset from the wrapper's window origin.
     *
     * Returned as [android.graphics.Rect] in screen pixel
     * coordinates, suitable for passing to a separate
     * WindowManager window.
     */
    fun closeButtonScreenRect(): android.graphics.Rect {
        val wrapperLp = layoutParams as? WindowManager.LayoutParams
        val originX = wrapperLp?.x ?: 0
        val originY = wrapperLp?.y ?: 0
        // [closeButton] is a child of [imageCard]. imageCard has
        // gravity TOP | CENTER_HORIZONTAL inside the wrapper, and
        // closeButton has gravity TOP | END inside imageCard.
        // To get closeButton's position in the wrapper's
        // coordinate system, add imageCard.left/top.
        val left = originX + imageCard.left + closeButton.left
        val top = originY + imageCard.top + closeButton.top
        val right = left + closeButton.width
        val bottom = top + closeButton.height
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
        val targetAlpha = if (toolbarShown) 1f else 0f
        val targetTransY = if (toolbarShown) 0f else dpToPx(TOOLBAR_ANIM_TRANSLATE_DP).toFloat()

        closeButton.animate()
            .alpha(targetAlpha)
            .setDuration(SELECTION_ANIM_MS)
            .start()

        toolbar.animate()
            .alpha(targetAlpha)
            .translationY(targetTransY)
            .setDuration(TOOLBAR_ANIM_MS)
            .start()
    }

    // ----------------------------------------------------------------
    // Touch handling
    // ----------------------------------------------------------------
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
                drawingOverlay?.onTouchEvent(event)
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
                    val anchor = anchorScreenPosition(cornerIndex)
                    cornerDragAnchorX = anchor.first
                    cornerDragAnchorY = anchor.second
                    cornerDragDownFingerX = event.rawX
                    cornerDragDownFingerY = event.rawY
                    cornerDragInitialDiagonal = hypot(
                        event.rawX - anchor.first,
                        event.rawY - anchor.second,
                    )
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
                if (cornerDragIndex >= 0) {
                    applyCornerDrag(event.rawX, event.rawY)
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
                            onPositionChanged(cx, cy)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        val r = dpToPx(CORNER_HIT_RADIUS_DP).toFloat()
        val rSq = r * r
        val w = currentW.toFloat()
        val h = currentH.toFloat()
        val inset = cornerRadiusPx
        // Map wrapper-local touch coords into imageCard-local coords.
        // imageCard is the first child at (0,0) with size (currentW, currentH).
        val imgX = localX - imageCard.left
        val imgY = localY - imageCard.top
        val positions = arrayOf(
            inset to inset,         // TL
            w - inset to inset,     // TR
            inset to h - inset,     // BL
            w - inset to h - inset, // BR
        )
        for ((i, p) in positions.withIndex()) {
            val dx = imgX - p.first
            val dy = imgY - p.second
            if (dx * dx + dy * dy <= rSq) {
                // The close button sits in the TR corner area, which
                // overlaps the TR handle's hit region. Only suppress
                // the handle when the user actually tapped *inside*
                // the close button — and only when the toolbar is
                // shown (the close button is alpha 0 / not useful
                // otherwise). The close button is a child of
                // imageCard so the same imgX/imgY coords apply.
                if (i == CORNER_TR && toolbarShown) {
                    val cb = closeButtonRect()
                    if (imgX >= cb.left && imgX <= cb.right &&
                        imgY >= cb.top && imgY <= cb.bottom
                    ) {
                        return -1
                    }
                }
                return i
            }
        }
        return -1
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
        val x: Float
        val y: Float
        when (dragIndex) {
            CORNER_TL, CORNER_BL -> x = (currentX + currentW).toFloat()
            CORNER_TR, CORNER_BR -> x = currentX.toFloat()
            else -> x = 0f
        }
        when (dragIndex) {
            CORNER_TL, CORNER_TR -> y = (currentY + currentH).toFloat()
            CORNER_BL, CORNER_BR -> y = currentY.toFloat()
            else -> y = 0f
        }
        return x to y
    }

    /** The aspect ratio (width / height) at the start of a corner drag, used to enforce proportional scaling. */
    private var cornerDragInitialAspect: Float = 1f

    private fun applyCornerDrag(fingerX: Float, fingerY: Float) {
        val index = cornerDragIndex
        if (index < 0) return

        val initialDiagonal = cornerDragInitialDiagonal
        val rawScale = if (initialDiagonal > 0.0001f) {
            hypot(fingerX - cornerDragAnchorX, fingerY - cornerDragAnchorY) / initialDiagonal
        } else {
            1f
        }

        val initialW = cornerDragInitialW.coerceAtLeast(1)
        val initialH = cornerDragInitialH.coerceAtLeast(1)
        val initialWf = initialW.toFloat()
        val initialHf = initialH.toFloat()
        val minScaleForW = MIN_SIZE.toFloat() / initialWf
        val minScaleForH = MIN_SIZE.toFloat() / initialHf
        val maxScaleForW = MAX_SIZE.toFloat() / initialWf
        val maxScaleForH = MAX_SIZE.toFloat() / initialHf
        val minScale = maxOf(minScaleForW, minScaleForH)
        val maxScale = minOf(maxScaleForW, maxScaleForH)
        val scaleFactor = rawScale.coerceIn(minScale, maxScale)

        // Compute new dimensions preserving the initial aspect ratio exactly.
        // Use the aspect ratio captured at drag start so that floating-point
        // drift from repeated toInt() truncation does not accumulate.
        val aspect = cornerDragInitialAspect
        var newWf = initialWf * scaleFactor
        var newHf = newWf / aspect
        // Clamp to size limits while preserving aspect ratio.
        if (newWf < MIN_SIZE) { newWf = MIN_SIZE.toFloat(); newHf = newWf / aspect }
        if (newHf < MIN_SIZE) { newHf = MIN_SIZE.toFloat(); newWf = newHf * aspect }
        if (newWf > MAX_SIZE) { newWf = MAX_SIZE.toFloat(); newHf = newWf / aspect }
        if (newHf > MAX_SIZE) { newHf = MAX_SIZE.toFloat(); newWf = newHf * aspect }

        val newW = newWf.toInt()
        val newH = newHf.toInt()

        val newX: Int
        val newY: Int
        when (index) {
            CORNER_TL -> {
                newX = (cornerDragAnchorX - newW).toInt()
                newY = (cornerDragAnchorY - newH).toInt()
            }
            CORNER_TR -> {
                newX = cornerDragAnchorX.toInt()
                newY = (cornerDragAnchorY - newH).toInt()
            }
            CORNER_BL -> {
                newX = (cornerDragAnchorX - newW).toInt()
                newY = cornerDragAnchorY.toInt()
            }
            CORNER_BR -> {
                newX = cornerDragAnchorX.toInt()
                newY = cornerDragAnchorY.toInt()
            }
            else -> return
        }

        if (newW != currentW || newH != currentH || newX != currentX || newY != currentY) {
            currentW = newW
            currentH = newH
            currentX = newX
            currentY = newY
            applyLayoutSize(newW, newH)
            applyLayoutPosition(newX, newY)
            onSizeChanged(newW.toFloat(), newH.toFloat())
            onPositionChanged(newX, newY)
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
        val toolbarOverhead = dpToPx(TOOLBAR_GAP_DP) + dpToPx(TOOLBAR_HEIGHT_DP)
        val wrapperH = h + toolbarOverhead
        val maxX = bounds.right - w / 4
        val minX = -w + w / 4
        val maxY = bounds.bottom - wrapperH / 4
        val minY = -wrapperH + wrapperH / 4
        return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
    }

    private fun applyLayoutPosition(x: Int, y: Int) {
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = x
        lp.y = y
        runCatching { windowManager.updateViewLayout(this, lp) }
    }

    internal fun updateLayoutFromExternal(x: Int, y: Int) {
        currentX = x
        currentY = y
        applyLayoutPosition(x, y)
    }

    internal fun updateSizeFromExternal(w: Int, h: Int) {
        currentW = w
        currentH = h
        applyLayoutSize(w, h)
    }

    private fun applyLayoutSize(w: Int, h: Int) {
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        val toolbarOverhead = dpToPx(TOOLBAR_GAP_DP) + dpToPx(TOOLBAR_HEIGHT_DP)
        // Wrapper is always at least minToolbarW wide, mirroring the
        // initial-layout logic. Resizing the image below that
        // threshold widens the wrapper so the toolbar (centered) can
        // extend past the image's left/right edges without being
        // clipped.
        val minToolbarW = dpToPx(MIN_TOOLBAR_WIDTH_DP)
        val wrapperW = maxOf(w, minToolbarW)
        lp.width = wrapperW
        lp.height = h + toolbarOverhead
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
        (toolbar.layoutParams as? FrameLayout.LayoutParams)?.apply {
            topMargin = h + dpToPx(TOOLBAR_GAP_DP)
        }
        // Mutating layoutParams alone doesn't trigger a re-layout on
        // most Android versions — the child keeps its old measured
        // size until something forces a measure pass. Explicitly
        // request a layout on the affected children.
        imageCard.requestLayout()
        toolbar.requestLayout()
        // Re-apply the current rotation so the swapped imageView
        // layoutParams (set in [applyRotation]) pick up the new
        // imageCard size. Without this, rotating-then-resizing would
        // leave the imageView with stale swapped dimensions and
        // re-introduce the blue-band gap.
        if (imageView.rotation != 0f) applyRotation(imageView.rotation.toInt())
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
        private const val CORNER_HIT_RADIUS_DP = 40f
        private const val HANDLE_RADIUS_DP = 7f
        private const val HANDLE_STROKE_DP = 2f

        // Layout measurements.
        private const val CORNER_RADIUS_DP = 16f
        private const val TOOLBAR_HEIGHT_DP = 40f
        private const val TOOLBAR_GAP_DP = 4f
        private const val TOOLBAR_BUTTON_DP = 40f
        // Toolbar content width at design size: 9 buttons × 40dp +
        // 4 dividers × 1dp. The wrapper is widened to at least this
        // many pixels so the toolbar never gets clipped on narrow
        // pins.
        // 10 icon buttons (40dp each) + 5 dividers (1dp each).
        // The wrapper must be at least this wide so the toolbar
        // pill can fit without clipping its rightmost button.
        private const val MIN_TOOLBAR_WIDTH_DP = 10 * 40f + 5 * 1f
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
        private const val NO_CORNER_DRAG = -1
        private const val CORNER_TL = 0
        private const val CORNER_TR = 1
        private const val CORNER_BL = 2
        private const val CORNER_BR = 3

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