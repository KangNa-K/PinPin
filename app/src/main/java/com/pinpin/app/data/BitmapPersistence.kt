package com.pinpin.app.data

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Process-wide encoding limit; keep lossless pixels and independent owned files. */
internal suspend fun writeLosslessPng(bitmap: Bitmap, target: File) {
    withPngEncoding {
        AssetFiles.writeAtomically(target) { output -> encodeLosslessPng(bitmap, output) }
    }
}

internal suspend fun <T> withPngEncoding(block: () -> T): T =
    bitmapEncodingMutex.withLock {
        withContext(Dispatchers.IO) { block() }
    }

internal fun encodeLosslessPng(bitmap: Bitmap, output: java.io.OutputStream) {
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
        throw IOException("Bitmap compression failed")
    }
}

private val bitmapEncodingMutex = Mutex()
