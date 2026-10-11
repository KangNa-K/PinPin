package com.pinpin.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent record for one pinned image.
 *
 *  - [imageUri]: a `content://` URI from PhotoPicker / `ACTION_SEND` or a
 *    `file://` URI into our own cache dir (when the source is a MediaProjection
 *    capture). The repository is responsible for keeping these reachable
 *    across reboots — PhotoPicker URIs are persistable for the lifetime of
 *    the app's access; MediaProjection captures are written to a private
 *    directory that is not auto-evicted.
 *  - [sourceType]: free-form tag (e.g. "test", "photopicker", "screencapture",
 *    "share") — useful for analytics and for choosing the right preview
 *    strategy when the URI cannot be resolved.
 *  - [x] / [y] / [width] / [height]: layout in screen pixels. Note that
 *    `width` / `height` here are the *display* size of the pin, not the
 *    intrinsic size of the underlying bitmap.
 *  - [locked]: when true, the pin rejects touch-driven drag / scale and
 *    is marked visually with a lock badge.
 *  - [createdAt]: epoch millis, used as a stable secondary sort key in case
 *    two pins ever collide on z-order in the future.
 *  - [zOrder]: explicit z-order. Higher = drawn on top. The repository
 *    assigns this on insert (max(existing) + 1) so the most recently
 *    inserted pin sits above the others.
 *  - [annotatedImageUri]: if non-null, the pin's overlay layer renders
 *    this URI instead of [imageUri]. Written when the user commits a
 *    freehand annotation (drawing / arrow / highlight) over the pinned
 *    image; the original is preserved by never being overwritten.
 *    See [com.pinpin.app.overlay.AnnotatePopupView] for the writer.
 */
@Entity(tableName = "pins")
data class PinEntity(
    @PrimaryKey val id: String,
    val imageUri: String,
    val sourceType: String,
    val x: Int,
    val y: Int,
    val width: Float,
    val height: Float,
    val locked: Boolean = false,
    val zOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Image rotation in degrees; one of 0 / 90 / 180 / 270. */
    val rotation: Int = 0,
    /** Whether the image is horizontally flipped. */
    val flipped: Boolean = false,
    /** Whether the serial-number badge is visible on the overlay. */
    val serialVisible: Boolean = false,
    /**
     * URI of the post-annotation image, or null if the pin has never
     * been annotated. The original [imageUri] is kept untouched; this
     * field points at a new file in the app's private cache dir. The
     * overlay service prefers this URI over [imageUri] when non-null.
     */
    val annotatedImageUri: String? = null,
)
