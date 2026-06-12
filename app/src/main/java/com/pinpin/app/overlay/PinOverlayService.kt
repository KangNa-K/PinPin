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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.pinpin.app.R
import com.pinpin.app.data.PinEntity
import com.pinpin.app.data.PinRepository
import com.pinpin.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

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
    private val hiddenPinIds = mutableSetOf<String>()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        drainPendingSync(this)
        return START_STICKY
    }

    private fun hideAllPins() {
        // Snapshot the current pin entities from the most recent sync
        // — we need them to be able to re-attach the views later. Since
        // [syncPinList] is the only path that adds pins, we can recover
        // the entity from the [PinWindowView] specs already held in
        // [activePins].
        activePins.keys.forEach { id -> hiddenPinIds.add(id) }
        activePins.values.forEach { view ->
            runCatching { windowManager.removeView(view) }
        }
        activePins.clear()
    }

    private fun showAllPins() {
        if (hiddenPinIds.isEmpty()) return
        // Re-fetch the full pin list from the repository (the source of
        // truth) and let [applyPinList] re-attach everything that was
        // hidden. The repository's StateFlow is cheap to read.
        scope.launch {
            pinRepository.pinsState.value
                .filter { it.id in hiddenPinIds }
                .let { toRestore ->
                    hiddenPinIds.clear()
                    applyPinList(pinRepository.pinsState.value)
                }
        }
    }

    override fun onDestroy() {
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
        val toRemove = activePins.keys - newIds
        toRemove.forEach { id ->
            removePassthroughButton(id)
            activePins.remove(id)?.let { runCatching { windowManager.removeView(it) } }
        }
        // Also drop them from the hidden set so a later "show all" does
        // not try to re-attach a pin that no longer exists.
        hiddenPinIds.retainAll(newIds)

        // 2 + 3. Add new views, update existing ones.
        entities.forEach { entity ->
            val existing = activePins[entity.id]
            if (existing == null) {
                if (entity.id in hiddenPinIds) {
                    // Pin was hidden — do not re-attach right now; the
                    // next [setAllPinsVisible] call will pick it up.
                    return@forEach
                }
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
                // Update position / size in case the user dragged/resized
                // before we wrote back to Room (rare race).
                if (existing.currentX != entity.x || existing.currentY != entity.y) {
                    existing.updateLayoutFromExternal(entity.x, entity.y)
                }
                if (existing.currentW != entity.width.toInt() ||
                    existing.currentH != entity.height.toInt()
                ) {
                    existing.updateSizeFromExternal(entity.width.toInt(), entity.height.toInt())
                }
                // Sync the live badge after a renumber. Fires
                // when another pin was deleted or un-numbered
                // and the repository re-allocated zOrders. The
                // on-screen red badge (if shown) and the next
                // minimized-ball badge are both kept in step.
                if (entity.zOrder != 0) {
                    existing.updateZOrder(entity.zOrder)
                }
            }
        }

        // 4. Refresh the notification with the current count.
        startForegroundCompat(activePins.size)
    }

    private fun attachNew(entity: PinEntity) {
        val spec = PinWindowView.PinSpec(
            label = pinLabel(entity),
            imageUri = entity.imageUri,
            sourceType = entity.sourceType,
            initialX = entity.x,
            initialY = entity.y,
            widthPx = entity.width.toInt(),
            heightPx = entity.height.toInt(),
            locked = entity.locked,
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
            id = entity.id,
            spec = spec,
            onPositionChanged = { x, y ->
                scope.launch { pinRepository.updatePosition(entity.id, x, y) }
            },
            onSizeChanged = { w, h ->
                scope.launch { pinRepository.updateSize(entity.id, w, h) }
            },
            onCloseClicked = {
                scope.launch { pinRepository.removePin(entity.id) }
            },
            onActionClicked = { actionId ->
                viewRef[0]?.let { handlePinAction(it, entity, actionId) }
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
        when (actionId) {
            PinWindowView.ACTION_ROTATE -> {
                scope.launch {
                    val next = pinRepository.rotateClockwise(entity.id)
                    view.applyRotation(next)
                }
            }
            PinWindowView.ACTION_FLIP -> {
                scope.launch {
                    val next = pinRepository.toggleFlip(entity.id)
                    view.applyFlip(next)
                }
            }
            PinWindowView.ACTION_SHARE -> sharePinImage(entity)
            PinWindowView.ACTION_SAVE -> savePinImageToGallery(entity)
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
                // Toggle a serial number badge on the image.
                // Toggling also drives the zOrder field in
                // Room so the rest of the pin set renumbers
                // automatically: turning a badge OFF drops
                // the pin out of the numbered sequence
                // (zOrder -> 0, remaining pins shift up),
                // turning it back ON gives it the next
                // available slot.
                if (view.serialBadge != null) {
                    view.removeSerialBadge()
                    scope.launch {
                        pinRepository.setZOrder(entity.id, 0)
                        pinRepository.renormalizeZOrders()
                    }
                } else {
                    scope.launch {
                        // Append to the end of the numbered
                        // sequence; renormalize then takes
                        // care of any collisions in case the
                        // pin already had a stale zOrder.
                        val nextZ = pinRepository.nextAvailableZOrder()
                        pinRepository.setZOrder(entity.id, nextZ)
                        pinRepository.renormalizeZOrders()
                        // showSerialBadge uses the live
                        // zOrder AFTER the renumber so the
                        // badge matches the rest of the
                        // sequence (it may have shifted if
                        // another pin was deleted in the
                        // meantime).
                        val currentZ = pinRepository.findZOrder(entity.id) ?: nextZ
                        view.showSerialBadge(currentZ)
                    }
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
                // Apply AND save to MediaStore (Pictures/pinpin).
                // The composited image is first written to the
                // pin's imageUri (same as Apply), then a copy is
                // pushed to MediaStore so the user can find it
                // in the device gallery.
                val bitmapHolder = arrayOfNulls<android.graphics.Bitmap>(1)
                view.onAnnotationDone = { bmp ->
                    bitmapHolder[0] = bmp
                    saveAnnotatedImage(view, entity, bmp)
                }
                view.exitAnnotationMode(discardChanges = false)
                // After exit, the bitmap was composited; copy to gallery.
                bitmapHolder[0]?.let { bmp ->
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "pinpin-annot-${entity.id.take(8)}.png")
                                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/pinpin")
                            }
                            val dest = contentResolver.insert(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                values,
                            ) ?: return@runCatching
                            contentResolver.openOutputStream(dest)?.use { out ->
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            }
                            android.widget.Toast.makeText(
                                this@PinOverlayService,
                                "已保存到相册",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure { Log.e(TAG, "save annotated to gallery failed", it) }
                    }
                }
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
    private fun sharePinImage(entity: PinEntity) {
        val rawUri = runCatching { Uri.parse(entity.imageUri) }.getOrNull() ?: return
        val shareUri: Uri = if (rawUri.scheme == "file") {
            // Convert file:// to content:// via FileProvider
            val file = File(rawUri.path ?: return)
            runCatching {
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file,
                )
            }.getOrNull() ?: rawUri
        } else {
            rawUri
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(
                Intent.createChooser(intent, getString(R.string.pin_action_share)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { Log.e(TAG, "share failed for ${entity.id}", it) }
    }

    /**
     * Copy the pin's image bytes into MediaStore so it appears in
     * the device gallery. We open an InputStream from the source
     * URI (works for both `content://` and `file://`) and pipe
     * them straight into a new Pictures/pinpin row.
     */
    private fun savePinImageToGallery(entity: PinEntity) {
        scope.launch {
            runCatching {
                val resolver = contentResolver
                val srcRaw = runCatching { Uri.parse(entity.imageUri) }.getOrNull()
                    ?: return@runCatching
                // For file:// URIs, open via File directly; for
                // content:// URIs, use the content resolver.
                val src = if (srcRaw.scheme == "file") {
                    // FileProvider doesn't help here — we need an
                    // InputStream, so open the file directly.
                    val file = File(srcRaw.path ?: return@runCatching)
                    if (!file.exists()) return@runCatching
                    null // signal to use file-based path below
                } else {
                    srcRaw
                }
                val mime = src?.let { resolver.getType(it) } ?: "image/png"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "pinpin-${entity.id.take(8)}.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/pinpin")
                }
                val dest = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return@runCatching
                resolver.openOutputStream(dest)?.use { out ->
                    if (src != null) {
                        resolver.openInputStream(src)?.use { input ->
                            input.copyTo(out)
                        } ?: return@runCatching
                    } else {
                        // file:// path — read directly
                        val file = File(srcRaw.path ?: return@runCatching)
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                }
                android.widget.Toast.makeText(
                    this@PinOverlayService,
                    getString(R.string.pin_action_save_toast),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { Log.e(TAG, "save failed for ${entity.id}", it) }
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
        val density = resources.displayMetrics.density
        val ballSize = (56 * density).toInt() + (20 * density).toInt() // 76dp hit area
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val pinCenterX = entity.x + entity.width.toInt() / 2
        val pinCenterY = entity.y + entity.height.toInt() / 2
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
        // 5. Show the pin's image inside the ball so the user
        //    can see what they're about to restore.
        val uri = runCatching { android.net.Uri.parse(entity.imageUri) }.getOrNull()
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

        runCatching { windowManager.addView(ballView, lp) }
            .onSuccess { minimizedBalls[entity.id] = ballView }
            .onFailure { Log.e(TAG, "minimizePin: addView(ball) failed for ${entity.id}", it) }

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
     * Save the composited (original + annotation) bitmap to the
     * cache directory and update the pin's imageUri to point to
     * the new file. The old file is left in place (it might be
     * referenced by other entities or the history table).
     */
    private fun saveAnnotatedImage(view: PinWindowView, entity: PinEntity, bitmap: Bitmap) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(cacheDir, "screenshots")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "annotated-${entity.id.take(8)}-${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                val newUri = Uri.fromFile(file).toString()
                // Persist the post-annotation URI in its own column.
                // The original [PinEntity.imageUri] is left untouched
                // so the user can always revert to the pre-annotation
                // image; the overlay service prefers annotatedImageUri
                // over imageUri when both are set.
                pinRepository.updateAnnotatedImage(entity.id, newUri)
                // Update the live view on the main thread. The view's
                // [PinWindowView.setAnnotatedImageUri] updates only the
                // display state — `spec.imageUri` is left intact.
                scope.launch(Dispatchers.Main) {
                    view.setAnnotatedImageUri(newUri)
                }
            }.onFailure { Log.e(TAG, "saveAnnotatedImage failed for ${entity.id}", it) }
        }
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
        val closeMargin = dpToPxCompat(8f, density)
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
        // Defer the position computation until the pin view has
        // been laid out. The close button's exact screen position
        // depends on its layout, which only happens after the
        // window manager has measured the view. PinWindowView's
        // `imageCard` has CENTER_HORIZONTAL gravity inside the
        // wrapper, so we must read imageCard.left/right to get
        // the true visible bounds, not the wrapper bounds.
        pinView.post {
            val lp = WindowManager.LayoutParams(
                closeSize,
                closeSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Anchor to the close button's exact screen
                // position. The close button sits inside
                // imageCard with a 8dp topMargin + 8dp marginEnd,
                // so its left edge = imageCard.left + 8dp and
                // its top = imageCard.top + 8dp. We need its
                // SCREEN coordinates, which are the wrapper's
                // window origin (set via layoutParams.x/y) plus
                // the child's position within the wrapper.
                val wrapperLp = pinView.layoutParams as? WindowManager.LayoutParams
                val originX = wrapperLp?.x ?: pinView.currentX
                val originY = wrapperLp?.y ?: pinView.currentY
                val cb = pinView.closeButtonScreenRect()
                x = cb.left
                y = cb.top
            }
            runCatching { windowManager.addView(button, lp) }
                .onSuccess { passthroughButtons[pinId] = button }
                .onFailure { Log.e(TAG, "Failed to add passthrough button for $pinId", it) }
        }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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

        fun start(context: Context) {
            val intent = Intent(context, PinOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
            val intent = Intent(context, PinOverlayService::class.java)
                .setAction(if (visible) ACTION_SHOW_ALL_PINS else ACTION_HIDE_ALL_PINS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
                    imageUri = e.imageUri,
                    sourceType = e.sourceType,
                    zOrder = e.zOrder,
                )
            })

            start(context)
            // We post the actual diff to a service-side queue. The service
            // drains the queue in [onStartCommand] — even though we just
            // called `start` and the service might not be fully created
            // yet, the queue is drained on the next `onStartCommand` call
            // (which `start` triggers synchronously enough on the same
            // thread for the enqueue-then-drain pattern to work).
            synchronized(pendingSync) { pendingSync.add(pins) }
        }

        private val pendingSync: MutableList<List<PinEntity>> = mutableListOf()

        private fun labelFor(e: PinEntity): String = when (e.sourceType) {
            "test" -> "测试图 #${e.zOrder}"
            "photopicker" -> "图钉 #${e.zOrder}"
            "screencapture" -> "截图 #${e.zOrder}"
            "share" -> "分享 #${e.zOrder}"
            else -> "钉图 #${e.zOrder}"
        }

        /**
         * Drain the [pendingSync] queue. Called from `onStartCommand` so
         * that callers do not have to wait for the service to be fully
         * created before issuing a sync.
         */
        internal fun drainPendingSync(service: PinOverlayService) {
            synchronized(pendingSync) {
                while (pendingSync.isNotEmpty()) {
                    val next = pendingSync.removeAt(pendingSync.lastIndex)
                    service.applyPinList(next)
                }
            }
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
