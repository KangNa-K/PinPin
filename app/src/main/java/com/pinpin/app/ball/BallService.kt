package com.pinpin.app.ball

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.core.content.edit
import com.pinpin.app.capture.registerCaptureReceiver
import com.pinpin.app.capture.CaptureProtocol as Protocol
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.pinpin.app.R
import com.pinpin.app.a11y.PinAccessibilityService
import com.pinpin.app.overlay.PinOverlayService
import com.pinpin.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Owns the persistent floating "ball" and the transient full-screen
 * selection overlay that pops up while the user is selecting a region
 * to capture.
 *
 * Why a foreground service: the ball must outlive every Activity. The
 * service is started once after the user finishes onboarding and stays
 * alive until the user explicitly stops it (notification action) or
 * the OS reclaims it under memory pressure.
 *
 * Capture flow (driven by [BallView] state machine):
 *  1. User drags the ball, finger goes down, ball state = DRAGGING.
 *  2. After [BallView.ARM_DELAY_MS] of stillness, [BallView.State.ARMED]
 *     fires and we [onArm]: addView a full-screen
 *     [SelectionOverlayView] at the armed point.
 *  3. The user extends the selection rect by moving their finger; the
 *     overlay draws the rubber-band rect.
 *  4. On finger up, the overlay reports a [Rect] via [onSelectDone].
 *     We capture the full screen via
 *     [PinAccessibilityService.captureBitmap] (the a11y trick that
 *     replaces MediaProjection — no "share this screen" dialog),
 *     crop to the rect, and add the resulting bitmap as a new pin.
 *  5. The overlay is removed; the ball re-enters IDLE; the user sees
 *     a fresh pin appear on the screen.
 */
@AndroidEntryPoint
class BallService : Service() {

    private val windowManager: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val ballView by lazy {
        BallView(this).also { it.setHost(ballHost) }
    }

    private val captureHandler = Handler(android.os.Looper.getMainLooper())
    private var pendingCapture: String? = null
    private var pendingAction: String = Protocol.ACTION_PIN
    private var savingCapture: String? = null
    private var hiddenForCapture: String? = null
    private var previousBallVisibility = android.view.View.VISIBLE
    private var captureStartedAt = 0L
    private var captureToast: android.widget.Toast? = null
    private val slowCaptureFeedback = Runnable {
        if (pendingCapture != null && savingCapture != null) {
            showCaptureMessage("截图已完成，正在保存", false)
        }
    }
    private val captureTimeout = Runnable {
        pendingCapture?.let { id ->
            if (savingCapture == id) return@let
            runCatching { sendBroadcast(Intent(Protocol.CANCEL).setPackage(packageName).putExtra(Protocol.ID, id)) }
            if (hiddenForCapture == id) {
                runCatching { PinOverlayService.setAllPinsVisible(this, true) }
            }
            restoreCaptureBall(id)
            // A missing SAVING/RESULT broadcast cannot prove that no commit occurred.
            // Keep the request ID so a delayed saving/commit receipt can still reconcile it.
            if (!PinAccessibilityService.hasPendingRequest(id)) {
                completeCapture(id, false, "status_unknown")
            } else {
                showCaptureMessage("截图状态未确认，正在等待服务回执", true)
            }
        }
    }

    private var ballAttached = false
    private var overlayView: SelectionOverlayView? = null
    private var overlayAttached = false
    private var selectionWindowManager: WindowManager? = null

    private val hideOverlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val id = runCatching { intent.getStringExtra(Protocol.ID) }.getOrNull()
                ?.takeIf(Protocol::validId) ?: return
            if (id != pendingCapture) return
            when (intent.action) {
                Protocol.HIDE -> {
                    if (savingCapture == id) return
                    if (hiddenForCapture == null) {
                        hiddenForCapture = id
                        previousBallVisibility = ballView.visibility
                        ballView.visibility = android.view.View.INVISIBLE
                    }
                    try {
                        check(detachOverlay()) { "Selection window did not detach" }
                        ballView.postOnAnimation {
                            ballView.postOnAnimation {
                                if (pendingCapture == id && hiddenForCapture == id) {
                                    runCatching {
                                        sendBroadcast(Intent(Protocol.HIDDEN).setPackage(packageName)
                                            .putExtra(Protocol.ID, id))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        runCatching { sendBroadcast(Intent(Protocol.CANCEL).setPackage(packageName).putExtra(Protocol.ID, id)) }
                        runCatching { PinOverlayService.setAllPinsVisible(this@BallService, true) }
                        completeCapture(id, false, "hide_failed")
                    }
                }
                Protocol.RESTORE -> restoreCaptureBall(id)
                Protocol.SAVING -> {
                    captureHandler.removeCallbacks(captureTimeout)
                    restoreCaptureBall(id)
                    if (savingCapture != id) {
                        savingCapture = id
                        if (SystemClock.elapsedRealtime() - captureStartedAt >= 1_000L) {
                            showCaptureMessage("截图已完成，正在保存", false)
                        }
                    }
                }
                Protocol.RESULT -> {
                    if (!intent.hasExtra(Protocol.SUCCESS)) return
                    completeCapture(id, intent.getBooleanExtra(Protocol.SUCCESS, false),
                        intent.getStringExtra(Protocol.ERROR),
                        intent.getStringExtra(Protocol.HISTORY_PATH))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = android.content.IntentFilter(Protocol.HIDE).apply {
            addAction(Protocol.RESTORE)
            addAction(Protocol.RESULT)
            addAction(Protocol.SAVING)
        }
        registerCaptureReceiver(this, hideOverlayReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "STOP action received")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_BALL_IMAGE -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_BALL_IMAGE_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_BALL_IMAGE_URI) as? Uri
                }
                if (uri != null) {
                    val scale = intent.getFloatExtra(EXTRA_BALL_IMAGE_SCALE, 1f)
                    val offsetX = intent.getFloatExtra(EXTRA_BALL_IMAGE_OFFSET_X, 0f)
                    val offsetY = intent.getFloatExtra(EXTRA_BALL_IMAGE_OFFSET_Y, 0f)
                    Log.d(
                        TAG,
                        "SET_BALL_IMAGE received: $uri scale=$scale offsetX=$offsetX offsetY=$offsetY",
                    )
                    applyBallImage(uri, scale, offsetX, offsetY, persist = true)
                }
            }
            ACTION_CLEAR_BALL_IMAGE -> {
                Log.d(TAG, "CLEAR_BALL_IMAGE received")
                clearBallImagePref()
                if (ballAttached) ballView.setBallImageUri(null)
            }
        }
        if (!ballAttached) {
            attachBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pendingCapture?.let { id ->
            runCatching { sendBroadcast(Intent(Protocol.CANCEL).setPackage(packageName).putExtra(Protocol.ID, id)) }
            if (hiddenForCapture == id) runCatching { PinOverlayService.setAllPinsVisible(this, true) }
            restoreCaptureBall(id)
        }
        captureHandler.removeCallbacksAndMessages(null)
        captureToast?.cancel()
        detachOverlay()
        if (ballAttached) runCatching { windowManager.removeView(ballView) }
        ballAttached = false
        runCatching { unregisterReceiver(hideOverlayReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachBall() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val ballSize = ballView.minimumWidth
        val lp = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start on the right edge, vertically centered. The x
            // is clamped to [0, w - ballSize] so we never land
            // off-screen — on devices whose current rotation makes
            // getRealMetrics() return a width that the actual
            // framebuffer doesn't expose, an unclamped value used
            // to put the ball *outside* the visible display and
            // made it impossible to tap.
            val targetX = w - ballSize - dp(8f).toInt()
            x = targetX.coerceIn(0, (w - ballSize).coerceAtLeast(0))
            y = ((h - ballSize) / 2).coerceIn(0, (h - ballSize).coerceAtLeast(0))
        }
        ballView.attachedParams = lp
        ballView.attachedDisplayWidth = w
        ballView.attachedDisplayHeight = h
        ballView.setArmRedirect { event ->
            // BallView is still the gesture target after we arm
            // (Android locks the gesture stream to the first
            // ACTION_DOWN view). We must re-dispatch the event to
            // the overlay so the user can extend / confirm the
            // selection.
            //
            // NOTE: this lambda runs on EVERY motion event the ball
            // receives during a selection (DOWN / MOVE* / UP). Do NOT
            // add Log.d here — `actionToString` + string interpolation
            // generate garbage and add JNI overhead at the touch
            // sample rate (60-120 Hz), which was the root cause of
            // the mid-drag stutter reported by the user. The Log.d
            // used to live here; we keep this comment so the next
            // person doesn't reintroduce it.
            overlayView?.onTouchEvent(event)
        }
        ballView.setSelectionGestureRetained {
            overlayView?.isPrecisionAdjusting == true
        }
        // Quick tap on the ball (down + up before the
        // 1.5s arm timer fires) launches the host app so the
        // user has a way to get back to the home screen from
        // the floating ball without having to long-press for
        // the screenshot gesture. The long-press / arm-then-
        // drag flow is unchanged; this only fires when the
        // finger lifts without ever arming.
        ballView.onTap = {
            Log.d(TAG, "ball tapped — launching host app")
            openHostApp()
        }
        runCatching { windowManager.addView(ballView, lp) }
            .onSuccess {
                ballAttached = true
                // Restore the user's last-chosen image (if any) so the
                // ball is in the right visual state the moment it
                // appears. We do this after addView so the view has
                // measured and its sizePx is meaningful.
                restoreBallImage()
            }
            .onFailure { Log.e(TAG, "addView(ball) failed", it) }
    }

    /**
     * Re-apply the user-chosen ball image from SharedPreferences. Safe
     * to call before the ball is attached; in that case [attachBall]
     * will call us again on first addView success.
     */
    private fun restoreBallImage() {
        val uri = readBallImagePref() ?: return
        val (scale, offsetX, offsetY) = readBallImageTransformPref()
        Log.d(TAG, "restoring saved ball image: $uri scale=$scale")
        applyBallImage(uri, scale, offsetX, offsetY, persist = false)
    }

    /**
     * Hand [uri] to the ball view for rendering. If [persist] is true,
     * also take a long-term read permission on the URI (required for
     * content URIs returned by SAF / the photo picker) and write the
     * URI + the user-chosen [scale] / [offsetX] / [offsetY] to
     * SharedPreferences so it survives process death.
     */
    private fun applyBallImage(
        uri: Uri,
        scale: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        persist: Boolean,
    ) {
        if (persist) {
            // Some sources (e.g. a freshly granted content URI from
            // the photo picker) already give us a long-term grant
            // and takePersistableUriPermission will throw
            // SecurityException if called redundantly. Swallow that
            // case so a benign race doesn't break the pick flow.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { Log.w(TAG, "takePersistableUriPermission failed for $uri", it) }
            writeBallImagePref(uri, scale, offsetX, offsetY)
        }
        if (ballAttached) {
            ballView.setBallImageUri(uri)
            // setBallImageUri kicks off an async decode; we apply
            // the transform after that completes by re-issuing
            // it here. [BallView] will ignore the call until the
            // bitmap is ready, then re-apply on the next draw. To
            // keep things simple we just call both back-to-back and
            // let setAvatarTransform re-trigger a redraw.
            ballView.setAvatarTransform(scale, offsetX, offsetY)
        }
    }

    private fun ballPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun readBallImagePref(): Uri? =
        ballPrefs().getString(KEY_BALL_IMAGE_URI, null)?.let(Uri::parse)
    private fun writeBallImagePref(uri: Uri, scale: Float, offsetX: Float, offsetY: Float) {
        ballPrefs().edit {
            putString(KEY_BALL_IMAGE_URI, uri.toString())
            putFloat(KEY_BALL_IMAGE_SCALE, scale)
            putFloat(KEY_BALL_IMAGE_OFFSET_X, offsetX)
            putFloat(KEY_BALL_IMAGE_OFFSET_Y, offsetY)
        }
    }
    private fun readBallImageTransformPref(): Triple<Float, Float, Float> {
        val prefs = ballPrefs()
        return Triple(
            prefs.getFloat(KEY_BALL_IMAGE_SCALE, 1f),
            prefs.getFloat(KEY_BALL_IMAGE_OFFSET_X, 0f),
            prefs.getFloat(KEY_BALL_IMAGE_OFFSET_Y, 0f),
        )
    }
    private fun clearBallImagePref() {
        ballPrefs().edit {
            remove(KEY_BALL_IMAGE_URI)
            remove(KEY_BALL_IMAGE_SCALE)
            remove(KEY_BALL_IMAGE_OFFSET_X)
            remove(KEY_BALL_IMAGE_OFFSET_Y)
        }
    }

    private fun attachOverlay(originX: Int, originY: Int): Boolean {
        if (overlayAttached) return true
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val accessibilityService = PinAccessibilityService.current()
        // Accessibility overlays sit above system bars; application overlays do not.
        val overlayContext = accessibilityService ?: this
        val manager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = SelectionOverlayView(overlayContext).apply {
            listener = overlayListener
            precisionEnabled = true
        }
        val type = if (accessibilityService != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = view.layoutParamsForScreen(metrics.widthPixels, metrics.heightPixels, type)
        return runCatching { manager.addView(view, lp) }
            .onSuccess {
                selectionWindowManager = manager
                overlayView = view
                overlayAttached = true
                Log.d(TAG, "overlay attached at ($originX, $originY)")
                // Pre-arm the overlay with the ball's armed point as
                // the rubber-band origin. Without this, the overlay
                // would wait for an ACTION_DOWN before drawing the
                // rect — but the ball has already consumed the only
                // ACTION_DOWN. By forceArming, the very next
                // ACTION_MOVE (forwarded from the ball) extends the
                // rect, and ACTION_UP confirms it.
                view.forceArm(originX.toFloat(), originY.toFloat(),
                    ballView.selectionTouchX, ballView.selectionTouchY)
            }
            .onFailure { Log.e(TAG, "addView(overlay) failed", it) }.isSuccess
    }

    private fun detachOverlay(): Boolean {
        if (!overlayAttached) return true
        overlayView?.let {
            try {
                checkNotNull(selectionWindowManager).removeViewImmediate(it)
            } catch (e: Exception) {
                Log.w(TAG, "Selection window removal failed", e)
                return false
            }
        }
        overlayView = null
        overlayAttached = false
        selectionWindowManager = null
        return true
    }

    private val ballHost: BallView.Host = object : BallView.Host {
        override fun onArm(originX: Int, originY: Int) {
            if (pendingCapture != null) {
                ballView.resetToIdle()
                return
            }
            Log.d(TAG, "ball.onArm at ($originX, $originY) — bringing up selection overlay")
            // Keep the original input target alive; the overlay renders the cursor.
            if (!attachOverlay(originX, originY)) {
                ballView.resetToIdle()
                return
            }
            ballView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            ballView.enterSelecting()
            // Alpha keeps the current input stream alive while the overlay draws its cursor.
            ballView.alpha = 0f
        }

        override fun onSelectDone(rectInScreen: Rect) {
            Log.d(TAG, "ball.onSelectDone $rectInScreen")
            detachOverlay()
            ballView.resetToIdle()
            // Only send the request here; screenshot processing remains off-thread.
            runCapture(rectInScreen)
        }

        override fun onCancel() {
            Log.d(TAG, "ball.onCancel")
            detachOverlay()
            ballView.resetToIdle()
        }
    }

    private val overlayListener: SelectionOverlayView.Listener = object : SelectionOverlayView.Listener {
        override fun onConfirm(rect: Rect) {
            ballHost.onSelectDone(rect)
        }
        override fun onAction(rect: Rect, action: SelectionOverlayView.Action) {
            val captureAction = when (action) {
                SelectionOverlayView.Action.ANNOTATE -> Protocol.ACTION_ANNOTATE
                SelectionOverlayView.Action.PIN -> Protocol.ACTION_PIN
                SelectionOverlayView.Action.COPY -> Protocol.ACTION_COPY
                SelectionOverlayView.Action.SHARE -> Protocol.ACTION_SHARE
                SelectionOverlayView.Action.SAVE -> Protocol.ACTION_SAVE
            }
            // The action toolbar can finish the selection directly, so it
            // does not pass through onSelectDone(). Restore the ball and
            // remove the full-screen overlay before capture starts.
            // Otherwise the ball remains alpha=0 from onArm() and the
            // capture restore only brings back its visibility, leaving it
            // apparently missing after a pin action.
            detachOverlay()
            ballView.resetToIdle()
            runCapture(rect, captureAction)
        }
        override fun onCancel() {
            ballHost.onCancel()
        }
    }

    private fun runCapture(rect: Rect, action: String = Protocol.ACTION_PIN) {
        // The a11y service does the actual takeScreenshot() — we can't
        // do it from here because Android 14+ throws
        // SecurityException("Services don't have the capability of
        // taking the screenshot.") when a non-a11y component invokes
        // AccessibilityService.takeScreenshot(). So we broadcast a
        // request, the a11y receiver hides pins, calls
        // takeScreenshot() itself, crops to the user's selection
        // rect, writes the cache, and inserts the pin.
        Log.d(TAG, "runCapture broadcasting CAPTURE_SCREENSHOT with rect=$rect")

        if (pendingCapture != null) return
        if (rect.isEmpty || rect.left < 0 || rect.top < 0) return
        val requestId = java.util.UUID.randomUUID().toString()
        pendingCapture = requestId
        pendingAction = action
        captureToast?.cancel()
        captureStartedAt = SystemClock.elapsedRealtime()
        captureHandler.postDelayed(slowCaptureFeedback, 1_000L)
        captureHandler.postDelayed(captureTimeout, Protocol.TIMEOUT_MS + 2_000)

        try {
            val intent = android.content.Intent(
                PinAccessibilityService.ACTION_CAPTURE_SCREENSHOT,
            ).apply {
                setPackage(packageName)
                putExtra(Protocol.ID, requestId)
                putExtra(Protocol.ACTION, action)
                putExtra(PinAccessibilityService.EXTRA_RECT_LEFT, rect.left)
                putExtra(PinAccessibilityService.EXTRA_RECT_TOP, rect.top)
                putExtra(PinAccessibilityService.EXTRA_RECT_RIGHT, rect.right)
                putExtra(PinAccessibilityService.EXTRA_RECT_BOTTOM, rect.bottom)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            completeCapture(requestId, false, "request_failed")
        }
    }

    private fun restoreCaptureBall(id: String) {
        if (hiddenForCapture != id) return
        hiddenForCapture = null
        if (ballAttached) ballView.visibility = previousBallVisibility
    }

    private fun completeCapture(id: String, success: Boolean, error: String?, historyPath: String? = null) {
        if (pendingCapture != id) return
        captureHandler.removeCallbacks(captureTimeout)
        captureHandler.removeCallbacks(slowCaptureFeedback)
        captureToast?.cancel()
        restoreCaptureBall(id)
        val action = pendingAction
        pendingCapture = null
        pendingAction = Protocol.ACTION_PIN
        savingCapture = null
        if (com.pinpin.app.BuildConfig.DEBUG) Log.d("CaptureTiming",
            "id=$id ball_receipt=${SystemClock.elapsedRealtime() - captureStartedAt}ms success=$success")
        if (!success) {
            Log.w(TAG, "Capture $id failed: $error")
            val message = when (error) {
                "status_unknown" -> "截图状态未确认，请检查历史记录；若没有新图，请重新启用截图服务后重试"
                "busy" -> "上一张截图仍在处理，请稍后再试"
                "timeout" -> "截图等待超时，请重试"
                "processing_failed", "saving_failed" -> "图片保存未完成，请检查历史记录和存储空间"
                "screenshot_${android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW}" ->
                    "当前页面禁止截图"
                "screenshot_${android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT}" ->
                    "截图操作过于频繁，请稍后重试"
                "cancelled", "interrupted" -> "截图已取消"
                else -> "截图未完成，请检查截图权限后重试 ($error)"
            }
            showCaptureMessage(message, true)
        } else if (historyPath != null) {
            performCaptureAction(action, historyPath)
        }
    }

    private fun performCaptureAction(action: String, rawPath: String) {
        val historyRoot = java.io.File(filesDir.canonicalFile, "screenshot_history").canonicalFile
        val file = runCatching { java.io.File(rawPath).canonicalFile }.getOrNull()
            ?.takeIf { it.parentFile == historyRoot && it.isFile } ?: return
        when (action) {
            Protocol.ACTION_COPY -> {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newRawUri("PinPin screenshot", uri))
                showCaptureMessage("已复制截图", false)
            }
            Protocol.ACTION_SHARE -> {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("PinPin screenshot", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "分享截图")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Protocol.ACTION_SAVE -> {
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                            "pinpin-${System.currentTimeMillis()}.png")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/pinpin")
                    }
                    val destination = contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: error("Could not create gallery entry")
                    try {
                        contentResolver.openOutputStream(destination)?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        } ?: error("Could not open gallery output")
                        contentResolver.update(destination, android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        }, null, null)
                    } catch (failure: Throwable) {
                        contentResolver.delete(destination, null, null)
                        throw failure
                    }
                    showCaptureMessage("已保存到相册", false)
                }.onFailure { showCaptureMessage("保存截图失败", true) }
            }
            Protocol.ACTION_ANNOTATE -> showCaptureMessage("已钉图，可在悬浮图工具栏中标注", false)
        }
    }

    private fun showCaptureMessage(message: String, long: Boolean) {
        captureToast?.cancel()
        captureToast = android.widget.Toast.makeText(this, message,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT)
        captureToast?.show()
    }

    /**
     * Bring the host app to the foreground. Used as the
     * [BallView.onTap] target so a quick tap on the floating
     * ball opens the home screen for the user.
     *
     * We use [Intent.FLAG_ACTIVITY_NEW_TASK] (the service has
     * no task of its own) plus [Intent.FLAG_ACTIVITY_CLEAR_TOP]
     * so if MainActivity is already in the back stack it gets
     * brought to the front instead of a fresh duplicate being
     * pushed on top. [Intent.FLAG_ACTIVITY_SINGLE_TOP] also
     * covers the case where MainActivity is already on top
     * and the user just tapped through an overlay.
     */
    private fun openHostApp() {
        val launch = android.content.Intent(this, com.pinpin.app.ui.MainActivity::class.java)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        runCatching { startActivity(launch) }
            .onFailure { Log.w(TAG, "openHostApp: startActivity failed", it) }
    }

    // region Notification

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPi = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, BallService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(getString(R.string.ball_notification_title))
            .setContentText(getString(R.string.ball_notification_text))
            .setOngoing(true)
            .setContentIntent(openAppPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.ball_action_stop),
                stopPi,
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ball_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.ball_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    // endregion

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )

    private fun readBitmapSize(file: java.io.File): Pair<Int, Int> {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) {
            opts.outWidth to opts.outHeight
        } else {
            1920 to 1080
        }
    }

    companion object {
        private const val TAG = "BallService"
        private const val NOTIFICATION_ID = 0x42414C  // "BAL"
        private const val CHANNEL_ID = "pinpin_ball"

        private const val PREFS_NAME = "pinpin_ball"
        private const val KEY_BALL_IMAGE_URI = "ball_image_uri"
        private const val KEY_BALL_IMAGE_SCALE = "ball_image_scale"
        private const val KEY_BALL_IMAGE_OFFSET_X = "ball_image_offset_x"
        private const val KEY_BALL_IMAGE_OFFSET_Y = "ball_image_offset_y"

        const val ACTION_STOP = "com.pinpin.app.ball.STOP"
        const val ACTION_HIDE_OVERLAY = "com.pinpin.app.ball.HIDE_OVERLAY"
        const val ACTION_SET_BALL_IMAGE = "com.pinpin.app.ball.SET_BALL_IMAGE"
        const val ACTION_CLEAR_BALL_IMAGE = "com.pinpin.app.ball.CLEAR_BALL_IMAGE"
        const val EXTRA_BALL_IMAGE_URI = "ball_image_uri"
        const val EXTRA_BALL_IMAGE_SCALE = "ball_image_scale"
        const val EXTRA_BALL_IMAGE_OFFSET_X = "ball_image_offset_x"
        const val EXTRA_BALL_IMAGE_OFFSET_Y = "ball_image_offset_y"

        fun start(context: Context) {
            val intent = Intent(context, BallService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BallService::class.java))
        }

        /**
         * Set the floating ball's image from a [Uri] (typically a
         * content URI returned by SAF / the photo picker). The URI is
         * persisted in SharedPreferences and the ball picks it up the
         * next time it is drawn. The optional [scale] / [offsetX] /
         * [offsetY] describe a user-driven transform layered on top
         * of the default CENTER_CROP cover; defaults to (1, 0, 0)
         * which is the legacy "fit cover, no pan" behavior. The
         * [BallImageAdjustActivity] preview passes the values the
         * user actually picked so the saved ball matches what they
         * saw on the preview.
         */
        fun setBallImage(
            context: Context,
            uri: Uri,
            scale: Float = 1f,
            offsetX: Float = 0f,
            offsetY: Float = 0f,
        ) {
            val intent = Intent(context, BallService::class.java)
                .setAction(ACTION_SET_BALL_IMAGE)
                .putExtra(EXTRA_BALL_IMAGE_URI, uri)
                .putExtra(EXTRA_BALL_IMAGE_SCALE, scale)
                .putExtra(EXTRA_BALL_IMAGE_OFFSET_X, offsetX)
                .putExtra(EXTRA_BALL_IMAGE_OFFSET_Y, offsetY)
            context.startForegroundService(intent)
        }

        /** Revert the ball to its default red/yellow style. */
        fun clearBallImage(context: Context) {
            val intent = Intent(context, BallService::class.java)
                .setAction(ACTION_CLEAR_BALL_IMAGE)
            context.startForegroundService(intent)
        }

        /** True if a custom image has been chosen and persisted. */
        fun hasCustomBallImage(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_BALL_IMAGE_URI, null).isNullOrEmpty()
        }
    }
}
