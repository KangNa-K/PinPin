package com.pinpin.app.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class AssetCancellationTest {
    @Test fun cancelledReturnReleasesOnlyNewAssetsForAllWriters() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "asset-cancel-${UUID.randomUUID()}").apply {
            mkdirs()
        }.canonicalFile
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        val store = PinAssetStore(context)
        val source = File(root, "source.png").apply { writeText("source") }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            val operations: List<suspend () -> Uri> = listOf(
                { store.copyFromUri(Uri.fromFile(source), "copy") },
                { store.copyFileToPin(source, "file") },
                { store.ensureDurable(Uri.fromFile(source), "durable") },
                { store.saveBitmap(bitmap, "bitmap") },
            )
            for (operation in operations) {
                val returns = LinkedBlockingQueue<Runnable>()
                val dispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        returns.add(block)
                    }
                }
                var delivered = false
                val startWrite = CompletableDeferred<Unit>()
                val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                    // Exercise the shared delivery boundary with a barrier so IO
                    // cannot finish before the caller has actually suspended.
                    store.deliverAsset {
                        startWrite.await()
                        operation()
                    }
                    delivered = true
                }
                startWrite.complete(Unit)
                // Let IO finish, but cancel before the caller dispatcher accepts the URI.
                val continuation = returns.poll(10, TimeUnit.SECONDS)
                assertNotNull("Writer did not return", continuation)
                job.cancel()
                continuation!!.run()
                job.join()
                assertFalse(delivered)
                val now = System.currentTimeMillis()
                val assets = File(root, "pin_assets").listFiles().orEmpty()
                assertEquals(1, assets.size)
                assertTrue(assets.single().setLastModified(now - 2 * DAY))
                assertEquals(1, store.mutate { store.sweep(emptySet(), now, DAY) })
            }
            val existing = store.copyFileToPin(source, "existing")
            val returns = LinkedBlockingQueue<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    returns.add(block)
                }
            }
            val startRead = CompletableDeferred<Unit>()
            val reader = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                store.deliverAsset(existing = existing) {
                    startRead.await()
                    store.ensureDurable(existing, "unused")
                }
            }
            startRead.complete(Unit)
            val continuation = returns.poll(10, TimeUnit.SECONDS)
            assertNotNull(continuation)
            reader.cancel()
            continuation!!.run()
            reader.join()
            val now = System.currentTimeMillis()
            assertTrue(File(existing.path!!).setLastModified(now - 2 * DAY))
            assertEquals(0, store.mutate { store.sweep(emptySet(), now, DAY) })
            assertFalse(bitmap.isRecycled)
            assertTrue(source.exists())
        } finally {
            bitmap.recycle()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
