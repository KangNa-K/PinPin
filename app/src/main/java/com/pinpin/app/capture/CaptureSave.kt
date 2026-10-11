package com.pinpin.app.capture

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Finish the local commit and deliver its receipt before returning to a cancelled caller. */
internal suspend fun <T : Any> completeCaptureSave(
    prepare: suspend () -> T,
    commit: suspend (T) -> Unit,
    discard: suspend (T) -> Unit,
    receipt: (Throwable?) -> Unit,
) = withContext(NonCancellable) {
    var asset: T? = null
    var committed = false
    var failure: Throwable? = null
    try {
        val prepared = prepare()
        asset = prepared
        commit(prepared)
        committed = true
    } catch (t: Throwable) {
        failure = t
    } finally {
        if (!committed) {
            asset?.let {
                try {
                    discard(it)
                } catch (cleanup: Throwable) {
                    val original = failure
                    if (original == null) failure = cleanup else original.addSuppressed(cleanup)
                }
            }
        }
    }
    // No dispatcher hop or cancellation check between a successful commit and its receipt.
    receipt(failure)
}
