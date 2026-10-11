package com.pinpin.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class PinOrderProjection(val id: String, val zOrder: Int)

// Only direct, ASCII native paths in directories checked for symlinks may
// bypass the conservative fallback. History never trusts synthetic URIs.
internal const val NATIVE_ASSET_REFERENCE_PREDICATE_SQL = """
            EXISTS (
                SELECT 1 FROM native_prefixes
                WHERE substr(reference, 1, length(prefix)) = prefix
                  AND substr(reference, length(prefix) + 1) NOT IN ('', '.', '..')
                  AND substr(reference, length(prefix) + 1) NOT GLOB '*[^A-Za-z0-9._-]*'
            )
"""

// HomeViewModel.addTestPin produces a non-file drawing, not a provider asset.
internal const val KNOWN_TEST_PIN_REFERENCE_PREDICATE_SQL = """
    (reference = 'pinpin://test'
        OR (reference GLOB 'pinpin://test/*'
            AND length(reference) > length('pinpin://test/')
            AND substr(reference, length('pinpin://test/') + 1) NOT GLOB '*[^0-9]*'))
"""

internal const val SUSPICIOUS_PIN_REFERENCE_SQL =
    "SELECT EXISTS (SELECT 1 FROM asset_references WHERE NOT (" +
        NATIVE_ASSET_REFERENCE_PREDICATE_SQL + " OR " +
        KNOWN_TEST_PIN_REFERENCE_PREDICATE_SQL + "))"

internal const val SUSPICIOUS_HISTORY_REFERENCE_SQL =
    "SELECT EXISTS (SELECT 1 FROM asset_references WHERE NOT (" +
        NATIVE_ASSET_REFERENCE_PREDICATE_SQL + "))"

internal const val NATIVE_ASSET_PREFIXES_SQL = """
    native_prefixes(prefix) AS (
        SELECT :pinPathPrefix UNION ALL SELECT :pinUriPrefix
        UNION ALL SELECT :historyPathPrefix UNION ALL SELECT :historyUriPrefix
    )
"""

@Dao
interface PinDao {
    @Query("SELECT * FROM pins")
    suspend fun getAll(): List<PinEntity>

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

    @Query("SELECT COUNT(*) FROM pins")
    suspend fun count(): Int

    /** Legacy id-only view in stable display order (numbered
     *  pins first by zOrder, then unnumbered pins by
     *  createdAt). Renormalization uses [ordersInDisplayOrder]
     *  to avoid fetching each pin separately. */
    @Query("SELECT id FROM pins ORDER BY CASE WHEN zOrder > 0 THEN 0 ELSE 1 END, zOrder ASC, createdAt ASC")
    suspend fun idsInDisplayOrder(): List<String>

    @Query("SELECT id, zOrder FROM pins ORDER BY CASE WHEN zOrder > 0 THEN 0 ELSE 1 END, zOrder ASC, createdAt ASC")
    suspend fun ordersInDisplayOrder(): List<PinOrderProjection>

    @Query("SELECT EXISTS (SELECT 1 FROM pins WHERE imageUri IN (:nativeReferences) OR annotatedImageUri IN (:nativeReferences))")
    suspend fun hasNativeAssetReference(nativeReferences: List<String>): Boolean

    @Query(
        "WITH asset_references(reference) AS (" +
            "SELECT imageUri FROM pins UNION ALL " +
            "SELECT annotatedImageUri FROM pins WHERE annotatedImageUri IS NOT NULL), " +
            NATIVE_ASSET_PREFIXES_SQL + SUSPICIOUS_PIN_REFERENCE_SQL,
    )
    suspend fun hasSuspiciousAssetReference(
        pinPathPrefix: String,
        pinUriPrefix: String,
        historyPathPrefix: String,
        historyUriPrefix: String,
    ): Boolean

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

    @Query("UPDATE pins SET serialVisible = :visible WHERE id = :id")
    suspend fun updateSerialVisible(id: String, visible: Boolean)

    @Query("UPDATE pins SET imageUri = :imageUri WHERE id = :id")
    suspend fun updateImageUri(id: String, imageUri: String)

    /**
     * Stamp the post-annotation URI onto the pin. Pass `null` to
     * revert to the original [PinEntity.imageUri] (used when the
     * user discards an in-progress annotation).
     */
    @Query("UPDATE pins SET annotatedImageUri = :annotatedImageUri WHERE id = :id")
    suspend fun updateAnnotatedImageUri(id: String, annotatedImageUri: String?)
}
