package com.pinpin.app.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pinpin.app.data.PinRepository
import com.pinpin.app.data.ScreenshotHistoryEntity
import com.pinpin.app.data.ScreenshotHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel backing the home screen.
 *
 *  - Pin mutations (add, remove) go through [PinRepository].
 *  - History mutations (delete, re-pin) go through
 *    [ScreenshotHistoryRepository].
 *  - For [addPinFromUri] we have to read the bitmap dimensions off the
 *    main thread, so we open an [InputStream] via the [ContentResolver]
 *    and use [BitmapFactory.Options.inJustDecodeBounds] to peek at the
 *    metadata without decoding the full image.
 *  - When the user picks from the gallery, we also save a copy into
 *    history so the "history list" feature stays populated.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val repository: PinRepository,
    private val historyRepository: ScreenshotHistoryRepository,
) : AndroidViewModel(application) {

    val history: StateFlow<List<ScreenshotHistoryEntity>> = historyRepository.historyState

    fun addPinFromUri(uri: Uri, sourceType: String) {
        viewModelScope.launch {
            val (w, h) = readImageSize(uri)
            repository.addPinFromUri(uri, sourceType, w, h)
            // Also persist a copy into history so the user can
            // re-pin it later. Best-effort — failures are logged
            // and do not break the pin creation.
            runCatching { historyRepository.addFromUri(uri, sourceType) }
        }
    }

    fun addTestPin() {
        viewModelScope.launch {
            val density = getApplication<Application>().resources.displayMetrics.density
            val w = (320 * density).toInt()
            val h = (180 * density).toInt()
            repository.addPinFromUri(
                imageUri = Uri.parse("pinpin://test/${System.currentTimeMillis()}"),
                sourceType = "test",
                defaultWidthPx = w,
                defaultHeightPx = h,
            )
        }
    }

    fun removePin(id: String) {
        viewModelScope.launch { repository.removePin(id) }
    }

    /** Re-pin a history entry onto the screen as a new pin. */
    fun repinFromHistory(item: ScreenshotHistoryEntity) {
        viewModelScope.launch { historyRepository.repinAsPin(item) }
    }

    /** Batch re-pin a list of history entries (multi-select flow). */
    fun repinManyFromHistory(items: List<ScreenshotHistoryEntity>) {
        viewModelScope.launch {
            items.forEach { historyRepository.repinAsPin(it) }
        }
    }

    /** Delete a history entry (and its backing file). */
    fun deleteHistory(id: String) {
        viewModelScope.launch { historyRepository.delete(id) }
    }

    /** Batch delete a list of history entries (multi-select flow). */
    fun deleteHistories(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { historyRepository.delete(it) }
        }
    }

    private suspend fun readImageSize(uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            opts.outWidth to opts.outHeight
        } else {
            1280 to 720
        }
    }
}
