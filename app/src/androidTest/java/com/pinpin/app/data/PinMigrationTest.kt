package com.pinpin.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PinMigrationTest {
    @Test fun realV4SchemaMigratesToV5AndPreservesRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                // Exact table definitions from the checked-in 4.json; no invented v1-v3.
                old.execSQL("CREATE TABLE IF NOT EXISTS `pins` (`id` TEXT NOT NULL, `imageUri` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `x` INTEGER NOT NULL, `y` INTEGER NOT NULL, `width` REAL NOT NULL, `height` REAL NOT NULL, `locked` INTEGER NOT NULL, `zOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `rotation` INTEGER NOT NULL, `flipped` INTEGER NOT NULL, `annotatedImageUri` TEXT, PRIMARY KEY(`id`))")
                old.execSQL("CREATE TABLE IF NOT EXISTS `screenshot_history` (`id` TEXT NOT NULL, `filePath` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                old.execSQL("INSERT INTO pins VALUES ('p', 'file:///original.png', 'test', 1, 2, 30, 40, 1, 5, 123, 270, 1, 'file:///annotation.png')")
                old.execSQL("INSERT INTO screenshot_history VALUES ('h', '/history.png', 30, 40, 'test', 123)")
                old.version = 4
            }
            val db = Room.databaseBuilder(context, PinDatabase::class.java, name)
                .addMigrations(PinDatabaseMigrations.MIGRATION_4_5).build()
            try {
                val pin = db.pinDao().findById("p")!!
                assertEquals("file:///original.png", pin.imageUri)
                assertEquals("file:///annotation.png", pin.annotatedImageUri)
                assertEquals(270, pin.rotation)
                assertTrue(pin.flipped)
                assertFalse(pin.serialVisible)
                assertEquals("/history.png", db.historyDao().findById("h")!!.filePath)
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
