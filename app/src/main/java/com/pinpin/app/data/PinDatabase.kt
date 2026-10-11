package com.pinpin.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PinEntity::class, ScreenshotHistoryEntity::class],
    // Schema history:
    //  v1 — pins
    //  v2 — screenshot_history
    //  v3 — pins.{rotation, flipped} added
    //  v4 — pins.annotatedImageUri added (post-annotation cache URI)
    // v4 adds [PinEntity.annotatedImageUri]; v5 persists serial badge
    // visibility. Every transition is covered by [PinDatabaseMigrations].
    version = 5,
    exportSchema = true,
)
abstract class PinDatabase : RoomDatabase() {
    abstract fun pinDao(): PinDao
    abstract fun historyDao(): ScreenshotHistoryDao

    companion object {
        const val DATABASE_NAME = "pinpin.db"
    }
}
