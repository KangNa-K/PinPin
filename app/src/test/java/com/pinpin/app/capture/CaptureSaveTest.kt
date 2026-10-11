package com.pinpin.app.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureSaveTest {
    @Test fun cancellationDuringCommitStillReportsSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            completeCaptureSave(
                prepare = { "asset" },
                commit = {
                    entered.complete(Unit)
                    release.await()
                    events.add("commit")
                },
                discard = { events.add("discard") },
                receipt = { events.add(if (it == null) "success" else "failure") },
            )
        }
        entered.await()
        worker.cancel()
        assertTrue(events.isEmpty())
        release.complete(Unit)
        worker.join()
        assertEquals(listOf("commit", "success"), events)
    }

    @Test fun cancellationWhileCopyingDoesNotLoseReturnedAsset() = runBlocking {
        val copying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            completeCaptureSave(
                prepare = {
                    copying.complete(Unit)
                    release.await()
                    "asset"
                },
                commit = { throw IllegalStateException("DB unavailable") },
                discard = { events.add("discard:$it") },
                receipt = { events.add(if (it == null) "success" else "failure") },
            )
        }
        copying.await()
        worker.cancel()
        release.complete(Unit)
        worker.join()
        assertEquals(listOf("discard:asset", "failure"), events)
    }

    @Test fun prepareFailureDoesNotDiscardAnAssetThatWasNeverReturned() = runBlocking {
        val failure = IllegalStateException("decode failed")
        val receipts = mutableListOf<Throwable?>()
        completeCaptureSave<String>(
            prepare = { throw failure },
            commit = { fail("No asset to commit") },
            discard = { fail("No asset to discard") },
            receipt = { receipts.add(it) },
        )
        assertEquals(listOf(failure), receipts)
    }

    @Test fun cleanupFailurePreservesOriginalFailureAndSingleReceipt() = runBlocking {
        val failure = IllegalStateException("commit failed")
        val cleanup = IllegalStateException("cleanup failed")
        val receipts = mutableListOf<Throwable?>()
        completeCaptureSave(
            prepare = { "asset" },
            commit = { throw failure },
            discard = { throw cleanup },
            receipt = { receipts.add(it) },
        )
        assertEquals(listOf(failure), receipts)
        assertArrayEquals(arrayOf(cleanup), failure.suppressed)
    }
}
