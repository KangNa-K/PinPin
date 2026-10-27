package com.pinpin.app.ball

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class CarriedGallerySaveTest {
    @Test fun savesFullResolutionPixelsIntoPublishedPinpinAlbum() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "gallery-test-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var uri: android.net.Uri? = null
        try {
            uri = saveCarriedImageToGallery(context, source)
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("Pictures/pinpin/", it.getString(1))
            }
            val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
            try {
                assertEquals(360, decoded.width)
                assertEquals(240, decoded.height)
                assertEquals(Color.MAGENTA, decoded.getPixel(180, 120))
            } finally { decoded.recycle() }
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }
}
