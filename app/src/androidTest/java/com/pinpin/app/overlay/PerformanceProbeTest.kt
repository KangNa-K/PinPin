package com.pinpin.app.overlay

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pinpin.app.data.PinAssetStore
import com.pinpin.app.data.PinDatabase
import com.pinpin.app.data.PinEntity
import com.pinpin.app.data.PinRepository
import com.pinpin.app.data.ScreenshotHistoryEntity
import com.pinpin.app.data.stopRepositoryCollectors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.ceil

/**
 * Diagnostic scaling probes, not release-device benchmarks or CI latency gates.
 * All data is synthetic and isolated from the application's normal database.
 */
class PerformanceProbeTest {
    private suspend fun measure(
        name: String,
        size: Int,
        samples: Int = 15,
        warmups: Int = 2,
        block: suspend () -> Unit,
    ) {
        repeat(warmups) { block() }
        val times = List(samples) {
            val start = SystemClock.elapsedRealtimeNanos()
            block()
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }
        val sorted = times.sorted()
        Log.i("PinPinPerf", JSONObject().apply {
            put("name", name)
            put("size", size)
            put("samples", samples)
            put("median_ms", sorted[sorted.size / 2])
            put("p95_ms", sorted[ceil(sorted.size * 0.95).toInt() - 1])
            put("max_ms", sorted.last())
            put("samples_ms", org.json.JSONArray(times))
        }.toString())
    }

    @Test fun databaseScaling() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (count in listOf(10, 100, 1000, 5000)) {
            val name = "performance-${UUID.randomUUID()}"
            val root = File(base.cacheDir, name).apply { mkdirs() }.canonicalFile
            val context = object : ContextWrapper(base) {
                override fun getFilesDir(): File = root
            }
            val db = Room.databaseBuilder(context, PinDatabase::class.java, name).build()
            var repository: PinRepository? = null
            try {
                val dao = db.pinDao()
                val pinDirectory = File(root, "pin_assets").apply { mkdirs() }
                val historyDirectory = File(root, "screenshot_history").apply { mkdirs() }
                db.withTransaction {
                    repeat(count) { index ->
                        val pinFile = File(pinDirectory, "original-$index.png").apply {
                            writeText("test")
                            PinAssetStore(context).committed(Uri.fromFile(this).toString())
                        }
                        val historyFile = File(historyDirectory, "history-$index.png").apply { writeText("test") }
                        dao.upsert(PinEntity(
                            id = "pin-$index", imageUri = Uri.fromFile(pinFile).toString(),
                            sourceType = "performance", x = 0, y = 0,
                            width = 200f, height = 200f, zOrder = index + 1,
                            createdAt = index.toLong(),
                        ))
                        db.historyDao().upsert(ScreenshotHistoryEntity(
                            id = "history-$index", filePath = historyFile.path,
                            width = 1080, height = 1920, sourceType = "performance",
                            createdAt = index.toLong(),
                        ))
                    }
                }
                val repo = PinRepository(context, dao, db, PinAssetStore(context))
                repository = repo
                measure("pins_sorted_read", count) {
                    assertEquals(count, dao.observeAll().first().size)
                }
                measure("history_sorted_read", count) {
                    assertEquals(count, db.historyDao().observeAll().first().size)
                }
                measure("asset_reference_scan", count) { repo.referencedAssetPaths() }
                val orphan = File(pinDirectory, "unreferenced.png").apply { writeText("test") }
                PinAssetStore(context).committed(Uri.fromFile(orphan).toString())
                measure("targeted_unreferenced_asset_check", count) {
                    assertFalse(repo.isAssetReferenced(orphan))
                }
                val referenced = File(pinDirectory, "original-0.png")
                measure("targeted_referenced_asset_check", count) {
                    assertTrue(repo.isAssetReferenced(referenced))
                }
                var position = 0
                measure("geometry_transaction", count) {
                    repo.updateGeometry("pin-0", position++, 10, 200f, 200f)
                }
                measure("renormalize_already_ordered", count, samples = 7, warmups = 1) {
                    repo.renormalizeZOrders()
                }
                val queryPlans = listOf(
                    "SELECT * FROM pins ORDER BY zOrder ASC, createdAt ASC",
                    "SELECT * FROM screenshot_history ORDER BY createdAt DESC",
                ).map { sql ->
                    db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(cursor.getString(3))
                        }.joinToString("; ")
                    }
                }
                Log.i("PinPinPerf", JSONObject().apply {
                    put("name", "query_plans")
                    put("size", count)
                    put("plans", org.json.JSONArray(queryPlans))
                }.toString())
            } finally {
                repository?.let { stopRepositoryCollectors(it) }
                db.close()
                base.deleteDatabase(name)
                root.deleteRecursively()
            }
        }
    }

    @Test fun largeImageRenderingAndPersistence() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "performance-images-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        val store = PinAssetStore(context)
        try {
            for ((width, height) in listOf(1080 to 1920, 3840 to 2160)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    // High-entropy synthetic pixels expose compression cost hidden by solid images.
                    val random = java.util.Random(42)
                    val row = IntArray(width)
                    repeat(height) { y ->
                        repeat(width) { x -> row[x] = random.nextInt() or (0xff shl 24) }
                        bitmap.setPixels(row, 0, width, 0, y, width, 1)
                    }
                    Log.i("PinPinPerf", JSONObject().apply {
                        put("name", "image_allocation")
                        put("width", width)
                        put("height", height)
                        put("source_bytes", bitmap.allocationByteCount)
                        put("source_plus_render_bytes", bitmap.allocationByteCount.toLong() * 2)
                    }.toString())
                    measure("image_rotate_flip", width * height, samples = 7, warmups = 1) {
                        val output = PinImageSnapshot(bitmap, 90, true).render()
                        try {
                            assertEquals(height, output.width)
                            assertEquals(width, output.height)
                        } finally { output.recycle() }
                    }
                    measure("png_atomic_save", width * height, samples = 7, warmups = 1) {
                        val uri = store.saveBitmap(bitmap, "performance")
                        val file = File(requireNotNull(uri.path))
                        assertTrue(file.length() > 0)
                        store.committed(uri.toString())
                        assertTrue(file.delete())
                    }
                    assertFalse(bitmap.isRecycled)
                } finally { bitmap.recycle() }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun actualEmulatorScreenshotPersistence() = runBlocking {
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "performance-screenshot-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        val store = PinAssetStore(context)
        try {
            measure("actual_emulator_screenshot_png", screenshot.width * screenshot.height, samples = 7, warmups = 1) {
                val uri = store.saveBitmap(screenshot, "performance-screen")
                val file = File(requireNotNull(uri.path))
                assertTrue(file.length() > 0)
                store.committed(uri.toString())
                assertTrue(file.delete())
            }
        } finally { screenshot.recycle(); root.deleteRecursively() }
    }

    @Test fun annotationDrawScaling() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (count in listOf(10, 100, 1000)) {
            lateinit var view: DrawingOverlayView
            val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            try {
                instrumentation.runOnMainSync {
                    view = DrawingOverlayView(ApplicationProvider.getApplicationContext())
                    view.layout(0, 0, 1080, 1920)
                    view.shapeRecognitionEnabled = false
                    repeat(count) { stroke ->
                        val time = SystemClock.uptimeMillis()
                        for (point in 0..20) {
                            val action = when (point) {
                                0 -> MotionEvent.ACTION_DOWN
                                20 -> MotionEvent.ACTION_UP
                                else -> MotionEvent.ACTION_MOVE
                            }
                            val event = MotionEvent.obtain(
                                time, time + point, action,
                                20f + point * 30, 20f + (stroke % 80) * 20 + point, 0,
                            )
                            try { view.onTouchEvent(event) } finally { event.recycle() }
                        }
                    }
                    assertTrue(view.hasStrokes())
                }
                // Timing stays inside the main-thread callback, excluding dispatch wait.
                val times = mutableListOf<Double>()
                instrumentation.runOnMainSync {
                    val canvas = Canvas(bitmap)
                    repeat(3) { view.draw(canvas) }
                    repeat(30) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        view.draw(canvas)
                        times.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                    }
                }
                val sorted = times.sorted()
                Log.i("PinPinPerf", JSONObject().apply {
                    put("name", "annotation_software_draw")
                    put("size", count)
                    put("points_per_stroke", 21)
                    put("samples", times.size)
                    put("median_ms", sorted[sorted.size / 2])
                    put("p95_ms", sorted[ceil(sorted.size * 0.95).toInt() - 1])
                    put("max_ms", sorted.last())
                    put("samples_ms", org.json.JSONArray(times))
                }.toString())
            } finally { bitmap.recycle() }
        }
    }

    @Test fun annotationExitMainThreadLatency() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (size in listOf(512, 2048)) {
            val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val times = mutableListOf<Double>()
            try {
                repeat(8) { sample ->
                    lateinit var view: PinWindowView
                    var saved = false
                    instrumentation.runOnMainSync {
                        view = PinWindowView(
                            ApplicationProvider.getApplicationContext(), "performance-exit",
                            PinWindowView.PinSpec(
                                label = "performance", imageUri = null, sourceType = "performance",
                                initialX = 20, initialY = 20, widthPx = 200, heightPx = 300,
                                locked = false, zOrder = 1,
                            ),
                            onPositionChanged = { _, _ -> }, onSizeChanged = { _, _ -> },
                            onCloseClicked = {}, onActionClicked = {},
                        )
                        val field = PinWindowView::class.java.getDeclaredField("sourceBitmap")
                        field.isAccessible = true
                        field.set(view, source)
                        descendants(view).filterIsInstance<ImageView>()
                            .first { it !is ImageButton }.setImageBitmap(source)
                        view.onAnnotationDone = { saved = true; it.recycle() }
                        view.enterAnnotationMode()
                        val canvas = descendants(view).filterIsInstance<DrawingOverlayView>().single()
                        canvas.layout(0, 0, 1080, 1920)
                        canvas.shapeRecognitionEnabled = false
                        repeat(100) { stroke ->
                            for (point in 0..20) {
                                val action = when (point) {
                                    0 -> MotionEvent.ACTION_DOWN
                                    20 -> MotionEvent.ACTION_UP
                                    else -> MotionEvent.ACTION_MOVE
                                }
                                val event = MotionEvent.obtain(
                                    0, point.toLong(), action,
                                    20f + point * 30, 20f + stroke * 10, 0,
                                )
                                try { canvas.onTouchEvent(event) } finally { event.recycle() }
                            }
                        }
                        val start = SystemClock.elapsedRealtimeNanos()
                        view.exitAnnotationMode()
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        assertFalse(view.isAnnotationMode())
                        if (sample > 0) times.add(elapsed)
                        view.animate().cancel()
                    }
                    runBlocking { view.awaitAnnotationRendering() }
                    assertTrue(saved)
                }
                val sorted = times.sorted()
                Log.i("PinPinPerf", JSONObject().apply {
                    put("name", "annotation_exit_main_thread")
                    put("source_width", size)
                    put("source_height", size)
                    put("strokes", 100)
                    put("canvas_width", 1080)
                    put("canvas_height", 1920)
                    put("samples", times.size)
                    put("median_ms", sorted[sorted.size / 2])
                    put("p95_ms", sorted.last())
                    put("samples_ms", org.json.JSONArray(times))
                    put("warning", "Unattached synthetic view; excludes real compositor and PNG save.")
                }.toString())
            } finally { source.recycle() }
        }
    }

    private fun descendants(parent: ViewGroup): List<View> = buildList {
        repeat(parent.childCount) { index ->
            val child = parent.getChildAt(index)
            add(child)
            if (child is ViewGroup) addAll(descendants(child))
        }
    }
}
