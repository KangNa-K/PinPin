package com.pinpin.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiOperationTest {
    @Test
    fun historyFailureIsPartialAndDoesNotInviteReimport() = runBlocking {
        val result = finishImportHistory { throw IllegalStateException("disk full") }
        assertTrue(result.succeeded)
        assertTrue(result.partial)
        assertTrue(result.message.contains("请勿重复导入"))
    }

    @Test
    fun historySuccessIsNotPartial() = runBlocking {
        val result = finishImportHistory {}
        assertTrue(result.succeeded)
        assertFalse(result.partial)
    }

    @Test
    fun copiedAssetIsDiscardedWhenCancelledWaitingForOperationLock() = runBlocking {
        val lock = Mutex(locked = true)
        val copied = CompletableDeferred<Unit>()
        val discarded = mutableListOf<String>()
        val job = launch {
            withImportAsset(
                acquire = { "private-image" },
                discard = {
                    currentCoroutineContext().ensureActive()
                    discarded.add(it)
                },
                onCleanupFailure = { throw AssertionError(it) },
            ) {
                copied.complete(Unit)
                lock.withLock { error("must not acquire lock") }
            }
        }
        copied.await()
        job.cancelAndJoin()
        assertEquals(listOf("private-image"), discarded)
        assertTrue(job.isCancelled)
    }

    @Test
    fun invalidImageStillDiscardsItsCopiedAsset() = runBlocking {
        var discarded = false
        var failed = false
        try {
            withImportAsset(
                acquire = { "invalid-image" },
                discard = { discarded = true },
                onCleanupFailure = { throw AssertionError(it) },
            ) { throw IllegalArgumentException("invalid bounds") }
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(discarded)
    }

    @Test
    fun successfulImportAlsoRunsReferenceCheckedCleanup() = runBlocking {
        var discarded = false
        withImportAsset(
            acquire = { "referenced-image" },
            discard = { discarded = true },
            onCleanupFailure = { throw AssertionError(it) },
        ) {}
        assertTrue(discarded)
    }

    @Test
    fun cleanupFailureDoesNotReplaceSuccessfulImport() = runBlocking {
        var reported = false
        withImportAsset(
            acquire = { "image" },
            discard = { throw IllegalStateException("cleanup") },
            onCleanupFailure = { reported = true },
        ) {}
        assertTrue(reported)
    }

    @Test
    fun recreationIsDeduplicatedButExplicitNewShareIsAccepted() {
        val gate = ShareRequestGate()
        assertTrue(gate.claim("request-1"))
        assertFalse(gate.claim("request-1"))
        assertTrue(gate.claim("request-2"))
    }

    @Test
    fun historyCancellationIsNotReportedAsPartialSuccess() = runBlocking {
        var cancelled = false
        try {
            finishImportHistory { throw CancellationException("stop") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun failureDoesNotSkipRemainingItemsAndReportsInOrder() = runBlocking {
        val results = mutableListOf<UiItemResult>()
        runUiItems(listOf("first", "bad", "last"), { it }, { results.add(it) }) {
            if (it == "bad") throw IllegalStateException("unreadable")
            "done"
        }
        assertEquals(listOf("first", "bad", "last"), results.map { it.label })
        assertEquals(listOf(true, false, true), results.map { it.succeeded })
        assertEquals("unreadable", results[1].message)
    }

    @Test
    fun cancellationIsRethrownAndDoesNotBecomeAnItemFailure() = runBlocking {
        val results = mutableListOf<UiItemResult>()
        val visited = mutableListOf<Int>()
        var cancelled = false
        try {
            runUiItems(listOf(1, 2, 3), { it.toString() }, { results.add(it) }) {
                visited.add(it)
                if (it == 2) throw CancellationException("stop")
                "done"
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(listOf(1, 2), visited)
        assertEquals(1, results.size)
        assertTrue(results.single().succeeded)
    }

    @Test
    fun missingErrorMessageGetsUserVisibleFallback() = runBlocking {
        val results = mutableListOf<UiItemResult>()
        runUiItems(listOf(Unit), { "image" }, { results.add(it) }) {
            throw IllegalStateException()
        }
        assertFalse(results.single().succeeded)
        assertTrue(results.single().message.isNotBlank())
    }

    @Test
    fun emptyBatchDoesNothing() = runBlocking {
        runUiItems(emptyList<Int>(), { error("label") }, { error("result") }) {
            error("action")
        }
    }
}
