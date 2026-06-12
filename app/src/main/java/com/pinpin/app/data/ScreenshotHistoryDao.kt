package com.pinpin.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenshotHistoryDao {

    @Query("SELECT * FROM screenshot_history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScreenshotHistoryEntity>>

    @Query("SELECT * FROM screenshot_history WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ScreenshotHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScreenshotHistoryEntity)

    @Query("DELETE FROM screenshot_history WHERE id = :id")
    suspend fun deleteById(id: String)
}
