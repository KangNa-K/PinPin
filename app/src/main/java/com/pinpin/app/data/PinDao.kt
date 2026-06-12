package com.pinpin.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PinDao {

    @Query("SELECT * FROM pins ORDER BY zOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<PinEntity>>

    @Query("SELECT * FROM pins WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PinEntity?

    @Query("SELECT COALESCE(MAX(zOrder), 0) FROM pins")
    suspend fun maxZOrder(): Int

    /** Same as [maxZOrder] but ignores pins that have been
     *  "un-numbered" (zOrder = 0). Used when assigning the next
     *  serial number so a pin that opted out of numbering
     *  doesn't keep eating the #1 slot. */
    @Query("SELECT COALESCE(MAX(zOrder), 0) FROM pins WHERE zOrder > 0")
    suspend fun maxPositiveZOrder(): Int

    /** Stream of pin ids in stable display order (numbered
     *  pins first by zOrder, then unnumbered pins by
     *  createdAt). Used by the repository's
     *  [com.pinpin.app.data.PinRepository.renormalizeZOrders]
     *  loop to figure out which pin should get the next slot. */
    @Query("SELECT id FROM pins ORDER BY CASE WHEN zOrder > 0 THEN 0 ELSE 1 END, zOrder ASC, createdAt ASC")
    suspend fun idsInDisplayOrder(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pin: PinEntity)

    @Query("DELETE FROM pins WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE pins SET x = :x, y = :y WHERE id = :id")
    suspend fun updatePosition(id: String, x: Int, y: Int)

    @Query("UPDATE pins SET width = :width, height = :height WHERE id = :id")
    suspend fun updateSize(id: String, width: Float, height: Float)

    @Query("UPDATE pins SET locked = :locked WHERE id = :id")
    suspend fun updateLocked(id: String, locked: Boolean)

    @Query("UPDATE pins SET zOrder = :zOrder WHERE id = :id")
    suspend fun updateZOrder(id: String, zOrder: Int)

    @Query("UPDATE pins SET rotation = :rotation WHERE id = :id")
    suspend fun updateRotation(id: String, rotation: Int)

    @Query("UPDATE pins SET flipped = :flipped WHERE id = :id")
    suspend fun updateFlipped(id: String, flipped: Boolean)

    @Query("UPDATE pins SET imageUri = :imageUri WHERE id = :id")
    suspend fun updateImageUri(id: String, imageUri: String)
}
