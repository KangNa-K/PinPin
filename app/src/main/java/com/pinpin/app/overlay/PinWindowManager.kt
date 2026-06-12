package com.pinpin.app.overlay

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pinpin.app.data.PinEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only projection of the pin list for the home screen.
 *
 * The actual source of truth is [com.pinpin.app.data.PinRepository], which
 * writes to Room. This object exists for two reasons:
 *
 *  1. The overlay service needs to know "the list of pins that should
 *     currently have a view" without going through a Hilt-injected
 *     repository (the service is a Hilt entry point, but a process-wide
 *     snapshot is simpler than binding).
 *  2. The Compose UI reads a [StateFlow] for collection in `collectAsState`;
 *     the repository exposes a `StateFlow` as well, but having a dedicated
 *     type for "the list as the UI sees it" keeps the dependency direction
 *     clean (UI → projection, projection ← repository).
 *
 * The [syncPinList] companion hook is called by the repository on every
 * Room invalidation; it diffs against the previous snapshot and pushes the
 * diff to the overlay service.
 */
object PinWindowManager {

    data class PinState(
        val id: String,
        val label: String,
        val width: Float,
        val height: Float,
        val x: Int,
        val y: Int,
        val locked: Boolean,
        val imageUri: String?,
        val sourceType: String?,
        val zOrder: Int,
    ) {
        /** Convenience for the home-screen list: "(x, y) on screen". */
        fun positionLabel(): String = "($x, $y)"
    }

    private val _pins = MutableStateFlow<List<PinState>>(emptyList())
    val pins: StateFlow<List<PinState>> = _pins.asStateFlow()

    /**
     * Internal hook used by the service. Not part of the public API — the
     * Compose UI reads [pins], mutators go through [com.pinpin.app.data.PinRepository].
     */
    internal fun applySnapshot(pins: List<PinState>) {
        _pins.value = pins
    }
}
