package com.pinpin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-disk history of "screenshots the user has produced /
 * imported so far". Each entry is persisted under the app's private
 * files dir, so the URI is stable across reboots and across
 * PhotoPicker grants expiring.
 *
 *  - [addFromBitmap] is used by the screen-capture path (we already
 *    have a [Bitmap] in memory).
 *  - [addFromUri] is used when the user picks from the gallery: the
 *    original bytes are streamed into our files dir without a full decode.
 *  - [observeHistory] is the cold flow consumed by the home screen.
 *  - [repinAsPin] is the "钉在屏幕上" action: copy the historical
 *    file into the pin-managed asset dir and insert a [PinEntity]
 *    pointing at the new copy. (The original history file is kept
 *    untouched so the user can re-pin it again later.)
 */
@Singleton
class ScreenshotHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ScreenshotHistoryDao,
    private val pinRepository: PinRepository,
    private val assetStore: PinAssetStore,
) {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeHistory(): Flow<List<ScreenshotHistoryEntity>> =
        dao.observeAll().distinctUntilChanged()

    val historyState: StateFlow<List<ScreenshotHistoryEntity>> =
        observeHistory().stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /**
     * Save [bitmap] to the history dir as a PNG and insert a row.
     * The bitmap is recycled after writing. Returns the absolute
     * path of the persisted file.
     */
    suspend fun addFromBitmap(bitmap: Bitmap, sourceType: String): String {
        try {
            return assetStore.mutate { persistBitmap(bitmap, sourceType) }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun persistBitmap(bitmap: Bitmap, sourceType: String): String {
        val id = UUID.randomUUID().toString()
        val file = File(historyDir(), "$id.png")
        file.parentFile?.mkdirs()
        val w = bitmap.width
        val h = bitmap.height
        try {
            writeLosslessPng(bitmap, file)
            dao.upsert(
                ScreenshotHistoryEntity(
                    id = id,
                    filePath = file.absolutePath,
                    width = w,
                    height = h,
                    sourceType = sourceType,
                ),
            )
            return file.absolutePath
        } catch (failure: Throwable) {
            compensate(file)
            throw failure
        }
    }

    /**
     * Persist the original bytes behind [uri] without decoding the full
     * image into a Bitmap. Bounds are read first for the history metadata,
     * then the source stream is copied atomically into private storage.
     */
    suspend fun addFromUri(uri: Uri, sourceType: String) = assetStore.mutate {
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val file = File(historyDir(), "$id.img")
        file.parentFile?.mkdirs()

        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
        } ?: throw IOException("Cannot open image")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid image bounds")

        try {
            AssetFiles.writeAtomically(file) { output ->
                val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open image")
                input.use { it.copyTo(output) }
            }
            dao.upsert(
                ScreenshotHistoryEntity(
                    id = id,
                    filePath = file.absolutePath,
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    sourceType = sourceType,
                ),
            )
        } catch (failure: Throwable) {
            compensate(file)
            throw failure
        }
    }

    suspend fun delete(id: String) = assetStore.mutate {
        val row = dao.findById(id) ?: return@mutate
        dao.deleteById(id)
        compensate(File(row.filePath))
    }

    private suspend fun compensate(file: File) {
        try {
            val owned = AssetFiles.ownedFile(historyDir(), file) ?: return
            if (!pinRepository.isAssetReferenced(owned)) owned.delete()
        } catch (failure: Exception) {
            android.util.Log.w(TAG, "History cleanup deferred", failure)
        }
    }

    /**
     * Re-pin a history entry onto the screen. Copies the file into
     * the durable pin asset store so the pin layer's URI handling keeps
     * working without modification, and inserts a new [PinEntity]
     * via the existing [PinRepository] path.
     */
    suspend fun repinAsPin(item: ScreenshotHistoryEntity) = withContext(Dispatchers.IO) {
        val src = File(item.filePath)
        if (!src.exists()) return@withContext
        // addPinFromUri owns the copy/commit unit; do not hand off a loose file.
        pinRepository.addPinFromUri(
            imageUri = Uri.fromFile(src),
            sourceType = "history-${item.sourceType}",
            defaultWidthPx = item.width,
            defaultHeightPx = item.height,
        )
    }

    private fun historyDir(): File =
        File(context.filesDir.canonicalFile, "screenshot_history")

    companion object {
        private const val TAG = "ScreenshotHistoryRepo"
    }
}
