package com.pinpin.app.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.content.ContentResolver
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bound transient render allocations across simultaneous pin exports and saves. */
internal object ImageRenderWork {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        withContext(Dispatchers.Default) { block() }
    }
}

internal object AnnotationSaveState {
    private val pins = ConcurrentHashMap.newKeySet<String>()
    fun begin(pinId: String): Boolean = pins.add(pinId)
    fun end(pinId: String) { pins.remove(pinId) }
    fun isSaving(pinId: String): Boolean = pinId in pins
}

/** Export persisted pixels, not Coil's downsampled display bitmap. Caller owns the result. */
internal data class PersistedPinImageSnapshot(
    val uri: Uri,
    val rotation: Int,
    val flipped: Boolean,
) {
    suspend fun render(resolver: ContentResolver): Bitmap {
        val source = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("Cannot decode export image")
        }
        if (rotation % 360 == 0 && !flipped) return source
        try {
            return PinImageSnapshot(source, rotation, flipped).render()
        } finally {
            source.recycle()
        }
    }
}

internal class AnnotationSnapshot(
    private val source: Bitmap,
    private val drawing: DrawingSnapshot,
    private val inverseValues: FloatArray,
    private val drawableWidth: Int,
    private val drawableHeight: Int,
) {
    fun render(): Bitmap {
        val ink = drawing.render()
        var output: Bitmap? = null
        try {
            val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            output = bitmap
            Canvas(bitmap).apply {
                drawBitmap(source, 0f, 0f, null)
                scale(source.width.toFloat() / drawableWidth, source.height.toFloat() / drawableHeight)
                concat(Matrix().apply { setValues(inverseValues) })
                drawBitmap(ink, 0f, 0f, null)
            }
            return bitmap
        } catch (failure: Throwable) {
            output?.recycle()
            throw failure
        } finally {
            ink.recycle()
        }
    }
}

/** Captured on main; rendering does not read mutable View state. */
internal data class PinImageSnapshot(val source: Bitmap, val rotation: Int, val flipped: Boolean) {
    fun render(): Bitmap {
        val quarterTurn = rotation % 180 != 0
        val width = if (quarterTurn) source.height else source.width
        val height = if (quarterTurn) source.width else source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val matrix = Matrix().apply {
                setValues(PinImageTransform.values(
                    source.width, source.height, width, height, rotation, flipped, false,
                ))
            }
            Canvas(output).drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }
}
