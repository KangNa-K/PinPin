package com.pinpin.app.ball

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.provider.MediaStore
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Owns the carried file separately from deletable screenshot history and temporary URI grants. */
internal class BallCarryController(
    private val context: Context,
    private val ball: BallView,
    private val manager: WindowManager,
    private val message: (String) -> Unit,
    private val openApp: () -> Unit,
    private val preferencesName: String = "ball_carry",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private var file: File? = null
    private var dialog: AlertDialog? = null
    private var animation: ValueAnimator? = null
    private var flight: View? = null
    private var generation = 0
    private var preparing = false
    private var dragging = false
    private var readOnlyExport: File? = null

    init {
        ball.onCarryDrag = { startDrag(it) }
        ball.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> dragging
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (dragging) {
                        dragging = false
                        ball.resetToIdle(park = false)
                        if (event.result) {
                            clear(notify = false)
                            ball.attachedParams?.let(ball::snapToEdge)
                            message("目标应用已接收，已清空悬浮球；请在目标应用确认导入结果")
                        } else message("未接收，图片仍在悬浮球中，可重试或保存到相册")
                    }
                    true
                }
                else -> dragging
            }
        }
        val name = preferences.getString("file", null)
        if (name != null && name.matches(Regex("[a-f0-9-]+\\.png"))) {
            val existing = File(context.filesDir, "carried_images/$name")
            if (existing.isFile) load(existing, null, false)
        }
    }

    val hasImage: Boolean get() = file != null
    val isBusy: Boolean get() = preparing || dragging

    fun accept(history: File, bounds: Rect?) = load(history, bounds, true)

    private fun load(source: File, bounds: Rect?, copy: Boolean) {
        val token = ++generation
        preparing = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val owned = if (copy) File(context.filesDir, "carried_images/${UUID.randomUUID()}.png").also {
                        it.parentFile?.mkdirs()
                        try { source.inputStream().use { input -> it.outputStream().use(input::copyTo) } }
                        catch (failure: Exception) { it.delete(); throw failure }
                    } else source
                    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(owned.path, dimensions)
                    require(dimensions.outWidth > 0 && dimensions.outHeight > 0)
                    var sample = 1
                    while (maxOf(dimensions.outWidth, dimensions.outHeight) / sample > 768) sample *= 2
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(owned.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                    // A unique exported copy remains readable if the carried image is replaced later.
                    val exported = File(context.cacheDir, "exports/carry-${UUID.randomUUID()}.png")
                    exported.parentFile?.mkdirs()
                    exported.parentFile?.listFiles()?.filter {
                        it.name.startsWith("carry-") && it.extension == "png" &&
                            System.currentTimeMillis() - it.lastModified() > 7L * 24 * 60 * 60 * 1000
                    }?.forEach { it.delete() }
                    try { owned.copyTo(exported); Triple(owned, bitmap, exported) }
                    catch (failure: Exception) { bitmap.recycle(); exported.delete(); throw failure }
                }
                if (generation != token) { result.second.recycle(); return@launch }
                cancelFlight()
                val previous = file
                file = result.first
                readOnlyExport = result.third
                preferences.edit().putString("file", result.first.name).apply()
                if (previous != null && previous != result.first) scope.launch(Dispatchers.IO) { previous.delete() }
                ball.setCarriedBitmap(result.second)
                ball.alpha = 1f
                ball.finishCaptureGesture()
                if (bounds != null) animateIntoBall(result.second, bounds)
                if (copy) message("已吸入图片，保持按住可直接拖动导入；接收成功后自动清空")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                ball.finishCaptureGesture()
                ball.alpha = 1f
                message("图片未能装入悬浮球，原图仍保留在截图历史中")
            } finally {
                if (generation == token) {
                    preparing = false
                    ball.resumeCarryDragIfMoved()
                }
            }
        }
    }

    private fun startDrag(source: View): Boolean {
        val exported = readOnlyExport ?: return false
        if (preparing || dragging || !exported.isFile) return false
        cancelFlight()
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exported)
            val clip = ClipData.newUri(context.contentResolver, "PinPin screenshot", uri)
            dragging = true
            val started = source.startDragAndDrop(clip, View.DragShadowBuilder(source), null,
                View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
            if (started) ball.alpha = 0f else {
                dragging = false
                message("系统未能开始拖放；轻点悬浮球可分享或保存")
            }
            started
        } catch (_: Exception) {
            dragging = false
            ball.alpha = 1f
            message("无法开始跨应用拖放；轻点悬浮球可分享或保存")
            false
        }
    }

    fun showActions() {
        if (preparing || dragging || file == null) return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(context).setTitle("悬浮球中的图片")
            .setItems(arrayOf("保存到相册", "分享图片", "移动悬浮球", "清空携带图片", "打开钉图")) { _, choice ->
                when (choice) {
                    0 -> saveToGallery()
                    1 -> share()
                    2 -> { ball.moveOnly = true; message("下一次拖动仅移动悬浮球，不导出图片") }
                    3 -> clear()
                    4 -> openApp()
                }
            }.setNegativeButton("取消", null).create().also {
                it.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                it.show()
            }
    }

    private fun saveToGallery() {
        val source = file ?: return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    saveCarriedImageToGallery(context, source)
                }.isSuccess
            }
            if (saved && file == source) clear(notify = false)
            message(if (saved) "已保存到相册 · Pictures/pinpin，已清空悬浮球" else "保存失败，图片仍在悬浮球中")
        }
    }

    private fun share() {
        val exported = readOnlyExport ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exported)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "PinPin screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享截图").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { message("无法打开分享，图片仍保留") }
    }

    private fun clear(notify: Boolean = true) {
        generation++
        preparing = false
        cancelFlight()
        ball.resetToIdle(park = false)
        val previous = file
        file = null
        readOnlyExport = null
        preferences.edit().remove("file").apply()
        ball.moveOnly = false
        ball.setCarriedBitmap(null)
        scope.launch(Dispatchers.IO) { previous?.delete() }
        if (notify) message("已清空携带图片，截图历史仍保留")
    }

    private fun animateIntoBall(bitmap: Bitmap, bounds: Rect) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val location = IntArray(2)
        ball.getLocationOnScreen(location)
        val endX = location[0] + ball.width / 2f
        val endY = location[1] + ball.height / 2f
        val side = ball.width * 0.78f
        var progress = 0f
        val rect = RectF()
        val path = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val view = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                val t = progress
                val cx = bounds.exactCenterX() * (1 - t) + endX * t
                val cy = bounds.exactCenterY() * (1 - t) + endY * t - 80 * resources.displayMetrics.density * t * (1 - t)
                val w = bounds.width() * (1 - t) + side * t
                val h = bounds.height() * (1 - t) + side * t
                getLocationOnScreen(location)
                rect.set(cx - w / 2 - location[0], cy - h / 2 - location[1], cx + w / 2 - location[0], cy + h / 2 - location[1])
                path.reset()
                path.addRoundRect(rect, side * t / 2, side * t / 2, Path.Direction.CW)
                val save = canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(bitmap, null, rect, paint)
                canvas.restoreToCount(save)
            }
        }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        if (runCatching { manager.addView(view, params) }.isFailure) return
        flight = view
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = PathInterpolator(0.77f, 0f, 0.175f, 1f)
            addUpdateListener { progress = it.animatedValue as Float; view.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    runCatching { manager.removeViewImmediate(view) }
                    if (flight === view) flight = null
                }
            })
            start()
        }
    }

    private fun cancelFlight() {
        animation?.cancel()
        animation = null
        flight?.let { runCatching { manager.removeViewImmediate(it) } }
        flight = null
    }

    fun close() {
        generation++
        scope.cancel()
        dialog?.dismiss()
        cancelFlight()
        ball.setOnDragListener(null)
        ball.onCarryDrag = null
    }
}

/** Pending MediaStore rows are never exposed if copying or publication fails. */
internal fun saveCarriedImageToGallery(context: Context, source: File): android.net.Uri {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "pinpin-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/pinpin")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        checkNotNull(resolver.openOutputStream(uri)).use { output -> source.inputStream().use { it.copyTo(output) } }
        check(resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0)
        return uri
    } catch (failure: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw failure
    }
}
