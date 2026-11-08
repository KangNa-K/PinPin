package com.pinpin.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.View
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.pinpin.app.R
import com.pinpin.app.data.PinAssetStore
import com.pinpin.app.data.PinEntity
import com.pinpin.app.data.PinRepository
import com.pinpin.app.data.writeLosslessPng
import com.pinpin.app.data.withPngEncoding
import com.pinpin.app.data.encodeLosslessPng
import com.pinpin.app.a11y.PinAccessibilityService
import com.pinpin.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that owns the [WindowManager] and the set of active
 * [PinWindowView]s.
 *
 * Why a foreground service:
 *  - the user's Q1 answer was "钉图要继续显示 when the app is not in the
 *    foreground", so we need to outlive the activity;
 *  - on Android 14+, drawing a [WindowManager.LayoutParams] with
 *    `type = TYPE_APPLICATION_OVERLAY` from a background app is rejected by
 *    the platform unless the app holds an active foreground service of a
 *    matching type — we declare `specialUse` with the subtype
 *    `floating_pinned_image_overlay` (see `AndroidManifest.xml`).
 *
 * Lifecycle:
 *  - The service is started on demand by [syncPinList] whenever the
 *    repository detects a list change. [syncPinList] is the public entry
 *    point — the [PinRepository] coroutine in `Application.onCreate`
 *    observes the Room flow and calls it on every emission.
 *  - [syncPinList] diffs the new list against the active views and adds,
 *    updates, or removes the corresponding [PinWindowView]s. The diff
 *    strategy:
 *      1. Remove views whose id is no longer in the new list.
 *      2. Add views for ids that are in the new list but not in the
 *         active set.
 *      3. For views that already exist, update position / size / lock
 *         state if those fields differ.
 *      4. Bring-to-front is a `bringToFront(id)` companion call — handled
 *         by re-issuing the same view with the new z-order, which we
 *         implement by removing and re-adding (cheap).
 *
 * On service destruction (low memory, explicit stop, notification "Stop"
 * action), all active views are removed from the window manager. The pin
 * list in Room is not touched here — it is the source of truth.
 */
@AndroidEntryPoint
class PinOverlayService : Service() {

    @Inject lateinit var pinRepository: PinRepository
    @Inject lateinit var assetStore: PinAssetStore

    private val windowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val activePins = ConcurrentHashMap<String, PinWindowView>()

    /**
     * Floating "exit passthrough" buttons, one per pin that is in
     * passthrough mode. When passthrough is on, the main pin window
     * has FLAG_NOT_TOUCHABLE so touches pass through to the
     * underlying app. The user needs a separate touchable overlay
     * window to disable passthrough — these buttons serve that
     * purpose. They are independent WindowManager windows, not
     * children of the pin view, so they remain clickable even when
     * the main window has FLAG_NOT_TOUCHABLE.
     */
    private val passthroughButtons = ConcurrentHashMap<String, ImageView>()

    /**
     * Floating balls that stand in for "minimized" pins. When
     * the user taps the new "收起" toolbar action on a pin,
     * [minimizePin] removes the [PinWindowView] from the
     * WindowManager and replaces it with a [BallView] that
     * shows the pin's image. Tapping the ball restores the
     * full pin via [restorePin]. The ball is a top-level
     * WindowManager window (not a child of the pin view) so
     * the user can keep dragging / re-anchoring it on the
     * screen edge while the underlying pin is detached.
     */
    private val minimizedBalls = ConcurrentHashMap<String, android.view.View>()

    /**
     * Pin ids whose views are currently detached because of a temporary
     * hide (e.g. during a MediaProjection capture). The Room source-of-truth
     * still lists these pins; we just do not render them. On the next
     * [setAllPinsVisible] call with `true`, we re-attach the views.
     */
    private var allPinsHidden = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() +
        CoroutineExceptionHandler { _, failure ->
            Log.e(TAG, "Overlay operation failed", failure)
            mainHandler.post { showError("操作未完成，请重试") }
        })

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        pinRepository.startOverlaySync()
    }

    /**
     * Light / dark mode can change at runtime — the user toggles
     * it in Settings, the system auto-switches based on time of
     * day, or a foldable flips between book / tablet modes. The
     * foreground service is not destroyed across these changes
     * (no Activity restart), but every [PinWindowView]'s toolbar
     * pill, close button, and icon tints need a refresh.
     *
     * We re-theme every active pin view and any visible
     * passthrough exit button. The action id we receive here is
     * the old UI mode; the new one is what [PinTheme.of] reads
     * via [Context.getResources]. The system delivers
     * [Configuration.UI_MODE_NIGHT_MASK] updates with the new
     * mode already applied to the resources we read.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemeToAllPins()
    }

    /**
     * Walk every active pin view, the visible passthrough exit
     * buttons, and any other themed surface, and re-apply the
     * current system theme. Cheap (no view rebuild, just
     * in-place tint + drawable swap), safe to call from
     * anywhere on the main thread.
     */
    private fun applyThemeToAllPins() {
        activePins.values.forEach { view -> view.applyTheme() }
        // The passthrough exit button is a separate top-level
        // window with its own drawable + tint; re-apply here so
        // it stays in sync with the main pin's toolbar pill.
        passthroughButtons.values.forEach { button ->
            val theme = PinTheme.of(this)
            (button.background as? android.graphics.drawable.GradientDrawable)?.apply {
                setColor(theme.surfaceFill)
                setStroke(
                    (resources.displayMetrics.density * 1).toInt(),
                    theme.surfaceStroke,
                )
            }
            button.imageTintList = android.content.res.ColorStateList.valueOf(theme.iconColor)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(activePins.size)
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE_ALL_PINS -> {
                hideAllPins()
                return START_STICKY
            }
            ACTION_SHOW_ALL_PINS -> {
                showAllPins()
                return START_STICKY
            }
        }
        // Drain any pin-list snapshots that were enqueued before the
        // service was created. Without this, the first few inserts after
        // a cold start are dropped on the floor — the Compose list updates
        // (because [PinWindowManager.applySnapshot] runs in the caller's
        // process), but no view is ever added to the WindowManager.
        applyPinList(pendingSync.getAndSet(null) ?: pinRepository.pinsState.value)
        return START_STICKY
    }

    private fun hideAllPins() {
        allPinsHidden = true
        ownedWindows().forEach { it.visibility = View.INVISIBLE }
    }

    private fun showAllPins() {
        allPinsHidden = false
        ownedWindows().forEach { it.visibility = View.VISIBLE }
    }

    private fun ownedWindows(): List<View> =
        activePins.values.toList() + minimizedBalls.values + passthroughButtons.values

    override fun onDestroy() {
        if (instance === this) instance = null
        passthroughButtons.values.forEach { runCatching { windowManager.removeView(it) } }
        passthroughButtons.clear()
        minimizedBalls.values.forEach { runCatching { windowManager.removeView(it) } }
        minimizedBalls.clear()
        activePins.values.forEach { runCatching { windowManager.removeView(it) } }
        activePins.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // region Pin list sync

    private fun applyPinList(entities: List<PinEntity>) {
        // 1. Remove views whose id is no longer in the list.
        val newIds = entities.map { it.id }.toSet()
        val toRemove = (activePins.keys + minimizedBalls.keys + passthroughButtons.keys) - newIds
        toRemove.forEach { id ->
            removePassthroughButton(id)
            activePins.remove(id)?.let { runCatching { windowManager.removeView(it) } }
            minimizedBalls.remove(id)?.let { runCatching { windowManager.removeView(it) } }
        }

        // 2 + 3. Add new views, update existing ones.
        entities.forEach { entity ->
            val existing = activePins[entity.id]
            if (existing == null) {
                if (minimizedBalls.containsKey(entity.id)) {
                    // Pin was collapsed to a floating ball by the
                    // user. The [PinWindowView] was deliberately
                    // removed from [activePins] by [minimizePin] —
                    // re-attaching it here would spawn a SECOND
                    // window for the same image, leaving the ball
                    // AND the full pin on screen at the same time.
                    // The floating ball is the user's chosen
                    // surface for this pin; a new
                    // [restorePin] call is the only path that
                    // should bring it back.
                    return@forEach
                }
                attachNew(entity)
            } else {
                if (AnnotationSaveState.isSaving(entity.id)) {
                    existing.setSerialVisible(entity.serialVisible, entity.zOrder)
                    existing.spec.zOrder = entity.zOrder
                    existing.updateZOrder(entity.zOrder)
                } else {
                    existing.applySnapshot(entity)
                }
            }
            (minimizedBalls[entity.id] as? com.pinpin.app.ball.BallView)?.zOrderBadge =
                entity.zOrder.takeIf { entity.serialVisible }
        }

        // 4. Refresh the notification with the current count.
        startForegroundCompat(activePins.size)
    }

    private fun attachNew(entity: PinEntity) {
        val spec = PinWindowView.PinSpec(
            label = pinLabel(entity),
            imageUri = entity.imageUri,
            annotatedImageUri = entity.annotatedImageUri,
            sourceType = entity.sourceType,
            initialX = entity.x,
            initialY = entity.y,
            widthPx = entity.width.toInt(),
            heightPx = entity.height.toInt(),
            locked = entity.locked,
            serialVisible = entity.serialVisible,
            rotation = entity.rotation,
            flipped = entity.flipped,
            // Surface the pin's display order so the "收起"
            // toolbar action can show a numeric badge — the
            // user can tell at a glance which pin the
            // minimize button belongs to, the same number
            // they will then see on the floating ball that
            // takes its place.
            zOrder = entity.zOrder,
        )
        // [PinWindowView] references itself in the onActionClicked
        // callback (used to apply rotation/flip/passthrough on the
        // live view). Kotlin disallows a `val` forward reference,
        // so we use a single-element array as a mutable holder.
        val viewRef = arrayOfNulls<PinWindowView>(1)
        val view = PinWindowView(
            context = this,
            pinId = entity.id,
            spec = spec,
            onPositionChanged = { x, y ->
                val current = viewRef[0]
                val width = current?.currentW?.toFloat() ?: entity.width
                val height = current?.currentH?.toFloat() ?: entity.height
                if (current != null) scope.launch {
                    try {
                        pinRepository.updateGeometry(entity.id, x, y, width, height)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        current.geometryCommitFailed()
                        pinRepository.pinsState.value.firstOrNull { it.id == entity.id }?.let(current::applySnapshot)
                        Log.e(TAG, "Could not save pin geometry", failure)
                        showError("位置保存失败，请重试")
                    }
                }
            },
            onSizeChanged = { _, _ -> },
            onCloseClicked = {
                scope.launch { pinRepository.removePin(entity.id) }
            },
            onActionClicked = { actionId ->
                viewRef[0]?.let { handlePinAction(it, entity, actionId) }
            },
            onLayoutChanged = {
                viewRef[0]?.postOnAnimation {
                    repositionPassthroughButton(viewRef[0]!!, entity.id)
                }
            },
        )
        viewRef[0] = view
        // Hook the passthrough state change so the service can show
        // or hide the floating exit button in its own WindowManager
        // window. The button is a top-level window, not a child of
        // the pin view, because the main window has FLAG_NOT_TOUCHABLE
        // while passthrough is on — children inside that window would
        // also be untouchable.
        view.onPassthroughChanged = { enabled ->
            if (enabled) {
                showPassthroughButton(view, entity.id)
            } else {
                removePassthroughButton(entity.id)
            }
        }
        // [PinWindowView.init] already builds a WindowManager.LayoutParams
        // with the correct size (imageW × wrapperH, where wrapperH
        // includes the gap + toolbar below the image) and sets it on
        // itself. We just need to copy its `x/y/gravity` and pass it
        // back into addView. Crucially, do NOT use
        // `spec.widthPx × spec.heightPx` here — that would replace
        // the view's LayoutParams with a height that only covers the
        // image, and the toolbar would render outside the window
        // and be invisible (which is why screenshots appeared
        // unpinned even though the view was added).
        val lp = (view.layoutParams as? WindowManager.LayoutParams
            ?: WindowManager.LayoutParams(
                spec.widthPx,
                spec.heightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )).apply {
            gravity = Gravity.TOP or Gravity.START
            x = spec.initialX
            y = spec.initialY
        }
        view.visibility = if (allPinsHidden) View.INVISIBLE else View.VISIBLE
        runCatching { windowManager.addView(view, lp) }
            .onSuccess {
                activePins[entity.id] = view
                // Auto-show the toolbar for newly created pins. The
                // user just performed an action that produced this
                // pin (screenshot / gallery pick) and expects to see
                // the rotate / share / save controls without an
                // extra tap. Touching the image body later still
                // toggles the toolbar.
                view.showToolbar(true)
            }
            .onFailure { Log.e(TAG, "addView failed for ${entity.id}", it) }
    }

    /**
     * Dispatch a toolbar button press. Each id comes from
     * [PinWindowView.ACTION_*]. The function returns immediately;
     * the actual effect is either:
     *  - a synchronous mutation on the live view (rotation, flip,
     *    passthrough, lock) — and a coroutine to persist it
     *  - a system intent (share, save)
     *  - a TODO placeholder (annotate, arrow, serial) — these are
     *    pending a dedicated editor overlay.
     */
    private fun handlePinAction(
        view: PinWindowView,
        entity: PinEntity,
        actionId: String,
    ) {
        if ((view.annotationSaving || AnnotationSaveState.isSaving(entity.id)) && actionId in setOf(
                PinWindowView.ACTION_SHARE, PinWindowView.ACTION_SAVE,
                PinWindowView.ACTION_ANNOTATE, PinWindowView.ACTION_ARROW,
                PinWindowView.ACTION_MINIMIZE, PinWindowView.ACTION_SAVE_ANNOTATE,
                PinWindowView.ACTION_APPLY_ANNOTATE,
            )
        ) {
            showError("正在保存标注，请稍候")
            return
        }
        when (actionId) {
            PinWindowView.ACTION_ROTATE -> {
                scope.launch {
                    val rotated = pinRepository.rotateClockwise(entity.id) ?: return@launch
                    if (activePins[entity.id] === view) view.applySnapshot(rotated)
                }
            }
            PinWindowView.ACTION_FLIP -> {
                scope.launch {
                    val next = pinRepository.toggleFlip(entity.id)
                    view.applyFlip(next)
                }
            }
            PinWindowView.ACTION_SHARE -> sharePinImage(view, entity)
            PinWindowView.ACTION_SAVE -> savePinImageToGallery(view, entity)
            PinWindowView.ACTION_LOCK -> {
                // Read the current lock state from the live view's
                // spec, NOT from the cached [entity]. [entity] is
                // captured at attach time and never mutated when the
                // toolbar toggles the lock; reading it here would
                // always yield the initial value, so the first
                // toggle would lock but every subsequent toggle
                // would re-lock instead of unlocking. [view.spec]
                // is updated synchronously by [setLockedVisual]
                // and is the source of truth at runtime.
                val next = !view.spec.locked
                scope.launch { pinRepository.setLocked(entity.id, next) }
                view.setLockedVisual(next)
            }
            PinWindowView.ACTION_PASSTHROUGH -> {
                // Toggling [setPassthrough] on the view flips the
                // state and triggers the [onPassthroughChanged]
                // callback, which shows or hides the floating
                // "exit passthrough" button in its own WindowManager
                // window.
                view.setPassthrough(!view.isPassthrough())
            }
            PinWindowView.ACTION_MINIMIZE -> {
                // "Minimize to ball" — tear down the pin window
                // and stand up a floating ball in its place. The
                // user can re-attach the full pin by tapping the
                // ball. State (position / size / rotation) is
                // already persisted in Room by the pin view's
                // own callbacks, so [restorePin] simply re-issues
                // the same entity through [attachNew].
                minimizePin(entity)
            }
            PinWindowView.ACTION_SERIAL -> {
                // Serial visibility is independent from zOrder. Toggling the
                // badge must not renumber or reorder any other Pin.
                val nextVisible = view.serialBadge == null
                view.setSerialVisible(nextVisible, view.spec.zOrder)
                scope.launch {
                    pinRepository.setSerialVisible(entity.id, nextVisible)
                }
            }
            PinWindowView.ACTION_ANNOTATE -> {
                if (view.isAnnotationMode()) {
                    view.exitAnnotationMode()
                } else {
                    view.onAnnotationDone = { bmp ->
                        saveAnnotatedImage(view, entity, bmp)
                    }
                    view.enterAnnotationMode(DrawingOverlayView.Mode.PEN)
                }
            }
            PinWindowView.ACTION_ARROW -> {
                if (view.isAnnotationMode()) {
                    view.exitAnnotationMode()
                } else {
                    view.onAnnotationDone = { bmp ->
                        saveAnnotatedImage(view, entity, bmp)
                    }
                    view.enterAnnotationMode(DrawingOverlayView.Mode.ARROW)
                }
            }
            PinWindowView.ACTION_CANCEL_ANNOTATE -> {
                // Cancel without saving: just exit and discard any
                // pending strokes. Triggered by an explicit "×"
                // button on the annotation toolbar.
                view.exitAnnotationMode(discardChanges = true)
            }
            PinWindowView.ACTION_APPLY_ANNOTATE -> {
                // Apply: composite the annotation onto the pin
                // image and restore the original size / position.
                // No gallery write — that's what "保存" is for.
                view.exitAnnotationMode(discardChanges = false)
            }
            PinWindowView.ACTION_SAVE_ANNOTATE -> {
                // Composite once, then let saveAnnotatedImage write the
                // internal file and copy that file to MediaStore in the
                // same IO operation. Reusing the Bitmap from two
                // independent coroutines used to race with bitmap.recycle().
                val rotation = view.spec.rotation
                val flipped = view.spec.flipped
                view.onAnnotationDone = { bmp ->
                    saveAnnotatedImage(view, entity, bmp, saveToGallery = true, rotation = rotation, flipped = flipped)
                }
                view.exitAnnotationMode(discardChanges = false)
            }
            else -> Log.w(TAG, "unknown action $actionId for pin[${entity.id}]")
        }
    }

    /**
     * Launch the system share sheet with the pin's image URI.
     *
     * For `content://` URIs (from PhotoPicker / ACTION_SEND), the
     * URI is shared directly with FLAG_GRANT_READ_URI_PERMISSION.
     *
     * For `file://` URIs (from screenshots), we use [FileProvider]
     * to convert them to `content://` URIs. Android 7.0+ forbids
     * sharing `file://` URIs with other apps (FileUriExposedException),
     * and FLAG_GRANT_READ_URI_PERMISSION only works with content URIs.
     */
    private fun sharePinImage(view: PinWindowView, entity: PinEntity) {
        val snapshot = view.exportSnapshot() ?: return showError("图片载入后再分享")
        scope.launch {
            val file = withContext(NonCancellable + Dispatchers.IO) {
                val directory = File(cacheDir.canonicalFile, "exports").apply { mkdirs() }
                val now = System.currentTimeMillis()
                directory.listFiles()?.filter {
                    it.name.startsWith("pin-") && it.extension == "png" &&
                        now - it.lastModified() > 24 * 60 * 60 * 1000L
                }?.forEach { it.delete() }
                val target = File(directory, "pin-${java.util.UUID.randomUUID()}.png")
                ImageRenderWork.run {
                    val bitmap = snapshot.render(contentResolver)
                    try {
                        writeLosslessPng(bitmap, target)
                        target
                    } catch (failure: Throwable) {
                        target.delete()
                        throw failure
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val uri = FileProvider.getUriForFile(this@PinOverlayService, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("PinPin", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.pin_action_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Copy the pin's image bytes into MediaStore so it appears in
     * the device gallery. We open an InputStream from the source
     * URI (works for both `content://` and `file://`) and pipe
     * them straight into a new Pictures/pinpin row.
     */
    private fun savePinImageToGallery(view: PinWindowView, entity: PinEntity) {
        val snapshot = view.exportSnapshot() ?: return showError("图片载入后再保存")
        scope.launch {
            withContext(NonCancellable) {
                ImageRenderWork.run {
                    val bitmap = snapshot.render(contentResolver)
                    try {
                        withPngEncoding {
                            writeGalleryImage(entity.id) { output ->
                                encodeLosslessPng(bitmap, output)
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            showError(getString(R.string.pin_action_save_toast))
        }
    }

    // region Minimize / restore

    /**
     * Tear down the pin's full window and replace it with a
     * floating ball that shows the pin's image. The ball
     * appears at the pin's last known position so the user
     * sees a continuous "shrinking" gesture.
     *
     * The pin's state (x, y, w, h, rotation, locked, etc.)
     * is already persisted in Room by [PinWindowView]'s
     * onPositionChanged / onSizeChanged callbacks, so this
     * method only manipulates the in-memory [activePins] /
     * [minimizedBalls] maps and the WindowManager. On restore
     * the pin is rebuilt from the same Room row, so any state
     * the user changed before minimizing (rotation, lock,
     * passthrough, etc.) carries over.
     */
    private fun minimizePin(entity: PinEntity) {
        val view = activePins[entity.id] ?: return
        if (minimizedBalls.containsKey(entity.id)) return

        // 1. Remove the PinWindowView from the WindowManager
        //    and from [activePins]. The view object is dropped
        //    here; restoring will rebuild it from Room.
        runCatching { windowManager.removeView(view) }
        activePins.remove(entity.id)
        // 2. Also clear any in-flight "exit passthrough" button
        //    — restoring will recreate it if needed.
        removePassthroughButton(entity.id)

        // 3. Build the floating ball that stands in for the
        //    pin. We use the same BallView class the persistent
        //    selection ball uses, but the layout parameters are
        //    tweaked: size is the standard 76dp ball, position
        //    is centered on the pin's last known (x, y, w, h)
        //    so the gesture feels like "shrink to a ball" rather
        //    than "teleport to a corner".
        val ballView = com.pinpin.app.ball.BallView(this)
        val ballSize = ballView.minimumWidth
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val pinCenterX = view.currentX + view.currentW / 2
        val pinCenterY = view.currentY + view.currentH / 2
        val initialX = (pinCenterX - ballSize / 2)
            .coerceIn(0, (metrics.widthPixels - ballSize).coerceAtLeast(0))
        val initialY = (pinCenterY - ballSize / 2)
            .coerceIn(0, (metrics.heightPixels - ballSize).coerceAtLeast(0))

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
            x = initialX
            y = initialY
        }
        ballView.attachedParams = lp
        ballView.attachedDisplayWidth = metrics.widthPixels
        ballView.attachedDisplayHeight = metrics.heightPixels
        // 4. The ball is "tappable" — tapping it restores the
        //    pin instead of just snapping back to an edge.
        ballView.onTap = { restorePin(entity.id) }
        ballView.onSwipeDown = {
            PinAccessibilityService.current()?.showNotificationShade() == true
        }
        // 5. Show the pin's image inside the ball so the user
        //    can see what they're about to restore.
        val uri = runCatching {
            effectiveImageUri(view, entity).toUri()
        }.getOrNull()
        if (uri != null) {
            runCatching { ballView.setBallImageUri(uri) }
                .onFailure { Log.w(TAG, "minimizePin: setBallImageUri failed for ${entity.id}", it) }
        }
        // 6. Forward the serial badge so the user can still see
        //    the pin's number on the minimized ball (matches the
        //    red corner badge on the original pin). If the user
        //    never enabled the badge for this pin, the ball just
        //    shows the bare avatar.
        val badgeNumber = view.serialBadgeNumber()
        ballView.zOrderBadge = badgeNumber

        ballView.visibility = if (allPinsHidden) View.INVISIBLE else View.VISIBLE
        runCatching { windowManager.addView(ballView, lp) }
            .onSuccess { minimizedBalls[entity.id] = ballView }
            .onFailure {
                Log.e(TAG, "minimizePin: addView(ball) failed for ${entity.id}", it)
                attachNew(pinRepository.pinsState.value.firstOrNull { pin -> pin.id == entity.id } ?: entity)
            }

        // The notification count no longer includes this pin
        // (it's not currently a full window), but the user
        // might still want a hint that the ball represents a
        // pin. We refresh the count to activePins.size only.
        startForegroundCompat(activePins.size)
    }

    /**
     * Tap-on-ball handler. Tears down the ball for [pinId] and
     * re-issues the same Room row through [attachNew], which
     * rebuilds the full [PinWindowView] at the persisted
     * position / size. Any state the user set before
     * minimizing (rotation, lock, etc.) is preserved.
     */
    private fun restorePin(pinId: String) {
        val ball = minimizedBalls.remove(pinId) ?: return
        runCatching { windowManager.removeView(ball) }

        val entity = pinRepository.pinsState.value.firstOrNull { it.id == pinId }
        if (entity == null) {
            // The pin was deleted while minimized — nothing to
            // restore. Drop the ball view we already removed.
            Log.w(TAG, "restorePin: $pinId not found in repository")
            return
        }
        // Re-issue the entity through the standard attach path.
        // [applyPinList] would no-op here because the pin is
        // already in [activePins] in the repository's view,
        // but we already removed it from in-memory [activePins]
        // when we minimized — so we go straight to [attachNew]
        // to recreate the PinWindowView.
        attachNew(entity)
    }

    // endregion

    private fun pinLabel(entity: PinEntity): String {
        return when (entity.sourceType) {
            "test" -> "测试图 #${entity.zOrder}"
            "photopicker" -> "图钉 #${entity.zOrder}"
            "screencapture" -> "截图 #${entity.zOrder}"
            "share" -> "分享 #${entity.zOrder}"
            else -> "钉图 #${entity.zOrder}"
        }
    }

    /**
     * Save the composited annotation and optionally export the saved file
     * to MediaStore. The Bitmap is owned by this function and is recycled
     * only after every consumer has finished with it.
     */
    private suspend fun saveAnnotatedImage(
        view: PinWindowView,
        entity: PinEntity,
        bitmap: Bitmap,
        saveToGallery: Boolean = false,
        rotation: Int = view.spec.rotation,
        flipped: Boolean = view.spec.flipped,
    ) {
        view.annotationSaving = true
        withContext(NonCancellable + Dispatchers.IO) {
            var gallerySaved = false
            var savedUri: Uri? = null
            try {
                if (pinRepository.findZOrder(entity.id) == null) return@withContext
                savedUri = assetStore.saveBitmap(bitmap, "annotated")
                val newUri = savedUri.toString()
                if (!pinRepository.updateAnnotatedImage(entity.id, newUri)) return@withContext
                if (saveToGallery) {
                    if (rotation % 360 == 0 && !flipped) {
                        writeGalleryImage(entity.id) { out ->
                            File(requireNotNull(savedUri.path)).inputStream().use { it.copyTo(out) }
                        }
                    } else {
                        val exported = PinImageSnapshot(bitmap, rotation, flipped).render()
                        try {
                            withPngEncoding {
                                writeGalleryImage(entity.id) { out -> encodeLosslessPng(exported, out) }
                            }
                        } finally { exported.recycle() }
                    }
                    gallerySaved = true
                }

                // Persist only after the internal file has been written.
                withContext(Dispatchers.Main) {
                    if (activePins[entity.id] === view) view.setAnnotatedImageUri(newUri)
                    if (gallerySaved) {
                        android.widget.Toast.makeText(
                            this@PinOverlayService,
                            getString(R.string.pin_action_save_toast),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                Log.e(TAG, "saveAnnotatedImage failed for ${entity.id}", t)
                withContext(Dispatchers.Main) { showError("标注或相册保存未完成，请检查图片和存储空间") }
            } finally {
                try {
                    savedUri?.let { pinRepository.discardUnreferencedAsset(it) }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    withContext(Dispatchers.Main) { view.annotationSaving = false }
                }
            }
        }
    }

    private fun copyFileToGallery(file: File, pinId: String): Boolean {
        return try {
            writeGalleryImage(pinId) { output -> file.inputStream().use { it.copyTo(output) } }
            true
        } catch (failure: Exception) {
            Log.e(TAG, "Gallery export failed", failure)
            false
        }
    }

    private fun writeGalleryImage(pinId: String, write: (java.io.OutputStream) -> Unit) {
        val resolver = contentResolver
        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                "pinpin-annot-${pinId.take(8)}.png",
            )
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/pinpin",
            )
        }
        val dest = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("Could not create gallery entry")

        try {
            resolver.openOutputStream(dest)?.use { output ->
                write(output)
            } ?: error("Could not open MediaStore output stream")
            resolver.update(dest, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } catch (t: Throwable) {
            resolver.delete(dest, null, null)
            throw t
        }
    }

    private fun effectiveImageUri(view: PinWindowView, entity: PinEntity): String =
        view.spec.annotatedImageUri ?: view.spec.imageUri ?: entity.imageUri

    private fun showError(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    // region Passthrough floating button

    /**
     * Show a small floating button that allows the user to disable
     * passthrough mode. When passthrough is on, the main pin window
     * has FLAG_NOT_TOUCHABLE, so the toolbar is not interactive.
     * This separate overlay window provides the only way to exit
     * passthrough.
     */
    private fun showPassthroughButton(pinView: PinWindowView, pinId: String) {
        if (passthroughButtons.containsKey(pinId)) return
        val density = resources.displayMetrics.density
        val closeSize = dpToPxCompat(40f, density)
        // Read the current theme tokens so the exit-passthrough
        // button visually matches the main toolbar pill. The
        // [applyThemeToAllPins] re-apply loop re-tints this
        // button when the system theme changes, so it stays
        // locked in step with the rest of the pin UI.
        val theme = PinTheme.of(this)
        val button = ImageView(this).apply {
            setImageResource(R.drawable.ic_action_passthrough)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = android.content.res.ColorStateList.valueOf(theme.iconColor)
            contentDescription = getString(R.string.pin_action_passthrough)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(theme.surfaceFill)
                setStroke(dpToPxCompat(1f, density), theme.surfaceStroke)
            }
            setOnClickListener {
                val view = activePins[pinId] ?: return@setOnClickListener
                view.setPassthrough(false)
            }
        }
        // Wait for layout before anchoring the separate exit control
        // to the image's passthrough button, including wrapped rows.
        pinView.post {
            pinView.postOnAnimation {
                if (activePins[pinId] === pinView &&
                    pinView.isPassthrough() &&
                    !passthroughButtons.containsKey(pinId)
                ) {
                    val lp = WindowManager.LayoutParams(
                        closeSize,
                        closeSize,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        setFitInsetsTypes(0)
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        val cb = pinView.passthroughButtonScreenRect()
                        x = cb.left
                        y = cb.top
                    }
                    button.visibility = if (allPinsHidden) View.INVISIBLE else View.VISIBLE
                    runCatching { windowManager.addView(button, lp) }
                        .onSuccess {
                            passthroughButtons[pinId] = button
                            button.post { repositionPassthroughButton(pinView, pinId) }
                        }
                        .onFailure { Log.e(TAG, "Failed to add passthrough button for $pinId", it) }
                }
            }
        }
    }

    /**
     * Keep the top-level exit button anchored to the action button inside
     * the pin window. The two windows have independent WindowManager
     * layout params, so moving or resizing the pin cannot move this one
     * automatically.
     */
    private fun repositionPassthroughButton(pinView: PinWindowView, pinId: String) {
        val button = passthroughButtons[pinId] ?: return
        if (activePins[pinId] !== pinView || !pinView.isPassthrough()) return
        val lp = button.layoutParams as? WindowManager.LayoutParams ?: return
        val rect = pinView.passthroughButtonScreenRect()
        val actual = IntArray(2)
        button.getLocationOnScreen(actual)
        val dx = rect.left - actual[0]
        val dy = rect.top - actual[1]
        if (dx == 0 && dy == 0) return
        lp.x += dx
        lp.y += dy
        runCatching { windowManager.updateViewLayout(button, lp) }
            .onFailure { Log.w(TAG, "Failed to reposition passthrough button for $pinId", it) }
    }

    private fun removePassthroughButton(pinId: String) {
        passthroughButtons.remove(pinId)?.let { button ->
            runCatching { windowManager.removeView(button) }
        }
    }

    private fun dpToPxCompat(dp: Float, density: Float): Int =
        (dp * density).toInt()

    // endregion

    // endregion

    // region Notification

    private fun startForegroundCompat(count: Int) {
        val text = getString(R.string.overlay_notification_text, count)
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                Log.d(TAG, "startForeground: type=SPECIAL_USE")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "startForeground: legacy (pre-Android 14)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPi = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, PinOverlayService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.overlay_action_stop),
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
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    // endregion

    companion object {
        private const val TAG = "PinOverlayService"
        private const val CHANNEL_ID = "pinpin_overlay"
        private const val NOTIFICATION_ID = 0x50494E  // "PIN"
        private const val ACTION_STOP = "com.pinpin.app.overlay.STOP"
        private const val ACTION_HIDE_ALL_PINS = "com.pinpin.app.overlay.HIDE_ALL_PINS"
        private const val ACTION_SHOW_ALL_PINS = "com.pinpin.app.overlay.SHOW_ALL_PINS"
        @Volatile private var instance: PinOverlayService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun start(context: Context) {
            if (!android.provider.Settings.canDrawOverlays(context)) return
            val intent = Intent(context, PinOverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PinOverlayService::class.java))
        }

        /**
         * Toggle the visibility of all floating pin views without
         * removing them from the data model. Used during a screen
         * capture so that the screenshot does not include the pins
         * themselves.
         *
         * Implementation: posts an action to the service via
         * [start] (so we can call this from any thread / non-Activity
         * context). The service handles it in [onStartCommand].
         *
         * Important: we MUST use [Context.startForegroundService] (not
         * plain [Context.startService]) on Android 12+ — a regular
         * `startService` from a background process is silently dropped,
         * and our `setAllPinsVisible` is called from
         * [com.pinpin.app.ball.BallService] when the a11y screenshot
         * pipeline runs, which is not an Activity scope.
         */
        fun setAllPinsVisible(context: Context, visible: Boolean) {
            mainHandler.post {
                instance?.let { if (visible) it.showAllPins() else it.hideAllPins() }
            }
        }

        /**
         * Apply the latest list of pins to the overlay layer. Called by
         * the [com.pinpin.app.data.PinRepository] on every Room
         * invalidation. The service is started on demand if it is not
         * already running.
         */
        fun syncPinList(context: Context, pins: List<PinEntity>) {
            // Update the in-memory projection for the Compose UI.
            PinWindowManager.applySnapshot(pins.map { e ->
                PinWindowManager.PinState(
                    id = e.id,
                    label = labelFor(e),
                    width = e.width,
                    height = e.height,
                    x = e.x,
                    y = e.y,
                    locked = e.locked,
                    imageUri = e.annotatedImageUri ?: e.imageUri,
                    sourceType = e.sourceType,
                    zOrder = e.zOrder,
                )
            })

            // Overlay rendering is state synchronization, not an event log:
            // only the newest snapshot matters. Store it BEFORE starting or
            // re-starting the service so onStartCommand cannot drain an empty
            // container and miss this update. A newer Room invalidation simply
            // replaces an obsolete intermediate snapshot.
            pendingSync.set(pins)
            mainHandler.post { instance?.let(::drainPendingSync) }
        }

        private val pendingSync = AtomicReference<List<PinEntity>?>(null)

        private fun labelFor(e: PinEntity): String = when (e.sourceType) {
            "test" -> "测试图 #${e.zOrder}"
            "photopicker" -> "图钉 #${e.zOrder}"
            "screencapture" -> "截图 #${e.zOrder}"
            "share" -> "分享 #${e.zOrder}"
            else -> "钉图 #${e.zOrder}"
        }

        /**
         * Drain the newest pending snapshot. Called from `onStartCommand` so
         * callers do not have to wait for the service to be fully created
         * before issuing a sync. Intermediate snapshots are intentionally
         * discarded: the overlay should converge on the latest Room state,
         * never replay an old state after a newer one.
         */
        internal fun drainPendingSync(service: PinOverlayService) {
            pendingSync.getAndSet(null)?.let(service::applyPinList)
        }
    }
}

/**
 * Service-side shim to the [com.pinpin.app.data.PinRepository] mutators.
 *
 * The repository is a Hilt singleton; the service has a reference to it
 * via [dagger.hilt.android.AndroidEntryPoint] and routes writes through
 * `pinRepository.updatePosition(...)` etc. directly. This object is
 * kept as a placeholder in case we ever need to batch writes from a
 * non-Hilt context (e.g. a system-level gesture detector).
 */
@Suppress("unused")
private object PinRepositoryWrite {
    fun updatePosition(id: String, x: Int, y: Int) {
        // Reserved: see PinOverlayService.pinRepository for the active path.
    }
    fun updateSize(id: String, width: Float, height: Float) {}
    fun removePin(id: String) {}
}

/** Touch slop for differentiating tap from drag (in raw pixels). */
internal val PIN_TOUCH_SLOP_PX: Int = 12
