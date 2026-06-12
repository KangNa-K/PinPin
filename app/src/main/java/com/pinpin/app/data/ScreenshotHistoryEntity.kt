package com.pinpin.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent record for one historical screenshot. Differs from
 * [PinEntity] in that:
 *  - it has no on-screen layout (history items are not pinned yet);
 *  - [filePath] is always an absolute path inside the app's private
 *    files dir (we copy the bitmap there on save, so the URI never
 *    goes stale);
 *  - deletion is allowed (history is cleanup-friendly; pinned pins
 *    generally are not).
 */
@Entity(tableName = "screenshot_history")
data class ScreenshotHistoryEntity(
    @PrimaryKey val id: String,
    /** Absolute path to the PNG inside the app's files dir. */
    val filePath: String,
    val width: Int,
    val height: Int,
    /** Free-form tag for the original source (e.g. "ball-screencap", "photopicker"). */
    val sourceType: String,
    val createdAt: Long = System.currentTimeMillis(),
)
