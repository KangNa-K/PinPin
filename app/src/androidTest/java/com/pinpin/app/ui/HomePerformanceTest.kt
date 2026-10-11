package com.pinpin.app.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pinpin.app.data.PinAssetStore
import com.pinpin.app.data.PinDatabase
import com.pinpin.app.data.PinRepository
import com.pinpin.app.data.ScreenshotHistoryEntity
import com.pinpin.app.data.ScreenshotHistoryRepository
import com.pinpin.app.data.stopRepositoryCollectors
import com.pinpin.app.ui.theme.PinPinTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class HomePerformanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thousandHistoryThumbnailsScrollWithoutFailure(): Unit = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = File(application.cacheDir, "performance-home-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
        }
        val database = Room.inMemoryDatabaseBuilder(context, PinDatabase::class.java).build()
        val store = PinAssetStore(context)
        val pins = PinRepository(context, database.pinDao(), database, store)
        val history = ScreenshotHistoryRepository(context, database.historyDao(), pins, store)
        val viewModel = HomeViewModel(application, pins, history, store)
        try {
            val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val file = File(root, "thumbnail.png")
            try {
                image.eraseColor(android.graphics.Color.GREEN)
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { image.recycle() }
            database.withTransaction {
                repeat(1000) { index ->
                    database.historyDao().upsert(ScreenshotHistoryEntity(
                        id = "performance-$index", filePath = file.path,
                        width = 256, height = 256, sourceType = "performance",
                        createdAt = index.toLong(),
                    ))
                }
            }
            shell("appops set ${application.packageName} SYSTEM_ALERT_WINDOW default")
            compose.setContent { PinPinTheme { HomeScreen(viewModel) } }
            compose.waitUntil(15_000) { history.historyState.value.size == 1000 }
            compose.onNodeWithText("历史 (1000)").performClick()
            compose.waitForIdle()
            val grid = compose.onNode(hasScrollAction())
            val times = mutableListOf<Double>()
            for (index in listOf(0, 250, 500, 750, 999, 0, 500, 999, 0)) {
                val start = SystemClock.elapsedRealtimeNanos()
                grid.performScrollToIndex(index)
                compose.waitForIdle()
                times.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
            }
            grid.performScrollToIndex(0)
            compose.waitForIdle()
            shell("dumpsys gfxinfo ${application.packageName} reset")
            repeat(12) {
                grid.performTouchInput { swipeUp(durationMillis = 400) }
                compose.waitForIdle()
            }
            val frames = shell("dumpsys gfxinfo ${application.packageName} framestats")
            File(application.cacheDir, "pinpin-performance-home-frames.txt").writeText(frames)
            android.util.Log.i("PinPinPerf", JSONObject().apply {
                put("name", "home_history_scroll")
                put("size", 1000)
                put("scroll_to_index_and_idle_ms", org.json.JSONArray(times))
                put("swipes", 12)
                put("frame_summary", frames.substringBefore("---PROFILEDATA---").take(1800))
                put("warning", "Debug emulator; shared 256px thumbnail, cache-friendly; not 1000 unique large images.")
            }.toString())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.viewModelScope.cancel()
            }
            stopRepositoryCollectors(history)
            stopRepositoryCollectors(pins)
            database.close()
            root.deleteRecursively()
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
}
