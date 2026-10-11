package com.pinpin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BitmapPersistenceTest {
    @Test fun concurrentPngWritesPreserveAlphaPixelsAndSourceOwnership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "bitmap-performance-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.setPixel(1, 1, Color.RED)
        bitmap.setPixel(2, 2, Color.argb(128, 0, 128, 255))
        try {
            (0..3).map { index ->
                async {
                    val target = File(root, "$index.png")
                    writeLosslessPng(bitmap, target)
                    val decoded = requireNotNull(BitmapFactory.decodeFile(target.path))
                    try { assertTrue(decoded.sameAs(bitmap)) } finally { decoded.recycle() }
                }
            }.awaitAll()
            assertFalse(bitmap.isRecycled)
            assertEquals(4, root.listFiles().orEmpty().size)
        } finally { bitmap.recycle(); root.deleteRecursively() }
    }
}
