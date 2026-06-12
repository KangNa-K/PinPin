package com.pinpin.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PinEntity::class, ScreenshotHistoryEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class PinDatabase : RoomDatabase() {
    abstract fun pinDao(): PinDao
    abstract fun historyDao(): ScreenshotHistoryDao

    companion object {
        const val DATABASE_NAME = "pinpin.db"
    }
}
