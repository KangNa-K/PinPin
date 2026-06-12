package com.pinpin.app.ball

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pinpin.app.R
import com.pinpin.app.a11y.PinAccessibilityService
import com.pinpin.app.data.PinRepository
import com.pinpin.app.overlay.PinOverlayService
import com.pinpin.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    @Inject lateinit var pinRepository: PinRepository

    private val windowManager: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val ballView by lazy {
        BallView(this).also { it.setHost(ballHost) }
    }

    private val captureThread by lazy { HandlerThread("ball-capture").apply { start() } }
    private val captureHandler by lazy { Handler(captureThread.looper) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var ballAttached = false
    private var overlayView: SelectionOverlayView? = null
    private var overlayAttached = false

    private val hideOverlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != ACTION_HIDE_OVERLAY) return
            Log.d(TAG, "HIDE_OVERLAY broadcast received — tearing down overlay so a11y screencap is clean")
            // Synchronously remove the overlay view. WindowManager
            // itself completes the IPC synchronously, but the
            // SurfaceFlinger side takes a few frames to actually
            // drop the layer — the a11y side waits another 250ms
            // before calling takeScreenshot() so that race never
            // matters.
            detachOverlay()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = android.content.IntentFilter(ACTION_HIDE_OVERLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hideOverlayReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(hideOverlayReceiver, filter)
        }
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
        detachOverlay()
        if (ballAttached) runCatching { windowManager.removeView(ballView) }
        ballAttached = false
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        captureThread.quitSafely()
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
        ballPrefs().edit()
            .putString(KEY_BALL_IMAGE_URI, uri.toString())
            .putFloat(KEY_BALL_IMAGE_SCALE, scale)
            .putFloat(KEY_BALL_IMAGE_OFFSET_X, offsetX)
            .putFloat(KEY_BALL_IMAGE_OFFSET_Y, offsetY)
            .apply()
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
        ballPrefs().edit()
            .remove(KEY_BALL_IMAGE_URI)
            .remove(KEY_BALL_IMAGE_SCALE)
            .remove(KEY_BALL_IMAGE_OFFSET_X)
            .remove(KEY_BALL_IMAGE_OFFSET_Y)
            .apply()
    }

    private fun attachOverlay(originX: Int, originY: Int) {
        if (overlayAttached) return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val view = SelectionOverlayView(this).apply {
            listener = overlayListener
        }
        val lp = view.layoutParamsForScreen(metrics.widthPixels, metrics.heightPixels)
        runCatching { windowManager.addView(view, lp) }
            .onSuccess {
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
                view.forceArm(originX.toFloat(), originY.toFloat())
            }
            .onFailure { Log.e(TAG, "addView(overlay) failed", it) }
    }

    private fun detachOverlay() {
        if (!overlayAttached) return
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        overlayAttached = false
    }

    private val ballHost: BallView.Host = object : BallView.Host {
        override fun onArm(originX: Int, originY: Int) {
            Log.d(TAG, "ball.onArm at ($originX, $originY) — bringing up selection overlay")
            // Spec: 0.7s of "ball pressed" turns the ball yellow and
            // pops up a full-screen selection overlay at the armed
            // point. The user then extends the rect by moving their
            // finger; the overlay reports a final Rect on ACTION_UP
            // via onSelectDone. From this point the ball view's
            // ARMED/SELECTING branch is forwarding every subsequent
            // touch event to the overlay (see
            // BallView.onTouchEvent's armRedirect) — we just need
            // to flip the ball into SELECTING and let the overlay
            // own the rest of the gesture.
            attachOverlay(originX, originY)
            ballView.enterSelecting()
        }

        override fun onSelectDone(rectInScreen: Rect) {
            Log.d(TAG, "ball.onSelectDone $rectInScreen")
            detachOverlay()
            ballView.resetToIdle()
            // Run capture on a background thread so we don't block the
            // ball's touch event loop.
            captureHandler.post { runCapture(rectInScreen) }
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
        override fun onCancel() {
            ballHost.onCancel()
        }
    }

    private fun runCapture(rect: Rect) {
        // The a11y service does the actual takeScreenshot() — we can't
        // do it from here because Android 14+ throws
        // SecurityException("Services don't have the capability of
        // taking the screenshot.") when a non-a11y component invokes
        // AccessibilityService.takeScreenshot(). So we broadcast a
        // request, the a11y receiver hides pins, calls
        // takeScreenshot() itself, crops to the user's selection
        // rect, writes the cache, and inserts the pin.
        Log.d(TAG, "runCapture broadcasting CAPTURE_SCREENSHOT with rect=$rect")

        // Snapshot the pin count BEFORE the broadcast. We use this
        // 2 seconds later to detect the case where the broadcast
        // was silently dropped — most commonly because the a11y
        // service process has been killed (system marks it
        // "Crashed" after that and won't deliver any further
        // broadcasts until the user re-enables it from Settings →
        // Accessibility). Without this check, the user's selection
        // gesture simply disappears into the void and they get
        // no feedback that something went wrong.
        val pinCountBefore = pinRepository.pinsState.value.size

        captureHandler.post {
            val intent = android.content.Intent(
                PinAccessibilityService.ACTION_CAPTURE_SCREENSHOT,
            ).apply {
                setPackage(packageName)
                putExtra(PinAccessibilityService.EXTRA_RECT_LEFT, rect.left)
                putExtra(PinAccessibilityService.EXTRA_RECT_TOP, rect.top)
                putExtra(PinAccessibilityService.EXTRA_RECT_RIGHT, rect.right)
                putExtra(PinAccessibilityService.EXTRA_RECT_BOTTOM, rect.bottom)
            }
            sendBroadcast(intent)
        }

        // After a generous delay, verify the a11y service actually
        // received the broadcast and inserted a new pin. If not,
        // surface an actionable Toast so the user knows to
        // re-enable the a11y service in Settings (the most common
        // reason a previously-working capture stops is the system
        // killing the a11y service process for memory pressure).
        captureHandler.postDelayed({
            val pinCountAfter = pinRepository.pinsState.value.size
            if (pinCountAfter <= pinCountBefore) {
                Log.w(
                    TAG,
                    "runCapture: a11y service did not respond within 2s " +
                        "(pin count unchanged at $pinCountBefore). " +
                        "The a11y service process is likely killed — " +
                        "ask the user to re-enable it in Settings.",
                )
                android.widget.Toast.makeText(
                    this,
                    "无障碍服务未响应。请到 设置 → 辅助功能 → 钉图，关闭再打开一次以恢复截图功能",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }, 2000L)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Revert the ball to its default red/yellow style. */
        fun clearBallImage(context: Context) {
            val intent = Intent(context, BallService::class.java)
                .setAction(ACTION_CLEAR_BALL_IMAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** True if a custom image has been chosen and persisted. */
        fun hasCustomBallImage(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_BALL_IMAGE_URI, null).isNullOrEmpty()
        }
    }
}
