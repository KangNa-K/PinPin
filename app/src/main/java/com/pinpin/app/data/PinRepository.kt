package com.pinpin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.pinpin.app.capture.CaptureBounds
import com.pinpin.app.overlay.PinOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the pin list.
 *
 *  - Reads flow directly from [PinDao.observeAll] so any UI / service layer
 *    that collects it sees the same view of the data.
 *  - All mutators (`addPin`, `removePin`, `updatePosition`, ...) write
 *    through the DAO; the resulting Room invalidation pushes a new value
 *    into the flow, and downstream observers (the overlay service, the
 *    Compose home screen) react automatically.
 *  - The overlay service is kept in sync by a coroutine launched in
 *    [startOverlaySync] (called from `Application.onCreate`); that
 *    coroutine diffs the pin list against the active window-manager views
 *    and adds / removes / updates them via
 *    [com.pinpin.app.overlay.PinOverlayService.syncPinList].
 *
 * Threading: all mutators are `suspend` and run on [Dispatchers.IO] via
 * the [applicationScope]. The flow returned by [observePins] can be
 * collected on any dispatcher.
 */
@Singleton
class PinRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: PinDao,
    private val database: PinDatabase,
    private val assetStore: PinAssetStore,
) {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Activity recreation and the onboarding hand-off can both call
    // startOverlaySync(). Keep one collector for the lifetime of this
    // singleton so every Room invalidation is forwarded exactly once.
    private val overlaySyncLock = Any()
    private var overlaySyncJob: Job? = null
    private var legacyAssetMigrationAttempted = false

    /** Cold flow of all pins, ordered by z-order. */
    fun observePins(): Flow<List<PinEntity>> = dao.observeAll().distinctUntilChanged()

    /** Hot StateFlow snapshot for the home screen. */
    val pinsState: StateFlow<List<PinEntity>> =
        observePins().stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /**
     * Wire the repository to the overlay service: any time the pin list
     * changes, the new snapshot is forwarded to the service so it can
     * add, remove, or update the corresponding
     * [com.pinpin.app.overlay.PinWindowView]s.
     *
     * Safe to call from multiple Activity entry points; repeated calls
     * reuse the single active collector.
     */
    fun startOverlaySync() {
        synchronized(overlaySyncLock) {
            if (overlaySyncJob?.isActive == true) return

            overlaySyncJob = applicationScope.launch {
                observePins().collectLatest { pins ->
                    if (!legacyAssetMigrationAttempted) {
                        val changed = migrateLegacyAssets()
                        currentCoroutineContext().ensureActive()
                        legacyAssetMigrationAttempted = true
                        applicationScope.launch {
                            try {
                                cleanupOrphanAssets()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                Log.w(TAG, "Startup asset cleanup deferred", failure)
                            }
                        }
                        if (changed) return@collectLatest
                    }
                    PinOverlayService.syncPinList(context, pins)
                }
            }
        }
    }

    internal suspend fun migrateLegacyAssets(): Boolean = assetStore.mutate {
        data class Update(
            val id: String,
            val imageUri: String?,
            val annotatedImageUri: String?,
        )

        val created = mutableListOf<String>()
        try {
            val updates = dao.getAll().mapNotNull { pin ->
                val migratedImage = migrateLegacyUri(pin.imageUri, "legacy-original")
                if (migratedImage != pin.imageUri) created.add(migratedImage)
                val migratedAnnotation = pin.annotatedImageUri?.let {
                    migrateLegacyUri(it, "legacy-annotated")
                }
                if (migratedAnnotation != null && migratedAnnotation != pin.annotatedImageUri) {
                    created.add(migratedAnnotation)
                }
                if (migratedImage == pin.imageUri && migratedAnnotation == pin.annotatedImageUri) {
                    null
                } else {
                    Update(pin.id, migratedImage, migratedAnnotation)
                }
            }
            if (updates.isEmpty()) return@mutate false

            database.withTransaction {
                updates.forEach { update ->
                    update.imageUri?.let { dao.updateImageUri(update.id, it) }
                    dao.updateAnnotatedImageUri(update.id, update.annotatedImageUri)
                }
            }
            created.forEach(assetStore::committed)
            true
        } catch (failure: Throwable) {
            reclaim(created)
            throw failure
        }
    }

    private suspend fun migrateLegacyUri(rawUri: String, prefix: String): String {
        val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return rawUri
        if (uri.scheme == "pinpin" || assetStore.isManaged(uri)) return rawUri

        return try {
            assetStore.ensureDurable(uri, prefix).toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            Log.w(TAG, "Could not migrate legacy Pin asset: $rawUri", t)
            rawUri
        }
    }

    // region Mutators

    /**
     * Insert a new pin. The default size is computed from the image's
     * intrinsic dimensions, capped at 480×480 dp. The position is a
     * simple grid layout so successive pins don't stack on top of each
     * other. A fresh screen capture supplies its bounds to preserve its
     * on-screen position and display size instead.
     */
    suspend fun addPinFromUri(
        imageUri: Uri,
        sourceType: String,
        defaultWidthPx: Int,
        defaultHeightPx: Int,
        initialBounds: CaptureBounds? = null,
    ) {
        require(initialBounds == null ||
            (initialBounds.left >= 0 && initialBounds.top >= 0 &&
                initialBounds.right > initialBounds.left && initialBounds.bottom > initialBounds.top))
        val density = context.resources.displayMetrics.density
        val maxW = (480 * density).toInt()
        val maxH = (480 * density).toInt()
        val (targetW, targetH) = initialBounds?.let { it.width to it.height }
            ?: PinSizing.fitInside(defaultWidthPx, defaultHeightPx, maxW, maxH)
        val marginPx = (24 * density).toInt()

        assetStore.mutate {
            var durableUri = imageUri.toString()
            try {
                if (imageUri.scheme != "pinpin") {
                    durableUri = assetStore.ensureDurable(imageUri, "original").toString()
                }
                database.withTransaction {
                    // Allocate both the serial slot and the initial grid position from
                    // the same database snapshot so concurrent inserts cannot collide.
                    val newZ = dao.maxPositiveZOrder() + 1
                    val existingCount = dao.count()
                    val col = existingCount % 4
                    val row = existingCount / 4
                    val x = initialBounds?.left ?: (marginPx + col * (targetW / 3))
                    val y = initialBounds?.top ?: (marginPx + row * (targetH / 3))

                    dao.upsert(
                        PinEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            imageUri = durableUri,
                            sourceType = sourceType,
                            x = x,
                            y = y,
                            width = targetW.toFloat(),
                            height = targetH.toFloat(),
                            zOrder = newZ,
                        ),
                    )
                }
                assetStore.committed(durableUri)
            } catch (failure: Throwable) {
                reclaim(listOf(durableUri))
                throw failure
            }
        }
    }

    suspend fun removePin(id: String) {
        assetStore.mutate {
            val old = dao.findById(id)
            database.withTransaction {
                dao.deleteById(id)
                renormalizeZOrdersInTransaction()
            }
            reclaim(listOfNotNull(old?.imageUri, old?.annotatedImageUri))
        }
    }

    suspend fun updatePosition(id: String, x: Int, y: Int) {
        dao.updatePosition(id, x, y)
    }

    suspend fun updateSize(id: String, width: Float, height: Float) {
        dao.updateSize(id, width, height)
    }

    suspend fun updateGeometry(id: String, x: Int, y: Int, width: Float, height: Float) {
        database.withTransaction {
            dao.updatePosition(id, x, y)
            dao.updateSize(id, width, height)
        }
    }

    suspend fun setLocked(id: String, locked: Boolean) {
        dao.updateLocked(id, locked)
    }

    suspend fun setZOrder(id: String, zOrder: Int) {
        dao.updateZOrder(id, zOrder)
    }

    suspend fun bringToFront(id: String) {
        // Bump above any numbered pin; unnumbered pins
        // (zOrder = 0) don't compete for the top slot.
        database.withTransaction {
            val top = dao.maxPositiveZOrder() + 1
            dao.updateZOrder(id, top)
        }
    }

    /**
     * Renumber the pin set so the visible badges read 1, 2, 3,
     * ... without gaps. The numbering follows the existing
     * display order — numbered pins (zOrder > 0) keep their
     * relative order, then unnumbered pins (zOrder = 0) are
     * appended at the end in createdAt order. This is called
     * after any operation that leaves a gap in the sequence:
     * a pin deletion, a pin being un-numbered, etc.
     *
     * Pins that already have the correct zOrder are skipped
     * to keep the Room invalidation log quiet and avoid
     * redundant [applyPinList] rebuilds.
     */
    suspend fun renormalizeZOrders() {
        database.withTransaction {
            renormalizeZOrdersInTransaction()
        }
    }

    private suspend fun renormalizeZOrdersInTransaction() {
        val orders = dao.ordersInDisplayOrder()
        orders.forEachIndexed { index, pin ->
            val newZ = index + 1
            if (pin.zOrder != newZ) {
                dao.updateZOrder(pin.id, newZ)
            }
        }
    }

    /**
     * The next free positive slot in the numbered sequence.
     * Equal to `MAX(zOrder > 0) + 1`, or 1 if no pin is
     * currently numbered. Callers that turn a previously
     * un-numbered pin back on should use this as the
     * pin's new zOrder before invoking [renormalizeZOrders].
     */
    suspend fun nextAvailableZOrder(): Int = dao.maxPositiveZOrder() + 1

    /** Read the current zOrder of a single pin. Used after a
     *  renumber to stamp the badge with the post-shuffle
     *  value. Returns `null` if the pin no longer exists. */
    suspend fun findZOrder(id: String): Int? = dao.findById(id)?.zOrder

    /** Rotate the pin and its display bounds together so observers never see mismatched geometry. */
    suspend fun rotateClockwise(id: String): PinEntity? = database.withTransaction {
        val current = dao.findById(id) ?: return@withTransaction null
        val next = ((current.rotation / 90 + 1) * 90) % 360
        val rotated = current.copy(
            rotation = next,
            x = current.x + ((current.width - current.height) / 2f).toInt(),
            y = current.y + ((current.height - current.width) / 2f).toInt(),
            width = current.height,
            height = current.width,
        )
        dao.updateRotation(id, next)
        dao.updatePosition(id, rotated.x, rotated.y)
        dao.updateSize(id, rotated.width, rotated.height)
        rotated
    }

    suspend fun toggleFlip(id: String): Boolean = database.withTransaction {
        val current = dao.findById(id) ?: return@withTransaction false
        val next = !current.flipped
        dao.updateFlipped(id, next)
        next
    }

    suspend fun setSerialVisible(id: String, visible: Boolean) {
        dao.updateSerialVisible(id, visible)
    }

    suspend fun updateImageUri(id: String, imageUri: String) {
        replaceAsset(id, imageUri, annotation = false)
    }

    /**
     * Commit a freshly-rendered annotation. The overlay service
     * writes the composited bitmap to the app's private asset directory
     * and hands the file URI here; the entity is updated in-place
     * without a re-fetch so the live [PinWindowView] sees the new
     * image on the next draw.
     *
     * Pass `null` to [annotatedImageUri] to revert to the original
     * image (e.g. when the user cancels an in-progress annotation
     * without saving).
     */
    suspend fun updateAnnotatedImage(id: String, annotatedImageUri: String?): Boolean {
        return replaceAsset(id, annotatedImageUri, annotation = true)
    }

    private suspend fun replaceAsset(id: String, uri: String?, annotation: Boolean): Boolean {
        return assetStore.mutate { replaceAssetLocked(id, uri, annotation) }
    }

    private suspend fun replaceAssetLocked(id: String, uri: String?, annotation: Boolean): Boolean {
        var durableUri = uri
        try {
            if (uri != null && dao.findById(id) != null) {
                val parsed = Uri.parse(uri)
                if (parsed.scheme != "pinpin") {
                    durableUri = assetStore.ensureDurable(parsed, "replacement").toString()
                }
            }
            var committed = false
            val previous = database.withTransaction {
                val pin = dao.findById(id) ?: return@withTransaction null
                if (annotation) dao.updateAnnotatedImageUri(id, durableUri)
                else dao.updateImageUri(id, requireNotNull(durableUri))
                committed = true
                if (annotation) pin.annotatedImageUri else pin.imageUri
            }
            durableUri?.let(assetStore::committed)
            // Also compensates a save whose pin was deleted while rendering.
            reclaim(listOfNotNull(previous, durableUri))
            return committed
        } catch (failure: Throwable) {
            reclaim(listOfNotNull(durableUri))
            throw failure
        }
    }

    /** Consumes bitmap on every exit. File creation and DB commit are one unit. */
    suspend fun saveAnnotatedImage(id: String, bitmap: Bitmap) {
        try {
            assetStore.mutate {
                if (dao.findById(id) == null) return@mutate
                val uri = assetStore.saveBitmap(bitmap).toString()
                replaceAssetLocked(id, uri, annotation = true)
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    internal suspend fun referencedAssetPaths(): Set<String> {
        val raw = dao.getAll().flatMap { listOfNotNull(it.imageUri, it.annotatedImageUri) } +
            database.historyDao().allFilePaths()
        // A malformed reference aborts cleanup, rather than risking user data.
        return raw.mapNotNull {
            val uri = Uri.parse(it)
            when {
                uri.scheme == "file" -> uri.path?.let { path -> File(path).canonicalPath }
                uri.scheme == null && File(it).isAbsolute -> File(it).canonicalPath
                // An unresolved provider URI may alias a private file. Fail closed.
                uri.scheme == "content" -> throw java.io.IOException("Unresolved content reference")
                else -> null
            }
        }.toSet()
    }

    /** Call under assetStore.mutate, including history compensation. */
    internal suspend fun isAssetReferenced(file: File): Boolean {
        val canonical = file.canonicalFile
        if (!file.isAbsolute || canonical != file.absoluteFile) return true
        val root = context.filesDir.canonicalFile
        val pinDir = File(root, "pin_assets")
        val historyDir = File(root, "screenshot_history")
        val references = listOf(canonical.path, Uri.fromFile(canonical).toString())
        val pinPathPrefix = "${pinDir.path}/"
        val historyPathPrefix = "${historyDir.path}/"
        val pinUriPrefix = "${Uri.fromFile(pinDir)}/"
        val historyUriPrefix = "${Uri.fromFile(historyDir)}/"
        return database.withTransaction {
            val historyDao = database.historyDao()
            if (dao.hasNativeAssetReference(references) ||
                historyDao.hasNativeAssetReference(references)
            ) return@withTransaction true
            // A syntactically native sibling can still alias the target via
            // symlink. Inspect entries, not every DB reference's canonicalPath.
            for (directory in listOf(pinDir, historyDir)) {
                if (directory.canonicalFile != directory.absoluteFile ||
                    hasSymbolicLinks(directory)
                ) return@withTransaction true
            }
            dao.hasSuspiciousAssetReference(
                pinPathPrefix, pinUriPrefix, historyPathPrefix, historyUriPrefix,
            ) ||
                historyDao.hasSuspiciousAssetReference(
                    pinPathPrefix, pinUriPrefix, historyPathPrefix, historyUriPrefix,
                )
        }
    }

    private fun hasSymbolicLinks(directory: File): Boolean {
        val stat = try {
            Os.lstat(directory.path)
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT) return false
            throw failure
        }
        if (!OsConstants.S_ISDIR(stat.st_mode)) return true
        val entries = directory.listFiles() ?: return true
        // lstat failures propagate and defer cleanup, unlike isSymbolicLink's
        // false-on-IO-error result. This includes dangling symlink entries.
        return entries.any { OsConstants.S_ISLNK(Os.lstat(it.path).st_mode) }
    }

    private suspend fun reclaim(uris: List<String>) {
        if (uris.isEmpty()) return
        try {
            uris.distinct().forEach { raw ->
                val uri = Uri.parse(raw)
                if (!assetStore.isManaged(uri)) return@forEach
                val file = File(uri.path ?: return@forEach)
                // Non-native candidate URIs are also deferred, not normalized
                // into permission to delete a potentially different resource.
                if (raw != Uri.fromFile(file).toString()) return@forEach
                if (!isAssetReferenced(file)) {
                    assetStore.deleteUnreferenced(raw, emptySet())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A leak is preferable to deleting a possibly referenced image.
            Log.w(TAG, "Asset cleanup deferred", failure)
        }
    }

    /** Opt-in maintenance; no history, cache, external directories, or recursion. */
    suspend fun cleanupOrphanAssets(
        minimumAgeMillis: Long = 7 * 24 * 60 * 60 * 1000L,
    ): Int = assetStore.mutate {
        assetStore.sweep(referencedAssetPaths(), System.currentTimeMillis(), minimumAgeMillis)
    }

    /** For a caller abandoning saveBitmap -> updateAnnotatedImage before commit. */
    suspend fun discardUnreferencedAsset(uri: Uri) = withContext(NonCancellable) {
        assetStore.mutate { reclaim(listOf(uri.toString())) }
    }

    // endregion

    companion object {
        private const val TAG = "PinRepository"
    }
}
