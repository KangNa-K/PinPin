package com.pinpin.app.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.pinpin.app.data.PinRepository
import com.pinpin.app.overlay.PinOverlayService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Accessibility service that owns the screen-capture path.
 *
 * Why an a11y service:
 *   We could route the screen capture through MediaProjection, but
 *   that would require a "share this screen" system dialog the
 *   first time the user runs the app. fooView (FV 悬浮球) avoids
 *   that dialog by calling [AccessibilityService.takeScreenshot]
 *   from inside an a11y service. The a11y permission is the only
 *   thing the system asks for up front, and from then on the user
 *   can pin screenshots with zero extra dialogs.
 *
 * Why the capture MUST be invoked from inside this service:
 *   On Android 14+ the system enforces that takeScreenshot() be
 *   called *by the a11y service itself*, not by any other component
 *   that happens to hold a reference to the service instance. If
 *   BallService (a plain Service) calls
 *   a11yInstance.takeScreenshot(...) the system throws
 *   SecurityException("Services don't have the capability of
 *   taking the screenshot.") because the caller in the call stack
 *   is BallService, not the a11y service. The fix is for the a11y
 *   service to own the screenshot path end-to-end: it listens to a
 *   broadcast, calls takeScreenshot() itself, crops the result,
 *   writes it to disk, and inserts the pin via [PinRepository].
 *
 * Why we need minSdk 30:
 *   [AccessibilityService.takeScreenshot] was added in Android 11
 *   (API 30). On older devices the capture method short-circuits
 *   with a clear error log so the rest of the app stays usable
 *   (just no pin-from-screencap on those devices).
 *
 * Lifecycle:
 *   1. The system starts this service after the user enables it
 *      from the system a11y settings.
 *   2. [BallService] sends a CAPTURE_SCREENSHOT broadcast with the
 *      screen-space selection rect packed in its extras.
 *   3. The receiver in this service hides all currently-pinned
 *      overlays, calls [AccessibilityService.takeScreenshot], crops
 *      the returned bitmap to the rect, writes it to the cache
 *      directory, and inserts a new pin via [PinRepository].
 *   4. The receiver re-shows the pinned overlays whether or not
 *      takeScreenshot succeeded (otherwise the user's pinned
 *      images would vanish permanently after a failed capture).
 */
@AndroidEntryPoint
class PinAccessibilityService : AccessibilityService() {

    @Inject lateinit var pinRepository: PinRepository
    @Inject lateinit var historyRepository: com.pinpin.app.data.ScreenshotHistoryRepository

    private val captureThread by lazy { HandlerThread("pin-a11y-capture").apply { start() } }
    private val captureHandler by lazy { Handler(captureThread.looper) }
    private val captureExecutor: Executor = Executor { command ->
        captureHandler.post(command)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CAPTURE_SCREENSHOT) return
            val rect = Rect(
                intent.getIntExtra(EXTRA_RECT_LEFT, 0),
                intent.getIntExtra(EXTRA_RECT_TOP, 0),
                intent.getIntExtra(EXTRA_RECT_RIGHT, 0),
                intent.getIntExtra(EXTRA_RECT_BOTTOM, 0),
            )
            Log.d(TAG, "received CAPTURE_SCREENSHOT broadcast, rect=$rect")
            // Post the actual takeScreenshot() call to our own main
            // looper rather than invoking it from onReceive. The
            // IPC identity in onReceive is the system's
            // LoadedApk$ReceiverDispatcher thread, which Android
            // 14+'s stricter "is the caller really the a11y service"
            // check may reject with "Services don't have the
            // capability of taking the screenshot". Routing through
            // our own main looper makes the calling thread (and
            // therefore the binder identity) the a11y service itself.
            mainHandler.post { handleCaptureRequest(rect) }
        }
    }

    /**
     * Take a screenshot of the entire screen, crop to the user's
     * selection [rect], write the result to the cache dir, and
     * insert it as a new pin.
     *
     * Must be called from within the a11y service itself (i.e. from
     * a receiver or a callback that originated here) because
     * takeScreenshot() enforces the "caller must be the a11y service"
     * invariant on Android 14+.
     */
    private fun handleCaptureRequest(rect: Rect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+; current API is ${Build.VERSION.SDK_INT}")
            return
        }
        // Hide all currently-pinned overlays so the screenshot does
        // not include them. Must be done on the main thread because
        // PinOverlayService.startForegroundService → onStartCommand
        // posts to the service handler.
        PinOverlayService.setAllPinsVisible(this, false)
        // Also tear down BallService's selection overlay. Without
        // this the purple marquee was getting captured into the
        // bitmap because detachOverlay() in BallService only takes
        // effect on the next SurfaceFlinger frame, and takeScreenshot
        // runs ~4ms later — fast enough to race the layer teardown.
        // We wait 250ms before takeScreenshot so the layer is
        // guaranteed gone.
        sendBroadcast(
            android.content.Intent(com.pinpin.app.ball.BallService.ACTION_HIDE_OVERLAY)
                .setPackage(packageName),
        )
        mainHandler.postDelayed({ requestScreenshot(rect) }, 250)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshot(rect: Rect) {
        Log.d(TAG, "requestScreenshot: rect=$rect, calling takeScreenshot()")
        takeScreenshot(
            /* grayscaleMode = */ 0,
            captureExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    Log.d(TAG, "takeScreenshot onSuccess: buffer=${result.hardwareBuffer}, colorSpace=${result.colorSpace}")
                    // Always restore pin visibility, even on
                    // processing errors, so the user's pinned
                    // images don't vanish if a11y capture chokes.
                    PinOverlayService.setAllPinsVisible(this@PinAccessibilityService, true)
                    val buffer = result.hardwareBuffer ?: run {
                        Log.w(TAG, "screenshot returned null hardwareBuffer")
                        return
                    }
                    val colorSpace: ColorSpace? = result.colorSpace
                    val full = try {
                        Bitmap.wrapHardwareBuffer(buffer, colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
                    } finally {
                        buffer.close()
                    }
                    if (full == null) {
                        Log.w(TAG, "wrapHardwareBuffer returned null bitmap")
                        return
                    }
                    Log.d(TAG, "full bitmap: ${full.width}x${full.height}, cropping to $rect")
                    val cropped = cropRect(full, rect)
                    if (cropped !== full) full.recycle()
                    Log.d(TAG, "cropped bitmap: ${cropped.width}x${cropped.height}")
                    persistBitmapAndPin(cropped)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed with error code $errorCode (see PinAccessibilityService.kt comment for code mapping)")
                    PinOverlayService.setAllPinsVisible(this@PinAccessibilityService, true)
                }
            },
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = serviceInfo ?: return
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }
        Log.d(TAG, "a11y service connected")
        instance = this
        val filter = IntentFilter(ACTION_CAPTURE_SCREENSHOT)
        // Targeting Android 14+, exported receivers must declare
        // RECEIVER_NOT_EXPORTED or RECEIVER_EXPORTED. We're filtering
        // to our own package via setPackage() in BallService, so
        // NOT_EXPORTED is correct (and safer).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(captureReceiver, filter)
        }
        Log.d(TAG, "registered CAPTURE_SCREENSHOT receiver")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "a11y service unbound")
        instance = null
        runCatching { unregisterReceiver(captureReceiver) }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(captureReceiver) }
        captureThread.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't act on events for now.
    }

    private fun persistBitmapAndPin(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        scope.launch {
            try {
                // Save into history first (this recycles the bitmap
                // and persists a PNG under filesDir). We then copy
                // that PNG into the pin cache dir so the pin layer's
                // existing file:// URI handling keeps working.
                val historyPath = historyRepository.addFromBitmap(bitmap, "ball-screencap")
                val historyFile = java.io.File(historyPath)
                val pinFile = java.io.File(
                    java.io.File(cacheDir, "screenshots"),
                    "crop-${System.currentTimeMillis()}.png",
                )
                pinFile.parentFile?.mkdirs()
                historyFile.copyTo(pinFile, overwrite = true)
                Log.d(TAG, "wrote pin file ${pinFile.absolutePath} (${pinFile.length()} bytes)")
                pinRepository.addPinFromUri(
                    imageUri = android.net.Uri.fromFile(pinFile),
                    sourceType = "ball-screencap",
                    defaultWidthPx = w,
                    defaultHeightPx = h,
                )
                Log.d(TAG, "pin added via repository")
            } catch (t: Throwable) {
                Log.e(TAG, "persistBitmapAndPin failed", t)
            }
        }
    }

    private fun cropRect(full: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, full.width - 1)
        val top = rect.top.coerceIn(0, full.height - 1)
        val right = rect.right.coerceIn(1, full.width)
        val bottom = rect.bottom.coerceIn(1, full.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        if (left == 0 && top == 0 && w == full.width && h == full.height) {
            return full
        }
        return Bitmap.createBitmap(full, left, top, w, h)
    }

    companion object {
        private const val TAG = "PinAccessibilityService"

        const val ACTION_CAPTURE_SCREENSHOT = "com.pinpin.app.a11y.CAPTURE_SCREENSHOT"
        const val EXTRA_RECT_LEFT = "rect_left"
        const val EXTRA_RECT_TOP = "rect_top"
        const val EXTRA_RECT_RIGHT = "rect_right"
        const val EXTRA_RECT_BOTTOM = "rect_bottom"

        @Volatile
        private var instance: PinAccessibilityService? = null

        fun current(): PinAccessibilityService? = instance

        fun isEnabled(context: android.content.Context): Boolean {
            val expected = "${context.packageName}/${PinAccessibilityService::class.java.name}"
            val raw = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return raw.split(':').any { token ->
                token.equals(expected, ignoreCase = true)
            }
        }

        fun buildSettingsIntent(context: android.content.Context): android.content.Intent {
            return android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
