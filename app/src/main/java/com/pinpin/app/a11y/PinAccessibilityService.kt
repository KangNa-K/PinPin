package com.pinpin.app.a11y

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import com.pinpin.app.overlay.ImageRenderWork
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.pinpin.app.capture.CaptureGate
import com.pinpin.app.capture.CaptureBounds
import com.pinpin.app.capture.CaptureTimings
import com.pinpin.app.capture.completeCaptureSave
import com.pinpin.app.capture.registerCaptureReceiver
import com.pinpin.app.capture.CaptureProtocol as Protocol
import com.pinpin.app.data.PinAssetStore
import com.pinpin.app.data.PinRepository
import com.pinpin.app.data.ScreenshotHistoryRepository
import com.pinpin.app.overlay.PinOverlayService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import javax.inject.Inject

/** Owns capture, bitmap lifetime, persistence and the single outstanding request. */
@AndroidEntryPoint
class PinAccessibilityService : AccessibilityService() {
    @Inject lateinit var pinRepository: PinRepository
    @Inject lateinit var historyRepository: ScreenshotHistoryRepository
    @Inject lateinit var assetStore: PinAssetStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = CaptureGate()
    private var request: Request? = null
    private var registered = false
    private var closed = false
    private val completedIds = LinkedHashSet<String>()

    private class Request(val id: String, val rect: Rect) {
        val timings = CaptureTimings(id)
        var terminal = false
        var saving = false
        var windowsRestored = false
        var hidden = false
        var worker: Job? = null
        var timeout: Runnable? = null
        var capture: Runnable? = null
        var action: String = Protocol.ACTION_PIN
        var historyPath: String? = null
    }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = runCatching { intent.getStringExtra(Protocol.ID) }.getOrNull()
                ?.takeIf(Protocol::validId) ?: return
            when (intent.action) {
                ACTION_CAPTURE_SCREENSHOT -> {
                    val rect = runCatching {
                        val keys = listOf(EXTRA_RECT_LEFT, EXTRA_RECT_TOP, EXTRA_RECT_RIGHT, EXTRA_RECT_BOTTOM)
                        require(keys.all { intent.hasExtra(it) })
                        Rect(intent.getIntExtra(keys[0], -1), intent.getIntExtra(keys[1], -1),
                            intent.getIntExtra(keys[2], -1), intent.getIntExtra(keys[3], -1))
                    }.getOrNull()
                    if (closed || rect == null || rect.left < 0 || rect.top < 0 || rect.isEmpty) {
                        reply(id, "invalid_request")
                    } else if (id in completedIds) {
                        reply(id, "expired_request")
                    } else if (gate.activeId == id) {
                        return // Active retransmission must not terminate its caller.
                    } else if (!gate.acquire(id)) {
                        reply(id, "busy")
                    } else {
                        begin(Request(id, rect).also {
                            it.action = intent.getStringExtra(Protocol.ACTION)
                                ?.takeIf { action -> action in setOf(
                                    Protocol.ACTION_PIN, Protocol.ACTION_COPY,
                                    Protocol.ACTION_SHARE, Protocol.ACTION_SAVE,
                                    Protocol.ACTION_ANNOTATE,
                                ) } ?: Protocol.ACTION_PIN
                        })
                    }
                }
                Protocol.HIDDEN -> request?.takeIf { it.id == id && !it.terminal && !it.hidden }?.let { r ->
                    r.hidden = true
                    r.timings.mark("hidden_ack")
                    // Allow visibility transactions to reach the compositor.
                    r.capture = Runnable { if (!r.terminal) screenshot(r) }
                    mainHandler.postDelayed(r.capture!!, 250)
                }
                Protocol.CANCEL -> request?.takeIf { it.id == id }?.let { abortCapture(it, "cancelled") }
            }
        }
    }

    private fun begin(r: Request) {
        request = r
        activeRequestIds.add(r.id)
        r.timeout = Runnable { abortCapture(r, "timeout") }
        mainHandler.postDelayed(r.timeout!!, Protocol.TIMEOUT_MS)
        try {
            PinOverlayService.setAllPinsVisible(this, false)
            sendBroadcast(Intent(Protocol.HIDE).setPackage(packageName).putExtra(Protocol.ID, r.id))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to hide capture windows", e)
            finish(r, "hide_failed")
        }
    }

    private fun screenshot(r: Request) {
        try {
            r.timings.mark("capture_start")
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) = abortCapture(r, "screenshot_$errorCode")
                override fun onSuccess(result: ScreenshotResult) {
                    if (r.terminal || r.saving) {
                        result.hardwareBuffer.close()
                        return
                    }
                    // The deadline covers only hide/handshake/takeScreenshot. From here on,
                    // lifecycle cancellation must not turn a completed DB commit into failure.
                    r.saving = true
                    r.timings.mark("capture_ready")
                    r.timeout?.let(mainHandler::removeCallbacks)
                    restoreWindows(r)
                    reportSaving(r)
                    r.worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        var bitmap: Bitmap? = null
                        var width = 0
                        var height = 0
                        var capturedBounds: CaptureBounds? = null
                        // Own the buffer outside withContext so cancellation before dispatch closes it.
                        val buffer = result.hardwareBuffer
                        var bufferClosed = false
                        try {
                            completeCaptureSave(
                                prepare = {
                                    ImageRenderWork.run {
                                        withContext(Dispatchers.IO) {
                                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                                ?: error("Cannot wrap screenshot")
                                            val software = try {
                                                hardware.copy(Bitmap.Config.ARGB_8888, false)
                                                    ?: error("Cannot copy screenshot")
                                            } finally {
                                                hardware.recycle()
                                            }
                                            try {
                                                val crop = requireNotNull(CaptureBounds(r.rect.left, r.rect.top,
                                                    r.rect.right, r.rect.bottom).intersect(software.width, software.height)) {
                                                    "Selection outside screenshot"
                                                }
                                                bitmap = Bitmap.createBitmap(software, crop.left, crop.top, crop.width, crop.height)
                                                capturedBounds = crop
                                            } finally {
                                                if (bitmap !== software) software.recycle()
                                            }
                                            buffer.close()
                                            bufferClosed = true
                                            r.timings.mark("crop_ready")
                                        }
                                        val image = checkNotNull(bitmap)
                                        // History owns/recycles the bitmap; retain dimensions before handing it off.
                                        width = image.width
                                        height = image.height
                                        val historyPath = historyRepository.addFromBitmap(image, "ball-screencap")
                                        r.historyPath = historyPath
                                        r.timings.mark("history_saved")
                                        if (r.action == Protocol.ACTION_PIN || r.action == Protocol.ACTION_ANNOTATE) {
                                            assetStore.copyFileToPin(File(historyPath), "capture").also {
                                                r.timings.mark("asset_copied")
                                            }
                                        } else {
                                            Uri.fromFile(File(historyPath))
                                        }
                                    }
                                },
                                commit = { pinUri ->
                                    if (r.action == Protocol.ACTION_PIN || r.action == Protocol.ACTION_ANNOTATE) {
                                        pinRepository.addPinFromUri(
                                            imageUri = pinUri, sourceType = "ball-screencap",
                                            defaultWidthPx = width, defaultHeightPx = height,
                                            initialBounds = checkNotNull(capturedBounds),
                                        )
                                        r.timings.mark("pin_committed")
                                    }
                                },
                                discard = { pinRepository.discardUnreferencedAsset(it) },
                                receipt = { failure ->
                                    if (failure != null) Log.e(TAG, "Capture save failed", failure)
                                    finish(r, if (failure == null) null else "saving_failed")
                                },
                            )
                        } finally {
                            if (!bufferClosed) buffer.close()
                            bitmap?.let { if (!it.isRecycled) it.recycle() }
                        }
                    }
                    r.worker?.invokeOnCompletion {
                        mainHandler.post { if (r.terminal) release(r) }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot failed synchronously", e)
            abortCapture(r, "screenshot_exception")
        }
    }

    private fun reportSaving(r: Request) {
        runCatching {
            sendBroadcast(Intent(Protocol.SAVING).setPackage(packageName).putExtra(Protocol.ID, r.id))
        }.onFailure { Log.w(TAG, "Saving status delivery failed", it) }
    }

    private fun abortCapture(r: Request, error: String) {
        if (r.terminal) return
        if (r.saving) {
            restoreWindows(r)
            reportSaving(r)
        } else {
            finish(r, error)
        }
    }

    private fun restoreWindows(r: Request) {
        if (r.windowsRestored) return
        val pinsRestored = runCatching { PinOverlayService.setAllPinsVisible(this, true) }
            .onFailure { Log.e(TAG, "Restore pins failed", it) }.isSuccess
        val ballRestored = runCatching {
            sendBroadcast(Intent(Protocol.RESTORE).setPackage(packageName).putExtra(Protocol.ID, r.id))
        }.isSuccess
        r.windowsRestored = pinsRestored && ballRestored
    }

    /** Exactly one terminal receipt; cleanup runs on main, including teardown. */
    private fun finish(r: Request, error: String?) {
        if (r.terminal) return
        r.terminal = true
        r.timings.report(error)
        completedIds.add(r.id)
        if (completedIds.size > 128) completedIds.remove(completedIds.first())
        r.timeout?.let(mainHandler::removeCallbacks)
        r.capture?.let(mainHandler::removeCallbacks)
        if (error != null && !r.saving) r.worker?.cancel()
        restoreWindows(r)
        runCatching { reply(r.id, error, r.historyPath) }
            .onFailure { Log.e(TAG, "Capture receipt failed", it) }
        if (r.worker?.isCompleted != false) release(r)
    }

    private fun release(r: Request) {
        gate.release(r.id)
        activeRequestIds.remove(r.id)
        if (request === r) request = null
    }

    private fun reply(id: String, error: String?, historyPath: String? = null) {
        sendBroadcast(Intent(Protocol.RESULT).setPackage(packageName)
            .putExtra(Protocol.ID, id).putExtra(Protocol.SUCCESS, error == null)
            .putExtra(Protocol.ERROR, error)
            .putExtra(Protocol.HISTORY_PATH, historyPath))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        closed = false
        instance = this
        if (!registered) {
            registerCaptureReceiver(this, captureReceiver,
                IntentFilter(ACTION_CAPTURE_SCREENSHOT).apply {
                    addAction(Protocol.HIDDEN)
                    addAction(Protocol.CANCEL)
                })
            registered = true
        }
    }

    private fun shutdown() {
        closed = true
        instance = null
        request?.let { abortCapture(it, "service_destroyed") }
        if (registered) {
            unregisterReceiver(captureReceiver)
            registered = false
        }
    }
    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }
    override fun onInterrupt() { request?.let { abortCapture(it, "interrupted") } }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    companion object {
        private const val TAG = "PinAccessibilityService"
        const val ACTION_CAPTURE_SCREENSHOT = "com.pinpin.app.a11y.CAPTURE_SCREENSHOT"
        const val EXTRA_RECT_LEFT = "rect_left"
        const val EXTRA_RECT_TOP = "rect_top"
        const val EXTRA_RECT_RIGHT = "rect_right"
        const val EXTRA_RECT_BOTTOM = "rect_bottom"
        @Volatile private var instance: PinAccessibilityService? = null
        private val activeRequestIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun hasPendingRequest(id: String): Boolean = id in activeRequestIds
        fun current(): PinAccessibilityService? = instance
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${PinAccessibilityService::class.java.name}"
            val raw = android.provider.Settings.Secure.getString(context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return raw.split(':').any { it.equals(expected, ignoreCase = true) }
        }
        fun buildSettingsIntent(): Intent =
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
