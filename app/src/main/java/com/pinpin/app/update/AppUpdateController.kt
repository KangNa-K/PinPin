package com.pinpin.app.update

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val preparing: Boolean = false,
    val progress: Int = 0,
    val manifest: UpdateManifest? = null,
    val readyApk: File? = null,
    val noticeVisible: Boolean = false,
    val message: String? = null,
)

class AppUpdateController(private val context: Context, private val scope: CoroutineScope) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pinpin_updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var foreground = false
    private var awaitingPermission = false

    init {
        prefs.getString("manifest", null)?.let { json ->
            _state.update { it.copy(checking = true) }
            scope.launch {
                try {
                    val manifest = PinPinUpdater.parseManifest(json)
                    if (PinPinUpdater.newer(manifest.versionCode, PinPinUpdater.installedCode(context))) {
                        val cached = PinPinUpdater.cached(context, manifest)
                        _state.update {
                            it.copy(manifest = manifest, readyApk = cached,
                                noticeVisible = false)
                        }
                    } else prefs.edit().remove("manifest").apply()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    prefs.edit().remove("manifest").apply()
                } finally {
                    _state.update { it.copy(checking = false) }
                }
                if (foreground) check(false)
            }
        }
    }

    private fun shouldNotify(manifest: UpdateManifest): Boolean {
        val now = System.currentTimeMillis()
        return manifest.versionCode != prefs.getInt("ignored_code", -1) &&
            (manifest.versionCode != prefs.getInt("snoozed_code", -1) ||
                now >= prefs.getLong("snoozed_until", 0))
    }

    fun check(manual: Boolean = true) {
        if (_state.value.checking || _state.value.downloading || _state.value.preparing) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_successful_check", 0)
        if (!manual && last != 0L && now >= last && now - last < CHECK_INTERVAL_MS) {
            _state.update { it.copy(noticeVisible = it.manifest?.let(::shouldNotify) == true) }
            return
        }
        _state.update { it.copy(checking = true, noticeVisible = false, message = null) }
        job = scope.launch {
            try {
                val manifest = PinPinUpdater.check(context)
                prefs.edit().putLong("last_successful_check", System.currentTimeMillis()).apply()
                if (manifest == null) {
                    prefs.edit().remove("manifest").apply()
                    _state.value = AppUpdateState(message = if (manual) "已是最新版本" else null)
                } else {
                    prefs.edit().putString("manifest", JSONObject()
                        .put("versionCode", manifest.versionCode)
                        .put("versionName", manifest.versionName)
                        .put("apkUrl", manifest.apkUrl)
                        .put("sha256", manifest.sha256)
                        .put("size", manifest.size)
                        .put("notes", manifest.notes).toString()).apply()
                    val cached = PinPinUpdater.cached(context, manifest)
                    _state.update {
                        it.copy(manifest = manifest, readyApk = cached,
                            noticeVisible = manual || shouldNotify(manifest))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(message = if (manual) "检查失败：${error.localizedMessage ?: "网络不可用"}" else null)
                }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    fun onForeground() {
        foreground = true
        if (awaitingPermission) {
            awaitingPermission = false
            if (PinPinUpdater.canInstall(context)) {
                installReady()
                return
            }
        }
        check(false)
    }

    fun onBackground() { foreground = false }

    fun consumeMessage() {
        if (!_state.value.noticeVisible) _state.update { it.copy(message = null) }
    }

    fun downloadAndInstall() {
        val manifest = _state.value.manifest ?: return
        if (_state.value.checking || _state.value.downloading || _state.value.preparing) return
        _state.update { it.copy(downloading = true, progress = 0, message = null) }
        job = scope.launch {
            try {
                val apk = PinPinUpdater.download(context, manifest) { value ->
                    _state.update { it.copy(progress = value) }
                }
                _state.update { it.copy(downloading = false, readyApk = apk, message = "下载完成，等待安装") }
                if (foreground) installReady()
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(downloading = false, message = "下载已取消") }
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(downloading = false, message = "更新失败：${error.localizedMessage ?: "请重试"}") }
            }
        }
    }

    fun cancelDownload() {
        if (_state.value.downloading) job?.cancel()
    }

    fun installReady() {
        val manifest = _state.value.manifest ?: return
        if (_state.value.checking || _state.value.downloading || _state.value.preparing) return
        if (!foreground) {
            _state.update { it.copy(message = "请返回应用继续安装") }
            return
        }
        _state.update { it.copy(preparing = true, message = null) }
        job = scope.launch {
            try {
                val apk = PinPinUpdater.cached(context, manifest)
                    ?: error("安装包已失效，请重新下载")
                if (!foreground) return@launch
                if (!PinPinUpdater.canInstall(context)) {
                    awaitingPermission = true
                    _state.update { it.copy(message = "授权后返回继续安装") }
                    PinPinUpdater.requestPermission(context)
                    return@launch
                }
                PinPinUpdater.install(context, apk)
                _state.update { it.copy(message = "请在系统界面确认安装") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(message = "安装未开始：${error.localizedMessage ?: "请重试"}") }
            } finally {
                _state.update { it.copy(preparing = false) }
            }
        }
    }

    fun later() {
        _state.value.manifest?.let {
            prefs.edit().putInt("snoozed_code", it.versionCode)
                .putLong("snoozed_until", System.currentTimeMillis() + SNOOZE_MS).apply()
        }
        _state.update { it.copy(noticeVisible = false) }
    }

    fun ignoreCurrentVersion() {
        _state.value.manifest?.let { prefs.edit().putInt("ignored_code", it.versionCode).apply() }
        _state.update { it.copy(noticeVisible = false) }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val SNOOZE_MS = 24L * 60 * 60 * 1000
    }
}
