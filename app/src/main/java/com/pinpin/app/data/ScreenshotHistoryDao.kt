package com.pinpin.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenshotHistoryDao {
    @Query("SELECT filePath FROM screenshot_history")
    suspend fun allFilePaths(): List<String>

    @Query("SELECT EXISTS (SELECT 1 FROM screenshot_history WHERE filePath IN (:nativeReferences))")
    suspend fun hasNativeAssetReference(nativeReferences: List<String>): Boolean

    @Query(
        "WITH asset_references(reference) AS (SELECT filePath FROM screenshot_history), " +
            NATIVE_ASSET_PREFIXES_SQL + SUSPICIOUS_HISTORY_REFERENCE_SQL,
    )
    suspend fun hasSuspiciousAssetReference(
        pinPathPrefix: String,
        pinUriPrefix: String,
        historyPathPrefix: String,
        historyUriPrefix: String,
    ): Boolean

    @Query("SELECT * FROM screenshot_history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScreenshotHistoryEntity>>

    @Query("SELECT * FROM screenshot_history WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ScreenshotHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScreenshotHistoryEntity)

    @Query("DELETE FROM screenshot_history WHERE id = :id")
    suspend fun deleteById(id: String)
}
