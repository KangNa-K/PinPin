package com.pinpin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns durable image files used by pins.
 *
 * Room records can live for months, so a Pin must never depend on a temporary
 * picker grant or on [Context.cacheDir]. Every imported/captured/annotated
 * image is copied into this private files directory before its URI is stored
 * in Room.
 */
@Singleton
class PinAssetStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val mutationMutex = Mutex()
    // Files handed to a caller but not yet committed must not be swept.
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal suspend fun <T> mutate(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutationMutex.withLock {
            currentCoroutineContext().ensureActive()
            // Once started, finish the file/DB unit even if the caller disappears.
            withContext(NonCancellable + Dispatchers.IO) { block() }
        }
    }

    private val assetDir: File
        get() = File(context.filesDir.canonicalFile, ASSET_DIRECTORY)

    /** Return the URI unchanged when it already points at this store. */
    suspend fun ensureDurable(uri: Uri, prefix: String): Uri = deliverAsset(existing = uri) {
        if (isManaged(uri)) uri else copyFromUri(uri, prefix)
    }

    internal suspend fun deliverAsset(existing: Uri? = null, produce: suspend () -> Uri): Uri {
        currentCoroutineContext().ensureActive()
        var produced: Uri? = null
        try {
            return withContext(NonCancellable + Dispatchers.IO) {
                produce().also { produced = it }
            }
        } catch (failure: Throwable) {
            // Returning across dispatchers can discard the URI on cancellation.
            // Release only a newly produced file's lease; the age-gated, reference-
            // checked sweep handles deletion, never an existing caller-owned URI.
            produced?.takeIf { it != existing }?.let { committed(it.toString()) }
            throw failure
        }
    }

    fun isManaged(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return runCatching {
            AssetFiles.ownedFile(assetDir, File(path)) != null
        }.getOrDefault(false)
    }

    /** Copy an external content/file URI into durable private storage. */
    suspend fun copyFromUri(uri: Uri, prefix: String): Uri = deliverAsset {
        val extension = extensionFor(uri)
        val target = newPinFile(prefix, extension)
        copyAtomically(
            target = target,
            openSource = {
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open source URI: $uri")
            },
        )
        Uri.fromFile(target)
    }

    /** Copy an existing durable file into a separate Pin-owned asset. */
    suspend fun copyFileToPin(source: File, prefix: String): Uri = deliverAsset {
        if (!source.isFile) {
            throw IOException("Pin source file does not exist: ${source.absolutePath}")
        }
        val target = newPinFile(prefix, source.extension.ifBlank { "img" })
        copyAtomically(
            target = target,
            openSource = { source.inputStream() },
        )
        Uri.fromFile(target)
    }

    /**
     * Allocate a durable Pin asset path for callers that generate bytes
     * themselves, such as the annotation renderer.
     */
    fun newPinFile(prefix: String, extension: String = "png"): File {
        val safePrefix = prefix.replace(UNSAFE_FILENAME_CHARS, "_")
        val safeExtension = extension.removePrefix(".").replace(UNSAFE_FILENAME_CHARS, "_").ifBlank { "img" }
        if (!assetDir.mkdirs() && !assetDir.isDirectory) throw IOException("Cannot create asset directory")
        if (assetDir.canonicalFile != assetDir.absoluteFile) throw IOException("Symlink asset directory")
        return File(assetDir, "$safePrefix-${UUID.randomUUID()}.$safeExtension").also {
            pending.add(it.absolutePath)
        }
    }

    private fun copyAtomically(target: File, openSource: () -> java.io.InputStream) {
        try {
            AssetFiles.writeAtomically(target) { output ->
                openSource().use { it.copyTo(output) }
            }
        } catch (failure: Throwable) {
            pending.remove(target.absolutePath)
            throw failure
        }
    }

    /** Atomic PNG write. Caller retains bitmap ownership and must recycle it. */
    suspend fun saveBitmap(bitmap: Bitmap, prefix: String = "annotated"): Uri {
        return deliverAsset {
            val target = newPinFile(prefix)
            try {
                writeLosslessPng(bitmap, target)
                Uri.fromFile(target)
            } catch (failure: Throwable) {
                pending.remove(target.absolutePath)
                throw failure
            }
        }
    }

    internal fun committed(rawUri: String) {
        Uri.parse(rawUri).path?.let { pending.remove(it) }
    }

    internal fun deleteUnreferenced(rawUri: String, references: Set<String>) {
        val uri = Uri.parse(rawUri)
        if (!isManaged(uri)) return
        val file = File(requireNotNull(uri.path))
        if (file.canonicalPath !in references) {
            file.delete()
            pending.remove(file.absolutePath)
        }
    }

    /** Call only under mutate, with a fresh cross-table reference snapshot. */
    internal fun sweep(references: Set<String>, now: Long, minimumAgeMillis: Long): Int {
        require(minimumAgeMillis >= 24 * 60 * 60 * 1000L)
        if (assetDir.canonicalFile != assetDir.absoluteFile) return 0
        var deleted = 0
        for (entry in assetDir.listFiles().orEmpty()) {
            val file = AssetFiles.ownedFile(assetDir, entry) ?: continue
            // Only our UUID naming convention; never recurse or touch history.
            if (!OWNED_NAME.matches(file.name)) continue
            val finalPath = if (file.name.startsWith(".")) {
                File(assetDir, file.name.removePrefix(".").removeSuffix(".tmp")).absolutePath
            } else file.absolutePath
            if (finalPath in pending || finalPath in references) continue
            if (file.lastModified() <= 0 || now < file.lastModified() ||
                now - file.lastModified() < minimumAgeMillis
            ) continue
            if (file.delete()) deleted++
        }
        return deleted
    }

    private fun extensionFor(uri: Uri): String {
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        return when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            else -> "img"
        }
    }

    companion object {
        private const val ASSET_DIRECTORY = "pin_assets"
        private val UNSAFE_FILENAME_CHARS = Regex("[^a-zA-Z0-9._-]")
        private val OWNED_NAME = Regex(
            """\.?[a-zA-Z0-9._-]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-zA-Z0-9_-]+(\.tmp)?""",
        )
    }
}
