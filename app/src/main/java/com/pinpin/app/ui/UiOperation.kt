package com.pinpin.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class ShareRequestGate {
    private val requests = mutableSetOf<String>()
    fun claim(id: String): Boolean = requests.add(id)
}

internal suspend fun <T> withImportAsset(
    acquire: suspend () -> T,
    discard: suspend (T) -> Unit,
    onCleanupFailure: (Exception) -> Unit,
    use: suspend (T) -> Unit,
) {
    val asset = acquire()
    try {
        use(asset)
    } finally {
        withContext(NonCancellable) {
            try {
                discard(asset)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onCleanupFailure(error)
            }
        }
    }
}

internal suspend fun finishImportHistory(saveHistory: suspend () -> Unit): UiItemResult =
    try {
        saveHistory()
        UiItemResult("图片", "导入完成", true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        UiItemResult(
            "图片", "钉图已保存，历史保存失败：${error.message ?: "未知错误"}。请勿重复导入。",
            succeeded = true, partial = true,
        )
    }

data class UiItemResult(
    val label: String,
    val message: String,
    val succeeded: Boolean,
    val partial: Boolean = false,
)

data class UiOperation(
    val title: String = "",
    val total: Int = 0,
    val running: Boolean = false,
    val results: List<UiItemResult> = emptyList(),
)

internal suspend fun <T> runUiItems(
    items: List<T>,
    label: (T) -> String,
    onResult: (UiItemResult) -> Unit,
    action: suspend (T) -> String,
) {
    for (item in items) {
        currentCoroutineContext().ensureActive()
        val result = try {
            UiItemResult(label(item), action(item), true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            UiItemResult(label(item), error.message ?: "操作失败，请重试", false)
        }
        onResult(result)
    }
}
