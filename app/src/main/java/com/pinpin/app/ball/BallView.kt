package com.pinpin.app.ball

import androidx.core.graphics.toColorInt
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The persistent floating "ball" that lives on the edge of the screen.
 *
 * Lifecycle: created once by [BallService], added to the WindowManager as
 * a TYPE_APPLICATION_OVERLAY window, and persists for the lifetime of the
 * service.
 *
 * State machine:
 *  - [State.IDLE]      — at rest on a screen edge, half-hidden if user
 *                        has toggled auto-hide.
 *  - [State.DRAGGING]  — finger is down; movement past touch slop or a long
 *                        press unlocks moving the ball.
 *  - [State.ARMED]     — finger is still down but has *stopped* for
 *                        [ARM_DELAY_MS] over an arbitrary point on the
 *                        screen. We notify the host service, which will
 *                        show a full-screen selection overlay and start
 *                        treating subsequent moves as the rubber-band
 *                        rect origin.
 *  - [State.SELECTING] — the full-screen selection overlay owns the
 *                        gesture; the ball itself stops doing anything
 *                        (the host service hides it briefly so the
 *                        overlay can be tapped freely).
 *  - [State.RESET]     — transient state after release; goes back to
 *                        IDLE on the next frame.
 *
 * The ball does **not** own the selection rectangle. It only detects
 * "finger down, finger stopped" and tells the host; the host spins up a
 * full-screen [SelectionOverlayView] which actually draws the rect.
 */
class BallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    enum class State { IDLE, DRAGGING, ARMED, SELECTING }

    /**
     * Callback to the host service. The host is expected to:
     *  - on [onArm]: bring up a full-screen overlay at the armed point.
     *  - on [onSelectDone]: tear down the overlay and start the capture.
     *  - on [onIdle]: bring everything back to the resting state.
     */
    interface Host {
        fun onArm(originX: Int, originY: Int)
        fun onSelectDone(rectInScreen: android.graphics.Rect)
        fun onCancel()
    }

    private var host: Host? = null
    fun setHost(h: Host) { host = h }

    /**
     * Called by the host (BallService) when this view receives a
     * touch event after arming. The host hands the event off to
     * the selection overlay, which is what actually drives the
     * rubber-band rect. We can't just return false and let the
     * overlay pick up the gesture from InputDispatcher because
     * Android locks the gesture stream to whichever view received
     * the first ACTION_DOWN — the overlay would never see a single
     * touch after we arm. So the ball view stays the "first
     * ACTION_DOWN" target and re-dispatches subsequent events to
     * the overlay on its behalf.
     */
    private var armRedirect: ((MotionEvent) -> Unit)? = null
    fun setArmRedirect(r: ((MotionEvent) -> Unit)?) { armRedirect = r }

    /** True when the overlay deliberately keeps the gesture alive for precision editing. */
    private var selectionGestureRetained: (() -> Boolean)? = null
    fun setSelectionGestureRetained(check: (() -> Boolean)?) {
        selectionGestureRetained = check
    }

    /**
     * Optional tap callback. When the user releases the ball
     * without dragging, the ball normally snaps back to the
     * screen edge. If [onTap] is non-null, the callback fires
     * INSTEAD of the snap-back — used by the "minimized pin"
     * flow, where a tap on the ball should restore the full
     * pin window rather than just re-anchoring the ball.
     *
     * IMPORTANT: this only fires on a true tap (no significant
     * movement before release). A drag-and-release still snaps
     * back so the ball can be repositioned as usual.
     */
    var onTap: (() -> Unit)? = null
    /** Returns true when the swipe was handled by a system action. */
    var onSwipeDown: (() -> Boolean)? = null
    var onCarryDrag: ((View) -> Boolean)? = null
    var carryingImage: Boolean = false
    var moveOnly: Boolean = false
    private var carryDragAttempted = false
    private var suppressCarryTap = false
    private var carriedBitmap: Bitmap? = null
    private val carriedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setCarriedBitmap(bitmap: Bitmap?) {
        if (carriedBitmap !== bitmap) carriedBitmap?.recycle()
        carriedBitmap = bitmap
        carryingImage = bitmap != null
        invalidate()
    }

    private var captureGestureActive = false
    private var continuousCarryGesture = false
    private var captureTouchX = 0f
    private var captureTouchY = 0f

    /** Keep the original input window/stream until the asynchronous screenshot is ready. */
    fun beginCarryCaptureGesture() {
        captureTouchX = armX
        captureTouchY = armY
        resetToIdle(park = false)
        captureGestureActive = true
        continuousCarryGesture = true
        ignoreUntilNextDown = false
        // The selection overlay has just disappeared; keep the idle crosshair
        // from flashing while the screenshot request is being handed off.
        alpha = 0f
    }

    fun finishCaptureGesture() {
        val continued = continuousCarryGesture && captureGestureActive
        resetToIdle(park = false)
        captureGestureActive = false
        continuousCarryGesture = false
        ignoreUntilNextDown = false
        carryDragAttempted = !continued
        suppressCarryTap = true
        dragStartRawX = captureTouchX
        dragStartRawY = captureTouchY
    }

    internal fun resumeCarryDragIfMoved() {
        if (!carryingImage || carryDragAttempted) return
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        if (kotlin.math.hypot(armX - dragStartRawX, armY - dragStartRawY) > slop) {
            carryDragAttempted = true
            onCarryDrag?.invoke(this)
        }
    }

    /**
     * Optional serial-number badge. When non-null, the ball
     * draws a small red circle with the number in the
     * top-right corner. Used by the "minimized pin" flow so
     * the user can still see which pin the ball stands in
     * for (mirroring the serial badge on the original pin).
     * Set to `null` to hide.
     */
    var zOrderBadge: Int? = null

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Updated by the host so we know the ball's true screen position. */
    var attachedParams: WindowManager.LayoutParams? = null
    var attachedDisplayWidth: Int = 0
    var attachedDisplayHeight: Int = 0

    private var state: State = State.IDLE

    private val sizePx: Int = dp(56f).toInt()
    private val coreRadiusPx: Float = dp(22f)
    private val ringStrokePx: Float = dp(2f)
    private val edgeMarginPx: Int = dp(8f).toInt()

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#E67A1F2E".toColorInt() // deep slate red
    }
    private val coreArmedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#F2C744".toColorInt() // FV-like yellow when armed
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = ringStrokePx
    }
    private val armProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = coreArmedPaint.color
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val armTracker = StationaryArmTracker(ARM_DELAY_MS, dp(ARM_MOVE_THRESHOLD_DP))
    private val screenLocation = IntArray(2)
    internal val selectionTouchX: Float get() = armX
    internal val selectionTouchY: Float get() = armY

    // region Custom-image rendering
    //
    // The ball can show a user-supplied image instead of the default
    // red/yellow core. The image is decoded off the main thread, drawn
    // through a [BitmapShader] clipped to a circle (CENTER_CROP), and
    // recycled when the view is detached. ARMED state adds a yellow
    // ring around the image so the user still gets the "ready to
    // select" affordance; the crosshair is hidden when an image is set
    // (it would clash with arbitrary photos).
    private var avatarBitmap: Bitmap? = null
    private var avatarShader: BitmapShader? = null
    private val avatarShaderMatrix = Matrix()
    /**
     * User-driven transform layered on top of the default CENTER_CROP
     * cover. See [setAvatarTransform]. `avatarScale` is a multiplier
     * on the base CENTER_CROP scale (1 = unchanged). The offsets are
     * in screen pixels and shift the centered image.
     */
    private var avatarScale: Float = 1f
    private var avatarOffsetX: Float = 0f
    private var avatarOffsetY: Float = 0f
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Outer ring color for the image case (yellow when armed, white otherwise). */
    private val imageRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    /** Job tracking the in-flight decode; cancelled when a newer image is requested. */
    private var imageLoadJob: Job? = null

    /** Coroutine scope for off-thread image decoding. Cancelled in onDetachedFromWindow. */
    private var imageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var snapAnimator: ValueAnimator? = null

    /**
     * Set a custom image for the ball by [Uri] (typically a `content://`
     * URI returned by SAF / the photo picker). The image is decoded
     * asynchronously at ~2× the ball's on-screen size and applied as
     * the ball's visual. Pass `null` to revert to the default style.
     *
     * Persisting the URI is the caller's responsibility — this view
     * only renders what it's given.
     */
    fun setBallImageUri(uri: Uri?) {
        imageLoadJob?.cancel()
        if (uri == null) {
            Log.d(TAG, "setBallImageUri(null) — reverting to default")
            setBallImageBitmap(null)
            return
        }
        Log.d(TAG, "setBallImageUri($uri) — kicking off async decode")
        // Cancel any in-flight decode so the latest request wins.
        imageLoadJob?.cancel()
        val targetSide = (sizePx * 2).coerceAtLeast(1)  // 2× for anti-alias headroom
        imageLoadJob = imageScope.launch {
            var decoded: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    decoded = decodeSampledBitmap(context, uri, targetSide)
                }
                setBallImageBitmap(decoded)
                decoded = null
            } finally {
                decoded?.recycle()
            }
        }
    }

    /**
     * Set a custom image for the ball from an already-decoded [Bitmap].
     * The view takes ownership of the bitmap for its lifetime — it will
     * be recycled in [onDetachedFromWindow]. Callers that want to
     * share the bitmap across views should pass a copy or refrain from
     * recycling it themselves.
     */
    fun setBallImageBitmap(bitmap: Bitmap?) {
        // Release the previous shader's reference (we don't recycle
        // the bitmap here — callers may still hold it elsewhere; we
        // recycle in onDetachedFromWindow to cover the common case).
        avatarShader = null
        avatarPaint.shader = null
        if (avatarBitmap !== bitmap) avatarBitmap?.let { if (!it.isRecycled) it.recycle() }
        avatarBitmap = bitmap
        if (bitmap != null) {
            avatarShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Log.d(TAG, "setBallImageBitmap: ${if (bitmap == null) "null (revert)" else "${bitmap.width}x${bitmap.height}"} — invalidate")
        invalidate()
    }

    /**
     * Apply a user-driven transform on top of the default CENTER_CROP
     * cover. The default is `scale = 1, offsetX = 0, offsetY = 0`
     * (which is identical to the previous behavior). `scale > 1`
     * zooms the avatar in, `scale < 1` zooms out; the offset
     * shifts the avatar in screen pixels (positive values move
     * the image right/down so the user can see more of the
     * top-left corner of the source). Used by the
     * [com.pinpin.app.ball.BallImageAdjustActivity] preview, which
     * applies the same values here so what the user adjusts is
     * what they see on the actual ball.
     */
    fun setAvatarTransform(scale: Float, offsetX: Float, offsetY: Float) {
        val s = if (scale.isFinite() && scale > 0f) scale else 1f
        avatarScale = s
        avatarOffsetX = if (offsetX.isFinite()) offsetX else 0f
        avatarOffsetY = if (offsetY.isFinite()) offsetY else 0f
        invalidate()
    }

    /**
     * Decode [uri] with its short edge at least [reqSide] where the source
     * allows, sampled down so the heap footprint stays bounded (see
     * [BallGeometry.sampleSize]).
     * Returns `null` on any failure (missing provider, OOM, unsupported
     * format, ...).
     */
    private fun decodeSampledBitmap(ctx: Context, uri: Uri, reqSide: Int): Bitmap? {
        val resolver = ctx.contentResolver
        return try {
            // First pass: read dimensions only.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val firstStream = resolver.openInputStream(uri)
            if (firstStream == null) {
                Log.w(TAG, "decodeSampledBitmap: openInputStream returned null for $uri")
                return null
            }
            firstStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            Log.d(TAG, "decodeSampledBitmap: bounds ${bounds.outWidth}x${bounds.outHeight} mime=${bounds.outMimeType}")
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "decodeSampledBitmap: invalid bounds ${bounds.outWidth}x${bounds.outHeight}")
                return null
            }
            val sample = BallGeometry.sampleSize(bounds.outWidth, bounds.outHeight, reqSide)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            Log.d(TAG, "decodeSampledBitmap: inSampleSize=$sample for target $reqSide")
            val secondStream = resolver.openInputStream(uri)
            if (secondStream == null) {
                Log.w(TAG, "decodeSampledBitmap: second openInputStream returned null")
                return null
            }
            val bmp = secondStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            if (bmp == null) {
                Log.w(TAG, "decodeSampledBitmap: decodeStream returned null on 2nd pass")
            }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "decodeSampledBitmap failed for $uri", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Ball image too large", e)
            null
        }
    }
    // endregion

    private val handler = Handler(Looper.getMainLooper())
    private var dragUnlocked = false
    private var holdEligible = false
    private val unlockDragRunnable = Runnable {
        if (state == State.DRAGGING && holdEligible) {
            dragUnlocked = true
            holdEligible = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
    private val armRunnable: Runnable = Runnable {
        if (state == State.DRAGGING) {
            if (armTracker.remainingMs(SystemClock.uptimeMillis()) > 0) {
                restartArmTimer()
            } else {
                // Report the ball's visual center as the rect origin,
                // NOT the raw touch point. The hit area is larger than
                // the visible circle, so a finger that lands on the
                // ball's edge is offset from the ball's center — if we
                // forwarded the touch point here the rubber-band rect
                // would start wherever the user's finger happened to
                // be on the hit area instead of where they see the
                // ball, leaving a visible offset between the ball and
                // the rect origin.
                getLocationOnScreen(screenLocation)
                val centerX = screenLocation[0] + width / 2
                val centerY = screenLocation[1] + height / 2
                Log.d(TAG, "armRunnable: finger stopped — arming at ball center ($centerX, $centerY)")
                isEnabled = false
                // Let the host call enterSelecting() which sets
                // state = SELECTING directly — this avoids a one-frame
                // flash of the ARMED (yellow-ring) state that causes
                // the selection rect to visually "jump".
                val host = host
                if (host != null) {
                    host.onArm(centerX, centerY)
                } else {
                    // No host — fall back to ARMED so the state
                    // machine still works for ACTION_UP handling.
                    state = State.ARMED
                    invalidate()
                }
            }
        }
    }

    private fun restartArmTimer() {
        handler.removeCallbacks(armRunnable)
        handler.postDelayed(armRunnable, armTracker.remainingMs(SystemClock.uptimeMillis()))
        postInvalidateOnAnimation()
    }

    /** Position where the finger was last seen, captured when the arm timer fires. */
    private var armX = 0f
    private var armY = 0f
    private var ignoreUntilNextDown = false

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0
    private var dragStartEventTime = 0L
    private var hasDragged: Boolean = false
    private var notificationSwipeActive = false
    private var notificationSwipeAttempted = false
    private var notificationSwipePending = false

    private var attachEdge: Int = EDGE_RIGHT  // which screen edge we're stuck to

    init {
        // Keep only 2dp of tolerance on each side of the visible ball.
        val hitSize = sizePx + dp(4f).toInt()
        layoutParams = LayoutParams(hitSize, hitSize)
        minimumWidth = hitSize
        minimumHeight = hitSize

        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val carried = carriedBitmap
        if (carried != null && !carried.isRecycled) {
            val cx = width / 2f
            val cy = height / 2f
            val side = coreRadiusPx * 2
            val save = canvas.save()
            val path = android.graphics.Path().apply { addCircle(cx, cy, coreRadiusPx, android.graphics.Path.Direction.CW) }
            canvas.clipPath(path)
            val scale = maxOf(side / carried.width, side / carried.height)
            val w = carried.width * scale
            val h = carried.height * scale
            canvas.drawBitmap(carried, null, android.graphics.RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), carriedPaint)
            canvas.restoreToCount(save)
            canvas.drawCircle(cx, cy, coreRadiusPx, ringPaint.apply { strokeWidth = ringStrokePx })
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        val r = coreRadiusPx

        val shader = avatarShader
        if (shader != null && avatarBitmap != null) {
            // Custom image: scale to cover the ball, clipped to a
            // circle. The user may have applied an additional
            // [setAvatarTransform] (zoom/pan) — we compose that
            // on top of the base CENTER_CROP matrix so what the
            // user sees in the adjust preview is exactly what
            // ends up rendered on the ball. The base matrix
            // already centers the image, so the user's
            // (scale, offsetX, offsetY) is applied relative to
            // that center. We re-compute on every draw because the
            // bitmap's intrinsic size is fixed but we may have
            // been resized (rotation, density change, etc.).
            val bmp = avatarBitmap!!
            val side = r + ringStrokePx
            val targetDiameter = side * 2f
            val baseScale = maxOf(
                targetDiameter / bmp.width,
                targetDiameter / bmp.height,
            )
            val finalScale = baseScale * avatarScale
            val scaledW = bmp.width * finalScale
            val scaledH = bmp.height * finalScale
            avatarShaderMatrix.reset()
            avatarShaderMatrix.setScale(finalScale, finalScale)
            // postTranslate uses the bitmap's top-left in the
            // scaled coordinate system; we want it centered at
            // (cx, cy) plus the user-driven offset. Subtracting
            // half the scaled dimensions yields the centering,
            // and adding (avatarOffsetX, avatarOffsetY) lets the
            // user pan the image in pixels.
            avatarShaderMatrix.postTranslate(
                cx - scaledW / 2f + avatarOffsetX,
                cy - scaledH / 2f + avatarOffsetY,
            )
            shader.setLocalMatrix(avatarShaderMatrix)
            avatarPaint.shader = shader
            // Filled disc = the image, masked by the circle primitive.
            canvas.drawCircle(cx, cy, side, avatarPaint)
            // Ring: white idle, yellow armed — mirrors the default
            // core-color convention so the user still sees the same
            // "ready to select" affordance.
            imageRingPaint.strokeWidth = ringStrokePx
            imageRingPaint.color = if (state == State.ARMED) {
                coreArmedPaint.color
            } else {
                Color.WHITE
            }
            canvas.drawCircle(cx, cy, side, imageRingPaint)
            drawArmProgress(canvas, cx, cy)
            // Also overlay the zOrder badge so the user can tell
            // which pin this ball represents. The badge is drawn
            // AFTER the ring so it sits on top of the avatar.
            drawZOrderBadge(canvas)
            return
        }

        // Default rendering. The previous design flipped the
        // core from red to yellow in ARMED state, but on the
        // small 48dp ball yellow + white ring + red badge
        // composited into something that reads as muddy/dark.
        // We keep the core red (that's the ball identity the
        // user has learned) and instead flip the OUTER ring
        // to yellow in ARMED — that gives a clear, high-
        // contrast "red heart inside a yellow ring" signal
        // for the screenshot-ready state.
        ringPaint.color = if (state == State.ARMED) {
            coreArmedPaint.color
        } else {
            Color.WHITE
        }
        ringPaint.strokeWidth = ringStrokePx
        canvas.drawCircle(cx, cy, r + ringStrokePx, ringPaint)

        // Core: always red — the ball's identity colour.
        canvas.drawCircle(cx, cy, r, corePaint)
        drawArmProgress(canvas, cx, cy)

        // Tiny crosshair on the ball — visual hint to the user
        // that the ball is the "selector origin". We always draw it
        // for the default style; it's hidden when a custom image is
        // shown (set above).
        val ch = r * 0.35f
        canvas.drawLine(cx - ch, cy, cx + ch, cy, crosshairPaint)
        canvas.drawLine(cx, cy - ch, cx, cy + ch, crosshairPaint)

        // Serial-number badge overlay — only drawn for the
        // default style (the custom-image path returns early
        // above). Mirrors the badge on the original pin
        // so the user can still see which pin this ball
        // represents when the image is collapsed.
        drawZOrderBadge(canvas)
    }

    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#E53935".toColorInt() // M3 error red
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun drawZOrderBadge(canvas: Canvas) {
        val n = zOrderBadge ?: return
        // Match the on-pin badge: 18dp diameter circle pinned
        // to the top-right corner, with a thin white ring to
        // separate it from the avatar.
        val badgeRadius = dp(9f)
        val cx = width - badgeRadius - dp(1f)
        val cy = badgeRadius + dp(1f)
        canvas.drawCircle(cx, cy, badgeRadius, badgeFillPaint)
        canvas.drawCircle(cx, cy, badgeRadius, badgeStrokePaint)
        // Number — sized to fit. 2–3 digit numbers use a
        // slightly smaller font so the digit stays inside the
        // ring.
        val text = n.toString()
        badgeTextPaint.textSize = if (text.length <= 1) dp(11f) else dp(9f)
        // Vertically center the glyph baseline on the badge
        // center; Paint.FontMetrics gives the offset from the
        // baseline to the visual top of the text.
        val fm = badgeTextPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, badgeTextPaint)
    }

    private fun drawArmProgress(canvas: Canvas, cx: Float, cy: Float) {
        if (state != State.DRAGGING || host == null) return
        val radius = coreRadiusPx + ringStrokePx + dp(3f)
        val progress = armTracker.progress(SystemClock.uptimeMillis())
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
            -90f, 360f * progress, false, armProgressPaint)
        if (progress < 1f) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val lp = attachedParams ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoreUntilNextDown = false
        if (continuousCarryGesture) {
            armX = event.rawX
            armY = event.rawY
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN ->
                    captureGestureActive = false
                MotionEvent.ACTION_DOWN -> captureGestureActive = false
            }
            return true
        }
        if (ignoreUntilNextDown) return true
        if (notificationSwipeActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> notificationSwipeActive = false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    notificationSwipeAttempted = false
                    notificationSwipeActive = false
                    notificationSwipePending = false
                    return true
                }
                else -> return true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) notificationSwipeAttempted = false
        // Selection/capture owns the stream even when replacing an existing image.
        if (carryingImage && !moveOnly && state != State.ARMED && state != State.SELECTING)
            return handleCarriedTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            ignoreUntilNextDown = true
            notificationSwipePending = false
            pendingSelectRect = null
            host?.onCancel()
            resetToIdle()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            notificationSwipePending = false
            handler.removeCallbacks(armRunnable)
            armTracker.cancel()
            pendingSelectRect = null
            host?.onCancel()
            resetToIdle()
            return true
        }
        // Once we've armed, Android keeps dispatching subsequent
        // touches to *this* view (the one that received the first
        // ACTION_DOWN) — it does not automatically reroute them to
        // the overlay that we just addView'd. We need to forward
        // them to the overlay ourselves; otherwise the user is
        // stuck mid-selection forever and the gesture is silently
        // dropped on ACTION_UP.
        //
        // The overlay draws the selection cursor. Keep this input window
        // stationary so each touch sample does not trigger a window IPC.
        if (state == State.ARMED || state == State.SELECTING) {
            armRedirect?.invoke(event)
            if (state == State.SELECTING &&
                event.actionMasked == MotionEvent.ACTION_UP &&
                selectionGestureRetained?.invoke() == true
            ) {
                return true
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                Log.d(TAG, "ACTION_DOWN at raw=(${event.rawX}, ${event.rawY}) isEnabled=$isEnabled clickable=$isClickable")
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartEventTime = event.eventTime
                notificationSwipeAttempted = false
                notificationSwipePending = false
                dragStartParamX = lp.x
                dragStartParamY = lp.y
                hasDragged = false
                dragUnlocked = false
                holdEligible = true
                // armX/armY hold the "where did the user actually
                // stop" coordinates. We seed them at DOWN so the
                // arm timer has a sensible fallback if the user
                // never moves (a tap-drag-stop pattern); the MOVE
                // branch updates them in real time.
                armX = event.rawX
                armY = event.rawY
                state = State.DRAGGING
                handler.removeCallbacks(unlockDragRunnable)
                handler.postDelayed(unlockDragRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragUnlocked) {
                    if (tryStartNotificationSwipe(event, lp)) return true
                    if (notificationSwipeAttempted || shouldDeferNotificationSwipe(event)) {
                        notificationSwipePending = true
                        return true
                    }
                    if (kotlin.math.hypot(event.rawX - dragStartRawX, event.rawY - dragStartRawY) <=
                        ViewConfiguration.get(context).scaledTouchSlop
                    ) return true
                    // Crossing touch slop is already a deliberate drag. Previously
                    // this cancelled long-press eligibility but kept the ball locked,
                    // so every later MOVE was ignored until the user lifted.
                    handler.removeCallbacks(unlockDragRunnable)
                    holdEligible = false
                    dragUnlocked = true
                }
                val rawX = event.rawX
                val rawY = event.rawY
                val wasDragged = hasDragged
                moveBallWithFinger(event, lp)
                armX = rawX
                armY = rawY
                if (!moveOnly && hasDragged) {
                    if (!wasDragged) {
                        armTracker.start(rawX, rawY, SystemClock.uptimeMillis())
                        restartArmTimer()
                    } else if (armTracker.move(rawX, rawY, SystemClock.uptimeMillis())) {
                        restartArmTimer()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragUnlocked && event.actionMasked == MotionEvent.ACTION_UP &&
                    tryStartNotificationSwipe(event, lp)) return true
                notificationSwipePending = false
                handler.removeCallbacks(unlockDragRunnable)
                handler.removeCallbacks(armRunnable)
                armTracker.cancel()
                when (state) {
                    State.DRAGGING -> {
                        // Released without ever arming. Two cases:
                        //   1. The ball wasn't dragged (hasDragged ==
                        //      false): this is a real "tap on the
                        //      ball". If a tap callback is wired up
                        //      (minimized-pin flow), fire it.
                        //   2. The ball was dragged: snap back to
                        //      the edge as before.
                        if (!hasDragged && !dragUnlocked) {
                            val tap = onTap
                            if (tap != null) {
                                Log.d(TAG, "ACTION_UP from DRAGGING: tap on ball — firing onTap")
                                moveOnly = false
                                tap.invoke()
                                // Don't snap back — the host
                                // (PinOverlayService) is about to
                                // tear the ball down and re-attach
                                // the full pin. Skipping snap-back
                                // avoids a one-frame visual jolt.
                                state = State.IDLE
                                invalidate()
                                return true
                            }
                        }
                        if (dragUnlocked && hasDragged) snapToEdge(lp)
                        moveOnly = false
                    }
                    State.ARMED -> {
                        // Released after arming: nothing useful happens
                        // (we only had an origin, not a destination).
                        // Treat as cancel.
                        Log.d(TAG, "ACTION_UP from ARMED: cancel")
                        host?.onCancel()
                        snapToEdge(lp)
                    }
                    State.SELECTING -> {
                        // The host's overlay owns the gesture now; the
                        // ball only sees this event because the host
                        // released focus to it. Forward the screen rect
                        // the host computed.
                        val rect = pendingSelectRect
                        pendingSelectRect = null
                        if (rect != null) {
                            Log.d(TAG, "ACTION_UP from SELECTING: done with $rect")
                            host?.onSelectDone(rect)
                        } else {
                            Log.d(TAG, "ACTION_UP from SELECTING: cancel")
                            host?.onCancel()
                        }
                        snapToEdge(lp)
                    }
                    State.IDLE -> Unit
                }
                state = State.IDLE
                invalidate()
                return true
            }
        }
        return false
    }

    /** Set by the host when SELECTING completes; consumed on the next ACTION_UP. */
    private fun handleCarriedTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartEventTime = event.eventTime
                notificationSwipeAttempted = false
                notificationSwipePending = false
                dragStartParamX = attachedParams?.x ?: 0
                dragStartParamY = attachedParams?.y ?: 0
                carryDragAttempted = false
                suppressCarryTap = false
                armX = event.rawX
                armY = event.rawY
                state = State.DRAGGING
                armTracker.start(armX, armY, SystemClock.uptimeMillis())
                restartArmTimer()
            }
            MotionEvent.ACTION_MOVE -> {
                if (tryStartNotificationSwipe(event, attachedParams ?: return true)) return true
                if (shouldDeferNotificationSwipe(event)) {
                    notificationSwipePending = true
                    if (kotlin.math.hypot(
                            event.rawX - dragStartRawX,
                            event.rawY - dragStartRawY,
                        ) > android.view.ViewConfiguration.get(context).scaledTouchSlop
                    ) {
                        suppressCarryTap = true
                    }
                    return true
                }
                notificationSwipePending = false
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (!carryDragAttempted && kotlin.math.hypot(event.rawX - dragStartRawX, event.rawY - dragStartRawY) > slop) {
                    carryDragAttempted = true
                    handler.removeCallbacks(armRunnable)
                    armTracker.cancel()
                    state = State.IDLE
                    onCarryDrag?.invoke(this)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tryStartNotificationSwipe(event, attachedParams ?: return true)) return true
                notificationSwipePending = false
                handler.removeCallbacks(armRunnable)
                armTracker.cancel()
                state = State.IDLE
                if (!carryDragAttempted && !suppressCarryTap) onTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                notificationSwipePending = false
                handler.removeCallbacks(armRunnable)
                armTracker.cancel()
                state = State.IDLE
                carryDragAttempted = true
            }
        }
        return true
    }

    private fun tryStartNotificationSwipe(
        event: MotionEvent,
        lp: WindowManager.LayoutParams,
    ): Boolean {
        if (onSwipeDown == null || notificationSwipeAttempted ||
            (event.actionMasked != MotionEvent.ACTION_MOVE && event.actionMasked != MotionEvent.ACTION_UP)
        ) return false
        val dx = event.rawX - dragStartRawX
        val dy = event.rawY - dragStartRawY
        val elapsed = event.eventTime - dragStartEventTime
        if (dy < dp(NOTIFICATION_SWIPE_DISTANCE_DP) ||
            kotlin.math.abs(dx) > dy * NOTIFICATION_SWIPE_DIRECTION_RATIO ||
            elapsed !in 0..NOTIFICATION_SWIPE_MAX_DURATION_MS
        ) return false
        notificationSwipeAttempted = true
        if (onSwipeDown?.invoke() != true) return false

        notificationSwipeActive = true
        notificationSwipePending = false
        handler.removeCallbacks(unlockDragRunnable)
        handler.removeCallbacks(armRunnable)
        armTracker.cancel()
        pendingSelectRect = null
        carryDragAttempted = true
        suppressCarryTap = true
        hasDragged = true
        state = State.IDLE
        if (lp.x != dragStartParamX || lp.y != dragStartParamY) {
            lp.x = dragStartParamX
            lp.y = dragStartParamY
            runCatching { windowManager.updateViewLayout(this, lp) }
        }
        invalidate()
        return true
    }

    private fun shouldDeferNotificationSwipe(event: MotionEvent): Boolean {
        if (onSwipeDown == null || notificationSwipeAttempted || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val dx = event.rawX - dragStartRawX
        val dy = event.rawY - dragStartRawY
        val elapsed = event.eventTime - dragStartEventTime
        return dy > 0f &&
            kotlin.math.abs(dx) <= dy * NOTIFICATION_SWIPE_DIRECTION_RATIO &&
            elapsed in 0..NOTIFICATION_SWIPE_MAX_DURATION_MS
    }

    internal var pendingSelectRect: android.graphics.Rect? = null

    fun enterSelecting() {
        armTracker.cancel()
        state = State.SELECTING
        // Belt-and-suspenders: even though the selection overlay
        // sits above us in z-order and now (post-fix) consumes
        // touches itself, also disable our own hit testing so a
        // stray touch that does reach us is ignored. Otherwise the
        // ACTION_MOVE that should extend the rubber-band rect
        // would instead drag the ball around.
        isEnabled = false
        isClickable = false
        isFocusable = false
        invalidate()
    }

    fun resetToIdle(park: Boolean = true) {
        handler.removeCallbacks(unlockDragRunnable)
        holdEligible = false
        dragUnlocked = false
        captureGestureActive = false
        continuousCarryGesture = false
        handler.removeCallbacks(armRunnable)
        armTracker.cancel()
        alpha = 1f
        state = State.IDLE
        isEnabled = true
        isClickable = true
        isFocusable = true
        // Animate back to the parked screen edge. Without this the
        // ball would be left wherever the user released their finger
        // after a successful capture / cancel. The ACTION_UP handler
        // in onTouchEvent does call snapToEdge, but only on the
        // DRAGGING-state path — the ARMED/SELECTING branch returns
        // early (it forwards the event to the overlay), so without
        // this line we'd never snap after a real selection.
        if (park) attachedParams?.let { snapToEdge(it) }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        carriedBitmap?.recycle()
        carriedBitmap = null
        snapAnimator?.cancel()
        handler.removeCallbacks(unlockDragRunnable)
        handler.removeCallbacks(armRunnable)
        armTracker.cancel()
        // Cancel any pending image decode and recycle the bitmap we
        // own. We only recycle the bitmap that came from
        // setBallImageBitmap (where we documented ownership transfer);
        // URIs we decoded ourselves always fall into that bucket.
        imageLoadJob?.cancel()
        imageLoadJob = null
        imageScope.cancel()
        avatarShader = null
        val bmp = avatarBitmap
        avatarBitmap = null
        if (bmp != null && !bmp.isRecycled) bmp.recycle()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (imageScope.coroutineContext[Job]?.isActive != true) {
            imageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    /**
     * Move the ball view itself to follow the user's finger.
     * During selection the overlay cursor takes over without moving this window.
     */
    private fun moveBallWithFinger(event: MotionEvent, lp: WindowManager.LayoutParams) {
        val rawX = event.rawX
        val rawY = event.rawY
        val dx = rawX - dragStartRawX
        val dy = rawY - dragStartRawY
        if (!hasDragged &&
            (kotlin.math.abs(dx) > dp(8f) || kotlin.math.abs(dy) > dp(8f))
        ) {
            hasDragged = true
        }
        if (!hasDragged) return
        val newX = (dragStartParamX + dx).toInt()
        val newY = (dragStartParamY + dy).toInt()
        if (lp.x == newX && lp.y == newY) return
        lp.x = newX
        lp.y = newY
        runCatching { windowManager.updateViewLayout(this, lp) }
        // Keep armX/armY in sync so that if the user releases
        // the finger without further moves, the rect origin is
        // wherever the ball *is*, not where it was at arm time.
        armX = rawX
        armY = rawY
    }

    /**
     * Animate the ball back to whichever screen edge it was
     * originally parked on. Public so the host can call it
     * directly (e.g. right after a capture so the ball respawns
     * where the user expects it) without having to also re-emit a
     * phantom ACTION_UP just to land in the [State.SELECTING]
     * branch.
     */
    fun snapToEdge(lp: WindowManager.LayoutParams) {
        snapAnimator?.cancel()
        val target = BallGeometry.snapTarget(
            lp.x, lp.y, width, height,
            attachedDisplayWidth, attachedDisplayHeight, edgeMarginPx,
            edgeLeft = EDGE_LEFT, edgeRight = EDGE_RIGHT,
        )
        val targetY = target.y
        attachEdge = target.edge
        snapAnimator = ValueAnimator.ofInt(lp.x, target.x).apply {
            duration = 220
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener {
                lp.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(this@BallView, lp) }
            }
            start()
        }
        if (lp.y != targetY) {
            lp.y = targetY
            runCatching { windowManager.updateViewLayout(this, lp) }
        }
    }

    @Suppress("unused")
    private fun edgeName(): String = if (attachEdge == EDGE_LEFT) "left" else "right"

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val TAG = "BallView"
        // Delay before arming after the finger stops moving.
        private const val ARM_DELAY_MS = 500L
        // Minimum movement (in dp) to count as a real drag.
        // Anything below this is treated as touch sensor jitter.
        private const val ARM_MOVE_THRESHOLD_DP = 12f
        private const val NOTIFICATION_SWIPE_DISTANCE_DP = 24f
        private const val NOTIFICATION_SWIPE_DIRECTION_RATIO = 0.75f
        private const val NOTIFICATION_SWIPE_MAX_DURATION_MS = 500L
        const val EDGE_LEFT = 0
        const val EDGE_RIGHT = 1
    }
}
