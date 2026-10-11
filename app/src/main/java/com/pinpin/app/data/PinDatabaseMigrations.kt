package com.pinpin.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Explicit Room schema migrations; user pins and history must survive upgrades. */
object PinDatabaseMigrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS screenshot_history (
                    id TEXT NOT NULL PRIMARY KEY,
                    filePath TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    sourceType TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE pins ADD COLUMN rotation INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE pins ADD COLUMN flipped INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE pins ADD COLUMN annotatedImageUri TEXT",
            )
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE pins ADD COLUMN serialVisible INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
}
