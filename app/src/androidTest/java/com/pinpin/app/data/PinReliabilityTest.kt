package com.pinpin.app.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pinpin.app.capture.CaptureBounds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PinReliabilityTest {
    private fun pin(id: String, uri: String, annotation: String? = null) =
        PinEntity(id, uri, "test", 0, 0, 10f, 10f, annotatedImageUri = annotation)

    @Test fun freshCapturePreservesClippedScreenBoundsInsteadOfGridOrSizeCap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PinDatabase::class.java).build()
        val repo = PinRepository(context, db.pinDao(), db, PinAssetStore(context))
        try {
            val largeSide = (600 * context.resources.displayMetrics.density).toInt()
            val bounds = CaptureBounds(230, 410, 230 + largeSide, 410 + largeSide)
            repeat(2) {
                repo.addPinFromUri(
                    Uri.parse("pinpin://capture-$it"), "ball-screencap",
                    bounds.width, bounds.height, initialBounds = bounds,
                )
            }
            db.pinDao().getAll().forEach { pin ->
                assertEquals(bounds.left, pin.x)
                assertEquals(bounds.top, pin.y)
                assertEquals(bounds.width.toFloat(), pin.width, 0f)
                assertEquals(bounds.height.toFloat(), pin.height, 0f)
            }
            val clipped = CaptureBounds(-20, -30, 200, 300).intersect(1080, 1920)!!
            repo.addPinFromUri(
                Uri.parse("pinpin://clipped"), "ball-screencap",
                clipped.width, clipped.height, initialBounds = clipped,
            )
            val pin = db.pinDao().getAll().single { it.imageUri == "pinpin://clipped" }
            assertEquals(0, pin.x)
            assertEquals(0, pin.y)
            assertEquals(200f, pin.width, 0f)
            assertEquals(300f, pin.height, 0f)
        } finally {
            stopRepositoryCollectors(repo)
            db.close()
        }
    }

    @Test fun importedImagesRetainDefaultGridPlacementAndSizeCap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PinDatabase::class.java).build()
        val repo = PinRepository(context, db.pinDao(), db, PinAssetStore(context))
        try {
            val density = context.resources.displayMetrics.density
            val side = (600 * density).toInt()
            val cap = (480 * density).toInt()
            val margin = (24 * density).toInt()
            repeat(2) {
                repo.addPinFromUri(Uri.parse("pinpin://import-$it"), "photopicker", side, side)
            }
            val pins = db.pinDao().getAll().sortedBy { it.zOrder }
            pins.forEach { pin ->
                assertEquals(cap.toFloat(), pin.width, 0f)
                assertEquals(cap.toFloat(), pin.height, 0f)
                assertEquals(margin, pin.y)
            }
            assertEquals(margin, pins[0].x)
            assertEquals(margin + cap / 3, pins[1].x)
        } finally {
            stopRepositoryCollectors(repo)
            db.close()
        }
    }

    @Test fun deletionAndReplacementKeepAllSharedAndHistoryReferences() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "data-test-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        val db = Room.inMemoryDatabaseBuilder(context, PinDatabase::class.java).build()
        val store = PinAssetStore(context)
        val repo = PinRepository(context, db.pinDao(), db, store)
        var history: ScreenshotHistoryRepository? = null
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val shared = try {
                store.saveBitmap(bitmap).also { assertFalse(bitmap.isRecycled) }
            } finally {
                bitmap.recycle()
            }
            val file = File(shared.path!!)
            db.pinDao().upsert(pin("a", shared.toString()))
            db.pinDao().upsert(pin("b", "pinpin://test", shared.toString()))
            repo.removePin("a")
            assertTrue(file.exists())
            db.historyDao().upsert(ScreenshotHistoryEntity("h", file.path, 2, 2, "test"))
            assertTrue(repo.updateAnnotatedImage("b", null))
            assertTrue(file.exists())
            db.historyDao().deleteById("h")
            repo.updateImageUri("b", shared.toString())
            repo.removePin("b")
            assertFalse(file.exists())
            assertFalse("Deleted pin must not report an annotation commit", repo.updateAnnotatedImage("b", null))

            val external = File(root, "user.png").apply { writeText("user") }
            db.pinDao().upsert(pin("external", Uri.fromFile(external).toString()))
            repo.removePin("external")
            assertTrue(external.exists())

            repo.updateGeometry("external", 1, 2, 3f, 4f) // Deleted row remains absent.
            assertNull(db.pinDao().findById("external"))
            db.pinDao().upsert(pin("geometry", "pinpin://test"))
            repo.updateGeometry("geometry", 11, 22, 33f, 44f)
            val geometry = db.pinDao().findById("geometry")!!
            assertEquals(11, geometry.x)
            assertEquals(22, geometry.y)
            assertEquals(33f, geometry.width, 0f)
            assertEquals(44f, geometry.height, 0f)

            val orphan = store.newPinFile("orphan").apply { writeText("orphan") }
            val pending = store.newPinFile("pending").apply { writeText("pending") }
            val arbitrary = File(orphan.parentFile, "user.png").apply { writeText("user") }
            val old = System.currentTimeMillis() - 10 * 24 * 60 * 60 * 1000L
            listOf(orphan, pending, arbitrary).forEach { assertTrue(it.setLastModified(old)) }
            store.committed(Uri.fromFile(orphan).toString())
            assertEquals(1, repo.cleanupOrphanAssets())
            assertFalse(orphan.exists())
            assertTrue(pending.exists())
            assertTrue(arbitrary.exists())

            // A SQLite abort leaves the database readable for compensation.
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_history BEFORE INSERT ON screenshot_history " +
                    "BEGIN SELECT RAISE(ABORT, 'injected'); END",
            )
            val historyRepository = ScreenshotHistoryRepository(context, db.historyDao(), repo, store)
            history = historyRepository
            val failedBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                historyRepository.addFromBitmap(failedBitmap, "test")
                fail("Expected database failure")
            } catch (_: android.database.sqlite.SQLiteException) {}
            assertTrue(failedBitmap.isRecycled)
            assertTrue(File(root, "screenshot_history").listFiles().orEmpty().isEmpty())
        } finally {
            history?.let { stopRepositoryCollectors(it) }
            stopRepositoryCollectors(repo)
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun concurrentReadModifyWritesDoNotLoseUpdates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PinDatabase::class.java).build()
        val repo = PinRepository(context, db.pinDao(), db, PinAssetStore(context))
        try {
            val original = pin("a", "pinpin://test").copy(x = -31, y = 17, width = 401f, height = 200f)
            db.pinDao().upsert(original)
            coroutineScope {
                (1..21).map { async { repo.rotateClockwise("a"); repo.toggleFlip("a") } }.awaitAll()
            }
            assertEquals(90, db.pinDao().findById("a")!!.rotation)
            assertTrue(db.pinDao().findById("a")!!.flipped)
            val rotated = db.pinDao().findById("a")!!
            assertEquals(200f, rotated.width, 0f)
            assertEquals(401f, rotated.height, 0f)
            assertEquals(69, rotated.x)
            assertEquals(-83, rotated.y)
            coroutineScope {
                (1..10).map {
                    async {
                        val id = "z$it"
                        db.pinDao().upsert(pin(id, "pinpin://test"))
                        repo.bringToFront(id)
                    }
                }.awaitAll()
            }
            val orders = db.pinDao().getAll().filter { it.id != "a" }.map { it.zOrder }
            assertEquals(10, orders.toSet().size)
        } finally {
            stopRepositoryCollectors(repo)
            db.close()
        }
    }

    @Test fun rotationUsesLatestGeometryAndFourTurnsRestoreBounds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PinDatabase::class.java).build()
        val repo = PinRepository(context, db.pinDao(), db, PinAssetStore(context))
        try {
            val original = pin("rotation", "pinpin://test").copy(
                x = -31, y = 17, width = 401f, height = 200f,
            )
            db.pinDao().upsert(original)
            repeat(4) { index ->
                val rotated = repo.rotateClockwise(original.id)!!
                assertEquals(((index + 1) * 90) % 360, rotated.rotation)
                assertEquals(rotated, db.pinDao().findById(original.id))
                assertEquals(if (index % 2 == 0) 200f else 401f, rotated.width, 0f)
                assertEquals(if (index % 2 == 0) 401f else 200f, rotated.height, 0f)
            }
            assertEquals(original, db.pinDao().findById(original.id))

            repo.updateGeometry(original.id, 150, -20, 600f, 300f)
            val resized = repo.rotateClockwise(original.id)!!
            assertEquals(300, resized.x)
            assertEquals(-170, resized.y)
            assertEquals(300f, resized.width, 0f)
            assertEquals(600f, resized.height, 0f)
            assertNull(repo.rotateClockwise("deleted"))
        } finally {
            stopRepositoryCollectors(repo)
            db.close()
        }
    }

    @Test fun cancelledLockWaitDoesNotStartMigrationAndCanRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PinDatabase::class.java).build()
        val store = PinAssetStore(context)
        val repo = PinRepository(context, db.pinDao(), db, store)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch {
                store.mutate {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val cancelled = launch { repo.migrateLegacyAssets() }
            cancelled.cancelAndJoin()
            release.complete(Unit)
            holder.join()
            assertFalse(repo.migrateLegacyAssets())
        } finally {
            stopRepositoryCollectors(repo)
            db.close()
        }
    }
}
