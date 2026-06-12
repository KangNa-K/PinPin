package com.pinpin.app.data

import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-disk history of "screenshots the user has produced /
 * imported so far". Each entry is a PNG persisted under the app's
 * private files dir, so the URI is stable across reboots and across
 * PhotoPicker grants expiring.
 *
 *  - [addFromBitmap] is used by the screen-capture path (we already
 *    have a [Bitmap] in memory).
 *  - [addFromUri] is used when the user picks from the gallery: we
 *    stream-decode and re-encode into our own files dir, so the
 *    history row is durable even after the picker's grant expires.
 *  - [observeHistory] is the cold flow consumed by the home screen.
 *  - [repinAsPin] is the "钉在屏幕上" action: copy the historical
 *    file into the pin-managed cache dir and insert a [PinEntity]
 *    pointing at the new copy. (The original history file is kept
 *    untouched so the user can re-pin it again later.)
 */
@Singleton
class ScreenshotHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScreenshotHistoryDao,
    private val pinRepository: PinRepository,
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
    suspend fun addFromBitmap(bitmap: Bitmap, sourceType: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val file = File(historyDir(), "$id.png")
        file.parentFile?.mkdirs()
        val w = bitmap.width
        val h = bitmap.height
        FileOutputStream(file).use { os ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        }
        bitmap.recycle()
        dao.upsert(
            ScreenshotHistoryEntity(
                id = id,
                filePath = file.absolutePath,
                width = w,
                height = h,
                sourceType = sourceType,
            ),
        )
        file.absolutePath
    }

    /**
     * Decode the bytes behind [uri] (already cached by the picker),
     * re-encode into our history dir, and insert a row. The source
     * URI is not retained because the picker's URI grant may expire.
     */
    suspend fun addFromUri(uri: Uri, sourceType: String) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val file = File(historyDir(), "$id.png")
        file.parentFile?.mkdirs()
        var width = 0
        var height = 0
        FileOutputStream(file).use { os ->
            // We don't need a full Bitmap on the heap — we can copy
            // bytes straight from the input stream to PNG. But for
            // fidelity we decode → re-encode. Read bounds first, then
            // decode and compress.
            val bmp = resolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            } ?: return@withContext
            width = bmp.width
            height = bmp.height
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            bmp.recycle()
        }
        if (width == 0 || height == 0) {
            file.delete()
            return@withContext
        }
        dao.upsert(
            ScreenshotHistoryEntity(
                id = id,
                filePath = file.absolutePath,
                width = width,
                height = height,
                sourceType = sourceType,
            ),
        )
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val row = dao.findById(id) ?: return@withContext
        dao.deleteById(id)
        runCatching { File(row.filePath).delete() }
    }

    /**
     * Re-pin a history entry onto the screen. Copies the file into
     * the pin cache so the pin layer's existing URI handling keeps
     * working without modification, and inserts a new [PinEntity]
     * via the existing [PinRepository] path.
     */
    suspend fun repinAsPin(item: ScreenshotHistoryEntity) = withContext(Dispatchers.IO) {
        val src = File(item.filePath)
        if (!src.exists()) return@withContext
        val dest = File(File(context.cacheDir, "screenshots"), "history-${System.currentTimeMillis()}.png")
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        pinRepository.addPinFromUri(
            imageUri = Uri.fromFile(dest),
            sourceType = "history-${item.sourceType}",
            defaultWidthPx = item.width,
            defaultHeightPx = item.height,
        )
    }

    // Small helpers because Bitmap.getWidth/getHeight are deprecated
    // for Hardware bitmaps — we only ever pass software bitmaps here.
    private fun bitmapWidth(b: Bitmap): Int = b.width
    private fun bitmapHeight(b: Bitmap): Int = b.height

    private fun historyDir(): File =
        File(context.filesDir, "screenshot_history")

    companion object {
        private const val TAG = "ScreenshotHistoryRepo"
    }
}
