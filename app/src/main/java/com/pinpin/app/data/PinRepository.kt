package com.pinpin.app.data

import android.content.Context
import android.net.Uri
import com.pinpin.app.overlay.PinOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

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
    @ApplicationContext private val context: Context,
    private val dao: PinDao,
) {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     * Called once from `Application.onCreate`.
     */
    fun startOverlaySync() {
        applicationScope.launch {
            observePins().collect { pins ->
                PinOverlayService.syncPinList(context, pins)
            }
        }
    }

    // region Mutators

    /**
     * Insert a new pin. The default size is computed from the image's
     * intrinsic dimensions, capped at 480×480 dp. The position is a
     * simple grid layout so successive pins don't stack on top of each
     * other.
     */
    suspend fun addPinFromUri(
        imageUri: Uri,
        sourceType: String,
        defaultWidthPx: Int,
        defaultHeightPx: Int,
    ) {
        val newZ = dao.maxZOrder() + 1
        val density = context.resources.displayMetrics.density
        val maxW = (480 * density).toInt()
        val maxH = (480 * density).toInt()
        val (targetW, targetH) = fitInside(defaultWidthPx, defaultHeightPx, maxW, maxH)

        // Grid offset so successive inserts don't overlap.
        val marginPx = (24 * density).toInt()
        val existingCount = pinsState.value.size
        val col = existingCount % 4
        val row = existingCount / 4
        val x = marginPx + col * (targetW / 3)
        val y = marginPx + row * (targetH / 3)

        val entity = PinEntity(
            id = java.util.UUID.randomUUID().toString(),
            imageUri = imageUri.toString(),
            sourceType = sourceType,
            x = x,
            y = y,
            width = targetW.toFloat(),
            height = targetH.toFloat(),
            zOrder = newZ,
        )
        dao.upsert(entity)
    }

    suspend fun removePin(id: String) {
        dao.deleteById(id)
    }

    suspend fun updatePosition(id: String, x: Int, y: Int) {
        dao.updatePosition(id, x, y)
    }

    suspend fun updateSize(id: String, width: Float, height: Float) {
        dao.updateSize(id, width, height)
    }

    suspend fun setLocked(id: String, locked: Boolean) {
        dao.updateLocked(id, locked)
    }

    suspend fun setZOrder(id: String, zOrder: Int) {
        dao.updateZOrder(id, zOrder)
    }

    suspend fun bringToFront(id: String) {
        val top = dao.maxZOrder() + 1
        dao.updateZOrder(id, top)
    }

    /**
     * Bump the rotation by 90° clockwise. Persists the new value
     * and returns the resulting rotation, so the caller can apply
     * a matching transform to the live [PinWindowView].
     */
    suspend fun rotateClockwise(id: String): Int {
        val current = dao.findById(id) ?: return 0
        val next = ((current.rotation / 90 + 1) * 90) % 360
        dao.updateRotation(id, next)
        return next
    }

    suspend fun toggleFlip(id: String): Boolean {
        val current = dao.findById(id) ?: return false
        val next = !current.flipped
        dao.updateFlipped(id, next)
        return next
    }

    suspend fun updateImageUri(id: String, imageUri: String) {
        dao.updateImageUri(id, imageUri)
    }

    // endregion

    private fun fitInside(
        srcW: Int,
        srcH: Int,
        maxW: Int,
        maxH: Int,
    ): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) {
            val w = min(maxW, (320 * 3))
            val h = min(maxH, (180 * 3))
            return w to h
        }
        val aspect = srcW.toFloat() / srcH.toFloat()
        val w = min(srcW, maxW)
        val h = (w / aspect).toInt().coerceAtMost(maxH)
        return max(w, 80) to max(h, 80)
    }
}
