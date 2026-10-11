package com.pinpin.app.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pinpin.app.data.PinAssetStore
import com.pinpin.app.data.PinRepository
import com.pinpin.app.data.ScreenshotHistoryEntity
import com.pinpin.app.data.ScreenshotHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
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
    private val assetStore: PinAssetStore,
) : AndroidViewModel(application) {

    val history: StateFlow<List<ScreenshotHistoryEntity>> = historyRepository.historyState
    val pins = repository.pinsState
    private val operationLock = Mutex()
    private val _operation = MutableStateFlow(UiOperation())
    val operation = _operation.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _importsInFlight = MutableStateFlow(0)
    val importsInFlight = _importsInFlight.asStateFlow()
    private val _overlayStartRequested = MutableStateFlow(false)
    val overlayStartRequested = _overlayStartRequested.asStateFlow()

    fun consumeOverlayStartRequest() { _overlayStartRequested.value = false }
    fun startOverlaySync() { repository.startOverlaySync() }
    private val shareRequests = ShareRequestGate()

    fun importShare(requestId: String, uri: Uri) {
        if (shareRequests.claim(requestId)) addPinFromUri(uri, "share")
    }

    fun reportMessage(message: String) { _message.value = message }
    fun dismissMessage() { _message.value = null }
    fun dismissResults() { if (!_operation.value.running) _operation.value = UiOperation() }

    private fun <T> perform(
        title: String,
        items: List<T>,
        label: (T) -> String,
        action: suspend (T) -> String,
    ) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            operationLock.withLock {
                _operation.value = UiOperation(title, items.size, true)
                try {
                    runUiItems(items, label, { result ->
                        _operation.value = _operation.value.copy(
                            results = _operation.value.results + result,
                        )
                    }, action)
                } finally {
                    _operation.value = _operation.value.copy(running = false)
                }
            }
        }
    }

    fun addPinFromUri(uri: Uri, sourceType: String) {
        _importsInFlight.value++
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Copy while the temporary grant is live, then wait in this same job.
                withImportAsset(
                    acquire = { assetStore.copyFromUri(uri, "import") },
                    discard = repository::discardUnreferencedAsset,
                    onCleanupFailure = { reportMessage("导入文件清理失败：${it.message}") },
                ) { durableUri ->
                    operationLock.withLock {
                        _operation.value = UiOperation("导入图片", 1, true)
                        try {
                            val (w, h) = readImageSize(durableUri)
                            repository.addPinFromUri(durableUri, sourceType, w, h)
                            _overlayStartRequested.value = true
                            val result = finishImportHistory {
                                historyRepository.addFromUri(durableUri, sourceType)
                            }
                            val visibleResult = if (android.provider.Settings.canDrawOverlays(getApplication())) {
                                result
                            } else {
                                result.copy(message = "${result.message}；授予悬浮窗权限后显示钉图")
                            }
                            _operation.value = _operation.value.copy(results = listOf(visibleResult))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            _operation.value = _operation.value.copy(
                                results = listOf(UiItemResult("图片", importFailureMessage(error), false)),
                            )
                        } finally {
                            _operation.value = _operation.value.copy(running = false)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportMessage(importFailureMessage(error))
            } finally {
                _importsInFlight.value--
            }
        }
    }

    private fun importFailureMessage(error: Exception): String =
        "导入失败：${error.message ?: "无法读取图片"}。请从原应用重新分享，或从相册重新选择；返回首页不会自动重试。"

    fun addTestPin() {
        perform("测试钉图", listOf(Unit), { "测试图片" }) {
            val density = getApplication<Application>().resources.displayMetrics.density
            val w = (320 * density).toInt()
            val h = (180 * density).toInt()
            repository.addPinFromUri(
                imageUri = "pinpin://test/${System.currentTimeMillis()}".toUri(),
                sourceType = "test",
                defaultWidthPx = w,
                defaultHeightPx = h,
            )
            _overlayStartRequested.value = true
            "已添加"
        }
    }

    fun removePin(id: String) {
        perform("关闭钉图", listOf(id), { it }) { repository.removePin(it); "已关闭" }
    }

    /** Re-pin a history entry onto the screen as a new pin. */
    fun repinFromHistory(item: ScreenshotHistoryEntity) {
        repinManyFromHistory(listOf(item))
    }

    /** Batch re-pin a list of history entries (multi-select flow). */
    fun repinManyFromHistory(items: List<ScreenshotHistoryEntity>) {
        perform("重新钉图", items.distinctBy { it.id }, { it.id }) {
            check(java.io.File(it.filePath).isFile) { "历史图片不存在" }
            check(android.provider.Settings.canDrawOverlays(getApplication())) { "请先授予悬浮窗权限" }
            historyRepository.repinAsPin(it)
            _overlayStartRequested.value = true
            "已重新钉图"
        }
    }

    /** Delete a history entry (and its backing file). */
    fun deleteHistory(id: String) {
        deleteHistories(listOf(id))
    }

    /** Batch delete a list of history entries (multi-select flow). */
    fun deleteHistories(ids: List<String>) {
        if (ids.isEmpty()) return
        perform("删除历史", ids.distinct(), { it }) {
            historyRepository.delete(it)
            "已删除"
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
            throw IllegalArgumentException("无法识别图片格式或图片已损坏")
        }
    }
}
