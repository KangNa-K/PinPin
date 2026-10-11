package com.pinpin.app.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal const val MANIFEST_URL =
    "https://raw.githubusercontent.com/KangNa-K/PinPin-updates/main/update/version.json"
private const val ASSET_PREFIX =
    "https://api.github.com/repos/KangNa-K/PinPin-updates/releases/assets/"
private const val MAX_APK_SIZE = 250L * 1024 * 1024

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val notes: String,
)

internal object PinPinUpdater {
    internal fun manifestRequestUrl(timestamp: Long): String =
        "$MANIFEST_URL?check=$timestamp"

    fun parseManifest(json: String): UpdateManifest = JSONObject(json).run {
        UpdateManifest(
            getInt("versionCode"), getString("versionName"), optString("apkUrl"),
            optString("sha256"), optLong("size"), optString("notes"),
        )
    }

    fun validate(manifest: UpdateManifest) {
        require(manifest.versionCode > 0 && manifest.versionName.isNotBlank())
        require(manifest.size in 1..MAX_APK_SIZE)
        require(manifest.apkUrl.startsWith(ASSET_PREFIX) &&
            manifest.apkUrl.removePrefix(ASSET_PREFIX).matches(Regex("[0-9]+")))
        require(manifest.sha256.matches(Regex("[a-fA-F0-9]{64}")))
    }

    fun newer(latest: Int, installed: Long): Boolean = latest.toLong() > installed

    fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    suspend fun check(context: Context): UpdateManifest? = withContext(Dispatchers.IO) {
        val connection = open(manifestRequestUrl(System.currentTimeMillis()), "application/json", 30_000)
        val manifest = try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val json = connection.inputStream.bufferedReader().use { it.readTextBounded() }
            parseManifest(json)
        } finally {
            connection.disconnect()
        }
        require(manifest.versionCode > 0 && manifest.versionName.isNotBlank())
        if (!newer(manifest.versionCode, installedCode(context))) return@withContext null
        validate(manifest)
        manifest
    }

    private fun java.io.Reader.readTextBounded(): String {
        val text = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(text.length + count <= 64 * 1024) { "Update manifest is too large" }
            text.append(buffer, 0, count)
        }
        return text.toString()
    }

    fun verifyFile(file: File, manifest: UpdateManifest): Boolean {
        if (!file.isFile || file.length() != manifest.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
            .equals(manifest.sha256, ignoreCase = true)
    }

    suspend fun cached(context: Context, manifest: UpdateManifest): File? =
        withContext(Dispatchers.IO) {
            validate(manifest)
            val file = apkFile(context, manifest)
            if (verifyFile(file, manifest)) {
                validatePackage(context, file, manifest)
                file
            } else null
        }

    suspend fun download(
        context: Context,
        manifest: UpdateManifest,
        progress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        validate(manifest)
        val destination = apkFile(context, manifest)
        if (verifyFile(destination, manifest)) {
            validatePackage(context, destination, manifest)
            return@withContext destination
        }
        val temporary = File(destination.parentFile, "${destination.name}.part.apk")
        val connection = open(manifest.apkUrl, "application/octet-stream", 60_000)
        try {
            check(connection.responseCode in 200..299) { "APK HTTP ${connection.responseCode}" }
            require(connection.url.protocol == "https")
            var bytes = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        check(bytes <= manifest.size) { "APK exceeds expected size" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        progress((bytes * 100 / manifest.size).toInt().coerceIn(0, 100))
                    }
                }
            }
            check(bytes == manifest.size) { "APK size mismatch" }
            check(digest.digest().joinToString("") { "%02x".format(it) }
                .equals(manifest.sha256, ignoreCase = true)) { "APK SHA-256 mismatch" }
            validatePackage(context, temporary, manifest)
            check(temporary.renameTo(destination)) { "Unable to finalize APK" }
            progress(100)
            destination
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun apkFile(context: Context, manifest: UpdateManifest): File =
        File(File(context.cacheDir, "updates").apply { mkdirs() }, "pinpin-${manifest.versionCode}.apk")

    private fun open(url: String, accept: String, timeout: Int) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = timeout
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "PinPin-Android-Updater")
            setRequestProperty("Cache-Control", "no-cache")
            useCaches = false
            if (accept == "application/octet-stream") {
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
        }

    private fun validatePackage(context: Context, apk: File, manifest: UpdateManifest) {
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not an APK")
        require(archive.packageName == context.packageName) { "APK package mismatch" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        require(code == manifest.versionCode.toLong() && code > installedCode(context)) {
            "APK version mismatch"
        }
        @Suppress("DEPRECATION")
        val installed = manager.getPackageInfo(context.packageName, flags)
        @Suppress("DEPRECATION")
        val currentSigners = if (Build.VERSION.SDK_INT >= 28)
            installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }
        else installed.signatures?.map { it.toCharsString() }
        @Suppress("DEPRECATION")
        val updateSigners = if (Build.VERSION.SDK_INT >= 28)
            archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }
        else archive.signatures?.map { it.toCharsString() }
        require(!currentSigners.isNullOrEmpty() && currentSigners == updateSigners) {
            "APK signing certificate mismatch"
        }
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun requestPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .apply { clipData = ClipData.newRawUri("PinPin update", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
