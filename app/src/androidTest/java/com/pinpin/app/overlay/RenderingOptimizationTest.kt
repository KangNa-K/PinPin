package com.pinpin.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import com.pinpin.app.data.writeLosslessPng
import java.io.File
import java.util.UUID
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RenderingOptimizationTest {
    @Test fun persistedExportPreservesOriginalDimensionsAndAlpha(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir.canonicalFile, "export-test-${UUID.randomUUID()}.png")
        val original = Bitmap.createBitmap(4096, 8, Bitmap.Config.ARGB_8888)
        original.eraseColor(0x804080C0.toInt())
        try {
            writeLosslessPng(original, file)
            val snapshot = PersistedPinImageSnapshot(Uri.fromFile(file), 0, false)
            val exported = ImageRenderWork.run { snapshot.render(context.contentResolver) }
            try {
                assertEquals(4096, exported.width)
                assertEquals(8, exported.height)
                assertTrue("Lossless export must keep alpha and pixels", original.sameAs(exported))
            } finally { exported.recycle() }
            val rotated = ImageRenderWork.run {
                snapshot.copy(rotation = 90, flipped = true).render(context.contentResolver)
            }
            try {
                assertEquals(8, rotated.width)
                assertEquals(4096, rotated.height)
                assertEquals(original.getPixel(0, 0), rotated.getPixel(0, 0))
            } finally { rotated.recycle() }
            assertFalse(original.isRecycled)
        } finally {
            original.recycle()
            file.delete()
        }
    }

    @Test fun cachedLayerMatchesExportAcrossUndoRedoEditAndEraser() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = DrawingOverlayView(ApplicationProvider.getApplicationContext<Context>())
            view.layout(0, 0, 200, 200)
            val field = DrawingOverlayView::class.java.getDeclaredField("strokes").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val strokes = field.get(view) as MutableList<DrawingOverlayView.Stroke>
            fun stroke(mode: DrawingOverlayView.Mode, color: Int = Color.RED) =
                DrawingOverlayView.Stroke(
                    listOf(DrawingOverlayView.Point(20f, 50f, 1f), DrawingOverlayView.Point(160f, 100f, 0.5f)),
                    color, mode, DrawingOverlayView.BrushStyle.PEN, 12f,
                )
            fun checkPixels() {
                val displayed = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
                val exported = view.renderToBitmap()
                try {
                    view.draw(Canvas(displayed))
                    assertTrue("Cached display differs from lossless export", displayed.sameAs(exported))
                    displayed.eraseColor(Color.TRANSPARENT)
                    view.draw(Canvas(displayed))
                    assertTrue("Repeated redraw changed pixels", displayed.sameAs(exported))
                } finally { displayed.recycle(); exported.recycle() }
            }
            strokes.add(stroke(DrawingOverlayView.Mode.PEN))
            checkPixels()
            strokes.add(stroke(DrawingOverlayView.Mode.PEN, Color.BLUE).copy(
                brushStyle = DrawingOverlayView.BrushStyle.HIGHLIGHTER,
            ))
            checkPixels()
            strokes.add(stroke(DrawingOverlayView.Mode.ARROW, Color.GREEN))
            checkPixels()
            strokes.add(stroke(DrawingOverlayView.Mode.ERASER))
            checkPixels()
            view.undo()
            checkPixels()
            view.redo()
            checkPixels()
            repeat(70) { index ->
                strokes.add(stroke(DrawingOverlayView.Mode.PEN, Color.BLUE).copy(
                    points = listOf(
                        DrawingOverlayView.Point(20f, 10f + index * 2, 0.3f),
                        DrawingOverlayView.Point(150f, 20f + index * 2, 0.8f),
                    ),
                ))
            }
            checkPixels()
            repeat(12) { view.undo() }
            checkPixels()
            repeat(12) { view.redo() }
            checkPixels()
            strokes[0] = strokes[0].copy(points = listOf(
                DrawingOverlayView.Point(10f, 120f, 0.2f), DrawingOverlayView.Point(180f, 120f, 1f),
            ))
            checkPixels()
            view.layout(0, 0, 180, 180)
            val resized = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
            val exported = view.renderToBitmap()
            try {
                view.draw(Canvas(resized))
                assertTrue(resized.sameAs(exported))
            } finally { resized.recycle(); exported.recycle() }
            view.clear()
            assertFalse(view.hasStrokes())
        }
    }

    @Test fun detachedDrawingSnapshotRetainsOnlyCommittedStrokeState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingOverlayView
        lateinit var snapshot: DrawingSnapshot
        instrumentation.runOnMainSync {
            view = DrawingOverlayView(ApplicationProvider.getApplicationContext())
            view.layout(0, 0, 100, 100)
            val field = DrawingOverlayView::class.java.getDeclaredField("strokes").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val strokes = field.get(view) as MutableList<DrawingOverlayView.Stroke>
            strokes.add(DrawingOverlayView.Stroke(
                listOf(DrawingOverlayView.Point(10f, 50f, 1f), DrawingOverlayView.Point(90f, 50f, 1f)),
                Color.RED, DrawingOverlayView.Mode.PEN, DrawingOverlayView.BrushStyle.PEN, 6f,
            ))
            snapshot = view.snapshot()
            view.clear()
        }
        val output = snapshot.render()
        try {
            assertEquals(Color.RED, output.getPixel(50, 50))
            assertEquals(Color.TRANSPARENT, output.getPixel(50, 10))
        } finally { output.recycle() }
    }

    @Test fun imagePipelineSerializesAcrossSuspensionAndCancelledQueue() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            ImageRenderWork.run { entered.complete(Unit); release.await() }
        }
        entered.await()
        var secondRan = false
        val second = launch { ImageRenderWork.run { secondRan = true } }
        second.cancelAndJoin()
        assertFalse(secondRan)
        release.complete(Unit)
        first.join()
        ImageRenderWork.run { assertFalse(secondRan) }
    }

    @Test fun annotationRendererPreservesTransparentInkAndReleasesTemporaryPixels() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        val stroke = DrawingOverlayView.Stroke(
            listOf(DrawingOverlayView.Point(10f, 50f, 1f), DrawingOverlayView.Point(90f, 50f, 1f)),
            Color.RED, DrawingOverlayView.Mode.PEN, DrawingOverlayView.BrushStyle.PEN, 6f,
        )
        val inverse = FloatArray(9).also { Matrix().getValues(it) }
        try {
            val output = AnnotationSnapshot(source, DrawingSnapshot(100, 100, listOf(stroke)), inverse, 100, 100).render()
            try {
                assertEquals(Color.RED, output.getPixel(50, 50))
                assertEquals(Color.BLUE, output.getPixel(50, 10))
                assertFalse(source.isRecycled)
            } finally { output.recycle() }
        } finally { source.recycle() }
    }
}
