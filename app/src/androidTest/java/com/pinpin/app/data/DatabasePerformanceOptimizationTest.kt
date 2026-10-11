package com.pinpin.app.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class DatabasePerformanceOptimizationTest {
    private class Fixture {
        private val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "database-perf-${UUID.randomUUID()}")
            .apply { mkdirs() }.canonicalFile
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        val queries = CopyOnWriteArrayList<String>()
        val db = Room.inMemoryDatabaseBuilder(context, PinDatabase::class.java)
            .setQueryCallback(
                { sql, _ -> queries.add(sql) },
                Executor { it.run() },
            ).build()
        val store = PinAssetStore(context)
        val pins = PinRepository(context, db.pinDao(), db, store)
        val history = ScreenshotHistoryRepository(context, db.historyDao(), pins, store)

        fun asset(): File = store.newPinFile("test").apply {
            writeText("asset")
            store.committed(Uri.fromFile(this).toString())
        }

        fun historyFile(): File = File(root, "screenshot_history/${UUID.randomUUID()}.png")
            .apply { parentFile!!.mkdirs(); writeText("history") }

        suspend fun hasSuspiciousReference(): Boolean {
            val pinDir = File(root, "pin_assets")
            val historyDir = File(root, "screenshot_history")
            val pinPathPrefix = "${pinDir.path}/"
            val pinUriPrefix = "${Uri.fromFile(pinDir)}/"
            val historyPathPrefix = "${historyDir.path}/"
            val historyUriPrefix = "${Uri.fromFile(historyDir)}/"
            return db.pinDao().hasSuspiciousAssetReference(
                pinPathPrefix, pinUriPrefix, historyPathPrefix, historyUriPrefix,
            ) || db.historyDao().hasSuspiciousAssetReference(
                pinPathPrefix, pinUriPrefix, historyPathPrefix, historyUriPrefix,
            )
        }

        suspend fun close() {
            stopRepositoryCollectors(history)
            stopRepositoryCollectors(pins)
            db.close()
            root.deleteRecursively()
        }
    }

    private fun pin(
        id: String,
        uri: String = "pinpin://test",
        annotation: String? = null,
        order: Int = 0,
        createdAt: Long = 0,
        serialVisible: Boolean = false,
    ) = PinEntity(
        id, uri, "test", 0, 0, 10f, 10f,
        zOrder = order, createdAt = createdAt, serialVisible = serialVisible,
        annotatedImageUri = annotation,
    )

    private fun history(id: String, path: String) =
        ScreenshotHistoryEntity(id, path, 2, 2, "test")

    @Test fun renormalizationUsesOneProjectionAndWritesOnlyChangedOrders() = runBlocking {
        val f = Fixture()
        try {
            f.db.withTransaction {
                (1..512).forEach { index ->
                    f.db.pinDao().upsert(pin("p$index", order = index, createdAt = index.toLong()))
                }
            }
            val sqlite = f.db.openHelper.writableDatabase
            sqlite.execSQL("CREATE TABLE order_updates (pinId TEXT NOT NULL)")
            sqlite.execSQL(
                "CREATE TRIGGER record_order_update AFTER UPDATE OF zOrder ON pins " +
                    "BEGIN INSERT INTO order_updates VALUES (NEW.id); END",
            )
            f.queries.clear()
            f.pins.renormalizeZOrders()
            assertEquals(1, f.queries.count { it.startsWith("SELECT id, zOrder FROM pins") })
            assertFalse(f.queries.any { it.startsWith("SELECT * FROM pins WHERE id") })
            sqlite.query("SELECT COUNT(*) FROM order_updates").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }

            f.db.pinDao().updateZOrder("p512", 900)
            sqlite.execSQL("DELETE FROM order_updates")
            f.queries.clear()
            f.pins.renormalizeZOrders()
            assertEquals(1, f.queries.count { it.startsWith("SELECT id, zOrder FROM pins") })
            assertFalse(f.queries.any { it.startsWith("SELECT * FROM pins WHERE id") })
            sqlite.query("SELECT pinId FROM order_updates").use {
                assertTrue(it.moveToFirst())
                assertEquals("p512", it.getString(0))
                assertFalse(it.moveToNext())
            }
        } finally {
            f.close()
        }
    }

    @Test fun numberingPreservesNumberedThenUnnumberedOrderAndBadgeFlags() = runBlocking {
        val f = Fixture()
        try {
            val originals = listOf(
                pin("zero-late", order = 0, createdAt = 40, serialVisible = true),
                pin("tie-late", order = 7, createdAt = 30),
                pin("first", order = 1, createdAt = 100, serialVisible = true),
                pin("zero-early", order = 0, createdAt = 10),
                pin("tie-early", order = 7, createdAt = 20, serialVisible = true),
                // Preserve the previous query's handling of nonpositive orders.
                pin("negative", order = -1, createdAt = 50),
            )
            originals.forEach { f.db.pinDao().upsert(it) }
            f.pins.renormalizeZOrders()
            assertEquals(
                listOf("first", "tie-early", "tie-late", "negative", "zero-early", "zero-late"),
                f.db.pinDao().ordersInDisplayOrder().map { it.id },
            )
            assertEquals((1..6).toList(), f.db.pinDao().ordersInDisplayOrder().map { it.zOrder })
            originals.forEach { original ->
                assertEquals(
                    original.copy(zOrder = f.db.pinDao().findById(original.id)!!.zOrder),
                    f.db.pinDao().findById(original.id),
                )
            }
            f.pins.removePin("tie-early")
            assertEquals((1..5).toList(), f.db.pinDao().ordersInDisplayOrder().map { it.zOrder })
            assertEquals(6, f.pins.nextAvailableZOrder())
            f.db.pinDao().updateZOrder("first", 0)
            f.pins.renormalizeZOrders()
            assertEquals("first", f.db.pinDao().ordersInDisplayOrder().last().id)
        } finally {
            f.close()
        }
    }

    @Test fun targetedReclaimPreservesMultiplePinsAnnotationsAndHistoryRows() = runBlocking {
        val f = Fixture()
        try {
            val shared = f.asset()
            val uri = Uri.fromFile(shared).toString()
            f.db.pinDao().upsert(pin("original", uri))
            f.db.pinDao().upsert(pin("annotation", annotation = uri))
            f.db.historyDao().upsert(history("h1", shared.path))
            f.db.historyDao().upsert(history("h2", uri))
            f.pins.removePin("original")
            assertTrue(shared.exists())
            f.pins.updateAnnotatedImage("annotation", null)
            assertTrue(shared.exists())
            f.db.historyDao().deleteById("h1")
            f.pins.discardUnreferencedAsset(Uri.fromFile(shared))
            assertTrue(shared.exists())
            f.db.historyDao().deleteById("h2")
            f.queries.clear()
            f.pins.discardUnreferencedAsset(Uri.fromFile(shared))
            assertFalse(shared.exists())
            assertTargetedQueries(f.queries)

            // History-owned files share the same cross-table protection.
            val historic = f.historyFile()
            f.db.historyDao().upsert(history("one", historic.path))
            f.db.historyDao().upsert(history("two", historic.path))
            f.db.pinDao().upsert(pin("reader", Uri.fromFile(historic).toString()))
            f.db.pinDao().upsert(pin("annotated-reader", annotation = historic.path))
            f.history.delete("one")
            assertTrue(historic.exists())
            f.db.pinDao().deleteById("reader")
            f.history.delete("two")
            assertTrue(historic.exists())
            f.db.historyDao().upsert(history("last", historic.path))
            f.db.pinDao().deleteById("annotated-reader")
            f.queries.clear()
            f.history.delete("last")
            assertFalse(historic.exists())
            assertTargetedQueries(f.queries)
        } finally {
            f.close()
        }
    }

    @Test fun suspiciousReferencesFailClosedInBothTablesAndBothPinColumns() = runBlocking {
        val f = Fixture()
        try {
            val target = f.asset()
            val canonicalUri = Uri.fromFile(target).toString()
            val nativeOriginal = Uri.fromFile(f.asset()).toString()
            val suspicious = listOf(
                "${target.parent}/../pin_assets/${target.name}",
                "file://${target.parent}/./${target.name}",
                canonicalUri.replace("test-", "%74est-"),
                canonicalUri.replace("/pin_assets/", "/%70in_assets/"),
                canonicalUri.replace("/pin_assets/", "/pin_assets%2F"),
                "file://localhost${target.path}",
                "file:/${target.path.removePrefix("/")}",
                "$canonicalUri?ignored=query",
                "$canonicalUri#fragment",
                "content://com.pinpin.app.fileprovider/pin_assets/${target.name}",
                "pinpin://test/",
                "pinpin://test/123x",
                "pinpin://test/%31",
                "pinpin://test/123?query",
                "pinpin://test/123#fragment",
                "pinpin://unresolved/opaque",
                "https://provider/opaque",
                "pin_assets/${target.name}",
                "file:///bad%ZZ",
                "",
            )
            for (raw in suspicious) {
                for (column in 0..2) {
                    when (column) {
                        0 -> f.db.pinDao().upsert(pin("suspicious", raw))
                        1 -> f.db.pinDao().upsert(pin("suspicious", nativeOriginal, annotation = raw))
                        else -> f.db.historyDao().upsert(history("suspicious", raw))
                    }
                    assertTrue("SQL missed $raw in column $column", f.hasSuspiciousReference())
                    f.pins.discardUnreferencedAsset(Uri.fromFile(target))
                    assertTrue("Deleted target with reference $raw in column $column", target.exists())
                    f.db.pinDao().deleteById("suspicious")
                    f.db.historyDao().deleteById("suspicious")
                    assertFalse(f.hasSuspiciousReference())
                }
            }
            // Suspicious candidates are not normalized into deletion permission.
            f.pins.discardUnreferencedAsset(Uri.parse(canonicalUri.replace("test-", "%74est-")))
            assertTrue(target.exists())
            f.db.historyDao().upsert(history("not-a-history-path", "pinpin://test/123"))
            assertTrue(f.hasSuspiciousReference())
            f.pins.discardUnreferencedAsset(Uri.fromFile(target))
            assertTrue(target.exists())
            f.db.historyDao().deleteById("not-a-history-path")
            assertFalse(f.hasSuspiciousReference())
            f.pins.discardUnreferencedAsset(Uri.fromFile(target))
            assertFalse(target.exists())

            val historic = f.historyFile()
            for (raw in listOf(
                "content://provider/opaque",
                "pinpin://test/",
                "pinpin://test/123x",
                "screenshot_history/${historic.name}",
                "file://${historic.parent}/../screenshot_history/${historic.name}",
                Uri.fromFile(historic).toString().replace(".png", "%2Epng"),
                "file:///bad%",
            )) {
                f.db.pinDao().upsert(pin("suspicious", nativeOriginal, annotation = raw))
                f.db.historyDao().upsert(history("delete-me", historic.path))
                f.history.delete("delete-me")
                assertTrue("History compensation deleted target with $raw", historic.exists())
                f.db.pinDao().deleteById("suspicious")
                f.db.historyDao().upsert(history("suspicious", raw))
                f.db.historyDao().upsert(history("delete-me", historic.path))
                f.history.delete("delete-me")
                assertTrue(historic.exists())
                f.db.historyDao().deleteById("suspicious")
            }
            f.db.historyDao().upsert(history("delete-me", historic.path))
            f.history.delete("delete-me")
            assertFalse(historic.exists())
        } finally {
            f.close()
        }
    }

    @Test fun nativeLookingSymlinksAndExternalAliasesCannotBypassProtection() = runBlocking {
        val f = Fixture()
        try {
            val target = f.asset()
            val historic = f.historyFile()
            val nativeOriginal = Uri.fromFile(f.asset()).toString()
            val aliases = listOf(
                File(target.parentFile, "native-alias.png"),
                File(historic.parentFile, "native-alias.png"),
                File(f.root, "external-alias.png"),
            )
            for (alias in aliases) {
                Os.symlink(target.path, alias.path)
                try {
                    f.db.pinDao().upsert(pin("alias", Uri.fromFile(alias).toString()))
                    f.pins.discardUnreferencedAsset(Uri.fromFile(target))
                    assertTrue(target.exists())
                    f.db.pinDao().deleteById("alias")
                    f.db.historyDao().upsert(history("alias", alias.path))
                    f.pins.discardUnreferencedAsset(Uri.fromFile(target))
                    assertTrue(target.exists())
                    f.db.historyDao().deleteById("alias")
                } finally {
                    assertTrue(alias.delete())
                }
            }
            val historyAlias = File(target.parentFile, "history-alias.png")
            Os.symlink(historic.path, historyAlias.path)
            f.db.pinDao().upsert(pin("history-alias", nativeOriginal, annotation = Uri.fromFile(historyAlias).toString()))
            f.db.historyDao().upsert(history("delete-me", historic.path))
            f.history.delete("delete-me")
            assertTrue(historic.exists())
            f.db.pinDao().deleteById("history-alias")
            assertTrue(historyAlias.delete())
            f.db.historyDao().upsert(history("delete-me", historic.path))
            f.history.delete("delete-me")
            assertFalse(historic.exists())

            // The whole history directory may be an alias into pin_assets.
            val historyDir = historic.parentFile!!
            assertTrue(historyDir.delete())
            Os.symlink(target.parent, historyDir.path)
            try {
                f.db.historyDao().upsert(history("directory-alias", File(historyDir, target.name).path))
                f.pins.discardUnreferencedAsset(Uri.fromFile(target))
                assertTrue(target.exists())
                f.db.historyDao().deleteById("directory-alias")
            } finally {
                assertTrue(historyDir.delete())
            }
            // Even an unreferenced, dangling native-looking sibling is deferred.
            val dangling = File(target.parentFile, "dangling.png")
            Os.symlink(File(f.root, "missing.png").path, dangling.path)
            try {
                f.pins.discardUnreferencedAsset(Uri.fromFile(target))
                assertTrue(target.exists())
            } finally {
                assertTrue(dangling.delete())
            }
            f.pins.discardUnreferencedAsset(Uri.fromFile(target))
            assertFalse(target.exists())
        } finally {
            f.close()
        }
    }

    @Test fun nativePopulationDoesNotMaterializeReferencesAndSweepKeepsFullScan() = runBlocking {
        val f = Fixture()
        try {
            val asset = f.asset()
            val historic = f.historyFile()
            f.db.withTransaction {
                (1..512).forEach {
                    f.db.pinDao().upsert(pin("p$it", Uri.fromFile(asset).toString(), order = it))
                    f.db.historyDao().upsert(history("h$it", historic.path))
                }
                f.db.pinDao().upsert(pin("synthetic", "pinpin://test/1791676800000"))
            }
            assertFalse(f.hasSuspiciousReference())
            val orphan = f.asset()
            f.queries.clear()
            f.pins.discardUnreferencedAsset(Uri.fromFile(orphan))
            assertFalse(orphan.exists())
            assertTrue(asset.exists())
            assertTrue(historic.exists())
            assertTargetedQueries(f.queries)
            assertTrue(f.queries.any { it.contains("SELECT EXISTS") })

            val sweepOrphan = f.asset()
            val day = 24 * 60 * 60 * 1000L
            assertTrue(sweepOrphan.setLastModified(System.currentTimeMillis() - 3 * day))
            f.queries.clear()
            assertEquals(1, f.pins.cleanupOrphanAssets(day))
            assertTrue(f.queries.any { it == "SELECT * FROM pins" })
            assertTrue(f.queries.any { it == "SELECT filePath FROM screenshot_history" })
            assertTrue(asset.exists())
        } finally {
            f.close()
        }
    }

    @Test fun failedHistoryWriteCompensatesAndRecyclesBitmap() = runBlocking {
        val f = Fixture()
        try {
            // Preserve the production test-pin placeholder during compensation.
            f.db.pinDao().upsert(pin("geometry"))
            f.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_history BEFORE INSERT ON screenshot_history " +
                    "BEGIN SELECT RAISE(ABORT, 'injected'); END",
            )
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            f.queries.clear()
            try {
                f.history.addFromBitmap(bitmap, "test")
                fail("Expected database failure")
            } catch (_: android.database.sqlite.SQLiteException) {
                assertTrue(bitmap.isRecycled)
                assertTrue(File(f.root, "screenshot_history").listFiles().orEmpty().isEmpty())
                assertTargetedQueries(f.queries)
            }
        } finally {
            f.close()
        }
    }

    @Test fun knownSyntheticPinsAreNonFileButHistoryNeverTrustsThem() = runBlocking {
        val f = Fixture()
        try {
            for (raw in listOf("pinpin://test", "pinpin://test/123", "pinpin://test/1791676800000")) {
                f.db.pinDao().upsert(pin("synthetic", raw, annotation = raw))
                assertFalse("Known pin route rejected: $raw", f.hasSuspiciousReference())
                val orphan = f.asset()
                f.pins.discardUnreferencedAsset(Uri.fromFile(orphan))
                assertFalse(orphan.exists())

                val protected = f.asset()
                f.db.historyDao().upsert(history("synthetic-history", raw))
                assertTrue("History trusted a synthetic pin route: $raw", f.hasSuspiciousReference())
                f.pins.discardUnreferencedAsset(Uri.fromFile(protected))
                assertTrue(protected.exists())
                val historic = f.historyFile()
                f.db.historyDao().upsert(history("delete-me", historic.path))
                f.history.delete("delete-me")
                assertTrue(historic.exists())

                f.db.historyDao().deleteById("synthetic-history")
                assertFalse(f.hasSuspiciousReference())
                f.pins.discardUnreferencedAsset(Uri.fromFile(protected))
                assertFalse(protected.exists())
                f.db.historyDao().upsert(history("delete-me", historic.path))
                f.history.delete("delete-me")
                assertFalse(historic.exists())
                f.db.pinDao().deleteById("synthetic")
            }
        } finally {
            f.close()
        }
    }

    private fun assertTargetedQueries(queries: List<String>) {
        assertFalse(queries.any { it == "SELECT * FROM pins" })
        assertFalse(queries.any { it == "SELECT filePath FROM screenshot_history" })
    }
}
