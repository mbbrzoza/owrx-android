package pl.sp8mb.owrx.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scan_hits", indices = [Index(value = ["freqHz"], unique = true)])
data class ScanHitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val freqHz: Long,
    val mod: String,
    val profileId: String?,
    val firstHeard: Long,
    val lastHeard: Long,
    val hitCount: Int = 1,
    val label: String? = null,
    val blacklisted: Boolean = false,
)

@Dao
interface ScanHitDao {
    @Query("SELECT * FROM scan_hits ORDER BY lastHeard DESC")
    fun all(): Flow<List<ScanHitEntity>>

    @Query("SELECT * FROM scan_hits WHERE freqHz = :freqHz")
    suspend fun byFreq(freqHz: Long): ScanHitEntity?

    @Query("SELECT freqHz FROM scan_hits WHERE blacklisted = 1")
    suspend fun blacklistedFreqs(): List<Long>

    @Upsert
    suspend fun upsert(hit: ScanHitEntity): Long

    @Query("UPDATE scan_hits SET blacklisted = :value WHERE id = :id")
    suspend fun setBlacklisted(id: Long, value: Boolean)

    @Query("DELETE FROM scan_hits WHERE blacklisted = 0")
    suspend fun clearHits()
}
