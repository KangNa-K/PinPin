package com.pinpin.app.capture

import android.os.SystemClock
import android.util.Log
import com.pinpin.app.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/** Debug-only spans; never records screen contents, crop coordinates or file paths. */
internal class CaptureTimings(private val id: String) {
    private val started = SystemClock.elapsedRealtime()
    private val marks = ConcurrentHashMap<String, Long>()

    fun mark(stage: String) {
        if (BuildConfig.DEBUG) marks[stage] = SystemClock.elapsedRealtime() - started
    }

    fun report(error: String?) {
        if (!BuildConfig.DEBUG) return
        val spans = marks.entries.sortedBy { it.value }.joinToString(" ") { "${it.key}=${it.value}ms" }
        Log.d("CaptureTiming", "id=$id $spans total=${SystemClock.elapsedRealtime() - started}ms result=${error ?: "success"}")
    }
}
