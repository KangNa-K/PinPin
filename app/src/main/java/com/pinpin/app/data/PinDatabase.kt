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
    // Bumping to v4 because [PinEntity] grew a nullable
    // [PinEntity.annotatedImageUri] column. With
    // [fallbackToDestructiveMigration] the existing v3 db is dropped
    // and recreated; pin data is lost, which is acceptable for now
    // (matches the policy set in [com.pinpin.app.di.DataModule]).
    version = 4,
    exportSchema = false,
)
abstract class PinDatabase : RoomDatabase() {
    abstract fun pinDao(): PinDao
    abstract fun historyDao(): ScreenshotHistoryDao

    companion object {
        const val DATABASE_NAME = "pinpin.db"
    }
}
