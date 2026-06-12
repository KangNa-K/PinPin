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
                    Log.d(TAG, "SET_BALL_IMAGE received: $uri")
                    applyBallImage(uri, persist = true)
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
        Log.d(TAG, "restoring saved ball image: $uri")
        applyBallImage(uri, persist = false)
    }

    /**
     * Hand [uri] to the ball view for rendering. If [persist] is true,
     * also take a long-term read permission on the URI (required for
     * content URIs returned by SAF / the photo picker) and write the
     * URI to SharedPreferences so it survives process death.
     */
    private fun applyBallImage(uri: Uri, persist: Boolean) {
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
            writeBallImagePref(uri)
        }
        if (ballAttached) {
            ballView.setBallImageUri(uri)
        }
    }

    private fun ballPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun readBallImagePref(): Uri? =
        ballPrefs().getString(KEY_BALL_IMAGE_URI, null)?.let(Uri::parse)
    private fun writeBallImagePref(uri: Uri) {
        ballPrefs().edit().putString(KEY_BALL_IMAGE_URI, uri.toString()).apply()
    }
    private fun clearBallImagePref() {
        ballPrefs().edit().remove(KEY_BALL_IMAGE_URI).apply()
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

        const val ACTION_STOP = "com.pinpin.app.ball.STOP"
        const val ACTION_HIDE_OVERLAY = "com.pinpin.app.ball.HIDE_OVERLAY"
        const val ACTION_SET_BALL_IMAGE = "com.pinpin.app.ball.SET_BALL_IMAGE"
        const val ACTION_CLEAR_BALL_IMAGE = "com.pinpin.app.ball.CLEAR_BALL_IMAGE"
        const val EXTRA_BALL_IMAGE_URI = "ball_image_uri"

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
         * next time it is drawn.
         */
        fun setBallImage(context: Context, uri: Uri) {
            val intent = Intent(context, BallService::class.java)
                .setAction(ACTION_SET_BALL_IMAGE)
                .putExtra(EXTRA_BALL_IMAGE_URI, uri)
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
